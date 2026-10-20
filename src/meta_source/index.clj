(ns meta-source.index
  "The static view of a project's sources, read from its files without
   loading any code: the entries in its EDN files, with the line each key is
   written on; the registries its libraries declare, read by packs (specs and
   malli schemas, Pathom resolvers, integrant systems, a project's own); and
   the vars that carry its meta key (^{:meta-source/id ...}), with what each
   var calls and which qualified keywords it mentions, as clj-kondo reads
   them. What several sources say about one id is merged (meta-source.merge).

   It answers where an entity is defined and implemented, what refers to it,
   who produces and consumes a data key, and where the code and the sources
   disagree. `build` reuses a cache of the code analysis and re-analyses only
   the source files whose modification time changed, so every call sees the
   working tree as it is, edits included.

   Runs under babashka (clj-kondo as a pod) and on the JVM (clj-kondo on the
   classpath)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [meta-source.merge :as merge]
            [meta-source.pack.integrant :as integrant]
            [meta-source.pack.pathom :as pathom]
            [meta-source.pack.re-frame :as re-frame]
            [meta-source.pack.schema :as schema]
            [meta-source.read :as read]))

(def ^:private bb? (some? (System/getProperty "babashka.version")))

(def cache-version 1)

(def default-id-key
  "The meta key a plain var carries to say which id it implements. A
   project can choose another: :id-key in meta-source.edn."
  :meta-source/id)

;; =============================================================================
;; Files
;; =============================================================================

(defn- canonical [f] (.getCanonicalPath (io/file f)))

(defn- under [root d]
  (let [f (io/file d)] (if (.isAbsolute f) f (io/file root d))))

