(ns meta-source.hover
  "EXPERIMENTAL (see README.md) — what an editor shows for a keyword
   at point, from meta-source.index: a short line for eldoc, a longer text
   for a describe buffer, and the locations for jump-to-definition and
   find-references.

   A keyword is one of
   - an entity (an id the sources define): what gives it (a pack, an EDN
     entry, a var's meta key), what it consumes and produces, where each
     source defines it, its implementing var, who uses it, and any problem
     `check` reports for it;
   - a data key (consumed or produced by some entity, or shaped by a
     schema): its shape, who produces and consumes it, and where code
     mentions it;
   - none of these.

   Locations are absolute paths, so an editor can open them directly."
  (:require [meta-source.index :as index]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- abs-path [idx file] (str (io/file (:root idx) file)))

(defn- at [{:keys [file line end-line]}]
  (if file
    (str file ":" line (when (and end-line (not= end-line line)) (str "-" end-line)))
    "(no file)"))

(defn- source-line
  "One line saying where a source is and what it gave."
  [s]
  (str (at s) "    "
       (cond
         (:runtime-as-of s) (str "running process: snapshot " (:runtime-as-of s) "    ")
         (:binding s) "meta key    ")
       (str/join " " (map name (:keys s)))
       (when (seq (:dropped s)) (str "    (as code: " (str/join " " (map name (:dropped s))) ")"))))

(defn- few
  "The first n items printed, and how many more there are."
  [xs n]
  (let [xs (vec xs)]
    (str (str/join " " (map pr-str (take n xs)))
         (when (> (count xs) n) (str " +" (- (count xs) n))))))


;; =============================================================================
;; Entity
;; =============================================================================

(defn- entity [idx id]
  (let [w (index/where idx id)
        u (index/uses idx id)
        entry (:entry w)
        problems (filter #(= id (:dev-id %)) (index/check idx))
        {:keys [consumes produces]} (index/contract-keys idx entry)
        deps (map :dev-id (:depends-on (index/dependencies idx id)))
        ;; places that only refer to it (a dispatch) are not where it is defined
        {referring true defining false} (group-by #(boolean (:refers %)) (:sources w))
        ;; keys only those places give (:re-frame/dispatched) are said by "referred to from"
        marker? (set (remove (set (mapcat :keys defining)) (mapcat :keys referring)))
        n-used (+ (count (:referenced-by u)) (count (:called-from u)) (count referring))
        ;; what gives it: a pack, an EDN entry, or only a var's meta key
        kind (cond (get-in w [:definition :pack]) (name (get-in w [:definition :pack]))
                   (get-in w [:definition :binding]) "binding"
                   :else "entry")
        contract (or (index/describe idx entry)
                     (when (or (seq consumes) (seq produces))
                       (str "[" (few (sort consumes) 4) "] → [" (few (sort produces) 4) "]")))
        line2 (str/join "  ·  " (remove nil? [contract
                                               (when (seq deps) (str "deps " (few deps 3)))
                                               (str "used by " n-used)
                                               (when (seq problems) (str "⚠ " (count problems)))]))]
    {:kind "entity"
     :eldoc (str (pr-str id) "  " kind "\n" line2)
     :describe
     (str/join
      "\n"
      (concat
       [(str (pr-str id) "    " kind) ""]
       (when (seq consumes) [(str "consumes   " (str/join " " (map pr-str (sort consumes))))])
       (when (seq produces) [(str "produces   " (str/join " " (map pr-str (sort produces))))])
       (when (seq deps) [(str "deps       " (str/join " " (map pr-str deps)))])
       (for [[k v] (sort-by key entry) :when (not (marker? k))] (str (pr-str k) "  " (pr-str v)))
       [""]
       (for [[i s] (map-indexed vector defining)]
         (str (if (zero? i) "defined    " "           ") (source-line s)))
       (when (and (seq referring) (empty? defining)) ["defined    nowhere read"])
       [(if-let [impl (:impl w)]
          (str "impl       " (or (:var impl) "inline") "    " (at impl))
          "impl       none")]
       [""]
       (when (seq referring)
         (concat (cons "referred to from" (for [s referring] (str "  " (at s) "    " (str/join " " (map name (:keys s))))))
                 [""]))
       (cond
         (seq (:referenced-by u))
         (cons "used by" (for [r (:referenced-by u)] (str "  " (pr-str (:dev-id r)) "  via " (pr-str (:via r)) "    " (at (:definition r)))))
         (empty? referring) ["used by    nothing names it"])
       (when (seq (:called-from u))
         (cons "called from" (for [c (:called-from u)] (str "  " (:var c) (when (:dev-id c) (str " " (pr-str (:dev-id c)))) "    " (at c)))))
       (when-let [note (get-in u [:coverage :note])]
         [(str "  (" (get-in u [:coverage :linked-vars]) " of " (get-in u [:coverage :public-fns]) " public fns linked; " note ")")])
       (when (seq problems)
         (concat [""] (for [p problems]
                        (str "⚠ " (name (:severity p)) " " (name (:problem p))
                             (when (:keys p) (str " " (few (:keys p) 6)))
                             (when (:calls p) (str " calls " (pr-str (:calls p))))
                             (when-let [l (or (:impl p) (:definition p) (when (:file p) p))] (str "    " (at l)))))))))
     :definitions (vec (concat (for [s defining :when (:file s)]
                                 {:file (abs-path idx (:file s)) :line (:line s)
                                  :summary (str "defined: " (str/join " " (map name (:keys s))))})
                               (when-let [impl (:impl w)]
                                 [{:file (abs-path idx (:file impl)) :line (:line impl) :summary (str "impl: " (or (:var impl) "inline"))}])))
     :references (vec (concat (for [s referring :when (:file s)]
                                {:file (abs-path idx (:file s)) :line (:line s)
                                 :summary (str/join " " (map name (:keys s)))})
                              (for [r (:referenced-by u)]
                                {:file (abs-path idx (get-in r [:definition :file])) :line (get-in r [:definition :line])
                                 :summary (str (pr-str (:dev-id r)) " via " (pr-str (:via r)))})
                              (for [c (:called-from u)]
                                {:file (abs-path idx (:file c)) :line (:line c) :summary (str "called from " (:var c))})))}))

;; =============================================================================
;; Data key
;; =============================================================================

(defn- mentions
  "Where code mentions keyword `k`, by the var it appears in."
  [idx k]
  (for [[file {:keys [keywords]}] (sort-by key (:code idx))
        kw keywords
        :when (and (= (str (:ns kw)) (namespace k)) (= (str (:name kw)) (name k)))]
    {:file file :line (:row kw) :var (symbol (str (:from kw)) (str (:from-var kw)))}))

(defn- data-key [idx k]
  (let [{:keys [producers consumers shape props member-of]} (index/data-key idx k)
        code (mentions idx k)
        ids #(str/join ", " (map (comp pr-str :dev-id) %))
        problems (filter #(= k (:dev-id %)) (index/check idx))]
    {:kind "data-key"
     :eldoc (str (pr-str k) "  data key"
                 (str/join (for [s shape] (str "  " (name (:pack s)) " " (:form s))))
                 (str/join (for [[p v] props] (str "  " (pr-str p) " " (pr-str v))))
                 "\nproduced by " (if (seq producers) (ids producers) "nothing")
                 "  ·  consumed by " (if (seq consumers) (ids consumers) "nothing")
                 (when (seq member-of) (str "  ·  in " (ids member-of))))
     :describe (str/join "\n" (concat [(str (pr-str k) "    data key") ""]
                                      (for [s shape] (str (format "%-11s" (name (:pack s))) (:form s) "    " (at s)))
                                      (for [[p v] props] (str (pr-str p) "  " (pr-str v)))
                                      (when (seq member-of)
                                        (cons "member of" (for [m member-of] (str "  " (pr-str (:dev-id m)) "    " (at (:definition m))))))
                                      (when (or (seq shape) (seq props) (seq member-of)) [""])
                                      ["produced by"] (for [p producers] (str "  " (pr-str (:dev-id p)) "    " (at (:definition p))))
                                      [""] ["consumed by"] (for [c consumers] (str "  " (pr-str (:dev-id c)) "    " (at (:definition c))))
                                      [""] [(str "mentioned in code (" (count code) ")")]
                                      (for [m code] (str "  " (:var m) "    " (at {:file (#'index/relative (:root idx) (:file m)) :line (:line m)})))
                                      (when (seq problems)
                                        (concat [""] (for [p problems]
                                                       (str "⚠ " (name (:severity p)) " " (name (:problem p))
                                                            (when (:keys p) (str " " (few (:keys p) 6)))))))))
     :definitions (vec (concat (for [s shape :when (:file s)]
                                 {:file (abs-path idx (:file s)) :line (:line s) :summary (str (name (:pack s)) ": " (:form s))})
                               (for [p producers] {:file (abs-path idx (get-in p [:definition :file])) :line (get-in p [:definition :line]) :summary (str "produced by " (pr-str (:dev-id p)))})))
     :references (vec (concat (for [c consumers] {:file (abs-path idx (get-in c [:definition :file])) :line (get-in c [:definition :line]) :summary (str "consumed by " (pr-str (:dev-id c)))})
                              (for [m code] {:file (:file m) :line (:line m) :summary (str "in " (:var m))})))}))

;; =============================================================================
;; Diagnostics: check's problems, for one file
;; =============================================================================

(defn locations
  "Every {:file :line :end-line} a problem points at."
  [p]
  (concat
   (when (and (:file p) (:line p)) [(select-keys p [:file :line])])
   (for [k [:definition :impl] :let [l (get p k)] :when (:file l)] l)
   (filter :file (:vars p))
   (filter :file (:definitions p))
   (for [v (:values p) s (:sources v) :when (:file s)] s)))

(defn- token-at
  "[line col end-col] for the first occurrence of `k` (a keyword, or a
   symbol) written in `lines` from line `from` (1-based) to `to`, or nil.
   ::name forms are not matched; :ns/name and :name are."
  [lines k from to]
  (let [text (pr-str k)
        pat (re-pattern (str "(?<![\\w:/.*+!?<>=-])" (java.util.regex.Pattern/quote text) "(?![\\w/.*+!?<>=-])"))]
    (some (fn [n]
            (when-let [l (nth lines (dec n) nil)]
              (let [m (re-matcher pat l)]
                (when (.find m) [n (inc (.start m)) (inc (.end m))]))))
          (range from (inc (min to (count lines)))))))

(defn- own-ns-token
  "[line col end-col] for `k` written as ::name, or nil."
  [lines k from to]
  (when (qualified-keyword? k) (token-at lines (symbol (str "::" (name k))) from to)))

(def ^:private window
  "How many lines after a problem's line a key it names is looked for, at
   most: the search also stops where the file's next definition starts."
  40)

(defn- starts
  "The lines where something is defined in the file at canonical `path`:
   entries, forms a pack read, vars."
  [idx path]
  (into (sorted-set)
        (concat (for [[_ e] (:entities idx) d (:definitions e) :when (and (= path (:file d)) (:line d))] (:line d))
                (for [v (get-in idx [:code path :vars])] (:row v)))))

(defn message
  "What the problem says, in a line, about `k` when it is about one key."
  [p k]
  (let [id (some-> (:dev-id p) pr-str)
        ks (or (some-> k pr-str) (str/join " " (map pr-str (:keys p))))]
    (str "meta-source: "
         (case (:problem p)
           :keys-outside-contract (str ks " is used here but is not in " id "'s contract")
           :undeclared-call (str id " calls " (pr-str (:calls p)) ", which its entry doesn't name")
           :conflicting-values (str id " " (pr-str (:key p)) ": the sources give different values: "
                                    (str/join ", " (for [v (:values p) src (:sources v)] (str (:file src) ":" (:line src)))))
           :ref-to-undefined (str "no config defines " ks)
           :init-key-without-config (str "init-key for " id ", which no config defines")
           :ref-ambiguous (str "a ref to " ks " matches " (str/join ", " (map pr-str (:candidates p)))
                               ", configured together: integrant can't choose")
           :ref-cycle (str "the refs go round in a circle: " (str/join " → " (map pr-str (:cycle p))))
           :ref-invalid (str id ": integrant refuses these refs, not a qualified keyword or a vector of them: "
                             (str/join " " (map pr-str (:refs p))))
           :var-unbound (str id ": #ig/var " (str/join " " (:vars p)) " is not in the file's :vars: ig/init refuses it")
           :no-init-key (str "nothing builds " id ": no init-key on it or what it derives from, and no fn named like it")
           :removed-method (str (:method p) " for " id ": integrant removed it in " (:removed-in p)
                                (if (:integrant-version p) (str ", and the project uses " (:integrant-version p)) ""))
           :ref-maybe-from-module (str "no config defines " ks ", which the project's module " (pr-str (:module p)) " may add")
           :data-key-without-schema (str "no schema shapes " ks)
           :schema-key-without-shape (str ks " has no spec of its own")
           :unqualified-schema-keys (str "unqualified key " ks ": dataflow can't follow it")
           :input-without-producer (str "nothing produces " ks)
           :operation-option-not-read (str id ": " (str/join ", " (map name (:options p))) " written as code, not read")
           :pack-form-not-read (str (some-> (:pack p) name) " form not read: " (:reason p))
           :register-call-not-read (str "register! call not read: " (:reason p))
           :id-in-several-packs (str id " is declared by several packs: " (str/join ", " (map name (:packs p))))
           :runtime-disagrees (str id " differs from the running process"
                                   (when (:changed-since-snapshot p) " (the file changed since the snapshot)"))
           :not-in-runtime (str id " is not registered in the running process")
           :runtime-only (str id " is registered in the running process, and in no file read")
           :binding-only (str id ": the meta key names it, no source says anything about it yet")
           :rule-violated (str (or (:doc p) (pr-str (:rule p))) ": " id
                               (when (seq (:offenders p))
                                 (str " (" (str/join " " (for [o (:offenders p)]
                                                             (if-let [path (get (:paths p) o)]
                                                               (str/join " → " (map pr-str path))
                                                               (pr-str o))))
                                      ")"))
                               "  [" (pr-str (:rule p)) "]")
           :rule-not-understood (str "rule " (pr-str (:rule p)) " not understood: " (str/join "; " (:reasons p)))
           :unreadable-rules-file (str "rules file not readable: " (:message p))
           :event-not-registered (str "no reg-event registers " id)
           :route-not-defined (str "no route is named " id)
           :include-not-found (str "#include " (pr-str (name (:dev-id p))) " is not found: aero reads it as {:aero/missing-include ...}")
           :tag-without-reader (str "no reader for #" (str/replace (name (:dev-id p)) "." "/") ": reading the config throws")
           :profile-never-read (str id ": no profile read sees " (str/join " " (map pr-str (:keys p))))
           :profile-without-branch (str id ": #profile has no :default, and no branch for " (str/join " " (map pr-str (:keys p))))
           :ref-to-nothing (str id ": #ref to nothing: " (str/join " " (map pr-str (:paths p))))
           :env-not-set (str id " is read with no default, and no :env-sources file sets it")
           :not-a-number (str id ": #" (name (:tag p)) " over " (pr-str (:value p)) ", not a number")
           :pull-join-not-ref (str "pull joins on " id ", which is not a ref" (when (:in-no-schema p) " (no schema read gives it)"))
           :reverse-not-ref (str "reverse " id " needs it to be a ref" (when (:in-no-schema p) " (no schema read gives it)"))
           :lookup-ref-not-unique (str "lookup ref on " id ", which is not :db/unique" (when (:in-no-schema p) " (no schema read gives it)"))
           :schema-disagrees (str id " is " (:datascript p) " in the Datascript schema, one " (:shape p) " in its spec")
           :handler-not-found (str id "'s handler " (:calls p) " is not defined")
           :sub-not-registered (str "no reg-sub registers " id)
           :effect-not-registered (str "no reg-fx registers the effect " id)
           :cofx-not-registered (str "no reg-cofx registers " id)
           :event-never-dispatched (str "nothing dispatches " id)
           :sub-never-subscribed (str "nothing subscribes to " id)
           :transition-to-no-state (str id ": " (pr-str (:state p)) " on " (pr-str (:on p)) " goes to " (pr-str (:to p))
                                        ", which is no state of it")
           :initial-state-not-a-state (str id " starts at " (pr-str (:to p)) ", which is no state of it")
           :state-defined-twice (str id " defines the state " (pr-str (:state p)) " twice: tilakone takes the first")
           :state-unreachable (str (pr-str (:state p)) " is not reached from " (pr-str (:from p)) " by " id "'s transitions")
           (str (name (:problem p)) (when id (str " " id)) (when (seq (:keys p)) (str " " ks))
                (when (:reason p) (str ": " (:reason p))) (when (:message p) (str ": " (:message p))))))))

(defn brief
  "check's problems as lines for a terminal, most severe first: severity,
   where it is (its first location), and the message the editor shows."
  [idx]
  (vec (for [p (index/check idx)
             :let [l (first (locations p))]]
         (format "%-7s %-34s %s"
                 (name (:severity p))
                 (if l (str (:file l) ":" (:line l)) "")
                 (subs (message p nil) (count "meta-source: "))))))

(defn diagnostics
  "check's problems located in `file` (absolute, or relative to the root),
   for an editor: [{:line :col :end-col :severity :problem :message}], the
   columns 1-based and only when a token was found. A problem about keys
   is underlined at each key, after its line; otherwise at its dev-id on
   its line; otherwise the whole line."
  [idx file]
  (let [f (io/file file)
        path (str (.getCanonicalFile (if (.isAbsolute f) f (io/file (:root idx) file))))
        rel (#'index/relative (:root idx) path)
        lines (when (.isFile (io/file path)) (str/split-lines (slurp path)))
        starts (starts idx path)]
    (vec
     (distinct
      (for [p (index/check idx)
            l (locations p)
            :when (= rel (:file l))
            :let [line (:line l)
                  next-start (first (subseq starts > line))
                  end (or (:end-line l) (min (+ line window) (if next-start (dec next-start) Long/MAX_VALUE)))
                  severity (get {:error "error" :warning "warning"} (:severity p) "note")
                  ;; the key as written, or its name where it is destructured
                  ;; ({:user/keys [loyalty-tier]}), within the form
                  at-keys (keep (fn [k] (when-let [t (or (token-at lines k line end)
                                                         (own-ns-token lines k line end)
                                                         (when (qualified-keyword? k) (token-at lines (symbol (name k)) line end)))]
                                          [k t]))
                                (:keys p))
                  ;; the dev-id as written, or its name as the defining
                  ;; form's symbol ((pco/defresolver seed ...))
                  at-id (when-let [id (:dev-id p)]
                          (or (token-at lines id line line)
                              (own-ns-token lines id line line)
                              (when (qualified-keyword? id) (token-at lines (symbol (name id)) line line))))]
            d (cond
                (seq at-keys) (for [[k [n c e]] at-keys] {:line n :col c :end-col e :key k})
                at-id (let [[n c e] at-id] [{:line n :col c :end-col e}])
                :else [{:line line}])]
        (cond-> (assoc (dissoc d :key) :severity severity :problem (name (:problem p)) :message (message p (:key d)))
          (nil? (:col d)) (dissoc :col :end-col)))))))

;; =============================================================================

(defn lookup
  "What to show for keyword `k`. {:kind \"none\"} when meta-source doesn't know it."
  [idx k]
  (let [entities (:entities idx)]
    (cond
      (:shape (index/data-key idx k)) (data-key idx k)
      (contains? entities k) (entity idx k)
      (or (seq (:producers (index/data-key idx k))) (seq (:consumers (index/data-key idx k)))) (data-key idx k)
      :else {:kind "none"})))
