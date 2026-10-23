(ns meta-source.corpus
  "The precision corpus: meta-source's checks run over pinned real apps, each
   finding judged by a person, and per check how many of its findings are
   real. See corpus/README.md.

     bb corpus fetch            clone each app at its commit, under .corpus/apps
     bb corpus run [app ...]    check each app, into .corpus/findings.edn
     bb corpus report           per check: findings, labels, precision
     bb corpus unlabeled [n]    findings no label judges, with their code
     bb corpus todo             append an entry to fill in to labels.edn per
                                unjudged finding, its code as comments above it
     bb corpus plant            plant each known defect, see if its check finds it
     bb corpus ci               run, then fail on findings no label judges

   From another project, with meta-source as a dependency (as its CI runs
   meta-source check), from anywhere, outside the checkout if its bb.edn
   would shadow meta-source's deps:

     MS_CORPUS_DIR=<project>/meta-source/corpus bb -Sdeps '<meta-source as a git dep>' -m meta-source.corpus report

   An app's :path is relative to the directory holding corpus.edn:
   {:path \"../..\"} from <project>/meta-source/corpus is the project itself,
   read with its own meta-source.edn.

   A finding's key leaves out line numbers, so labels survive edits that move
   code: [app problem dev-id file what-else-it-names]."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [meta-source.index :as index]))

;; the corpus: this repository's (corpus/), or a private one MS_CORPUS_DIR
;; names (an app that can't be public), whose clones and findings stay in it
(def corpus-dir (or (System/getenv "MS_CORPUS_DIR") "corpus"))
(def corpus-file (str corpus-dir "/corpus.edn"))
(def labels-file (str corpus-dir "/labels.edn"))
(def plants-file (str corpus-dir "/plants.edn"))
(def work (if (System/getenv "MS_CORPUS_DIR") (str corpus-dir "/.work") ".corpus"))
(def findings-file (str work "/findings.edn"))

(defn- read-edn [f] (edn/read-string (slurp f)))

(defn corpus [] (read-edn corpus-file))
(defn labels [] (if (fs/exists? labels-file) (read-edn labels-file) []))
;; the entries judged: todo's blank ones (:label nil) don't count
(defn judged [] (filter :label (labels)))

;; =============================================================================
;; The apps
;; =============================================================================

(defn- checkout-dir
  "Where an app is: its clone, or its :path, which, when relative, is
   relative to the directory holding corpus.edn, wherever the harness runs
   from."
  [{:keys [git path]}]
  (cond
    (and path (fs/absolute? path)) path
    path (str (fs/normalize (fs/path corpus-dir path)))
    :else (str work "/apps/" (str/replace (fs/file-name git) #"\.git$" ""))))

(defn app-root [app]
  (str (fs/path (checkout-dir app) (or (:root app) "."))))

(defn- run-git [dir & args]
  (apply p/shell {:dir dir :out :string :err :string} "git" args))

(defn fetch
  "Each app's repository at its commit. Apps that share a repository share
   the checkout; a checkout already at the commit is left alone."
  []
  (doseq [[dir apps] (group-by checkout-dir (remove :path (:apps (corpus))))
          :let [{:keys [git sha]} (first apps)]]
    (when-not (fs/exists? dir)
      (println "clone" git)
      (fs/create-dirs (fs/parent dir))
      (p/shell "git" "clone" "-q" "--filter=blob:none" git dir))
    (when-not (= sha (str/trim (:out (run-git dir "rev-parse" "HEAD"))))
      (println "checkout" dir sha)
      (run-git dir "fetch" "-q" "origin" sha)
      (run-git dir "checkout" "-q" "--detach" sha))))

(defn- config
  "What meta-source reads the app with: its :config, or the app's own
   meta-source.edn."
  [app]
  (let [root (app-root app)
        cfg (or (:config app) (read-edn (str (fs/path root "meta-source.edn"))))]
    ;; a project's own packs, as meta-source.edn's :deps puts them on the classpath
    (when (seq (:deps cfg))
      ((requiring-resolve 'babashka.deps/add-deps) {:deps (:deps cfg)}))
    (merge {:registry [] :src ["src"]} (dissoc cfg :deps) {:root root})))

;; =============================================================================
;; Findings
;; =============================================================================

(def ^:private place-keys #{:line :end-line :col :end-col :row})

(defn- file-of [p]
  (or (get-in p [:definition :file]) (get-in p [:impl :file]) (:file p)))

(defn finding-key
  "A finding's identity without where its lines are: the app, the problem,
   the id, the file, and whatever else the problem names (keys, a target, a
   reason), with places dropped."
  [app-id p]
  (let [rest (walk/postwalk #(if (map? %) (apply dissoc % place-keys) %)
                            (dissoc p :severity :problem :dev-id :definition :impl :file :message))]
    [app-id (:problem p) (:dev-id p) (file-of p) (into (sorted-map) rest)]))

(defn- source-lines
  "Lines of Clojure and EDN under the app's :src and :registry, for findings
   per thousand lines."
  [{:keys [root src registry]}]
  (reduce + (for [d (concat src registry)
                  :let [dir (fs/path root d)] :when (fs/exists? dir)
                  f (fs/glob dir "**.{clj,cljs,cljc,edn}")]
              (count (str/split-lines (slurp (str f)))))))

(defn check-app [app]
  (let [cfg (config app)
        t0 (System/currentTimeMillis)
        idx (index/build cfg)
        ps (index/check idx)]
    {:app (:id app)
     :ms (- (System/currentTimeMillis) t0)
     ;; an app that reads nothing has no findings: that is no evidence
     :entities (count (:entities idx))
     :lines (source-lines cfg)
     :findings (vec (for [p ps]
                      {:key (finding-key (:id app) p)
                       :severity (:severity p)
                       :line (or (get-in p [:definition :line]) (get-in p [:impl :line]) (:line p))
                       :problem p}))}))

(defn run
  "Checks the apps (all, or those named) and writes .corpus/findings.edn,
   keeping what an earlier run found for the others."
  [ids]
  (let [only (set (map keyword ids))
        apps (cond->> (remove :plants-only (:apps (corpus))) (seq only) (filter (comp only :id)))
        before (if (and (seq only) (fs/exists? findings-file))
                 (apply dissoc (read-edn findings-file) (map :id (filter :plants-only (:apps (corpus)))))
                 {})
        results (into before (for [app apps]
                               (do (print (str (name (:id app)) " ")) (flush)
                                   (let [r (try (check-app app)
                                                (catch Exception e
                                                  {:app (:id app) :error (ex-message e) :findings []}))]
                                     (println (if (:error r) (str "ERROR " (:error r))
                                                  (str (count (:findings r)) " findings, " (:ms r) " ms")))
                                     [(:id app) r]))))]
    (fs/create-dirs work)
    (spit findings-file (with-out-str (pp/pprint results)))
    results))

(defn findings []
  (when-not (fs/exists? findings-file)
    (throw (ex-info "no findings yet: bb corpus run" {})))
  (read-edn findings-file))

;; =============================================================================
;; The report
;; =============================================================================

(defn wilson
  "The 95% Wilson interval of k successes in n."
  [k n]
  (when (pos? n)
    (let [z 1.96 p (/ (double k) n) z2 (* z z)
          c (/ (+ p (/ z2 (* 2 n))) (+ 1 (/ z2 n)))
          h (/ (* z (Math/sqrt (+ (/ (* p (- 1 p)) n) (/ z2 (* 4 n n))))) (+ 1 (/ z2 n)))]
      [(max 0.0 (- c h)) (min 1.0 (+ c h))])))

(defn- pct [x] (if x (format "%.0f%%" (* 100.0 x)) "-"))

(defn- precision-cell [ls]
  (let [{real :real false-pos :false} (frequencies (map :label ls))
        real (or real 0) false-pos (or false-pos 0) n (+ real false-pos)]
    (if (zero? n)
      "-"
      (let [[lo hi] (wilson real n)]
        (str (pct (/ real n)) " (" real "/" n ", " (pct lo) "–" (pct hi) ")")))))

(defn report
  "Per check: findings, how they were judged, precision with and without
   the labels no second person confirmed. Precision is real / (real + false);
   useless and unsure findings are counted, not in it."
  []
  (let [{:keys [not-judged]} (corpus)
        fs (mapcat :findings (vals (findings)))
        by-key (into {} (map (juxt :finding identity)) (judged))
        rows (for [[problem ps] (sort-by key (group-by (comp second :key) fs))
                   :let [ls (keep #(by-key (:key %)) ps)
                         freq (frequencies (map :label ls))
                         confirmed (filter :confirmed ls)]]
               {:problem problem
                :severity (:severity (first ps))
                :n (count ps)
                :apps (count (distinct (map (comp first :key) ps)))
                :real (freq :real 0) :false (freq :false 0) :useless (freq :useless 0) :unsure (freq :unsure 0)
                :unlabeled (- (count ps) (count ls))
                :precision (if (contains? not-judged problem) "not judged" (precision-cell ls))
                :confirmed (if (contains? not-judged problem) "" (precision-cell confirmed))})
        per-app (for [[id r] (sort-by key (findings))]
                  [id (count (:findings r)) (:entities r) (:lines r) (:ms r) (:error r)])]
    (println "## Precision per check\n")
    (println "| check | severity | findings | apps | real | false | useless | unsure | unlabeled | precision (all labels) | precision (confirmed) |")
    (println "|---|---|---|---|---|---|---|---|---|---|---|")
    (doseq [r rows]
      (println (str "| `" (name (:problem r)) "` | " (name (:severity r)) " | "
                    (str/join " | " (map r [:n :apps :real :false :useless :unsure :unlabeled :precision :confirmed])) " |")))
    (println "\n## Per app\n")
    (println "| app | findings | entities read | lines read | per 1k lines | ms |")
    (println "|---|---|---|---|---|---|")
    (doseq [[id n entities lines ms err] per-app]
      (println (str "| " (name id) " | " (if err (str "error: " err) n) " | " entities " | " lines " | "
                    (if (and lines (pos? lines)) (format "%.1f" (/ (* 1000.0 n) lines)) "-") " | " ms " |")))
    (let [keys (set (map :key fs))
          stale (remove #(keys (:finding %)) (judged))]
      (when (seq stale)
        (println (str "\n" (count stale) " labels judge findings no run produces now (bb corpus unlabeled shows the rest)"))))))

;; =============================================================================
;; Labeling
;; =============================================================================

(defn- snippet [app file line]
  (when (and file line)
    (let [f (fs/path (app-root app) file)]
      (when (fs/exists? f)
        (let [ls (vec (str/split-lines (slurp (str f))))
              from (max 0 (- line 3)) to (min (count ls) (+ line 3))]
          (str/join "\n" (for [i (range from to)]
                           (format "%s%5d  %s" (if (= (inc i) line) ">" " ") (inc i) (nth ls i)))))))))

(defn- distinct-by-key [fs]
  (vals (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) (map (juxt :key identity)) (reverse fs))))

(defn todo
  "Appends to labels.edn an entry with :label nil for each finding no label
   judges, under comments with its message and the code around it: fill in
   :label, :why and :by, set :confirmed true, and the report counts it."
  []
  (let [apps (into {} (map (juxt :id identity)) (:apps (corpus)))
        judged (set (map :finding (labels)))
        {:keys [not-judged]} (corpus)
        ;; the most severe first, each check's findings together
        todo (sort-by (fn [f] [({:error 0 :warning 1 :info 2} (:severity f) 3) (str (second (:key f))) (pr-str (:key f))])
                      (distinct-by-key
                       (for [[_ r] (sort-by key (findings)) f (:findings r)
                             :when (and (not (judged (:key f))) (not (not-judged (second (:key f)))))]
                         f)))
        text (str/join
              "\n"
              (for [{:keys [key line problem]} todo
                    :let [[app-id _ _ file] key]]
                (str "\n;; ---- " (name app-id) " " file ":" line "  " (name (:severity problem)) " " (name (:problem problem)) "\n"
                     ";; " (pr-str (dissoc problem :definition :impl :severity)) "\n"
                     (some->> (snippet (apps app-id) file line) str/split-lines (map #(str ";; " %)) (str/join "\n"))
                     "\n" (pr-str {:finding key :label nil :why "" :by "" :confirmed true}))))
        current (if (fs/exists? labels-file) (str/trim (slurp labels-file)) "[]")]
    (when (seq todo)
      ;; inside the vector: before its closing bracket
      (spit labels-file (str (subs current 0 (str/last-index-of current "]")) text "\n]\n")))
    (println (count todo) "entries appended to" labels-file)))

(defn unlabeled
  "Findings no label judges, each with the code around it and an entry to
   paste into corpus/labels.edn once judged."
  [n]
  (let [apps (into {} (map (juxt :id identity)) (:apps (corpus)))
        judged (set (map :finding (judged)))
        {:keys [not-judged]} (corpus)
        todo (for [[_ r] (sort-by key (findings)) f (:findings r)
                   :when (and (not (judged (:key f))) (not (not-judged (second (:key f)))))]
               f)]
    (println (count todo) "findings to judge\n")
    (doseq [{:keys [key line problem]} (take (or n 20) todo)
            :let [[app-id _ _ file] key]]
      (println "----" (name app-id) (str file ":" line))
      (prn (dissoc problem :definition :impl))
      (some-> (snippet (apps app-id) file line) println)
      (println "  {:finding" (pr-str key))
      (println "   :label :real|:false|:useless|:unsure :confirmed false :by \"\" :why \"\"}\n"))))

;; =============================================================================
;; Recall: planted defects
;; =============================================================================

(defn- matches? [expect p]
  (every? (fn [[k v]] (= v (get p k))) expect))

(defn plant
  "For each planted defect: copy its app, make the one edit, check, and see
   whether a problem matching :expect appears that wasn't there before."
  []
  (let [apps (into {} (map (juxt :id identity)) (:apps (corpus)))
        results (for [{:keys [id app file replace all expect]} (read-edn plants-file)
                      :let [a (apps app)
                            tmp (str (fs/create-temp-dir))
                            copy (str tmp "/app")]]
                  (try
                    (p/shell "rsync" "-a" "--exclude" ".git" "--exclude" ".meta-source" (str (app-root a) "/") (str copy "/"))
                    (let [a' (assoc a :path copy :root nil)
                          before (filter #(matches? expect %) (index/check (index/build (config a'))))
                          f (str (fs/path copy file))
                          text (slurp f)
                          [from to] replace]
                      (when-not (str/includes? text from)
                        (throw (ex-info (str "not in " file ": " from) {})))
                      (spit f (if all (str/replace text from to) (str/replace-first text from to)))
                      (let [after (filter #(matches? expect %) (index/check (index/build (config a'))))
                            found (> (count after) (count before))]
                        (println (if found "found " "MISSED") (name id) (pr-str expect))
                        {:id id :problem (:problem expect) :found found}))
                    (catch Exception e
                      (println "ERROR " (name id) (ex-message e))
                      {:id id :problem (:problem expect) :error (ex-message e)})
                    (finally (fs/delete-tree tmp))))]
    (doall results)
    (println "\n| check | planted | found | recall |")
    (println "|---|---|---|---|")
    (doseq [[problem rs] (sort-by key (group-by :problem (doall results)))
            :let [n (count (remove :error rs)) k (count (filter :found rs))]]
      (println (str "| `" (name problem) "` | " n " | " k " | " (if (pos? n) (pct (/ k n)) "-") " |")))))

;; =============================================================================

(defn ci
  "Runs the corpus and fails when a finding has no label: every new finding
   is judged before it ships."
  []
  (run [])
  (let [judged (set (map :finding (judged)))
        {:keys [not-judged]} (corpus)
        todo (for [[_ r] (findings) f (:findings r)
                   :when (and (not (judged (:key f))) (not (not-judged (second (:key f)))))]
               (:key f))]
    (report)
    (when (seq todo)
      (println (str "\n" (count todo) " findings have no label: bb corpus unlabeled"))
      (System/exit 1))))

(defn -main [& [cmd & args]]
  (case cmd
    "fetch" (fetch)
    "run" (do (run args) nil)
    "report" (report)
    "unlabeled" (unlabeled (some-> (first args) parse-long))
    "todo" (todo)
    "plant" (plant)
    "ci" (ci)
    (println (:doc (meta (the-ns 'meta-source.corpus))))))
