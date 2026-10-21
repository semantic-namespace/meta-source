(ns meta-source.pack.aero
  "EXPERIMENTAL (see README.md) — the aero pack: what a project's aero
   config files depend on, read as data with their tags kept. Nothing is
   loaded or evaluated, and no environment variable is read.

   `pack` is what meta-source.index runs (see the Packs section of
   meta-source.index), enabled by :aero under meta-source.edn's :packs:

     :aero {:configs [\"resources/config.edn\" \"resources/app\"]  ; files, or dirs of .edn
            :profiles [:prod :dev]        ; the profiles the code reads them with
            :resource-paths [\"resources\"] ; where #include looks, beside the file
            :provided [\".secrets.edn\"]    ; includes something else supplies
            :env-sources [\"deploy/main.tf\" \"docker-compose.yml\"]}  ; where env vars are set

   What it reads:

   - every #env, in the pack's vocabulary on an entity per variable,
     :env/NAME: :aero/defaults (the literal after it in an #or),
     :aero/required (some use has none), :aero/coerced (#long, #double,
     #integer, #keyword, #boolean around it). Each place it is read is a
     source, so `where :env/NAME` lists them;
   - every #include, resolved as aero's adaptive resolver does: on the
     resource paths, then beside the file. aero reads a missing one as
     {:aero/missing-include ...}, silently;
   - every tag, against the readers that exist: aero's own, and each
     (defmethod aero.core/reader 'tag ...) in the code;
   - each #profile, its branches; each #ref, its path; each number reader
     over a literal, its value."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [meta-source.read :as read]))

(def kind :aero)

(def builtin-tags
  "The tags aero reads itself, and Clojure's own."
  '#{env envf prop long double keyword boolean include join read-edn merge ref profile hostname user or inst uuid})

(def ^:private number-tags '#{long double integer})
(def ^:private coercions '#{long double integer keyword boolean})

(defn- tag-id
  "A tag's id: :aero.tag/ig.ref for #ig/ref."
  [t]
  (keyword "aero.tag" (str/replace (str t) "/" ".")))

(defn- number-text? [x] (or (number? x) (and (string? x) (re-matches #"-?\d+(\.\d+)?" x))))

;; =============================================================================
;; Walking a config value
;; =============================================================================

(defn- uses
  "What value `v`, under top-level key `k`, holds: [{:use ...}], a use
   being :env, :include, :tag, :profile, :ref or :not-a-number."
  ([v] (uses v nil))
  ([v coerce]
   (cond
     (tagged-literal? v)
     (let [{:keys [tag form]} v
           here (cons {:use :tag :tag tag}
                      (case tag
                        env [{:use :env :name (str form) :coerce coerce :required true}]
                        ;; #or [#env A #env B "default"]: A falls back to B, B to the
                        ;; default; an #env with nothing after it is required
                        or (let [xs (when (vector? form) form)
                                 env? #(and (tagged-literal? %) (= 'env (:tag %)))
                                 default (first (remove tagged-literal? xs))]
                             (concat
                              (for [[i x] (map-indexed vector xs) :when (env? x)
                                    :let [last-env? (not-any? env? (drop (inc i) xs))]]
                                (cond-> {:use :env :name (str (:form x)) :coerce coerce}
                                  (and last-env? (some? default)) (assoc :default default)
                                  (and last-env? (nil? default)) (assoc :required true)))
                              (when (some env? xs) [{:use :tag :tag 'env}])
                              (when (and (number-tags coerce) (some? default) (not (number-text? default)))
                                [{:use :not-a-number :tag coerce :value default}])
                              (mapcat #(uses % nil) (remove env? (filter tagged-literal? xs)))))
                        include [{:use :include :path (str form)}]
                        ref [{:use :ref :path form}]
                        profile (concat [{:use :profile :branches (vec (sort (keys form)))}]
                                        (mapcat #(uses % coerce) (vals form)))
                        (if (coercions tag)
                          (concat (when (and (number-tags tag) (not (tagged-literal? form)) (not (number-text? form)))
                                    [{:use :not-a-number :tag tag :value form}])
                                  (uses form tag))
                          (uses form nil))))]
       here)
     (map? v) (mapcat #(uses % nil) (concat (keys v) (vals v)))
     (coll? v) (mapcat #(uses % nil) v))))

(defn- paths
  "Every key path in map `m`."
  [m]
  (when (map? m)
    (mapcat (fn [[k v]] (cons [k] (map #(into [k] %) (paths v)))) m)))

;; =============================================================================
;; Reading the files, and the readers in the code
;; =============================================================================

(defn- include-found?
  "Whether `include` is found from config `file`: on a resource path or
   beside the file, as aero's adaptive resolver looks, or at the project
   root, as a root resolver or a map of names to paths does."
  [include file root resource-paths]
  (boolean (or (some #(.isFile (io/file root % include)) resource-paths)
               (.isFile (io/file (.getParent (io/file file)) include))
               ;; a root or map resolver, the process running at the root
               (.isFile (io/file root include)))))

(defn- set-names
  "Every identifier written in the files :env-sources names, under `root`:
   where a deployment sets environment variables (Terraform, docker-compose,
   a .env file). A presence check: a name written there counts as set."
  [root sources]
  (into #{} (for [f sources
                  :let [x (io/file root f)]
                  :when (.isFile x)
                  t (re-seq #"[A-Za-z_][A-Za-z0-9_]*" (slurp x))]
              t)))

(defn- read-configs [files opts]
  (let [{:keys [resource-paths env-sources] :or {resource-paths ["resources"]}} (:aero opts)
        proj (:meta-source/root opts)
        set-in-deploy (delay (set-names proj env-sources))]
    (vec
     (for [{:keys [file path text]} files
           r (let [kvs (try (read/keyed-lines text {:tags :keep}) (catch Exception ex ex))]
               (if (or (instance? Exception kvs) (nil? kvs))
                 [{:file file :row 1 :kind kind
                   :skipped (if (instance? Exception kvs) (str "not readable: " (ex-message kvs)) "not a map")}]
                 (let [lines (str/split-lines text)
                       find-row (fn [t from] (or (some #(when (str/includes? (nth lines (dec %)) t) %)
                                                       (range (max 1 from) (inc (count lines))))
                                                 from))
                       config (into {} (map (juxt first second)) kvs)]
                   (concat
                    (for [[k v line] kvs
                          u (uses v)
                          :let [at #(find-row % line)]
                          r (case (:use u)
                              :env [{:row (at (str "#env " (:name u))) :kind kind :refers true
                                     :dev-id (keyword "env" (:name u))
                                     :entry (cond-> {:aero/env true}
                                              (seq env-sources) (assoc :aero/set-in-deploy (contains? @set-in-deploy (:name u)))
                                              (contains? u :default) (assoc :aero/defaults #{(:default u)})
                                              (:required u) (assoc :aero/required true)
                                              (:coerce u) (assoc :aero/coerced #{(keyword (str (:coerce u)))}))}]
                              :tag (when-not (builtin-tags (:tag u))
                                     [{:row (at (str "#" (:tag u))) :kind kind :refers true
                                       :dev-id (tag-id (:tag u)) :entry {:aero/tag-used true}}])
                              :include [{:row (at (pr-str (:path u))) :kind kind :refers true
                                         :dev-id (keyword "aero.include" (:path u))
                                         :entry (cond-> {:aero/included true}
                                                  (not (include-found? (:path u) file proj resource-paths))
                                                  (assoc :aero/not-found-from #{path}))}]
                              (when (keyword? k)
                                [{:row (if (= :not-a-number (:use u)) (at (str "#" (:tag u))) line) :kind kind :dev-id k
                                  :entry (case (:use u)
                                           :profile {:aero/profile-branches #{(:branches u)}}
                                           :ref {:aero/refs #{(:path u)}
                                                 :aero/refs-to-nothing (if (some #{(vec (:path u))} (paths config)) #{} #{(:path u)})}
                                           :not-a-number {:aero/not-a-number #{[(keyword (str (:tag u))) (:value u)]}})}]))]
                      (assoc r :file file))))))]
       r))))

(defn- find-readers
  "Each (defmethod aero.core/reader 'tag ...) in the code: the defmethod
   form, at the row of its aero.core/reader usage."
  [analysis _opts]
  (let [rows (set (for [u (:var-usages analysis)
                        :when (and (:defmethod u) (= 'aero.core (:to u)) (= 'reader (:name u)))]
                    [(:filename u) (:row u)]))]
    (for [u (:var-usages analysis)
          :when (and ('#{clojure.core cljs.core} (:to u)) (= 'defmethod (:name u)) (rows [(:filename u) (:row u)]))]
      (select-keys u [:filename :row :name-row :name-col]))))

(defn- read-readers [sites {:keys [form-at]} _opts]
  (vec (for [{:keys [row name-row name-col]} sites
             :let [form (form-at name-row name-col)
                   d (when (seq? form) (nth form 2 nil))
                   t (if (and (seq? d) (= 'quote (first d))) (second d) d)]]
         (if (symbol? t)
           {:row row :kind kind :dev-id (tag-id t) :entry {:aero/reader true}}
           {:row row :kind kind :skipped "the reader's tag is not a quoted symbol"}))))

;; =============================================================================
;; Checks and the hover
;; =============================================================================

(defn- checks
  "- :include-not-found (warning): an #include no resource path or the
     including file's directory holds, and :provided doesn't list: aero
     reads it as {:aero/missing-include ...} and carries on;
   - :tag-without-reader (warning): a tag that is not aero's or Clojure's,
     with no (defmethod aero.core/reader ...) in the code read: reading the
     config throws;
   - :profile-never-read (info): a #profile branch none of :profiles
     names, and not :default: no profile the code passes reads it;
   - :profile-without-branch (warning): a #profile with no :default and no
     branch for one of :profiles: that profile reads nil;
   - :ref-to-nothing (warning): a #ref to a path the file doesn't hold;
   - :not-a-number (warning): #long, #double or #integer over a literal
     that isn't a number, or an #or default that isn't;
   - :env-not-set (info): an environment variable some use reads with no
     default, that none of the :env-sources files names. They may set it
     from elsewhere (a secret store, the console): a list to check, not a
     defect."
  [{:keys [of-kinds loc opts]}]
  (let [{:keys [profiles provided]} (:aero opts)
        profiles (set profiles)
        provided (set provided)
        es (sort-by key (of-kinds #{kind}))]
    (concat
     (for [[id e] es
           :let [missing (get-in e [:entry :aero/not-found-from])]
           :when (and (= "aero.include" (namespace id)) (seq missing) (not (provided (name id))))
           d (:definitions e)
           :let [l (loc d)]
           :when (contains? missing (:file l))]
       {:severity :warning :problem :include-not-found :dev-id id :definition l})
     (for [[id e] es
           :when (and (= "aero.tag" (namespace id)) (get-in e [:entry :aero/tag-used]) (not (get-in e [:entry :aero/reader])))
           d (:definitions e)]
       {:severity :warning :problem :tag-without-reader :dev-id id :definition (loc d)})
     (when (seq profiles)
       (for [[id e] es
             branches (get-in e [:entry :aero/profile-branches])
             :let [never (remove (conj profiles :default) branches)
                   missing (when-not (some #{:default} branches) (remove (set branches) profiles))]
             p (concat (when (seq never) [{:severity :info :problem :profile-never-read :dev-id id :keys (vec never)}])
                       (when (seq missing) [{:severity :warning :problem :profile-without-branch :dev-id id :keys (vec (sort missing))}]))]
         (assoc p :definition (loc e))))
     (for [[id e] es
           :let [bad (get-in e [:entry :aero/refs-to-nothing])]
           :when (seq bad)]
       {:severity :warning :problem :ref-to-nothing :dev-id id :paths (vec bad) :definition (loc e)})
     (for [[id e] es
           [tag v] (get-in e [:entry :aero/not-a-number])]
       {:severity :warning :problem :not-a-number :dev-id id :tag tag :value v :definition (loc e)})
     (for [[id e] es
           :let [{:aero/keys [required set-in-deploy]} (:entry e)]
           :when (and required (false? set-in-deploy))]
       {:severity :info :problem :env-not-set :dev-id id :definition (loc e)}))))

(defn- describe [entry]
  (cond
    (:aero/env entry) (str "environment variable"
                           (if (:aero/required entry) ", required somewhere" "")
                           (when-let [d (seq (:aero/defaults entry))] (str ", default " (str/join " " (map pr-str d))))
                           (when-let [c (seq (:aero/coerced entry))] (str ", read as " (str/join " " (map name c)))))
    (:aero/included entry) (str "aero #include" (when (seq (:aero/not-found-from entry)) ", not found from some files"))
    (or (:aero/reader entry) (:aero/tag-used entry)) (str "aero tag" (when-not (:aero/reader entry) ", no reader in the code"))))

(def pack
  {:pack/id :aero
   :pack/config-keys #{:aero}
   :pack/kinds #{kind}
   :pack/vocabulary #{"aero"}
   :pack/data-keys? false
   :pack/merge {:aero/not-found-from :union :aero/defaults :union :aero/coerced :union :aero/profile-branches :union
                :aero/refs :union :aero/refs-to-nothing :union :aero/not-a-number :union}
   :pack/find find-readers
   :pack/read read-readers
   :pack/data-files (fn [opts] (get-in opts [:aero :configs]))
   :pack/read-data read-configs
   :pack/checks checks
   :pack/describe describe})
