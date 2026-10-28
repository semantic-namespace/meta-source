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
            [meta-source.pack.aero :as aero]
            [meta-source.pack.datascript :as datascript]
            [meta-source.pack.fn-schema :as fn-schema]
            [meta-source.pack.integrant :as integrant]
            [meta-source.pack.namespaces :as namespaces]
            [meta-source.pack.pathom :as pathom]
            [meta-source.pack.re-frame :as re-frame]
            [meta-source.pack.reitit :as reitit]
            [meta-source.pack.overarch :as overarch]
            [meta-source.pack.tilakone :as tilakone]
            [meta-source.pack.schema :as schema]
            [meta-source.read :as read]
            [meta-source.rules :as rules])
  (:import (java.io File)))

(def ^:private bb? (some? (System/getProperty "babashka.version")))

(def cache-version 5)

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

(defn- resolver
  "A reader resolver for a file: ::name resolves to the file's namespace,
   ::alias/name to the namespace it aliases (the alias itself when the ns
   form names none), as Clojure's reader would, without creating or
   touching any namespace. Strings and comments are the reader's to skip."
  [own-ns aliases]
  (reify clojure.lang.LispReader$Resolver
    (currentNS [_] (symbol own-ns))
    (resolveClass [_ sym] sym)
    (resolveAlias [_ sym] (symbol (get aliases (str sym) (str sym))))
    (resolveVar [_ sym] sym)))

(defn- read-with
  "The next form of `reader`, with `resolver`'s namespaces and `read-opts`
   (the schema pack reads .cljc reader conditionals as :clj)."
  [reader resolver read-opts]
  (binding [*read-eval* false
            *default-data-reader-fn* tagged-literal
            *reader-resolver* resolver]
    (if read-opts (read read-opts reader) (read reader))))

