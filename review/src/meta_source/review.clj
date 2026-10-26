(ns meta-source.review
  "EXPERIMENTAL. meta-source on sdiff's review page: what a change does to
   check's findings. The project is read at the merge base and at the head,
   both with the head's meta-source.edn, and the findings are compared by
   what they say, not where: a finding that only moved is neither introduced
   nor fixed. The header lists the findings the change introduces and those
   it fixes; a changed form shows the ones located inside it.

   Load this namespace in a process that serves or prints sdiff reports; it
   registers its decorators and wraps the context builder already installed."
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [meta-source.cli :as cli]
            [meta-source.hover :as hover]
            [meta-source.index :as index]
            [sdiff.core :as core]
            [sdiff.decorate :as d :refer [derived]]))

;; --- findings, compared by what they say

(def ^:private where-keys #{:file :line :end-line :col :end-col :runtime-as-of})

(defn- per-key
  "A finding about several keys as one finding per key, so a change that adds
   a key to an existing finding introduces that key, not a new finding."
  [f]
  (if (next (:keys f)) (for [k (:keys f)] (assoc f :keys [k])) [f]))

(defn what
  "What `finding` says, without where it says it."
  [finding]
  (walk/postwalk #(if (map? %) (apply dissoc % where-keys) %) finding))

(defn delta
  "`{:introduced [...] :fixed [...]}`: the findings at head that base doesn't
   have, and the reverse, each as found on its own side."
  [base head]
  (let [base (mapcat per-key base) head (mapcat per-key head)
        in-base (set (map what base)) in-head (set (map what head))]
    {:introduced (vec (distinct (remove #(in-base (what %)) head)))
     :fixed (vec (distinct (remove #(in-head (what %)) base)))}))

;; --- the project at a commit

(def ^:private cache-root (io/file (System/getProperty "user.home") ".cache" "meta-source" "review"))

(def ^:private fallback-file (io/file (System/getProperty "user.home") ".config" "meta-source" "review.edn"))

(defn- fallback
  "The meta-source.edn to use for `repo` when it has none of its own, from
   ~/.config/meta-source/review.edn: `{\"owner/name\" {:packs {...}}}`."
  [repo]
  (when (and repo (.exists fallback-file))
    (get (edn/read-string (slurp fallback-file)) repo)))

(defn- harmless?
  "Is `err` only tar saying a pattern matched no file? A repository with no
   .cljs, say, is not a failure."
  [err]
  (every? #(re-find #"Not found in archive|Exiting with failure status due to previous errors" %)
          (remove str/blank? (str/split-lines err))))

(defn- sh! [cmd]
  (let [{:keys [exit err]} @(p/process ["bash" "-c" (str "set -o pipefail; " cmd)] {:err :string :out :string})]
    (when-not (or (zero? exit) (and (= 2 exit) (harmless? err)))
      (throw (ex-info (str (first (str/split cmd #" \| ")) " failed: " err) {})))))

(def ^:private own-config "The commit's own meta-source.edn, kept aside in a tree." ".meta-source.edn.own")

(defn- own-settings [dir]
  (let [f (io/file dir own-config)] (when (.exists f) (edn/read-string (slurp f)))))

(defn- tree!
  "A directory holding the project's sources and EDN at `sha`, extracted once:
   from GitHub with `gh` when `source` is `{:gh \"owner/name\"}`, from a local
   clone with `git archive` when it is `{:git dir}`."
  [source sha]
  (let [dir (io/file cache-root (str/replace (or (:gh source) (.getCanonicalPath (io/file (:git source)))) #"[/:]" "--") sha)
        done (io/file dir ".complete")]
    (locking (.intern (str dir))
      (when-not (.exists done)
        (.mkdirs dir)
        (if (:gh source)
          (sh! (str "gh api repos/" (:gh source) "/tarball/" sha " | tar -xz -C " dir
                                " --strip-components=1 --wildcards '*.clj' '*.cljc' '*.cljs' '*.edn' '*.bb'"))
          (sh! (str "git -C " (:git source) " archive " sha " | tar -x -C " dir)))
        (let [own (io/file dir "meta-source.edn")]
          (when (.exists own) (.renameTo own (io/file dir own-config))))
        (spit done "")))
    (.getCanonicalPath dir)))

(defn- read-with
  "check's findings at `dir`, read with `config`: written as the tree's
   meta-source.edn, since the trees are this module's own copies (the one the
   commit has is kept aside, see `tree!`)."
  [dir config]
  (let [f (io/file dir "meta-source.edn")]
    (when-not (and (.exists f) (= config (edn/read-string (slurp f))))
      (spit f (pr-str config)))
    (index/check (index/build (assoc (cli/config {:root dir}) :cache (str (io/file dir ".meta-source" "index.transit")))))))

(defonce ^:private computed (atom {}))

(defn compare-commits
  "The delta of check's findings between `base` and `head` of `source` (see
   `tree!`), with `:config-changed?` when the base had a different
   meta-source.edn, `:head-count` the findings at head; nil when the head has
   no meta-source.edn and no fallback names the repository."
  [source base head]
  (let [k [source base head]]
    (if (contains? @computed k)
      (@computed k)
      (let [head-dir (tree! source head)
            base-dir (tree! source base)
            base-own (own-settings base-dir)
            config (or (own-settings head-dir) (fallback (:gh source)))
            r (when config
                (let [h (read-with head-dir config) b (read-with base-dir config)]
                  (assoc (delta b h)
                         :head-count (count h)
                         :config-changed? (and base-own (not= base-own config)))))]
        (swap! computed assoc k r)
        r))))

;; --- on the page

(defn- source-of [report]
  (cond (get-in report [:pr :repo]) {:gh (get-in report [:pr :repo])}
        (::repo report) {:git (::repo report)}))

(defn- git-out [dir & args]
  (str/trim (:out @(p/process (into ["git" "-C" dir] args) {:out :string :err :string}))))

(defn- commit-of
  "The commit `ref` names in the clone `dir`, so the trees are cached by
   what they hold: a branch moves. The working tree (`.`) is the commit
   `git stash create` records for its tracked edits, else HEAD."
  [dir ref]
  (if (= "." ref)
    (or (not-empty (git-out dir "stash" "create")) (git-out dir "rev-parse" "HEAD"))
    (git-out dir "rev-parse" "--verify" (str ref "^{commit}"))))

(defn context-for
  "What the decorators read for `report`: `{::delta ...}`, or `{::error msg}`."
  [report]
  (when-let [source (source-of report)]
    (try (let [[base head] (if (:git source)
                             (map #(commit-of (:git source) %) [(:base report) (:head report)])
                             [(:base report) (:head report)])]
           {::delta (compare-commits source base head)})
         (catch Exception e {::error (ex-message e)}))))

(defn- line-of [p]
  (let [l (first (hover/locations p))
        msg (subs (hover/message p nil) (count "meta-source: "))]
    [:li [:span.tag (name (:severity p))] " " (when l [:code (str (:file l) ":" (:line l))]) " " msg]))

(defn header [ctx _report]
  (cond
    (::error ctx) (derived "meta-source" [:p "meta-source could not read the change: " (::error ctx)])
    (::delta ctx)
    (let [{:keys [introduced fixed head-count config-changed?]} (::delta ctx)]
      (derived "meta-source"
               (if (and (empty? introduced) (empty? fixed))
                 [:p (str "No finding introduced or fixed (" head-count " at head).")]
                 [:details {:open true}
                  [:summary (str (count introduced) " finding" (when (not= 1 (count introduced)) "s") " introduced · "
                                 (count fixed) " fixed (" head-count " at head)")]
                  (when (seq introduced) (list [:h5 "Introduced"] [:ul (map line-of introduced)]))
                  (when (seq fixed) (list [:h5 "Fixed"] [:ul (map line-of fixed)]))])
               (when config-changed?
                 [:p "meta-source.edn changed in this change; both sides are read with the head's."])))))

(defn- rows
  "[row end-row] of the form `id` in `src`, or nil."
  [src id]
  (when (seq src)
    (let [{:keys [row end-row]} (meta (get (core/index src) id))]
      (when row [row end-row]))))

(defn- inside [findings path [from to]]
  (filter (fn [p] (some #(and (= path (:file %)) (<= from (:line %) to)) (hover/locations p))) findings))

(defn form-line
  "The findings introduced inside the head side of `form`, and those fixed
   inside its base side."
  [ctx file form]
  (when-let [{:keys [introduced fixed]} (::delta ctx)]
    (let [path (:path file)
          fr (or (when (:new file) file) (some #(when (= path (:path %)) %) (:clj (:report ctx))))
          h (rows (:new fr) (:id form))
          b (rows (:old fr) (or (:was form) (:id form)))
          in (when h (inside introduced path h))
          out (when b (inside fixed path b))]
      (when (or (seq in) (seq out))
        (derived "meta-source"
                 (when (seq in) (list [:p "introduces"] [:ul (map line-of in)]))
                 (when (seq out) (list [:p "fixes"] [:ul (map line-of out)])))))))

(defn install!
  "Registers the decorators, and wraps the context builder in place so the
   host's own context is kept."
  []
  (let [prev @d/context-fn]
    (d/use-context! (fn [report] (merge (prev report) (context-for report)))))
  (d/add-header-decorator! ::findings header)
  (d/add-form-decorator! ::findings form-line))
