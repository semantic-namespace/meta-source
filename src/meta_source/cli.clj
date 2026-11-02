(ns meta-source.cli
  "EXPERIMENTAL (see README.md). The meta-source command, installed with
   bbin. Runs under babashka (cheshire, and clj-kondo as a pod).

   meta-source <command> [args] [--root dir] [--registry dir]... [--src dir]...
               [--json] [--brief] [--no-cache] [--fail-on error|warning|info]

   Answers from the project's files, nothing loaded or run. Settings come
   from the flags, or from <root>/meta-source.edn:

     {:src [\"src\"]                       ; code (default [\"src\"])
      :registry [\"registry\"]             ; EDN files of entries (default [\"registry\"])
      :packs {:integrant {:configs [\"resources\"]} :spec {} :pathom {}}
      :dataflow {:consumes [...] :produces [...]}
      :merge {:app/tags :union}
      :rules [\"rules\"]                   ; EDN files of the project's own rules
      :id-key :meta-source/id             ; the meta key a var carries
      :calls-by-id [app.engine/exec]      ; fns whose first argument, a literal keyword,
                                          ; is the entity they call: (exec :fn/load)
      :deps {app/packs {:local/root \"tools\"}}}  ; where a project's own packs live

   The code analysis is cached in <root>/.meta-source/index.transit and
   refreshed for the files that changed since the last call."
  (:require [meta-source.hover :as hover]
            [meta-source.index :as index]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(def usage
  "Commands:
  where <id>          where it is defined (EDN entries, packs) and implemented (var), with its entry
  uses <id>           entries that name it, and code that calls its var
  deps <id>           entities its entry names
  reach <id>          what it depends on, transitively (entries, calls, the component
                      that builds it), with the path to each (--via deps,calls,provided-by)
  reached-by <id>     what depends on it, transitively: the reverse of reach (--via too)
  data <data-key>     entities producing and consuming the key
  check               where code and registry files disagree (--brief: one line each;
                      --fail-on error: exit 1 when a problem is that severe or more, as CI wants)
  summary             counts, coverage, files re-analysed by this call
  hover <keyword>     what an editor shows for a keyword: eldoc line, describe text, locations
  diagnostics <file>  check's problems in one file, with the line and columns to underline
  serve               long-lived: one JSON request per line on stdin, one JSON reply per line
                      {\"id\":1,\"op\":\"hover\",\"keyword\":\":fn/x\"} -> {\"id\":1,\"result\":{...}}
                      the index stays in memory and is refreshed from disk before each reply")

(defn- parse [args]
  (loop [[a & more :as args] args opts {:registry [] :src [] :positional []}]
    (cond
      (empty? args) opts
      (= a "--root") (recur (rest more) (assoc opts :root (first more)))
      (= a "--registry") (recur (rest more) (update opts :registry conj (first more)))
      (= a "--src") (recur (rest more) (update opts :src conj (first more)))
      (= a "--json") (recur more (assoc opts :json true))
      (= a "--no-cache") (recur more (assoc opts :no-cache true))
      (= a "--brief") (recur more (assoc opts :brief true))
      (= a "--fail-on") (recur (rest more) (assoc opts :fail-on (first more)))
      (= a "--via") (recur (rest more) (assoc opts :via (mapv keyword (str/split (first more) #","))))
      :else (recur more (update opts :positional conj a)))))

(defn under-root
  "`deps` with each relative :local/root resolved against `root`, the
   project's: meta-source.edn names its packs' paths from the project, and
   meta-source may run from elsewhere (--root, as CI runs it)."
  [deps root]
  (update-vals deps (fn [coord]
                      (let [lr (:local/root coord)]
                        (if (and lr (not (.isAbsolute (io/file lr))))
                          (assoc coord :local/root (.getCanonicalPath (io/file root lr)))
                          coord)))))

(defn- add-deps!
  "Puts `deps` on the classpath, for a project's own packs (:deps in
   meta-source.edn): babashka resolves them as a deps.edn would, a relative
   :local/root from the project's root."
  [deps root]
  (when (seq deps)
    ((requiring-resolve 'babashka.deps/add-deps) {:deps (under-root deps root)})))

(def ^:private lib-src
  "The source directories of meta-source.edn's :libs (meta-source.libs)."
  (memoize (fn [root from-file]
             (if (seq (:libs from-file))
               ((requiring-resolve 'meta-source.libs/materialize) root (:libs from-file))
               []))))

(defn config
  "The index options for the project at `root`: the flags, else its
   meta-source.edn, else the defaults. Puts the project's own packs on the
   classpath."
  [{:keys [root registry src no-cache]}]
  (let [root (or root ".")
        f (io/file root "meta-source.edn")
        from-file (when (.exists f)
                    (try (edn/read-string (slurp f))
                         (catch Exception ex
                           (throw (ex-info (str "meta-source.edn is not readable: " (ex-message ex)) {:file (str f)})))))]
    (add-deps! (:deps from-file) root)
    (cond-> {:root root
             :registry (if (seq registry) registry (:registry from-file ["registry"]))
             :src (into (vec (if (seq src) src (:src from-file ["src"]))) (lib-src root from-file))
             :lib-src (lib-src root from-file)
             :packs (:packs from-file {})
             :dataflow (:dataflow from-file)
             :merge (:merge from-file)
             :rules (:rules from-file)
             :calls-by-id (:calls-by-id from-file)
             :not-covered (:not-covered from-file)
             :cache (when-not no-cache (str (io/file root ".meta-source" "index.transit")))}
      (:id-key from-file) (assoc :id-key (:id-key from-file)))))

(defn- kw [s] (keyword (str/replace s #"^:" "")))

(defn- serve
  "Answers one JSON request per line until stdin closes. Before each reply
   the index is refreshed from what changed on disk, in memory, and
   meta-source.edn is read again when it changed: a pack enabled while the
   editor is open takes effect on the next answer."
  [opts]
  (let [config-file (io/file (or (:root opts) ".") "meta-source.edn")
        state (atom {})]
    (loop []
      (when-let [line (read-line)]
        (let [{:keys [id op keyword file]} (try (json/parse-string line true) (catch Exception _ nil))
              reply (try
                      (let [stamp (.lastModified config-file)
                            {:keys [cfg idx]} (swap! state (fn [{:keys [stamp-was] :as st}]
                                                             (if (and (:cfg st) (= stamp stamp-was))
                                                               st
                                                               {:stamp-was stamp :cfg (dissoc (config opts) :cache)})))
                            idx (index/build (assoc cfg :previous idx))]
                        (swap! state assoc :idx idx)
                        {:id id
                         :result (case op
                                   "hover" (hover/lookup idx (kw keyword))
                                   "check" (index/check idx)
                                   "diagnostics" (hover/diagnostics idx file)
                                   "summary" (index/summary idx)
                                   {:error (str "unknown op " op)})})
                      (catch Exception e {:id id :error (ex-message e)}))]
          (println (json/generate-string reply))
          (flush)
          (recur))))))

(declare main)

(defn -main [& args]
  (try (apply main args)
       (catch clojure.lang.ExceptionInfo ex
         (binding [*out* *err*] (println (str "meta-source: " (ex-message ex))))
         (System/exit 1))))

(defn read-line-summary
  "One line saying what check read: its entities, and per pack what was read
   and what wasn't."
  [{:keys [entities source-files packs]}]
  (str "meta-source: read " entities " entities from " source-files " source files"
       (when (seq packs)
         (str "; " (str/join ", " (for [[kind {:keys [read not-read]}] packs]
                                     (str (name kind) " " read (when (pos? not-read) (str " (" not-read " not read)")))))))))

(def ^:private severity-rank {:info 0 :warning 1 :error 2})

(defn failing
  "The problems as severe as `fail-on` (\"error\", \"warning\", \"info\") or more."
  [problems fail-on]
  (let [floor (or (severity-rank (keyword fail-on))
                  (throw (ex-info (str "--fail-on takes error, warning or info, not " (pr-str fail-on)) {})))]
    (filter #(>= (severity-rank (:severity %) 0) floor) problems)))

(defn- main [& args]
  (let [{[cmd arg] :positional :as opts} (parse args)
        idx (delay (index/build (config opts)))
        ;; checked before any output, so a bad value fails fast
        fail-on (when (:fail-on opts) (failing [] (:fail-on opts)) (:fail-on opts))
        out (case cmd
              "where" (or (index/where @idx (kw arg)) {:not-found (kw arg)})
              "uses" (index/uses @idx (kw arg))
              "deps" (or (index/dependencies @idx (kw arg)) {:not-found (kw arg)})
              "reach" (or (if (:via opts) (index/reach @idx (kw arg) (:via opts)) (index/reach @idx (kw arg)))
                          {:not-found (kw arg)})
              "reached-by" (or (if (:via opts) (index/reached-by @idx (kw arg) (:via opts)) (index/reached-by @idx (kw arg)))
                               {:not-found (kw arg)})
              "data" (index/data-key @idx (kw arg))
              "check" (if (:brief opts) (hover/brief @idx) (index/check @idx))
              "diagnostics" (hover/diagnostics @idx arg)
              "summary" (index/summary @idx)
              "hover" (hover/lookup @idx (kw arg))
              "serve" (do (serve opts) (System/exit 0))
              (do (println usage) (System/exit (if cmd 1 0))))]
    (cond
      (:json opts) (println (json/generate-string out {:pretty true}))
      (and (:brief opts) (= cmd "check")) (run! println out)
      :else (pp/pprint out))
    (when (and (= cmd "check") (not (:json opts)))
      ;; what was read, on stderr: a project that reads nothing has no problems either
      (binding [*out* *err*] (println (read-line-summary (index/summary @idx)))))
    (when (and fail-on (= cmd "check"))
      (let [n (count (failing (index/check @idx) fail-on))]
        (when (pos? n)
          (binding [*out* *err*]
            (println (str "meta-source: " n " problem(s) at " fail-on " or above")))
          (System/exit 1))))))