(defn- source-text
  "A file's text, read once, with where each line starts: {:text :starts}."
  [lines]
  {:text (str/join "\n" lines)
   :starts (vec (reductions + 0 (map #(inc (count %)) lines)))})

(defn- call-at
  "The form that starts with the `(` just before the symbol at `row`/`col`
   (1-based), :unreadable when it can't be read as data, nil when the symbol
   isn't at the head of a call. It is read from there in the file's text,
   read once (`src`, source-text), with the file's namespace and aliases
   resolving its ::keywords."
  [lines src row col resolver read-opts]
  (let [line (nth lines (dec row) nil)]
    (when (and line (>= col 2) (= \( (nth line (- col 2) nil)))
      (try (let [r (java.io.StringReader. (:text src))]
             (.skip r (+ (nth (:starts src) (dec row)) (- col 2)))
             (read-with (java.io.PushbackReader. r) resolver read-opts))
           (catch Exception _ :unreadable)))))

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
;;   :pack/describes?       true when it describes what other packs declare
;;                          (atlas's registry, under an integrant component's
;;                          key): sharing their ids is no :id-in-several-packs,
;;                          and their source is the id's primary definition
;;   :pack/depicts?         true when it is a picture of the system (an
;;                          architecture model): never an id's primary
;;                          definition while anything else defines it
;;   :pack/dataflow         {:consumes [k ...] :produces [k ...]}: entry keys
;;                          that list data keys
;;   :pack/dataflow-of      (fn [entries]) → the same, derived from what was
;;                          read ({id entry}): atlas's ontologies declare
;;                          their context and response keys
;;   :pack/find             (fn [analysis opts]) → sites, each {:filename :row
;;                          :name-row :name-col ...} found in clj-kondo's
;;                          analysis; name-row/name-col is the symbol right
;;                          after the form's opening paren
;;   :pack/read             (fn [sites file opts]) → [{:row :kind :dev-id
;;                          :entry} or {:row :kind :skipped reason}] for one
;;                          file's sites (a skipped one with :pattern rule-id
;;                          breaks that rule of the pack's pattern, reported
;;                          as :off-pattern when the project asks); file is {:form-at (fn [row col])
;;                          :read (fn [text]) :find-row (fn [text row])
;;                          :own-ns :aliases}, form-at reading
;;                          the form at a position as data, read reading text
;;                          with the file's ::keywords resolved. A result
;;                          with :refers true is a place that refers to the
;;                          id (a dispatch): never its primary definition.
;;                          One with :var ns/name says that var implements
;;                          the id, as the meta key does (an s/fdef's)
;;   :pack/checks           (fn [view]) → problems; view is {:entities
;;                          :of-kinds :data-key? :loc :flow :opts
;;                          :var-defined? :namespaces :not-read :impl :linked-by}:
;;                          var-defined? true or
;;                          false for a qualified symbol in a namespace the
;;                          analysis read, nil for one it didn't; namespaces,
;;                          the names (strings) of those it read; not-read,
;;                          how many forms of a kind couldn't be read; impl,
;;                          where the var implementing an id is, or nil;
;;                          linked-by, the pack whose :pack/id-key linked it
;;   :pack/inline-data-keys (fn [entities]) → data keys its entities define
;;                          inline, with no entity of their own
;;   :pack/shape            (fn [entry kind]) → a data key's shape, as text
;;   :pack/members          (fn [entry]) → the data keys a schema is made of
;;   :pack/describe         (fn [entry]) → one line for an editor, or nil
;;   :pack/not-references   #{prop ...}: props of its entries that name ids
;;                          only as a lookup (what another prop refers to)
;;   :pack/hands            (fn [entry]) → {callee [id ...] or :unknown}:
;;                          what the entity's code hands each fn it calls
;;                          (callee "ns/name" or a bare name, "*" for any
;;                          other), {} for nothing, nil when not known
;;   :pack/merge            {key :union}: keys whose values it unions across
;;                          sources (a set of tags); others conflict
;;   :pack/data-files       (fn [opts]) → paths under the root, files or
;;                          directories of .edn, that the pack reads as data
;;   :pack/read-data        (fn [files opts]) → results as :pack/read gives,
;;                          each with its :file; files is [{:file :path :text}],
;;                          all of them at once, so a key several files define
;;                          can be one result (meta-source.read/keyed-lines
;;                          reads them with each key's line). A result may
;;                          instead be a finding, {:row :kind :problem
;;                          :severity ...}: what the library rejects on load
;;   :pack/derive          (fn [entities opts]) → {id {key value}}: facts it
;;                          derives from what every pack read, entities being
;;                          {id {:entry merged-entry :kinds #{source-kind}}}
;;                          (atlas's: a route is an :atlas/interface-endpoint).
;;                          A fact fills a key the entry lacks, never one a
;;                          source gave; `where` lists it with :derived-by
;;   :pack/runtime-fill     (fn [entry runtime]) → entry with what static reading
;;                          couldn't read taken from the running process's entry
;;   :pack/id-key           its library's meta key linking a var to an id
;;                          (atlas's :atlas.source/id), read as :id-key is: a
;;                          var it links with no source is the pack's to
;;                          report, not :binding-only
;;   :pack/var-meta         meta keys whose values clj-kondo's analysis gives on
;;                          each var definition (:meta), for :pack/find
;;   :pack/kondo-config     clj-kondo config the analysis adds ({:lint-as ...}),
;;                          as the library's own exported config would
;;   :pack/runtime-kinds    the source kinds its snapshot is compared with,
;;                          default its own: atlas's include :edn, its
;;                          registry files being its declarations
;;   :pack/runtime-diff     (fn [entry runtime]) → where code and the running
;;                          process disagree, or nil
;;
;; A pack's runtime half is a snapshot file the running process writes (the
;; Pathom pack's `snapshot!`): {:pack id :written epoch-ms :entities {dev-id
;; entry}}, at .meta-source/packs/<id>.edn or the pack's :snapshot option. The
;; index reads it beside the code: it fills what static reading couldn't
;; read, adds what only the process has, and checks where they disagree.
;;
;; `opts` is meta-source.edn's :packs map, with the project's root (a
;; canonical path) as :meta-source/root. What :pack/read returns is
;; cached per file, so it must be data. A pack needs :pack/find and
;; :pack/read, or :pack/data-files and :pack/read-data, or both.

(def builtin-packs [schema/pack fn-schema/pack datascript/pack pathom/pack integrant/pack aero/pack re-frame/pack reitit/pack tilakone/pack overarch/pack namespaces/pack])

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

(defn- pack-opts
  "What a pack is given as `opts`: meta-source.edn's :packs map, with the
   project's root as :meta-source/root."
  [config]
  (assoc (:packs config) :meta-source/root (:root config)))

(defn- read-packs
  "{pack-id [result ...]} for `file`: each pack's :pack/read over its sites
   there, once per position (a .cljc file reports each form per language)."
  [file found packs-config own-ns aliases]
  (let [lines (delay (str/split-lines (slurp file)))
        src (delay (source-text @lines))
        opts {:read-cond :allow :features #{:clj}}
        res (resolver own-ns aliases)
        f {:own-ns own-ns :aliases aliases
           :form-at (fn [row col] (call-at @lines @src row col res opts))
           :read (fn [text] (read-with (java.io.PushbackReader. (java.io.StringReader. text)) res opts))
           ;; the first line from `row` on where `text` is written, as is
           :find-row (fn [text row] (some (fn [n] (when (str/includes? (nth @lines (dec n)) text) n))
                                          (range (max 1 row) (inc (count @lines)))))}]
    (into {} (for [[pack sites] found
                   :let [sites (sort-by :row (map first (vals (group-by (juxt :name-row :name-col) sites))))]]
               [(:pack/id pack)
                ;; a pack that fails on a file costs that file, not the index
                (try (if-let [failed (some :pack-failed sites)]
                       [{:row 1 :kind (first (sort (:pack/kinds pack))) :skipped failed}]
                       (vec ((:pack/read pack) sites f packs-config)))
                     (catch Exception ex
                       [{:row (:row (first sites)) :kind (first (sort (:pack/kinds pack)))
                         :skipped (str "the pack failed reading this file: " (ex-message ex))}]))]))))

(defn- merge-policy
  "{key :union}: meta-source.edn's :merge, plus what each enabled pack declares."
  [config packs]
  (apply merge (concat (keep :pack/merge packs) [(:merge config)])))

(defn- pack-rules
  "The rules each pack's :pack/rules-of derives from `entities` (static, {id
   entity}), as atlas's reads them from the project's invariants: {rule-id
   rule}, as a rules file holds. A rule whose id is an entity's is located
   at that entity's definition."
  [root packs entities]
  (vec (for [p packs
             :let [f (:pack/rules-of p)]
             :when f
             [id rule] (sort-by (comp pr-str key) (f (update-vals entities :entry)))
             :let [d (first (filter :line (get-in entities [id :definitions])))]]
         (cond-> {:id id :rule rule :pack (:pack/id p)}
           d (assoc :file (relative root (:file d)) :line (:line d))))))

(defn- dataflow
  "Which entry keys list consumed and produced data keys: meta-source.edn's
   :dataflow, plus what each enabled pack declares, plus what each pack's
   :pack/dataflow-of derives from `entities` ({id entry}), as atlas's reads
   it from the project's ontologies."
  [config packs entities]
  (apply merge-with (comp vec distinct concat)
         {:consumes [] :produces []}
         (:dataflow config)
         (concat (keep :pack/dataflow packs)
                 (for [p packs :let [f (:pack/dataflow-of p)] :when f] (f entities)))))

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
  "The line a pack gives an editor for `entry`, or nil. A picture of the
   system (:pack/depicts?) gives its own, `depict`."
  [idx entry]
  (some #(when-let [d (:pack/describe %)] (d entry)) (remove :pack/depicts? (:packs idx))))

(defn depict
  "The line a picture of the system (an architecture model) gives for
   `entry`, or nil."
  [idx entry]
  (some #(when-let [d (:pack/describe %)] (d entry)) (filter :pack/depicts? (:packs idx))))

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
   each enabled pack reads there. A var carrying `id-key`, or an enabled
   pack's :pack/id-key, in its meta gets that :dev-id; with a pack's, it is
   :linked-by-pack that pack. clj-kondo reads the project's own .clj-kondo config under
   `root` (its :lint-as and hooks, the configs it imports), as the editor's
   clj-kondo does, wherever meta-source runs from: macros the project
   teaches clj-kondo are analysed as what they define."
  [root files packs packs-config id-key calls-by-id]
  (if (empty? files)
    {}
    (let [{:keys [analysis]} (@kondo (cond-> {:lint (vec files)
                                              :config (merge-with merge
                                                                  (apply merge-with merge (keep :pack/kondo-config packs))
                                                                  {:output {:canonical-paths true}
                                                                   :linters ^:replace {}
                                                                   :analysis {:var-definitions {:meta (vec (distinct (concat [id-key] (keep :pack/id-key packs) (mapcat :pack/var-meta packs))))}
                                                                              :keywords true}})}
                                       (kondo-config-dir root) (assoc :config-dir (kondo-config-dir root))))
          by-file (fn [k xf] (group-by :filename (into [] xf (get analysis k))))
          ;; the project's meta key, and each pack's own (atlas's :atlas.source/id)
          pack-keys (into {} (for [p packs :let [k (:pack/id-key p)] :when k] [k (:pack/id p)]))
          vars (by-file :var-definitions
                        (map (fn [{:keys [meta] :as d}]
                               (let [k (first (filter #(get meta %) (cons id-key (keys pack-keys))))]
                                 (cond-> (select-keys d [:filename :ns :name :row :end-row :private :defined-by])
                                   k (assoc :dev-id (get meta k))
                                   (pack-keys k) (assoc :linked-by-pack (pack-keys k)))))))
          ;; calls and keywords outside any var too: an implementation a pack
          ;; locates by its rows (an inline fn in a top-level register!) owns them
          calls (by-file :var-usages
                         (comp (filter :from)
                               (map #(select-keys % [:filename :from :from-var :to :name :row :col :end-row :end-col]))))
          own-ns (into {} (for [{:keys [filename name]} (:namespace-definitions analysis)] [filename (str name)]))
          aliases (update-vals (group-by :filename (filter :alias (:namespace-usages analysis)))
                               #(into {} (for [{:keys [alias to]} %] [(str alias) (str to)])))
          ;; a :refer in an ns form names a var; it is never a form to read
          for-packs (update analysis :var-usages #(remove :refer %))
          ;; a pack that fails finding its forms costs that pack, not the index:
          ;; the failure is reported at the first file, as a form not read
          found (vec (for [p packs :when (:pack/find p)]
                       [p (try (group-by :filename (vec ((:pack/find p) for-packs packs-config)))
                               (catch Exception ex
                                 {(canonical (first files))
                                  [{:filename (canonical (first files)) :row 1 :name-row 1 :name-col 1
                                    :pack-failed (str "the pack failed finding its forms: " (or (ex-message ex) (str (type ex))))}]}))]))
          kws (by-file :keywords
                       (comp (filter #(and (:from %) (:ns %)))
                             (map #(select-keys % [:filename :from :from-var :ns :name :row :col]))))
          ;; a call to a fn :calls-by-id names, whose first argument is a literal
          ;; keyword: (exec-fn :fn/load ...) calls the entity :fn/load
          id-calls (into {} (for [[f us] calls]
                              [f (vec (for [u us
                                            :when (contains? calls-by-id (symbol (str (:to u)) (str (:name u))))
                                            :let [k (first (sort-by (juxt :row :col)
                                                                    (for [k (get kws f)
                                                                          :when (or (> (:row k) (:row u))
                                                                                    (and (= (:row k) (:row u)) (> (:col k) (:col u))))
                                                                          :when (<= (:row k) (or (:end-row u) (:row u)) )]
                                                                      k)))]
                                            :when k]
                                        {:filename f :from (:from u) :from-var (:from-var u) :row (:row u)
                                         :id (keyword (str (:ns k)) (str (:name k)))}))]))]
      (into {} (for [f files :let [f (canonical f)]]
                 [f {:vars (vec (get vars f)) :calls (vec (get calls f)) :keywords (vec (get kws f))
                     :id-calls (get id-calls f [])
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
                :rules (vec (:rules opts))
                ;; the source directories of libraries (:libs): read, never reported on
                :lib-src (vec (:lib-src opts))
                ;; fns whose first argument, a literal keyword, is the entity they call
                :calls-by-id (vec (sort (map symbol (:calls-by-id opts))))
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
        rule-files (vec (distinct (for [d (:rules opts)
                                        :let [f (under root d)]
                                        path (cond (.isDirectory f) (files-under root [d] [".edn"])
                                                   (.isFile f) [(canonical f)])]
                                    path)))
        edn-stamps (into {} (map (juxt identity mtime))
                         (concat edn-files
                                 rule-files
                                 (for [p packs :let [f (snapshot-file root (:packs config) p)] :when (.isFile f)] (canonical f))
                                 (mapcat second data-files)))]
    (if (and previous (identical? cached previous) (= stamps (:stamps previous)) (= edn-stamps (:edn-stamps previous)))
      (assoc previous :analysed 0)
      (build* config packs data-files root cache cached stamps edn-stamps edn-files src-files rule-files))))

(defn- read-rules
  "[{:id :rule :file :line}] and read problems, from the rules files."
  [root files]
  (let [read (for [f files]
               (try {:rules (for [[id rule line] (or (read/keyed-lines (slurp f))
                                                     (throw (ex-info "a rules file holds a map of rule id to rule" {})))]
                              {:id id :rule rule :file (relative root f) :line line})}
                    (catch Exception ex
                      {:problem {:problem :unreadable-rules-file :file (relative root f) :message (ex-message ex)}})))]
    {:rules (vec (mapcat :rules read)) :problems (vec (keep :problem read))}))

(defn- build* [config packs data-files root cache cached stamps edn-stamps edn-files src-files rule-files]
  (let [fresh? #(and (= (stamps %) (get-in cached [:stamps %])) (contains? (:code cached) %))
        stale (remove fresh? src-files)
        code (merge (select-keys (:code cached) (filter fresh? src-files))
                    (analyse-code root stale packs (pack-opts config) (:id-key config) (set (:calls-by-id config))))
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
                                r (try ((:pack/read-data p) fs (pack-opts config))
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
                 (for [{:keys [file row kind dev-id entry dropped refers var impl-range body-range]}
                       (concat (for [[f {:keys [pack-results]}] code
                                     [_ results] (sort-by key pack-results)
                                     r results]
                                 (assoc r :file f))
                               data-results)
                       :when dev-id]
                   {:label (str file ":" row ":" dev-id) :file file :kind kind :dropped dropped :refers refers :var var
                    :impl-range impl-range :body-range body-range :lines {dev-id row} :entities {dev-id entry}})
                 ;; a call by id: the entity is called there, as a var call would
                 (for [[f {:keys [id-calls]}] code
                       {:keys [row id]} id-calls]
                   {:label (str f ":" row ":" id ":call") :file f :kind :call-by-id :refers true
                    :lines {id row} :entities {id {:meta-source/called-by-id true}}}))
        by-label (into {} (map (juxt :label identity)) sources)
        data-key-kinds (set (mapcat :pack/kinds (filter :pack/data-keys? packs)))
        describing-kinds (set (mapcat :pack/kinds (filter :pack/describes? packs)))
        depicting-kinds (set (mapcat :pack/kinds (filter :pack/depicts? packs)))
        merged (merge/merge-sources (for [{:keys [label entities]} sources] {:source label :entities entities})
                                    (merge-policy config packs))
        definition (fn [id label]
                     (let [{:keys [file kind lines dropped refers entities]} (by-label label)]
                       (cond-> {:file file :line (get lines id) :kind kind
                                :keys (vec (sort (for [[k ls] (get-in merged [:provenance id]) :when (contains? ls label)] k)))}
                         (seq dropped) (assoc :dropped dropped)
                         refers (assoc :refers true)
                         ;; what a schema said, so each shape shows even when they conflict
                         (data-key-kinds kind) (assoc :given (get entities id)))))
        static (into {} (for [[id entry] (:entities merged)
                              :let [defs (mapv #(definition id %) (merge/sources-of (:provenance merged) id))
                                    ;; what a pack declares (a component, a resolver, a
                                    ;; data key's schema) before what a describing pack
                                    ;; or EDN adds to it, then a picture of it (a
                                    ;; model), and all before a place that only
                                    ;; refers to it
                                    primary (or (first (filter #(data-key-kinds (:kind %)) defs))
                                                (first (remove #(or (= :edn (:kind %)) (:refers %) (describing-kinds (:kind %))) defs))
                                                (first (remove #(or (= :edn (:kind %)) (:refers %) (depicting-kinds (:kind %))) defs))
                                                (first (remove #(or (:refers %) (depicting-kinds (:kind %))) defs))
                                                (first (remove #(or (= :edn (:kind %)) (:refers %)) defs))
                                                (first (remove :refers defs))
                                                (first defs))]]
                          [id (cond-> (assoc primary :entry entry :definitions defs)
                                ;; a unioned key: where each of its values was given
                                (get-in merged [:value-provenance id])
                                (assoc :given-by (update-vals (get-in merged [:value-provenance id])
                                                              (fn [by-v] (update-vals by-v (fn [ls] (mapv #(definition id %) ls)))))))]))
        ;; what packs derive from what was read (atlas's types for routes and
        ;; components): facts that fill a key no source gave, never one one did
        static (reduce (fn [st p]
                         (if-let [f (:pack/derive p)]
                           (let [seen (into {} (for [[id e] st] [id {:entry (:entry e) :kinds (set (map :kind (:definitions e)))}]))]
                             (reduce (fn [st [id facts]]
                                       (let [e (get st id)
                                             new (into {} (remove #(contains? (:entry e) (key %))) facts)]
                                         (if (and e (seq new))
                                           (-> st
                                               (update-in [id :entry] merge new)
                                               (update-in [id :definitions] conj {:kind (first (sort (:pack/kinds p))) :derived (:pack/id p)
                                                                                  :keys (vec (sort (keys new)))}))
                                           st)))
                                     st (try (f seen (pack-opts config)) (catch Exception _ nil))))
                           st))
                       static packs)
        rules-read (read-rules root rule-files)
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
     :rules (into (:rules rules-read) (pack-rules root packs static))
     :rules-problems (:problems rules-read)
     :packs packs
     :dataflow (dataflow config packs (update-vals static :entry))
     :stamps stamps
     :edn-stamps edn-stamps
     :edn edn
     :entities (merge bindings static)
     ;; vars a pack says implement an id: {ns/name dev-id}
     :pack-links (into {} (for [{:keys [var entities]} sources :when var] [var (key (first entities))]))
     ;; code a pack says implements an id, by its rows, with no var of its own:
     ;; {dev-id {:filename :row :end-row}}
     :impl-ranges (into {} (for [{:keys [file impl-range entities]} sources :when impl-range]
                             [(key (first entities)) (assoc impl-range :filename file)]))
     ;; code a pack says is an id's own, by its rows, for its calls only: an
     ;; integrant init-key method. Unlike an implementation, it keeps no
     ;; contract. {dev-id {:filename :row :end-row}}
     :body-ranges (into {} (for [{:keys [file body-range entities]} sources :when body-range]
                             [(key (first entities)) (assoc body-range :filename file)]))
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
  "{dev-id [var-definition ...]} for the vars that name a dev-id: by the
   meta key, or because a pack says the var implements it (an s/fdef names
   its var). A var may implement both."
  [idx]
  (let [links (:pack-links idx)]
    (group-by :dev-id (concat (filter :dev-id (all-vars idx))
                              ;; one var per name: a (declare f) before the defn is not a second var
                              (for [[sym vs] (group-by #(symbol (str (:ns %)) (str (:name %))) (all-vars idx))
                                    :let [id (get links sym)
                                          v (or (last (remove #(= 'clojure.core/declare (:defined-by %)) vs)) (last vs))]
                                    :when (and id (not= id (:dev-id v)))]
                                (assoc v :dev-id id :linked-by :pack))))))

(defn- impl-ranges-in
  "{filename [[dev-id {:row :end-row}] ...]}: the implementations a pack
   locates by rows."
  [idx]
  (group-by (comp :filename second) (:impl-ranges idx)))

(defn- range-owner
  "The id whose implementation, located by rows, holds `row` of `filename`."
  [by-file filename row]
  (when row
    (some (fn [[id r]] (when (and (:row r) (:end-row r) (<= (:row r) row (:end-row r))) id)) (get by-file filename))))

(declare references)

(defn- depends-on
  "{id #{id}}: what each entity depends on by its entry: the ids it names,
   and what its pack resolves its names to (an integrant ref to a key that
   others derive from: :pack/depends)."
  [idx]
  (let [resolved (apply merge-with into (keep #(when-let [f (:pack/depends %)] (f (:entities idx))) (:packs idx)))]
    (into {} (for [[id e] (:entities idx)
                   :let [ds (into (set (map second (references idx (:entry e)))) (get resolved id))]
                   :when (seq ds)]
               [id (disj ds id)]))))

(defn edges
  "What the entities' code says they depend on, beyond what their entries
   name: {:calls :provided-by :given :inexact}.

   - :calls {id #{id}}: an entity's code (its linked var, or the rows a pack
     located: an inline implementation, an init-key method) calls the var
     another entity is linked to.
   - :provided-by {id #{id}}: an entity defined inside a var (a route in the
     fn that builds a route tree) and the entity whose code calls that var
     (the component whose init-key builds the routes).
   - :given {id #{id}}: what such an entity is handed, exactly, when its
     builder follows the pattern (docs/pattern.md): the components behind the
     config keys its init-key destructures and passes to the call building it.
   - :inexact {id #{{:pattern :at ...}}}: an entity whose reach isn't exact,
     and the pattern rule broken that makes it so. Nothing is guessed for it:
     I5, a builder whose config isn't destructured or is used whole or
     rebound (:pattern/integrant-destructure); R4a, a builder using its
     config outside the calls that build, in a middleware or an option, which
     may wrap all it builds (:pattern/reitit-hands-route-fns); R4b, a
     component taking the routes (depending on their builder, transitively)
     that holds other components, which may wrap them
     (:pattern/reitit-router-holds-routes)."
  [idx]
  (let [var->id (into {} (for [[id vs] (linked-vars idx) v vs] [[(symbol (str (:ns v))) (symbol (str (:name v)))] id]))
        ranges (group-by (comp :filename second) (concat (:impl-ranges idx) (:body-ranges idx)))
        owner (fn [c] (or (when (:from-var c) (var->id [(symbol (str (:from c))) (symbol (str (:from-var c)))]))
                          (when-not (:from-var c) (range-owner ranges (:filename c) (:row c)))))
        ;; {[ns name] #{id}}: the entities whose definition is written inside a var
        defined-in (let [defs (for [[id e] (:entities idx) d (:definitions e)
                                    :when (and (:file d) (:line d) (not (:refers d)))]
                                [id d])
                         by-file (group-by (comp :file second) defs)]
                     (into {} (for [v (all-vars idx)
                                    :let [k [(symbol (str (:ns v))) (symbol (str (:name v)))]
                                          own (var->id k)
                                          ids (set (for [[id d] (get by-file (:filename v))
                                                         :when (and (not= id own) (:row v) (:end-row v)
                                                                    (<= (:row v) (:line d) (:end-row v)))]
                                                     id))]
                                    :when (seq ids)]
                                [k ids])))
        builders (set (for [[ns n] (keys defined-in)] (str ns "/" n)))
        ;; {component #{id}}: entities written inside a component's own rows
        ;; (a route tree in an init-key's body), no call between them
        defined-inline (let [defs (for [[id e] (:entities idx) d (:definitions e)
                                        :when (and (:file d) (:line d) (not (:refers d)))]
                                    [id d])
                             by-file (group-by (comp :file second) defs)]
                         (into {} (for [[c r] (:body-ranges idx)
                                        :let [ids (set (for [[id d] (get by-file (:filename r))
                                                             :when (and (not= id c) (<= (:row r) (:line d) (:end-row r)))]
                                                         id))]
                                        :when (seq ids)]
                                    [c ids])))
        add (fn [m k v] (if (and k v (not= k v)) (update m k (fnil conj #{}) v) m))
        ;; where an entity's code uses what it was given, as its pack reads it
        hands (memoize (fn [id] (let [entry (get-in idx [:entities id :entry])]
                                  (some #(when-let [f (:pack/hands %)] (f entry)) (:packs idx)))))
        {:keys [calls provided-by builds]}
        (reduce (fn [acc c]
                  (if-let [from (owner c)]
                    (let [k [(:to c) (:name c)]
                          built (defined-in k)]
                      (as-> acc a
                        (update a :calls add from (var->id k))
                        (reduce #(update %1 :provided-by add %2 from) a built)
                        (reduce #(update %1 :builds conj [%2 from (str (:to c) "/" (:name c))]) a built)))
                    acc))
                {:calls {} :provided-by (reduce (fn [m [c ids]] (reduce #(update %1 %2 (fnil conj #{}) c) m ids)) {} defined-inline)
                 :builds #{}}
                (for [c (mapcat :calls (vals (:code idx)))]
                  (update c :to #(symbol (str %)))))
        ;; exactly what each built entity is handed, or why it isn't known
        handed (reduce (fn [acc [r from v]]
                         (let [h (hands from)
                               ;; the uses inside the call building it: a body-bound value
                               ;; or the whole config there isn't followed (I5)
                               mine (when (sequential? h) (filter (fn [[_ callees]] (some #{v} callees)) h))
                               ;; the config's own uses outside every call that builds (a
                               ;; body-bound value carries the config only through such a
                               ;; use, its binding)
                               loose (when (sequential? h) (remove (fn [[k callees]] (or (= :rebound k) (some builders callees))) h))]
                           (cond
                             (or (not (sequential? h)) (some #(#{:unknown :rebound} (first %)) mine))
                             (update-in acc [:inexact r] (fnil conj #{}) {:pattern :pattern/integrant-destructure :at from})
                             (seq loose)
                             (update-in acc [:inexact r] (fnil conj #{}) {:pattern :pattern/reitit-hands-route-fns :at from})
                             :else (reduce #(update %1 :given add r %2) acc (mapcat first mine)))))
                       {:given {} :inexact {}}
                       builds)
        ;; written inline in an init-key: exact only when it uses no config,
        ;; which an inline handler could close over (R4: routes in route fns)
        handed (reduce (fn [acc [c r]]
                         (let [h (hands c)]
                           (cond
                             (and (sequential? h) (empty? h)) acc
                             (not (sequential? h)) (update-in acc [:inexact r] (fnil conj #{}) {:pattern :pattern/integrant-destructure :at c})
                             :else (update-in acc [:inexact r] (fnil conj #{}) {:pattern :pattern/reitit-routes-in-route-fns :at c}))))
                       handed
                       (for [[c ids] defined-inline r ids] [c r]))
        ;; what takes the routes and holds anything else may wrap them
        deps (depends-on idx)
        users (reduce (fn [m [from tos]] (reduce #(update %1 %2 (fnil conj #{}) from) m tos)) {} deps)
        takers (memoize (fn [c] (loop [todo [c] seen #{}]
                                  (if-let [x (peek todo)]
                                    (let [n (remove seen (users x))]
                                      (recur (into (pop todo) n) (into seen n)))
                                    seen))))
        builder-ids (set (mapcat val provided-by))
        handed (reduce (fn [acc [r from]]
                         (let [ks (takers from)
                               route-chain (conj ks from)]
                           (reduce (fn [acc k]
                                     (let [held (sort (remove #(or (route-chain %) (builder-ids %)) (deps k)))]
                                       (if (seq held)
                                         (update-in acc [:inexact r] (fnil conj #{}) {:pattern :pattern/reitit-router-holds-routes :at k :holds (vec held)})
                                         acc)))
                                   acc ks)))
                       handed
                       (for [[r froms] provided-by from froms] [r from]))]
    (merge {:calls calls :provided-by provided-by} handed)))

(defn- impl-of
  "Where `id` is implemented: its var, or the rows a pack located."
  [idx id]
  (or (first (get (linked-vars idx) id))
      (some-> (get-in idx [:impl-ranges id]) (assoc :inline true))))

(defn- loc [idx {:keys [file line filename row end-row ns name kind] :as m}]
  (cond
    (and (:runtime m) (not file)) {:pack kind :runtime-as-of (instant (:written m))
                                   :snapshot (relative (:root idx) (:snapshot m))}
    file (cond-> {:file (relative (:root idx) file) :line line}
           (= kind :binding) (assoc :binding true)
           ((pack-kinds idx) kind) (assoc :pack kind))
    (contains? m :file) nil
    (:inline m) {:file (relative (:root idx) filename) :line row :end-line end-row :inline true}
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
  (let [ids (:entities idx)
        ;; a pack's lookup tables name ids its other keys already refer to
        skip (set (mapcat :pack/not-references (:packs idx)))]
    (distinct
     (for [[k v] entry
           :when (not (skip k))
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
                              (:derived d) (assoc :derived-by (:derived d))
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
        var->id (into {} (for [[k [v]] (linked-vars idx)] [[(:ns v) (:name v)] k]))
        ranges (impl-ranges-in idx)
        caller (fn [c] (or (var->id [(:from c) (:from-var c)])
                           (when-not (:from-var c) (range-owner ranges (:filename c) (:row c)))))]
    {:dev-id id
     :referenced-by (vec (for [[other e] (sort-by key (:entities idx))
                               [prop x] (references idx (:entry e))
                               :when (= x id)]
                           {:dev-id other :via prop :definition (loc idx e)}))
     :called-from (vec (concat
                        (when (and impl (not (:inline impl)))
                          (for [c (mapcat :calls (vals (:code idx)))
                                :when (and (= (:to c) (:ns impl)) (= (:name c) (:name impl))
                                           (or (:from-var c) (caller c)))]
                            (cond-> {:file (relative (:root idx) (:filename c)) :line (:row c)}
                              (:from-var c) (assoc :var (symbol (str (:from c)) (str (:from-var c))))
                              (caller c) (assoc :dev-id (caller c)))))
                        ;; and where a :calls-by-id fn calls it by its id
                        (for [c (mapcat :id-calls (vals (:code idx)))
                              :when (and (= id (:id c)) (or (:from-var c) (caller c)))]
                          (cond-> {:by-id true :file (relative (:root idx) (:filename c)) :line (:row c)}
                            (:from-var c) (assoc :var (symbol (str (:from c)) (str (:from-var c))))
                            (caller c) (assoc :dev-id (caller c))))))
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
                               ;; each source's own shape: two that conflict are left
                               ;; out of the merged entry, and both are shown here
                               (assoc (loc idx d) :form (when-let [f (:pack/shape p)] (f (or (:given d) own) (:kind d))))))
                 :props (into (sorted-map) (remove #(vocabulary (namespace (key %)))) own))
      (seq member-of) (assoc :member-of member-of))))

;; =============================================================================
;; Checks
;; =============================================================================

(defn- undeclared-calls
  "A var linked by the meta key calling another linked var whose entity its
   entry doesn't name. A var a pack links (an s/fdef's) declares no calls."
  [idx]
  (let [linked (for [[k [v]] (linked-vars idx)] [[(:ns v) (:name v)] k v])
        var->id (into {} (map (juxt first second)) linked)
        declaring (into {} (for [[nv k v] linked :when (not= :pack (:linked-by v))] [nv k]))
        ranges (impl-ranges-in idx)
        ;; the entity a call is made from: its var's, or the rows holding it
        from-of (fn [c] (or (declaring [(:from c) (:from-var c)])
                            (when-not (:from-var c) (range-owner ranges (:filename c) (:row c)))))]
    (concat
     (for [c (mapcat :calls (vals (:code idx)))
          :let [from (from-of c)
                to (var->id [(:to c) (:name c)])]
          :when (and from to (not= from to)
                     (not (some #(= to (second %)) (references idx (get-in idx [:entities from :entry])))))]
      {:severity :warning :problem :undeclared-call :dev-id from :calls to
       :file (relative (:root idx) (:filename c)) :line (:row c)})
    ;; and the calls by id (:calls-by-id): to an entity the index has
    (for [c (mapcat :id-calls (vals (:code idx)))
          :let [from (from-of c)
                to (:id c)]
          :when (and from (not= from to) (get-in idx [:entities to])
                     (not (some #(= to (second %)) (references idx (get-in idx [:entities from :entry])))))]
      {:severity :warning :problem :undeclared-call :dev-id from :calls to :by-id true
       :file (relative (:root idx) (:filename c)) :line (:row c)}))))

(defn- with-members
  "`ks` and, transitively, the keys the schemas among them are made of
   (an s/keys spec's keys, a malli :map's)."
  [idx ks]
  (let [members (fn [k] (when-let [e (get-in idx [:entities k])]
                          (mapcat #(when-let [m (:pack/members %)] (m (:entry e))) (:packs idx))))]
    (loop [seen (set ks) todo (seq ks)]
      (if-let [[k & more] todo]
        (let [new (remove seen (members k))]
          (recur (into seen new) (seq (concat more new))))
        seen))))

(def ^:private schema-nses '#{clojure.spec.alpha cljs.spec.alpha malli.core})

(defn- token-positions
  "{[row col] position} for each token of `text` (1-based, as clj-kondo
   counts): the form it is in (:list :vector :map :set, :rc for a reader
   conditional), its index there, the form's head symbol (a list's), the
   map key before it (a map's), and the same of the form around that one.
   Read by scanning: strings, comments, characters and #_ are skipped, and
   metadata and #_ don't count as an element."
  [^String text]
  (let [n (count text)
        delim? (fn [c] (or (Character/isWhitespace (char c)) (#{\, \( \) \[ \] \{ \} \" \;} c)))
        out (volatile! (transient {}))]
    (loop [i 0 row 1 col 1 stack ()]
      (if (>= i n)
        (persistent! @out)
        (let [c (.charAt text i)
              frame (first stack)
              ;; a finished element of the form on top: counted, unless a
              ;; ^meta or #_ before it said to skip it
              done (fn [stack tok]
                     (if-let [f (first stack)]
                       (if (pos? (:skip f 0))
                         (cons (update f :skip dec) (rest stack))
                         (cons (cond-> (update f :idx inc)
                                 (and (= :list (:type f)) (zero? (:idx f)) (symbol? tok)) (assoc :head tok)
                                 (and (= :map (:type f)) (even? (:idx f))) (assoc :key tok))
                               (rest stack)))
                       stack))
              open (fn [type skip]
                     (cons {:type type :idx 0 :rc (= type :rc)
                            :parent (when frame (select-keys frame [:type :head :key :idx]))}
                           (drop skip stack)))]
          (cond
            (= c \newline) (recur (inc i) (inc row) 1 stack)
            (or (Character/isWhitespace c) (= c \,)) (recur (inc i) row (inc col) stack)
            (= c \;) (let [e (or (str/index-of text "\n" i) n)] (recur e row (+ col (- e i)) stack))
            (= c \") (let [e (loop [j (inc i)] (cond (>= j n) n (= \\ (.charAt text j)) (recur (+ j 2))
                                                     (= \" (.charAt text j)) (inc j) :else (recur (inc j))))
                           s (subs text i e) nl (count (filter #{\newline} s))]
                       (recur e (+ row nl) (if (pos? nl) (- (count s) (str/last-index-of s "\n")) (+ col (count s)))
                              (done stack nil)))
            (= c \\) (let [e (loop [j (+ i 2)] (if (and (< j n) (not (delim? (.charAt text j)))) (recur (inc j)) j))]
                       (recur e row (+ col (- e i)) (done stack nil)))
            (#{\( \[ \{} c) (recur (inc i) row (inc col) (open ({\( :list \[ :vector \{ :map} c) 0))
            (#{\) \] \}} c) (recur (inc i) row (inc col) (done (rest stack) nil))
            (= c \^) (recur (inc i) row (inc col) (cons (update (or frame {:type :top :idx 0}) :skip (fnil inc 0)) (rest stack)))
            (#{\' \` \~ \@} c) (recur (inc i) row (inc col) stack)
            (= c \#) (let [d (when (< (inc i) n) (.charAt text (inc i)))]
                       (case d
                         \{ (recur (+ i 2) row (+ col 2) (open :set 0))
                         \( (recur (+ i 2) row (+ col 2) (open :list 0))
                         \_ (recur (+ i 2) row (+ col 2) (cons (update (or frame {:type :top :idx 0}) :skip (fnil inc 0)) (rest stack)))
                         \? (let [j (if (= \@ (.charAt text (+ i 2))) (+ i 3) (+ i 2))]
                              (recur (inc j) row (+ col (- (inc j) i)) (open :rc 0)))
                         \" (recur (inc i) row (inc col) stack) ; a regex: the string after it
                         ;; #' #inst #:ns{..}: a prefix, the form after it is the element
                         (let [e (loop [j (inc i)] (if (and (< j n) (not (delim? (.charAt text j)))) (recur (inc j)) j))]
                           (recur e row (+ col (- e i)) stack))))
            :else
            (let [e (loop [j i] (if (and (< j n) (not (delim? (.charAt text j)))) (recur (inc j)) j))
                  s (subs text i e)
                  tok (cond (str/starts-with? s ":") (keyword (str/replace s #"^:+" ""))
                            (re-matches #"[-+]?\d.*" s) nil
                            :else (symbol s))]
              (when frame
                (vswap! out assoc! [row col] (assoc (select-keys frame [:type :idx :head :key :rc :parent]) :token tok)))
              (recur e row (+ col (- e i)) (done stack tok)))))))))

(def ^:private key-fns
  "Fns whose keyword arguments are keys of the map they take."
  '#{get get-in assoc assoc-in dissoc update update-in contains? find select-keys
     -> ->> some-> some->> cond-> cond->> doto})

(defn- key-fn? [head]
  (and (symbol? head)
       (contains? key-fns (if (#{"clojure.core" "cljs.core"} (namespace head)) (symbol (name head)) head))))

(defn- key-position?
  "Whether a keyword at `pos` (from token-positions) is in a key's place:
   (:k m), a key fn's argument, a map's key, {:keys [..]}, {sym :k}. A
   positional argument of any other call, or a map's value after a keyword
   key, holds the keyword as a value: (fetch cache ::count), [::a ::b]
   passed along. Where it isn't known, it counts as a key."
  [pos]
  (let [{:keys [type idx head key rc parent]} pos
        keys-key? #(and (keyword? %) (= "keys" (name %)))]
    (case type
      nil true
      :list (or rc (zero? idx) (key-fn? head))
      :map (or (even? idx) (symbol? key))
      (:vector :set) (or (and (= :map (:type parent)) (keys-key? (:key parent)))
                         (and (= :list (:type parent)) (key-fn? (:head parent))))
      true)))

(defn- keys-outside-contract
  "Data keys a linked var mentions that are neither in what it consumes or
   produces nor in what an entity it names produces, nor (the keys of the
   schemas among those), leaving out keys written inside a clojure.spec or
   malli call, or a call to a fn :spec or :malli lists under :schema-fns
   (a project's own validate), which name a schema rather than read a key,
   and keywords passed as values: an argument of a call other than a key
   fn's (fetch cache ::count), a map's value (key-position?). Only keys that appear
   in some entity's contract, or that a pack shapes, count as data keys. A
   key the entry names anywhere (a component in its deps) is declared, and
   so is what an entity it names consumes, the input it is passed. A
   binding-only entity has declared no contract, so it has none to keep; nor
   has one whose contract a pack read as code (a context built by (into ..))."
  [idx]
  (let [contracts (into {} (for [[id e] (:entities idx)] [id (contract-keys idx (:entry e))]))
        ;; a project's own fns that take a schema: (schema/validate ::message x)
        schema-fns (set (mapcat #(get-in idx [:config :packs % :schema-fns]) [:spec :malli]))
        data-keys (-> #{}
                      (into (mapcat (fn [{:keys [consumes produces]}] (concat consumes produces))) (vals contracts))
                      (into (map key) (data-key-entities idx))
                      (into (inline-data-keys idx)))
        positions (memoize (fn [f] (try (token-positions (slurp f)) (catch Exception _ {}))))
        dataflow-props (set (mapcat val (select-keys (:dataflow idx) [:consumes :produces])))]
    ;; each implementation: a linked var, or the rows a pack located (an
    ;; inline fn), whose keywords are those outside any var
    (for [[id v] (sort-by key (merge (update-vals (:impl-ranges idx) #(assoc % :inline true))
                                     (update-vals (linked-vars idx) first)))
          :let [defs (get-in idx [:entities id :definitions])
                entry (get-in idx [:entities id :entry])]
          :when (and (contains? (:entities idx) id)
                     (not (every? #(= :binding (:kind %)) defs))
                     ;; a contract written as code wasn't read: nothing to compare with
                     (not (some #(some dataflow-props (:dropped %)) defs)))
          :let [own (contracts id)
                ;; what the entities it names produce, and consume: the input it passes them
                via-deps (into #{} (mapcat #(let [c (contracts (second %))] (concat (:produces c) (:consumes c))))
                               (references idx entry))
                ;; a key the entry names anywhere: a component in its deps
                named (filter qualified-keyword? (tree-seq coll? seq (vals entry)))
                ;; a schema key in the contract stands for the keys it is made of
                allowed (into (with-members idx (into (into (:consumes own) (:produces own)) via-deps)) named)
                ;; (s/valid? ::order x), (m/validate ::order x): a schema named, no key read
                in-schema-call? (let [cs (filter #(or (schema-nses (:to %)) (schema-fns (symbol (str (:to %)) (str (:name %)))))
                                                 (get-in idx [:code (:filename v) :calls]))]
                                  (fn [{:keys [row col]}]
                                    (some #(and (:end-row %)
                                                (neg? (compare [(:row %) (:col %)] [row col]))
                                                (neg? (compare [row col] [(:end-row %) (:end-col %)])))
                                          cs)))
                candidates (->> (get-in idx [:code (:filename v) :keywords])
                                (filter (if (:inline v)
                                          #(and (nil? (:from-var %)) (:row %) (<= (:row v) (:row %) (:end-row v)))
                                          #(and (= (:from %) (:ns v)) (= (:from-var %) (:name v)))))
                                (remove in-schema-call?)
                                (filter #(let [k (keyword (str (:ns %)) (str (:name %)))]
                                           (and (data-keys k) (not (allowed k))))))
                ;; a keyword passed as a value, (fetch cache ::count), is not a key read
                outside (when (seq candidates)
                          (let [pos (positions (:filename v))]
                            (->> candidates
                                 (filter #(key-position? (get pos [(:row %) (:col %)])))
                                 (map #(keyword (str (:ns %)) (str (:name %))))
                                 distinct sort vec)))]
          :when (seq outside)]
      {:severity :info :problem :keys-outside-contract :dev-id id :keys outside
       :impl (loc idx v)})))

(defn- identity-problems
  "One id that two packs of declared things give (:id-in-several-packs,
   warning): a resolver and a component sharing a keyword are two things
   under one name. Schemas don't count: data keys are global, so a spec and
   a malli schema shaping one key are one entity, and a spec under a
   component's key is that component's shape. Nor does a pack that
   describes what others declare (:pack/describes?, atlas's registry
   describing an integrant component under its key), nor a place that
   only refers to an id."
  [idx]
  (let [kind->pack (into {} (for [p (:packs idx) k (:pack/kinds p)] [k p]))]
    (for [[id e] (sort-by key (:entities idx))
          :let [packs (distinct (keep #(kind->pack (:kind %)) (remove #(or (:runtime %) (:refers %) (:derived %)) (:definitions e))))]
          :when (> (count (remove #(or (:pack/data-keys? %) (:pack/describes? %)) packs)) 1)]
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
        :let [compared (or (:pack/runtime-kinds pack) #{kind})]
        [id e] (sort-by key (:entities idx))
        :let [code-defs (filter #(and (compared (:kind %)) (not (:runtime %)) (not (:derived %))) (:definitions e))
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
  (let [vars (into #{} (map #(symbol (str (:ns %)) (str (:name %)))) (all-vars idx))
        nses (into #{} (map #(str (:ns %))) (all-vars idx))
        view {:entities (:entities idx)
              :opts (pack-opts (:config idx))
              :of-kinds #(of-kinds idx %)
              :data-key? #(data-key? idx %)
              :loc #(loc idx %)
              :flow #(contract-keys idx %)
              ;; true or false for a var in a namespace the analysis read, nil otherwise
              :var-defined? #(when (nses (namespace %)) (contains? vars %))
              ;; the namespaces the analysis read: the project's own
              :namespaces nses
              ;; where the var implementing an id is, or nil
              :impl #(some->> (impl-of idx %) (loc idx))
              ;; the pack whose :pack/id-key linked that var, or nil
              :linked-by #(:linked-by-pack (impl-of idx %))
              ;; how many forms of a kind a pack found and couldn't read
              :not-read (let [n (frequencies (for [{:keys [pack-results]} (vals (:code idx)) [_ rs] pack-results r rs :when (:skipped r)] (:kind r)))]
                          #(get n % 0))}]
    (concat
     (mapcat #(when-let [c (:pack/checks %)] (c view)) (:packs idx))
     ;; a form not read; one a pack says breaks its pattern (docs/pattern.md),
     ;; when the project asks for it (:pattern true in the pack's options), is
     ;; :off-pattern instead
     ;; a finding a pack made reading its data: something the library itself
     ;; rejects when it loads it (an Overarch element with no id)
     (for [{:keys [file row kind problem] :as r} (:pack-data-results idx)
           :when problem]
       (merge (dissoc r :file :row :kind) {:pack kind :file (relative (:root idx) file) :line row}))
     (for [{:keys [file row kind skipped pattern]} (concat (for [[f {:keys [pack-results]}] (sort-by key (:code idx))
                                                                 [_ results] (sort-by key pack-results)
                                                                 r results]
                                                             (assoc r :file f))
                                                           (:pack-data-results idx))
           :when skipped
           :let [p (pack-of idx kind)
                 on? (and pattern (some #(get-in (:config idx) [:packs % :pattern]) (:pack/config-keys p)))]]
       (if on?
         {:severity :info :problem :off-pattern :pattern pattern :pack kind :lost skipped
          :file (relative (:root idx) file) :line row}
         {:severity :info :problem :pack-form-not-read :pack kind :reason skipped
          :file (relative (:root idx) file) :line row}))
     (identity-problems idx)
     (runtime-problems idx)
     ;; the pattern across packs (docs/pattern.md, I5 and R4): what builds
     ;; or takes routes hands components to them only through route fns'
     ;; destructured parameters, when the project asks
     (when (some #(get-in (:config idx) [:packs % :pattern]) [:integrant :reitit])
       (let [{:keys [inexact]} (edges idx)
             by (group-by (juxt :pattern :at) (for [[r rs] inexact x rs] (assoc x :route r)))]
         (for [[[pattern at] xs] (sort-by (comp pr-str key) by)]
           (cond-> {:severity :info :problem :off-pattern :pattern pattern :dev-id at
                    :routes (vec (sort (distinct (map :route xs))))
                    :lost (case pattern
                            :pattern/integrant-destructure "its config isn't destructured, or the whole config or a value bound in the body is handed to a route fn: what the routes it builds reach isn't in the graph"
                            :pattern/reitit-routes-in-route-fns "its init-key writes routes itself and uses its config: an inline handler may close over any of it, so what those routes reach isn't in the graph"
                            :pattern/reitit-hands-route-fns "its init-key uses its config outside the route fns' calls (a middleware, an option): what the routes it builds reach isn't in the graph"
                            :pattern/reitit-router-holds-routes "it takes routes and holds components that build none of them (a middleware's, or routes not written in a route fn), which may wrap them: what those routes reach isn't in the graph")
                    :definition (loc idx (get-in idx [:entities at]))}
             (:holds (first xs)) (assoc :holds (:holds (first xs))))))))))

(defn- relations-view
  "The relations rules and queries follow, each once for the whole index."
  [idx]
  (let [entities (sort-by key (:entities idx))
        index-by (fn [f] (reduce (fn [m [id e]] (reduce #(update %1 %2 (fnil conj []) id) m (f e))) {} entities))
        consumers (index-by #(:consumes (contract-keys idx (:entry %))))
        producers (index-by #(:produces (contract-keys idx (:entry %))))
        {:keys [calls provided-by given inexact]} (edges idx)
        deps (depends-on idx)
        reverse-of (fn [m] (reduce (fn [r [from tos]] (reduce #(update %1 %2 (fnil conj #{}) from) r tos)) {} m))
        called-by (reverse-of calls)
        provides (reverse-of provided-by)
        rdeps (reverse-of deps)]
    {:entities (:entities idx)
     :data-key? #(data-key? idx %)
     :loc #(loc idx %)
     :consumers #(get consumers %)
     :producers #(get producers %)
     ;; what its entry names, and what its pack resolves those names to
     :deps #(sort (get deps % #{}))
     :used-by #(sort (get rdeps % #{}))
     :calls #(sort (get calls %))
     :called-by #(sort (get called-by %))
     :provided-by #(sort (get provided-by %))
     :provides #(sort (get provides %))
     ;; what :reaches follows for :provided-by: what it is handed
     :handed #(sort (get given % #{}))
     ;; why an entity's reach isn't exact (docs/pattern.md): rules over it
     ;; are unverifiable rather than passing
     :inexact #(get inexact %)
     :inexact-all (fn [] (set (mapcat val inexact)))}))

(defn- rule-findings
  "The project's own rules (meta-source.rules) over the index, and the
   rules files that couldn't be read."
  [idx]
  (concat (for [p (:rules-problems idx)] (assoc p :severity :error))
          (rules/check (relations-view idx) (:rules idx))))

(defn reach
  "What `id` depends on, transitively, through `via` (default :deps, :calls
   and :provided-by): each reached entity with the path to it and where it
   is defined, nearest first."
  ([idx id] (reach idx id [:deps :calls :provided-by]))
  ([idx id via]
   (when (contains? (:entities idx) id)
     (let [view (relations-view idx)
           reached (vec (for [[r path] (sort-by (juxt (comp count val) key) (rules/reachable view via id))]
                          {:dev-id r :path path :definition (loc idx (get-in idx [:entities r]))}))
           ;; where the answer isn't exact, and why: off the pattern on the way
           not-exact (set (mapcat (:inexact view) (cons id (map :dev-id reached))))]
       (cond-> {:dev-id id :via (vec via) :reaches reached}
         (seq not-exact) (assoc :not-exact (vec (sort-by pr-str not-exact))))))))

(defn reached-by
  "What depends on `id`, transitively, through `via` (default :deps, :calls
   and :provided-by): the reverse of `reach`. Each with the path from it to
   `id` and where it is defined, nearest first: which routes break when a
   component changes."
  ([idx id] (reached-by idx id [:deps :calls :provided-by]))
  ([idx id via]
   (when (contains? (:entities idx) id)
     (let [view (relations-view idx)]
       {:dev-id id
        :via (vec via)
        :reached-by (vec (for [[r path] (sort-by (juxt (comp count val) key) (rules/reachers view via id))]
                           {:dev-id r :path path :definition (loc idx (get-in idx [:entities r]))}))}))))

(defn- in-lib?
  "Whether a finding is about a library's own code (:libs): it is located in
   a library's sources, or names an entity only a library defines."
  [idx]
  (let [dirs (map #(str (str/replace % #"/+$" "") "/") (get-in idx [:config :lib-src]))
        lib-file? (fn [f] (and f (let [f (relative (:root idx) (str f))] (some #(str/starts-with? f %) dirs))))
        lib-entity? (fn [id] (when-let [ds (seq (get-in idx [:entities id :definitions]))]
                               (every? #(lib-file? (:file %)) ds)))]
    (if (empty? dirs)
      (constantly false)
      (fn [f] (or (lib-file? (:file f)) (and (:dev-id f) (lib-entity? (:dev-id f))))))))

(defn check
  "Where code and registry files disagree, most severe first. What is found
   in a library's own code (:libs) is the library's, and left out."
  [idx]
  (let [linked (linked-vars idx)
        lib? (in-lib? idx)]
    ;; sort-by is stable: findings of one kind keep their order
    (vec (sort-by #(get {:error 0 :warning 1} (:severity %) 2)
                  (remove lib?
                  (concat
          (for [p (:read-problems idx)] (assoc p :severity :error))
          (for [[id vs] linked :when (> (count vs) 1)]
            {:severity :error :problem :dev-id-on-several-vars :dev-id id :vars (mapv #(loc idx %) vs)})
          ;; identity declared, nothing said about it yet: valid, unless a
          ;; pack's own meta key linked it, which makes it that pack's to report
          (for [[id e] (sort-by key (:entities idx))
                :when (and (every? #(= :binding (:kind %)) (:definitions e))
                           (not (:linked-by-pack (impl-of idx id))))]
            {:severity :info :problem :binding-only :dev-id id :impl (loc idx (impl-of idx id))})
          (undeclared-calls idx)
          (keys-outside-contract idx)
          (pack-problems idx)
          (rule-findings idx)))))))

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