(defn- hidden?
  "Dotfiles and dot-directories (.lsp, .clj-kondo, Emacs lock files .#x) and
   Emacs auto-save files (#x#): never sources."
  [^File f]
  (let [n (.getName f)] (or (str/starts-with? n ".") (str/starts-with? n "#"))))

(defn- files-under [root dirs exts]
  (sort (for [d dirs
              :let [top (under root d)]
              ^File f (tree-seq #(and (.isDirectory ^File %) (or (= % top) (not (hidden? %))))
                                #(seq (.listFiles ^File %))
                                top)
              :when (and (.isFile f) (not (hidden? f)) (some #(str/ends-with? (.getName f) %) exts))]
          (canonical f))))

(defn- relative [root path]
  (let [r (str (canonical root) "/")]
    (if (str/starts-with? path r) (subs path (count r)) path)))

;; =============================================================================
;; Registry files
;; =============================================================================

(defn read-registry-file
  "{dev-id {:entry entry :file path :line n}} for one EDN registry file."
  [path]
  (let [entries (read/keyed-lines (slurp path))]
    (when (nil? entries)
      (throw (ex-info "A registry file must hold a map of dev-id to entry" {:file path})))
    (into {} (for [[id entry line] entries]
               [id {:entry entry :file path :line line}]))))

;; =============================================================================
;; Reading forms in code
;; =============================================================================

(defn- resolve-keywords
  "`text` with auto-resolved keywords written out: ::alias/name with the
   namespace the file aliases, ::name with the file's own namespace."
  [text own-ns aliases]
  (str/replace text #"::([^\s/\[\](){}\"@^`~,;]+)(/)?"
               (fn [[_ a slash]]
                 (if slash
                   (str ":" (get aliases a a) "/")
                   (str ":" own-ns "/" a)))))

(defn- call-at
  "The form that starts with the `(` just before the symbol at `row`/`col`
   (1-based), :unreadable when it can't be read as data, nil when the symbol
   isn't at the head of a call. Auto-resolved keywords are resolved with the
   file's namespace and aliases first. `read-opts` go to read-string: the
   schema pack reads .cljc reader conditionals as :clj."
  ([lines row col own-ns aliases] (call-at lines row col own-ns aliases nil))
  ([lines row col own-ns aliases read-opts]
   (let [line (nth lines (dec row) nil)]
     (when (and line (>= col 2) (= \( (nth line (- col 2) nil)))
       (try (binding [*read-eval* false *default-data-reader-fn* tagged-literal]
              (let [text (resolve-keywords (str/join "\n" (cons (subs line (- col 2)) (drop row lines)))
                                           own-ns aliases)]
                (if read-opts (read-string read-opts text) (read-string text))))
            (catch Exception _ :unreadable))))))

;; =============================================================================
;; Packs
;; =============================================================================
;;
;; A pack reads one library's own registry, in place: clojure.spec and malli
;; (meta-source.pack.schema), Pathom (meta-source.pack.pathom), or a
;; project's own, named by its qualified symbol under :packs. A pack is a map;
;; only :pack/find and :pack/read are required.
;;
;;   :pack/id               a keyword naming it
;;   :pack/config-keys      the :packs keys that enable a built-in pack
;;   :pack/kinds            the :kind of the sources it gives (one pack per kind)
;;   :pack/vocabulary       the key namespaces its entries use
;;   :pack/data-keys?       true when its entities are data keys (schemas)
;;   :pack/dataflow         {:consumes [k ...] :produces [k ...]}: entry keys
;;                          that list data keys
;;   :pack/find             (fn [analysis opts]) → sites, each {:filename :row
;;                          :name-row :name-col ...} found in clj-kondo's
;;                          analysis; name-row/name-col is the symbol right
;;                          after the form's opening paren
;;   :pack/read             (fn [sites file opts]) → [{:row :kind :dev-id
;;                          :entry} or {:row :kind :skipped reason}] for one
;;                          file's sites; file is {:form-at (fn [row col])
;;                          :read (fn [text]) :own-ns :aliases}, form-at reading
;;                          the form at a position as data, read reading text
;;                          with the file's ::keywords resolved. A result
;;                          with :refers true is a place that refers to the
;;                          id (a dispatch): never its primary definition
;;   :pack/checks           (fn [view]) → problems; view is {:entities
;;                          :of-kinds :data-key? :loc :flow :opts}
;;   :pack/inline-data-keys (fn [entities]) → data keys its entities define
;;                          inline, with no entity of their own
;;   :pack/shape            (fn [entry kind]) → a data key's shape, as text
;;   :pack/members          (fn [entry]) → the data keys a schema is made of
;;   :pack/describe         (fn [entry]) → one line for an editor, or nil
;;   :pack/merge            {key :union}: keys whose values it unions across
;;                          sources (a set of tags); others conflict
;;   :pack/data-files       (fn [opts]) → paths under the root, files or
;;                          directories of .edn, that the pack reads as data
;;   :pack/read-data        (fn [files opts]) → results as :pack/read gives,
;;                          each with its :file; files is [{:file :path :text}],
;;                          all of them at once, so a key several files define
;;                          can be one result (meta-source.read/keyed-lines
;;                          reads them with each key's line)
;;   :pack/runtime-fill     (fn [entry runtime]) → entry with what static reading
;;                          couldn't read taken from the running process's entry
;;   :pack/runtime-diff     (fn [entry runtime]) → where code and the running
;;                          process disagree, or nil
;;
;; A pack's runtime half is a snapshot file the running process writes (the
;; Pathom pack's `snapshot!`): {:pack id :written epoch-ms :entities {dev-id
;; entry}}, at .meta-source/packs/<id>.edn or the pack's :snapshot option. The
;; index reads it beside the code: it fills what static reading couldn't
;; read, adds what only the process has, and checks where they disagree.
;;
;; `opts` is meta-source.edn's :packs map. What :pack/read returns is
;; cached per file, so it must be data. A pack needs :pack/find and
;; :pack/read, or :pack/data-files and :pack/read-data, or both.

(def builtin-packs [schema/pack pathom/pack integrant/pack re-frame/pack])

(defn enabled-packs
  "The packs `packs-config` (meta-source.edn's :packs) enables: each
   built-in pack one of whose :pack/config-keys it has, and each qualified
   symbol in it, resolved to a project's own pack."
  [packs-config]
  (let [ks (set (keys packs-config))
        own (for [k ks :when (qualified-symbol? k)]
              (let [p (some-> (requiring-resolve k) deref)]
                (when-not (and (map? p) (or (and (:pack/find p) (:pack/read p))
                                            (and (:pack/data-files p) (:pack/read-data p))))
                  (throw (ex-info "A pack is a map with :pack/find and :pack/read, or :pack/data-files and :pack/read-data" {:pack k})))
                (update p :pack/config-keys (fnil conj #{}) k)))
        ps (vec (concat (filter #(some ks (:pack/config-keys %)) builtin-packs) (sort-by :pack/id own)))]
    (when-let [dup (seq (for [[k n] (frequencies (mapcat :pack/kinds ps)) :when (> n 1)] k))]
      (throw (ex-info "Two packs give sources of the same kind" {:kinds (vec dup)})))
    ps))

(defn- read-packs
  "{pack-id [result ...]} for `file`: each pack's :pack/read over its sites
   there, once per position (a .cljc file reports each form per language)."
  [file found packs-config own-ns aliases]
  (let [lines (delay (str/split-lines (slurp file)))
        opts {:read-cond :allow :features #{:clj}}
        f {:own-ns own-ns :aliases aliases
           :form-at (fn [row col] (call-at @lines row col own-ns aliases opts))
           :read (fn [text] (binding [*read-eval* false] (read-string opts (resolve-keywords text own-ns aliases))))}]
    (into {} (for [[pack sites] found
                   :let [sites (sort-by :row (map first (vals (group-by (juxt :name-row :name-col) sites))))]]
               [(:pack/id pack)
                ;; a pack that fails on a file costs that file, not the index
                (try (vec ((:pack/read pack) sites f packs-config))
                     (catch Exception ex
                       [{:row (:row (first sites)) :kind (first (sort (:pack/kinds pack)))
                         :skipped (str "the pack failed reading this file: " (ex-message ex))}]))]))))

(defn- merge-policy
  "{key :union}: meta-source.edn's :merge, plus what each enabled pack declares."
  [config packs]
  (apply merge (concat (keep :pack/merge packs) [(:merge config)])))

(defn- dataflow
  "Which entry keys list consumed and produced data keys: meta-source.edn's
   :dataflow, plus what each enabled pack declares."
  [config packs]
  (apply merge-with (comp vec distinct concat)
         {:consumes [] :produces []}
         (:dataflow config)
         (keep :pack/dataflow packs)))

(defn- snapshot-file
  "Where `pack`'s runtime snapshot is: its :snapshot option, or
   .meta-source/packs/<id>.edn, under `root`."
  [root packs-config pack]
  (under root (or (some #(get-in packs-config [% :snapshot]) (:pack/config-keys pack))
                  (str ".meta-source/packs/" (name (:pack/id pack)) ".edn"))))

(defn- pack-data-files
  "[[pack [canonical-path ...]] ...] for the packs that read data files."
  [root packs-config packs]
  (vec (for [p packs :when (:pack/data-files p)]
         [p (vec (distinct (for [d ((:pack/data-files p) packs-config)
                                 :let [f (under root d)]
                                 path (cond (.isDirectory f) (files-under root [d] [".edn"])
                                            (.isFile f) [(canonical f)])]
                             path)))])))

(defn- read-snapshots
  "[{:pack-id :kind :file :written :entities}] for each enabled pack whose
   snapshot file exists and reads."
  [root packs-config packs]
  (vec (for [p packs
             :let [f (snapshot-file root packs-config p)]
             :when (.isFile f)
             :let [data (try (edn/read-string (slurp f)) (catch Exception _ nil))]
             :when (map? (:entities data))]
         {:pack-id (:pack/id p) :kind (first (sort (:pack/kinds p))) :file (canonical f)
          :written (:written data) :entities (:entities data)})))

(defn- instant [ms] (when ms (str (java.time.Instant/ofEpochMilli ms))))

(defn- pack-kinds [idx] (set (mapcat :pack/kinds (:packs idx))))

(defn- pack-of [idx kind] (first (filter #(contains? (:pack/kinds %) kind) (:packs idx))))

(defn describe
  "The line a pack gives an editor for `entry`, or nil."
  [idx entry]
  (some #(when-let [d (:pack/describe %)] (d entry)) (:packs idx)))

;; =============================================================================
;; Code (clj-kondo)
;; =============================================================================

(def ^:private kondo
  (delay
    (if bb?
      (do ((requiring-resolve 'babashka.pods/load-pod) 'clj-kondo/clj-kondo "2026.01.19")
          (requiring-resolve 'pod.borkdude.clj-kondo/run!))
      (or (try (requiring-resolve 'clj-kondo.core/run!) (catch Exception _ nil))
          (throw (ex-info "meta-source.index needs clj-kondo on the classpath" {}))))))

(defn- kondo-config-dir
  "The project's .clj-kondo directory under `root`, or nil."
  [root]
  (let [d (io/file root ".clj-kondo")]
    (when (.isDirectory d) (canonical d))))

(defn- kondo-config-stamps
  "{path mtime} for the project's clj-kondo config: its config.edn, the
   configs it imports and its hooks, not its .cache. When one changes, the
   code is analysed again."
  [root]
  (when-let [d (kondo-config-dir root)]
    (into (sorted-map)
          (for [^java.io.File f (file-seq (io/file d))
                :let [rel (relative d (canonical f))]
                :when (and (.isFile f) (not (str/starts-with? rel ".cache")))]
            [rel (.lastModified f)]))))

(defn- analyse-code
  "{file {:vars [..] :calls [..] :keywords [..] :pack-results {pack-id [..]}}}
   for `files`, the parts of clj-kondo's analysis the index uses, and what
   each enabled pack reads there. A var carrying `id-key` in its meta gets
   that :dev-id. clj-kondo reads the project's own .clj-kondo config under
   `root` (its :lint-as and hooks, the configs it imports), as the editor's
   clj-kondo does, wherever meta-source runs from: macros the project
   teaches clj-kondo are analysed as what they define."
  [root files packs packs-config id-key]
  (if (empty? files)
    {}
    (let [{:keys [analysis]} (@kondo (cond-> {:lint (vec files)
                                              :config {:output {:canonical-paths true}
                                                       :linters ^:replace {}
                                                       :analysis {:var-definitions {:meta [id-key]}
                                                                  :keywords true}}}
                                       (kondo-config-dir root) (assoc :config-dir (kondo-config-dir root))))
          by-file (fn [k xf] (group-by :filename (into [] xf (get analysis k))))
          vars (by-file :var-definitions
                        (map (fn [{:keys [meta] :as d}]
                               (cond-> (select-keys d [:filename :ns :name :row :end-row :private :defined-by])
                                 (get meta id-key) (assoc :dev-id (get meta id-key))))))
          calls (by-file :var-usages
                         (comp (filter :from-var)
                               (map #(select-keys % [:filename :from :from-var :to :name :row]))))
          own-ns (into {} (for [{:keys [filename name]} (:namespace-definitions analysis)] [filename (str name)]))
          aliases (update-vals (group-by :filename (filter :alias (:namespace-usages analysis)))
                               #(into {} (for [{:keys [alias to]} %] [(str alias) (str to)])))
          ;; a :refer in an ns form names a var; it is never a form to read
          for-packs (update analysis :var-usages #(remove :refer %))
          found (vec (for [p packs :when (:pack/find p)] [p (group-by :filename ((:pack/find p) for-packs packs-config))]))
          kws (by-file :keywords
                       (comp (filter #(and (:from-var %) (:ns %)))
                             (map #(select-keys % [:filename :from :from-var :ns :name :row]))))]
      (into {} (for [f files :let [f (canonical f)]]
                 [f {:vars (vec (get vars f)) :calls (vec (get calls f)) :keywords (vec (get kws f))
                     :pack-results (let [here (for [[p by-file] found :let [sites (get by-file f)] :when (seq sites)] [p sites])]
                                     (if (seq here)
                                       (read-packs f here packs-config (get own-ns f "user") (get aliases f {}))
                                       {}))}])))))

;; =============================================================================
;; Building
;; =============================================================================

(defn- mtime [path] (.lastModified (io/file path)))

(def ^:private transit
  "cognitect.transit's reader and writer fns when it is on the classpath (it
   is built into bb). The cache is ~10x faster to read as transit than EDN."
  (delay (try {:writer (requiring-resolve 'cognitect.transit/writer) :write (requiring-resolve 'cognitect.transit/write)
               :reader (requiring-resolve 'cognitect.transit/reader) :read (requiring-resolve 'cognitect.transit/read)}
              (catch Exception _ nil))))

(defn- write-cache [path c]
  (io/make-parents path)
  (if-let [{:keys [writer write]} @transit]
    (with-open [o (io/output-stream path)] (write (writer o :json) c))
    (spit path (pr-str c))))

(defn- read-cache [path config]
  (try
    (let [c (if-let [{:keys [reader read]} @transit]
              (with-open [i (io/input-stream path)] (read (reader i :json)))
              (edn/read-string (slurp path)))]
      (when (and (= cache-version (:version c)) (= config (:config c))) c))
    (catch Exception _ nil)))

(declare build*)

(defn build
  "The index of the project at `root`. `:registry` and `:src` are directories
   under root holding the EDN registry files and the source. With `:cache`, a
   file path, the code analysis is reused for unchanged source files and the
   cache rewritten. With `:previous`, an index this fn returned, that index
   is the cache instead: a long-lived process refreshes in memory, and the
   registry files are re-read only when one of them changed."
  [{:keys [root registry src cache previous packs dataflow id-key] :as opts
    :or {root "." id-key default-id-key}}]
  (let [config {:root (canonical root) :registry (vec registry) :src (vec src)
                :packs (or packs {}) :dataflow dataflow :merge (:merge opts) :id-key id-key
                ;; a changed clj-kondo config changes what the code analyses to
                :kondo-config (kondo-config-stamps root)}
        edn-files (vec (files-under root registry [".edn"]))
        src-files (files-under root src [".clj" ".cljc" ".cljs"])
        cached (cond
                 (and previous (= config (:config previous))) previous
                 cache (read-cache cache config))
        packs (enabled-packs (:packs config))
        stamps (into {} (map (juxt identity mtime)) src-files)
        data-files (pack-data-files root (:packs config) packs)
        edn-stamps (into {} (map (juxt identity mtime))
                         (concat edn-files
                                 (for [p packs :let [f (snapshot-file root (:packs config) p)] :when (.isFile f)] (canonical f))
                                 (mapcat second data-files)))]
    (if (and previous (identical? cached previous) (= stamps (:stamps previous)) (= edn-stamps (:edn-stamps previous)))
      (assoc previous :analysed 0)
      (build* config packs data-files root cache cached stamps edn-stamps edn-files src-files))))

(defn- build* [config packs data-files root cache cached stamps edn-stamps edn-files src-files]
  (let [fresh? #(and (= (stamps %) (get-in cached [:stamps %])) (contains? (:code cached) %))
        stale (remove fresh? src-files)
        code (merge (select-keys (:code cached) (filter fresh? src-files))
                    (analyse-code root stale packs (:packs config) (:id-key config)))
        ;; registry files: re-read only the ones that changed
        edn (into {} (for [f edn-files]
                       [f (if (= (edn-stamps f) (get-in cached [:edn-stamps f]))
                            (get-in cached [:edn f])
                            (try {:located (read-registry-file f)}
                                 (catch Exception ex
                                   {:problem {:problem :unreadable-registry-file :file (relative root f) :message (ex-message ex)}})))]))
        ;; packs reading data files: all of a pack's files at once
        data-results (vec (for [[p files] data-files
                                :let [fs (vec (for [f files] {:file f :path (relative root f) :text (slurp f)}))]
                                r (try ((:pack/read-data p) fs (:packs config))
                                       (catch Exception ex
                                         [{:file (first files) :row 1 :kind (first (sort (:pack/kinds p)))
                                           :skipped (str "the pack failed reading its files: " (ex-message ex))}]))]
                            r))
        ;; sources: each registry file
        sources (concat
                 (for [[f {:keys [located]}] edn :when located]
                   {:label f :file f :kind :edn
                    :lines (update-vals located :line) :entities (update-vals located :entry)})
                 ;; packs: one source per entry a pack reads, from code or data files
                 ;; :dropped, the props a pack read as code rather than data
                 (for [{:keys [file row kind dev-id entry dropped refers]}
                       (concat (for [[f {:keys [pack-results]}] code
                                     [_ results] (sort-by key pack-results)
                                     r results]
                                 (assoc r :file f))
                               data-results)
                       :when dev-id]
                   {:label (str file ":" row ":" dev-id) :file file :kind kind :dropped dropped :refers refers
                    :lines {dev-id row} :entities {dev-id entry}}))
        by-label (into {} (map (juxt :label identity)) sources)
        data-key-kinds (set (mapcat :pack/kinds (filter :pack/data-keys? packs)))
        merged (merge/merge-sources (for [{:keys [label entities]} sources] {:source label :entities entities})
                                    (merge-policy config packs))
        definition (fn [id label]
                     (let [{:keys [file kind lines dropped refers]} (by-label label)]
                       (cond-> {:file file :line (get lines id) :kind kind
                                :keys (vec (sort (for [[k ls] (get-in merged [:provenance id]) :when (contains? ls label)] k)))}
                         (seq dropped) (assoc :dropped dropped)
                         refers (assoc :refers true))))
        static (into {} (for [[id entry] (:entities merged)
                              :let [defs (mapv #(definition id %) (merge/sources-of (:provenance merged) id))
                                    ;; what a pack declares (a component, a resolver, a
                                    ;; data key's schema) before what EDN adds to it,
                                    ;; and both before a place that only refers to it
                                    primary (or (first (filter #(data-key-kinds (:kind %)) defs))
                                                (first (remove #(or (= :edn (:kind %)) (:refers %)) defs))
                                                (first (remove :refers defs))
                                                (first defs))]]
                          [id (assoc primary :entry entry :definitions defs)]))
        ;; packs' runtime snapshots: fill what static reading couldn't read,
        ;; add what only the running process has
        snapshots (read-snapshots root (:packs config) packs)
        pack-of-id (into {} (for [p packs] [(:pack/id p) p]))
        static (reduce (fn [st {:keys [pack-id kind file written entities]}]
                         (let [fill (or (:pack/runtime-fill (pack-of-id pack-id)) (fn [e _] e))]
                           (reduce (fn [st [id rentry]]
                                     (let [d {:kind kind :runtime true :written written :snapshot file
                                              :keys (vec (sort (keys rentry)))}]
                                       (if-let [e (get st id)]
                                         (assoc st id (-> e
                                                          (update :entry fill rentry)
                                                          (update :definitions conj d)
                                                          (assoc :runtime {:pack-id pack-id :entry rentry :written written})))
                                         (assoc st id (assoc d :entry rentry :definitions [d]
                                                             :runtime {:pack-id pack-id :entry rentry :written written})))))
                                   st entities)))
                       static snapshots)
        ;; a var carrying the meta key for an id no source defines: an entity
        ;; with its binding only, which hover, uses and checks still see
        bindings (into {} (for [[f {:keys [vars]}] code
                                {:keys [dev-id row] :as v} vars
                                :when (and dev-id (not (contains? static dev-id)))
                                :let [d {:file f :line row :kind :binding :keys []}]]
                            [dev-id (assoc d :entry {} :definitions [d])]))]
    (when (and cache (or (seq stale) (not= (:stamps cached) stamps)))
      (write-cache cache {:version cache-version :config config :stamps stamps :code code}))
    {:root (canonical root)
     :config config
     :packs packs
     :dataflow (dataflow config packs)
     :stamps stamps
     :edn-stamps edn-stamps
     :edn edn
     :entities (merge bindings static)
     :code code
     :analysed (count stale)
     :pack-snapshots (mapv #(-> % (dissoc :entities) (assoc :ids (set (keys (:entities %))))) snapshots)
     :pack-data-results data-results
     :read-problems (concat (keep :problem (vals edn))
                            (for [c (:conflicts merged)]
                              (update c :values (fn [vs] (mapv (fn [v] (update v :sources #(mapv (fn [l] (let [{:keys [file lines]} (by-label l)]
                                                                                                          {:file (relative root file) :line (get lines (:dev-id c))})) %))) vs)))))}))

;; =============================================================================
;; Views
;; =============================================================================

(defn- all-vars [idx] (mapcat :vars (vals (:code idx))))

(defn linked-vars
  "{dev-id [var-definition ...]} for the vars that name a dev-id."
  [idx]
  (group-by :dev-id (filter :dev-id (all-vars idx))))

(defn- impl-of [idx id] (first (get (linked-vars idx) id)))

(defn- loc [idx {:keys [file line filename row end-row ns name kind] :as m}]
  (cond
    (and (:runtime m) (not file)) {:pack kind :runtime-as-of (instant (:written m))
                                   :snapshot (relative (:root idx) (:snapshot m))}
    file (cond-> {:file (relative (:root idx) file) :line line}
           (= kind :binding) (assoc :binding true)
           ((pack-kinds idx) kind) (assoc :pack kind))
    (contains? m :file) nil
    filename {:file (relative (:root idx) filename) :line row :end-line end-row :var (symbol (str ns) (str name))}))

(defn- data-key?
  "True when a pack whose entities are data keys gives the entity, and no
   pack of declared things does: a spec under a component's own key (as
   integrant configs are often specced) shapes the component, which stays a
   component."
  [idx e]
  (let [{data true declared false} (group-by #(boolean (:pack/data-keys? %)) (:packs idx))
        kinds-of #(set (mapcat :pack/kinds %))
        given (set (map :kind (:definitions e)))]
    (boolean (and (some (kinds-of data) given) (not (some (kinds-of declared) given))))))

(defn- references
  "[[prop dev-id] ...] for every registry entity an entry names in its props.
   Data keys are not references: `data-key` answers for them."
  [idx entry]
  (let [ids (:entities idx)]
    (distinct
     (for [[k v] entry
           x (tree-seq coll? seq v)
           :when (and (qualified-keyword? x) (contains? ids x) (not (data-key? idx (get ids x))))]
       [k x]))))

(defn- of-kinds
  "The entities some source of one of `kinds` gives."
  [idx kinds]
  (filter (fn [[_ e]] (some #(contains? kinds (:kind %)) (:definitions e))) (:entities idx)))

(defn- data-key-entities [idx]
  (filter #(data-key? idx (val %)) (:entities idx)))

(defn- inline-data-keys
  "Data keys the packs' entities define inline, with no entity of their own."
  [idx]
  (into #{} (mapcat #(when-let [f (:pack/inline-data-keys %)] (f (of-kinds idx (:pack/kinds %))))) (:packs idx)))

(defn contract-keys
  "The data keys an entry consumes and produces: the values of the keys the
   index's :dataflow lists (meta-source.edn's, and each pack's)."
  [idx entry]
  (let [{:keys [consumes produces]} (:dataflow idx)
        declared (fn [ks] (mapcat #(let [v (get entry %)] (when (coll? v) v)) ks))]
    {:consumes (set (declared consumes))
     :produces (set (declared produces))}))

;; =============================================================================
;; Questions
;; =============================================================================

(defn where
  "Where `id` is defined and implemented, with its merged entry.
   :definition is its primary source; :sources lists every source that gave
   it something (a registry file, a pack's form, a running process's
   snapshot) and which keys, with the props a pack read as code rather than
   data under :dropped, and :refers true on a place that only refers to it
   (a re-frame dispatch). :impl is the var carrying the meta key."
  [idx id]
  (when-let [e (get-in idx [:entities id])]
    {:dev-id id
     :definition (loc idx e)
     :impl (some->> (impl-of idx id) (loc idx))
     :sources (mapv (fn [d] (cond-> (assoc (loc idx d) :keys (:keys d))
                              (:dropped d) (assoc :dropped (:dropped d))
                              (:refers d) (assoc :refers true)))
                    (:definitions e))
     :entry (:entry e)}))

(defn- coverage-note [idx]
  (let [linked (count (filter :dev-id (all-vars idx)))
        fns (count (filter #(and (not (:private %)) (= 'clojure.core/defn (:defined-by %))) (all-vars idx)))]
    {:linked-vars linked :public-fns fns
     :note (when (< 0 linked fns) "only linked vars are seen as entities; calls from unlinked code are listed but not their entities")}))

(defn uses
  "What refers to `id`: entries naming it in their props, and code calling
   its var."
  [idx id]
  (let [impl (impl-of idx id)
        var->id (into {} (for [[k [v]] (linked-vars idx)] [[(:ns v) (:name v)] k]))]
    {:dev-id id
     :referenced-by (vec (for [[other e] (sort-by key (:entities idx))
                               [prop x] (references idx (:entry e))
                               :when (= x id)]
                           {:dev-id other :via prop :definition (loc idx e)}))
     :called-from (vec (when impl
                         (for [c (mapcat :calls (vals (:code idx)))
                               :when (and (= (:to c) (:ns impl)) (= (:name c) (:name impl)))]
                           (cond-> {:var (symbol (str (:from c)) (str (:from-var c)))
                                    :file (relative (:root idx) (:filename c)) :line (:row c)}
                             (var->id [(:from c) (:from-var c)]) (assoc :dev-id (var->id [(:from c) (:from-var c)]))))))
     :coverage (coverage-note idx)}))

(defn dependencies
  "The entities `id`'s entry names, with where each is defined."
  [idx id]
  (when-let [e (get-in idx [:entities id])]
    {:dev-id id
     :depends-on (vec (for [[prop x] (references idx (:entry e))]
                        {:dev-id x :via prop :definition (loc idx (get-in idx [:entities x]))}))}))

(defn data-key
  "The entities producing (response) and consuming (context) data key `k`.
   When a pack shapes it: :shape, each pack's form and where it is written;
   :props, what sources other than the packs say about it (:data/privacy,
   say); :member-of, the schemas it is a key of."
  [idx k]
  (let [rows (for [[id e] (sort-by key (:entities idx))
                   :let [{:keys [consumes produces]} (contract-keys idx (:entry e))]]
               {:dev-id id :produces? (contains? produces k) :consumes? (contains? consumes k)
                :definition (loc idx e)})
        e (get-in idx [:entities k])
        own (when (and e (data-key? idx e)) (:entry e))
        vocabulary (into #{} (mapcat :pack/vocabulary) (:packs idx))
        member-of (vec (for [[id s] (sort-by key (data-key-entities idx))
                             :when (some (fn [p] (when-let [m (:pack/members p)] (some #{k} (m (:entry s)))))
                                         (:packs idx))]
                         {:dev-id id :definition (loc idx s)}))]
    (cond-> {:data-key k
             :producers (vec (for [r rows :when (:produces? r)] (select-keys r [:dev-id :definition])))
             :consumers (vec (for [r rows :when (:consumes? r)] (select-keys r [:dev-id :definition])))}
      own (assoc :shape (vec (for [d (:definitions e)
                                   :let [p (pack-of idx (:kind d))]
                                   :when (and p (:pack/data-keys? p))]
                               (assoc (loc idx d) :form (when-let [f (:pack/shape p)] (f own (:kind d))))))
                 :props (into (sorted-map) (remove #(vocabulary (namespace (key %)))) own))
      (seq member-of) (assoc :member-of member-of))))

;; =============================================================================
;; Checks
;; =============================================================================

(defn- undeclared-calls
  "A linked var calling another linked var whose entity its entry doesn't name."
  [idx]
  (let [var->id (into {} (for [[k [v]] (linked-vars idx)] [[(:ns v) (:name v)] k]))]
    (for [c (mapcat :calls (vals (:code idx)))
          :let [from (var->id [(:from c) (:from-var c)])
                to (var->id [(:to c) (:name c)])]
          :when (and from to (not= from to)
                     (not (some #(= to (second %)) (references idx (get-in idx [:entities from :entry])))))]
      {:severity :warning :problem :undeclared-call :dev-id from :calls to
       :file (relative (:root idx) (:filename c)) :line (:row c)})))

(defn- keys-outside-contract
  "Data keys a linked var mentions that are neither in what it consumes or
   produces nor in what an entity it names produces. Only keys that appear
   in some entity's contract, or that a pack shapes, count as data keys. A
   binding-only entity has declared no contract, so it has none to keep."
  [idx]
  (let [contracts (into {} (for [[id e] (:entities idx)] [id (contract-keys idx (:entry e))]))
        data-keys (-> #{}
                      (into (mapcat (fn [{:keys [consumes produces]}] (concat consumes produces))) (vals contracts))
                      (into (map key) (data-key-entities idx))
                      (into (inline-data-keys idx)))]
    (for [[id [v]] (sort-by key (linked-vars idx))
          :when (and (contains? (:entities idx) id)
                     (not (every? #(= :binding (:kind %)) (get-in idx [:entities id :definitions]))))
          :let [own (contracts id)
                via-deps (into #{} (mapcat #(get-in contracts [(second %) :produces]))
                               (references idx (get-in idx [:entities id :entry])))
                allowed (into (into (:consumes own) (:produces own)) via-deps)
                outside (->> (get-in idx [:code (:filename v) :keywords])
                             (filter #(and (= (:from %) (:ns v)) (= (:from-var %) (:name v))))
                             (map #(keyword (str (:ns %)) (str (:name %))))
                             (filter data-keys)
                             (remove allowed)
                             distinct sort vec)]
          :when (seq outside)]
      {:severity :info :problem :keys-outside-contract :dev-id id :keys outside
       :impl (loc idx v)})))

(defn- identity-problems
  "One id that two packs of declared things give (:id-in-several-packs,
   warning): a resolver and a component sharing a keyword are two things
   under one name. Schemas don't count: data keys are global, so a spec and
   a malli schema shaping one key are one entity, and a spec under a
   component's key is that component's shape."
  [idx]
  (let [kind->pack (into {} (for [p (:packs idx) k (:pack/kinds p)] [k p]))]
    (for [[id e] (sort-by key (:entities idx))
          :let [packs (distinct (keep #(kind->pack (:kind %)) (remove :runtime (:definitions e))))]
          :when (> (count (remove :pack/data-keys? packs)) 1)]
      {:severity :warning :problem :id-in-several-packs :dev-id id
       :packs (vec (sort (map :pack/id packs)))
       :definitions (vec (for [d (:definitions e) :when (and (kind->pack (:kind d)) (not (:runtime d)))] (loc idx d)))})))

(defn- runtime-problems
  "Where a pack's runtime snapshot and the code differ:
   - :runtime-disagrees (warning): the pack's :pack/runtime-diff between what
     the code says and what the running process registered.
     :changed-since-snapshot is true when the file changed after the
     snapshot was written, so the snapshot may be the stale side;
   - :not-in-runtime (info): defined in the code, not registered in the
     running process: unused, or a registration forgotten;
   - :runtime-only (info): registered in the running process, in no file
     the pack read: built as a value, generated, or in code not indexed."
  [idx]
  (for [{:keys [pack-id kind written ids] :as snap} (:pack-snapshots idx)
        :let [pack (first (filter #(= pack-id (:pack/id %)) (:packs idx)))
              diff (:pack/runtime-diff pack)
              snapshot (relative (:root idx) (:file snap))]
        [id e] (sort-by key (:entities idx))
        :let [code-defs (filter #(and (= kind (:kind %)) (not (:runtime %))) (:definitions e))
              runtime (:runtime e)]
        p (cond
            (and (seq code-defs) (not (contains? ids id)))
            [{:severity :info :problem :not-in-runtime :dev-id id :pack kind :definition (loc idx e)
              :snapshot snapshot :snapshot-as-of (instant written)}]
            (and runtime (empty? code-defs) (= pack-id (:pack-id runtime)))
            [{:severity :info :problem :runtime-only :dev-id id :pack kind :snapshot snapshot
              :snapshot-as-of (instant written)}]
            (and runtime (seq code-defs) diff)
            (when-let [d (diff (:entry e) (:entry runtime))]
              (let [changed (some #(when-let [t (get (:stamps idx) (:file %))] (> t (or written 0))) code-defs)]
                [{:severity :warning :problem :runtime-disagrees :dev-id id :pack kind :diff d
                  :definition (loc idx e) :snapshot snapshot :snapshot-as-of (instant written)
                  :changed-since-snapshot (boolean changed)}])))]
    p))

(defn- pack-problems
  "Each enabled pack's :pack/checks, the forms a pack found but couldn't
   read (:pack-form-not-read, info), and where its runtime snapshot and the
   code differ."
  [idx]
  (let [view {:entities (:entities idx)
              :opts (get-in idx [:config :packs])
              :of-kinds #(of-kinds idx %)
              :data-key? #(data-key? idx %)
              :loc #(loc idx %)
              :flow #(contract-keys idx %)}]
    (concat
     (mapcat #(when-let [c (:pack/checks %)] (c view)) (:packs idx))
     (for [{:keys [file row kind skipped]} (concat (for [[f {:keys [pack-results]}] (sort-by key (:code idx))
                                                         [_ results] (sort-by key pack-results)
                                                         r results]
                                                     (assoc r :file f))
                                                   (:pack-data-results idx))
           :when skipped]
       {:severity :info :problem :pack-form-not-read :pack kind :reason skipped
        :file (relative (:root idx) file) :line row})
     (identity-problems idx)
     (runtime-problems idx))))

(defn check
  "Where code and registry files disagree, most severe first."
  [idx]
  (let [linked (linked-vars idx)]
    ;; sort-by is stable: findings of one kind keep their order
    (vec (sort-by #(get {:error 0 :warning 1} (:severity %) 2)
                  (concat
          (for [p (:read-problems idx)] (assoc p :severity :error))
          (for [[id vs] linked :when (> (count vs) 1)]
            {:severity :error :problem :dev-id-on-several-vars :dev-id id :vars (mapv #(loc idx %) vs)})
          ;; identity declared, nothing said about it yet: valid
          (for [[id e] (sort-by key (:entities idx))
                :when (every? #(= :binding (:kind %)) (:definitions e))]
            {:severity :info :problem :binding-only :dev-id id :impl (loc idx (impl-of idx id))})
          (undeclared-calls idx)
          (keys-outside-contract idx)
          (pack-problems idx))))))

(defn summary [idx]
  (let [kinds (frequencies (for [[_ e] (:entities idx)] (:kind e)))]
    {:entities (count (:entities idx))
     :entities-by-source (into (sorted-map) kinds)
     :packs (let [rs (concat (for [{:keys [pack-results]} (vals (:code idx)) [_ results] pack-results r results] r)
                             (:pack-data-results idx))]
              (into (sorted-map) (for [[kind rs] (group-by :kind rs)]
                                   [kind {:read (count (filter :dev-id rs)) :not-read (count (filter :skipped rs))}])))
     :pack-snapshots (into (sorted-map) (for [{:keys [kind file written ids]} (:pack-snapshots idx)]
                                          [kind {:file (relative (:root idx) file) :as-of (instant written) :entities (count ids)}]))
     :source-files (count (:code idx))
     :analysed-now (:analysed idx)
     :coverage (dissoc (coverage-note idx) :note)}))
