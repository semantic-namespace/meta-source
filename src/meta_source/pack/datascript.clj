(ns meta-source.pack.datascript
  "EXPERIMENTAL (see README.md) — the Datascript pack: a database's
   attributes, read as data from its schemas, and the pulls and lookup refs
   that depend on them. Nothing is loaded or evaluated.

   `pack` is what meta-source.index runs (see the Packs section of
   meta-source.index), enabled by :datascript under meta-source.edn's
   :packs:

     :datascript {:schemas [\"resources/schema.edn\"]}   ; EDN schema files, if any

   A schema is read where it is written: a map of attribute to its
   properties ({:user/friends {:db/valueType :db.type/ref ...}}) or a vector
   of attribute maps with :db/ident (Datomic's form), in any def holding
   one, in a datascript.core call (create-conn, empty-db, init-db,
   conn-from-datoms, conn-from-db), or in the EDN files :schemas names.
   Each attribute is a data key, whatever schema gives it: in the pack's
   own vocabulary,

     :datascript/value-type   :db.type/ref, ...    :datascript/unique   :db.unique/identity, ...
     :datascript/cardinality  :db.cardinality/many :datascript/component  true
     :datascript/index        true                 :datascript/tuple-attrs  [...]
     :datascript/doc

   A pull pattern written as data (d/pull, d/pull-many, and (pull ?e [...])
   in a quoted d/q query) refers to the attributes it joins on, and to the
   ones it reads in reverse (:block/_children); a lookup ref [:attr v] as an
   entity id (d/entity, d/pull, d/pull-many) to the attribute it looks up."
  (:require [clojure.string :as str]))

(def kind :datascript)

(def ^:private schema-keys #{:db/valueType :db/cardinality :db/unique :db/isComponent :db/index :db/tupleAttrs :db/doc})
(def ^:private schema-fns '#{create-conn empty-db init-db conn-from-datoms conn-from-db})
(def ^:private pull-fns '#{pull pull-many})

;; =============================================================================
;; Schemas
;; =============================================================================

(defn- attribute-entry [props]
  (let [{:db/keys [valueType cardinality unique isComponent index tupleAttrs doc]} props]
    (cond-> {}
      valueType (assoc :datascript/value-type valueType)
      cardinality (assoc :datascript/cardinality cardinality)
      unique (assoc :datascript/unique unique)
      (some? isComponent) (assoc :datascript/component isComponent)
      (some? index) (assoc :datascript/index index)
      tupleAttrs (assoc :datascript/tuple-attrs tupleAttrs)
      doc (assoc :datascript/doc doc))))

(defn- schema-map?
  "A map of qualified attributes to maps of :db/* properties, or empty ones."
  [x]
  (and (map? x) (seq x) (every? qualified-keyword? (keys x)) (every? map? (vals x))
       (some #(some schema-keys (keys %)) (vals x))))

(defn- schema-vector?
  "Datomic's form: a vector of maps, each with a :db/ident."
  [x]
  (and (vector? x) (seq x) (every? #(and (map? %) (qualified-keyword? (:db/ident %))) x)))

(defn- schema-attributes
  "{attribute props} in schema `x`, or nil when it isn't one."
  [x]
  (cond
    (schema-map? x) x
    (schema-vector? x) (into {} (for [m x] [(:db/ident m) (dissoc m :db/ident)]))))

;; =============================================================================
;; Pull patterns and lookup refs
;; =============================================================================

(defn- reverse-attr
  "The attribute :ns/_name reads in reverse, or nil."
  [k]
  (when (and (qualified-keyword? k) (str/starts-with? (name k) "_"))
    (keyword (namespace k) (subs (name k) 1))))

(defn- pattern-refs
  "[[attr how] ...] in pull pattern `p`: :joined for a map's keys, :reversed
   for an :ns/_attr read in reverse (the attr it reverses)."
  [p]
  (let [attr-of (fn [x] (cond (keyword? x) x
                              (and (or (vector? x) (seq? x)) (keyword? (first x))) (first x)))
        one (fn one [x]
              (cond
                (map? x) (mapcat (fn [[k sub]]
                                   (let [a (attr-of k)]
                                     (concat (when a (if-let [r (reverse-attr a)] [[r :reversed]] [[a :joined]]))
                                             (when (vector? sub) (mapcat one sub)))))
                                 x)
                :else (when-let [r (reverse-attr (attr-of x))] [[r :reversed]])))]
    (when (vector? p) (mapcat one p))))

(defn- unquote* [x] (if (and (seq? x) (= 'quote (first x))) (second x) x))

(defn- lookup-attr [x] (when (and (vector? x) (= 2 (count x)) (qualified-keyword? (first x))) (first x)))

(defn- query-pulls
  "The pull patterns in a quoted query: (pull ?e [...])."
  [q]
  (for [x (tree-seq coll? seq (unquote* q))
        :when (and (seq? x) (= 'pull (first x)) (vector? (nth x 2 nil)))]
    (nth x 2)))

;; =============================================================================
;; Finding and reading
;; =============================================================================

(defn- find-forms
  "Each datascript.core call that takes a schema, a pull pattern or a
   lookup ref, and each def whose body has :db/* schema keys in it."
  [analysis _opts]
  (let [kws (group-by :filename (:keywords analysis))
        schema-rows? (fn [file row end-row]
                       (some #(and (:row %) (<= row (:row %) end-row) (= "db" (str (:ns %))) (schema-keys (keyword "db" (:name %))))
                             (get kws file)))]
    (concat
     (for [u (:var-usages analysis)
           :when (and (= 'datascript.core (:to u)) (or (schema-fns (:name u)) (pull-fns (:name u)) ('#{q entity} (:name u))))]
       (assoc (select-keys u [:filename :row :name-row :name-col]) :site (:name u)))
     (for [d (:var-definitions analysis)
           :when (and ('#{clojure.core/def cljs.core/def clojure.core/defonce cljs.core/defonce} (:defined-by d))
                      (:end-row d)
                      (schema-rows? (:filename d) (:row d) (:end-row d)))]
       {:filename (:filename d) :row (:row d) :name-row (:row d) :name-col (inc (:col d)) :site :def}))))

(defn- schema-results [attrs row find-row]
  (for [[a props] (sort-by key attrs)]
    {:row (or (find-row (pr-str a) row) row) :kind kind :dev-id a :entry (attribute-entry props)}))

(defn- ref-results [refs row find-row]
  (for [[a how] (distinct refs)]
    {:row (or (find-row (pr-str (if (= :reversed how) (keyword (namespace a) (str "_" (name a))) a)) row) row)
     :kind kind :dev-id a :refers true
     :entry {(case how :joined :datascript/joined :reversed :datascript/reversed :looked-up :datascript/looked-up) true}}))

(defn- read-forms [sites {:keys [form-at find-row]} _opts]
  (vec (for [{:keys [site row name-row name-col]} sites
             :let [form (form-at name-row name-col)
                   args (when (seq? form) (map unquote* (rest form)))]
             r (cond
                 (= form :unreadable) [{:row row :kind kind :skipped "not readable as data"}]
                 (not (seq? form)) nil
                 (= :def site) (when-let [attrs (schema-attributes (unquote* (last form)))]
                                 (schema-results attrs row find-row))
                 (schema-fns site) (keep identity
                                         (let [attrs (some schema-attributes args)]
                                           (when attrs (schema-results attrs row find-row))))
                 ;; (d/pull db pattern eid), (d/pull-many db pattern eids)
                 (pull-fns site) (let [[_db pattern eid] args]
                                   (ref-results (concat (pattern-refs pattern)
                                                        (when-let [a (lookup-attr eid)] [[a :looked-up]])
                                                        (when (= 'pull-many site)
                                                          (for [e (when (vector? eid) eid) :let [a (lookup-attr e)] :when a] [a :looked-up])))
                                                row find-row))
                 (= 'entity site) (let [[_db eid] args]
                                    (when-let [a (lookup-attr eid)] (ref-results [[a :looked-up]] row find-row)))
                 (= 'q site) (ref-results (mapcat pattern-refs (query-pulls (first args))) row find-row))]
         r)))

(defn- read-data
  "The EDN files :schemas names, each a schema map or vector."
  [files _opts]
  (vec (for [{:keys [file text]} files
             r (let [x (try (binding [*read-eval* false *default-data-reader-fn* tagged-literal] (read-string text))
                            (catch Exception ex ex))
                     attrs (when-not (instance? Exception x) (schema-attributes x))]
                 (cond
                   (instance? Exception x) [{:file file :row 1 :kind kind :skipped (str "not readable: " (ex-message x))}]
                   (nil? attrs) [{:file file :row 1 :kind kind :skipped "not a schema map or vector of :db/ident maps"}]
                   :else (let [lines (str/split-lines text)
                               find-row (fn [t from] (some #(when (str/includes? (nth lines (dec %)) t) %) (range from (inc (count lines)))))]
                           (map #(assoc % :file file) (schema-results attrs 1 find-row)))))]
         r)))

;; =============================================================================
;; Checks, shapes and the hover
;; =============================================================================

(def ^:private scalar-forms
  "Shapes that hold one plain value: a spec or malli form as text."
  #{"string?" "int?" "pos-int?" "nat-int?" "number?" "double?" "keyword?" "boolean?" "uuid?" "inst?" "decimal?"
    ":string" ":int" ":double" ":keyword" ":boolean" ":uuid" ":inst" "string" "int"})

(defn- checks
  "- :pull-join-not-ref (warning): a pull pattern joins on an attribute
     whose schema doesn't make it a ref; Datascript returns its value and
     ignores the subpattern. Info when no schema read gives the attribute;
   - :reverse-not-ref (warning): a pattern reads :ns/_attr in reverse, and
     :ns/attr is not a ref; Datascript refuses it;
   - :lookup-ref-not-unique (warning): a lookup ref [:attr v] whose
     attribute is not :db/unique; Datascript refuses it;
   - :schema-disagrees (warning): an attribute that is many, or a ref, in
     the Datascript schema, whose spec or malli schema is one plain value.
   Not reported when the pack read no schema."
  [{:keys [of-kinds loc not-read]}]
  (let [attrs (sort-by key (of-kinds #{kind}))
        schema? (fn [e] (some #(and (= kind (:kind %)) (not (:refers %))) (:definitions e)))
        any-schema? (some (comp schema? val) attrs)
        severity (fn [e] (if (and (schema? e) (zero? (not-read kind))) :warning :info))]
    (when any-schema?
      (concat
       (for [[id e] attrs
             :let [{:datascript/keys [value-type unique]} (:entry e)]
             d (:definitions e)
             k (:keys d)
             :let [p (case k
                       :datascript/joined (when (not= :db.type/ref value-type) :pull-join-not-ref)
                       :datascript/reversed (when (not= :db.type/ref value-type) :reverse-not-ref)
                       :datascript/looked-up (when-not unique :lookup-ref-not-unique)
                       nil)]
             :when p]
         (cond-> {:severity (severity e) :problem p :dev-id id :definition (loc d)}
           (not (schema? e)) (assoc :in-no-schema true)))
       (for [[id e] attrs
             :let [{:datascript/keys [value-type cardinality]} (:entry e)
                   shape (or (get-in e [:entry :spec/form]) (get-in e [:entry :malli/form]))
                   why (cond (not (scalar-forms shape)) nil
                             (= :db.cardinality/many cardinality) "many"
                             (and (= :db.type/ref value-type) (not (#{"int?" "pos-int?" "nat-int?" ":int"} shape))) "a ref")]
             :when why]
         {:severity :warning :problem :schema-disagrees :dev-id id :shape shape :datascript why
          :definition (loc e)})))))

(defn- shape [entry _kind]
  (let [{:datascript/keys [value-type cardinality unique component]} entry]
    (str/join ", " (remove nil? [(some-> value-type name) (some-> cardinality name) (some-> unique name)
                                 (when component "component")]))))

(defn- describe [entry]
  (let [s (shape entry nil)]
    (when (or (seq s) (some #(= "datascript" (namespace %)) (keys entry)))
      (str "datascript attribute" (when (seq s) (str "  " s))))))

(def pack
  {:pack/id :datascript
   :pack/config-keys #{:datascript}
   :pack/kinds #{kind}
   :pack/vocabulary #{"datascript"}
   :pack/data-keys? true
   :pack/find find-forms
   :pack/read read-forms
   :pack/data-files (fn [opts] (get-in opts [:datascript :schemas]))
   :pack/read-data read-data
   :pack/checks checks
   :pack/shape shape
   :pack/describe describe})
