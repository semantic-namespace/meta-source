(ns meta-source.pack.fn-schema
  "EXPERIMENTAL (see README.md) — function contracts the code already
   writes, read as data: clojure.spec's `s/fdef`, and malli's `m/=>`, a
   defn's {:malli/schema ...} metadata and malli.experimental's `mx/defn`
   :- annotations. Nothing is loaded or evaluated.

   `pack` is what meta-source.index runs (see the Packs section of
   meta-source.index), enabled by :spec and :malli under meta-source.edn's
   :packs, as the schema pack is. A function's entry is keyed by its var,
   ns/name as a keyword (:shop.checkout/total), and that var implements it
   with no meta key. In the libraries' own vocabularies:

     :spec/args, :spec/ret      the fdef's specs as written
     :spec/args-keys            qualified keys its :args names: what it consumes
     :spec/ret-keys             qualified keys its :ret names: what it produces
     :malli/fn                  the m/=> or :malli/schema schema as written,
                                or [:=> [:cat ...] ret] built from mx/defn's :-
     :malli/args-keys, :malli/ret-keys   the same, from each :=> or :-> in it

   A key a spec names stands for the keys it is made of, so code reading a
   key of an :args spec's s/keys is within its contract."
  (:require [meta-source.pack.schema :as schema]))

(def kinds #{:spec-fn :malli-fn})

(defn- qualified-keywords [form]
  (vec (sort (distinct (filter qualified-keyword? (tree-seq coll? seq form))))))

(defn- spec-keys
  "The qualified keys a spec form names, leaving out the tags of s/cat,
   s/alt and s/or ((s/cat :order ::order) names ::order; a ::tag is a tag)."
  [form]
  (let [walk (fn walk [x]
               (cond
                 (qualified-keyword? x) [x]
                 (and (seq? x) (symbol? (first x)) ('#{cat alt or} (symbol (name (first x)))))
                 (mapcat walk (take-nth 2 (drop 2 x)))
                 (coll? x) (mapcat walk x)))]
    (vec (sort (distinct (walk form))))))

(defn- var-id
  "The var `sym` names in the file, as a keyword, or nil."
  [sym own-ns aliases]
  (when (symbol? sym)
    (keyword (if-let [n (namespace sym)] (get aliases n n) own-ns) (name sym))))

(defn- fdef [[_ sym & opts] own-ns aliases]
  (let [{:keys [args ret]} (when (even? (count opts)) (apply hash-map opts))
        id (var-id sym own-ns aliases)]
    (cond
      (nil? id) {:skipped "the fdef's name is not a symbol"}
      (not (or args ret)) {:skipped "no :args or :ret"}
      :else {:dev-id id :var (symbol (namespace id) (name id))
             :entry (cond-> {}
                      args (assoc :spec/args (schema/form-text args))
                      ret (assoc :spec/ret (schema/form-text ret))
                      (seq (spec-keys args)) (assoc :spec/args-keys (spec-keys args))
                      (seq (spec-keys ret)) (assoc :spec/ret-keys (spec-keys ret)))})))

(defn- arities
  "[[args-schema ret-schema] ...] in a function schema: [:=> args ret],
   [:-> arg ... ret], [:function ...] of them."
  [s]
  (for [x (tree-seq coll? seq s)
        :when (and (vector? x) (#{:=> :->} (first x)))]
    (if (= :=> (first x))
      [(nth x 1 nil) (nth x 2 nil)]
      [(vec (butlast (rest x))) (last x)])))

(defn- schema-of
  "A function schema from an id and the schema itself: what m/=> and
   :malli/schema say."
  [id s]
  (let [as (arities s)]
    (if (empty? as)
      {:skipped "no [:=> ...] or [:-> ...] in the schema written there"}
      {:dev-id id :var (symbol (namespace id) (name id))
       :entry (let [in (qualified-keywords (map first as))
                    out (qualified-keywords (map second as))]
                (cond-> {:malli/fn (schema/form-text s)}
                  (seq in) (assoc :malli/args-keys in)
                  (seq out) (assoc :malli/ret-keys out)))})))

(defn- annotated
  "The schemas after each :- in `xs`."
  [xs]
  (for [[a b] (partition 2 1 xs) :when (= :- a)] b))

(defn- mx-defn
  "(mx/defn name doc? :- ret? [arg :- schema ...] body) or its arities
   ([args] body), as [:=> [:cat arg-schemas] ret]."
  [[_ sym & more] own-ns aliases]
  (let [id (var-id sym own-ns aliases)
        doc-or-attrs #(or (string? %) (map? %))
        ;; :- ret before or after the docstring
        more (drop-while doc-or-attrs more)
        [ret more] (if (= :- (first more)) [(second more) (drop-while doc-or-attrs (drop 2 more))] [nil more])
        arglists (if (vector? (first more)) [(first more)] (keep #(when (and (seq? %) (vector? (first %))) (first %)) more))
        arities (for [args arglists] [:=> (into [:cat] (annotated args)) (or ret :any)])]
    (cond
      (nil? id) {:skipped "the mx/defn name is not a symbol"}
      ;; no :- anywhere: a plain defn, with no contract to read
      (not (or ret (some #(> (count (second %)) 1) arities))) nil
      :else (schema-of id (if (= 1 (count arities)) (first arities) (into [:function] arities))))))

(defn- malli-fn [[_ sym s] own-ns aliases]
  (if-let [id (var-id sym own-ns aliases)]
    (schema-of id s)
    {:skipped "the m/=> name is not a symbol"}))

(def ^:private spec-nses '#{clojure.spec.alpha cljs.spec.alpha})

(defn- find-forms
  "Each s/fdef call (with :spec); with :malli, each m/=> call, defn whose
   meta has :malli/schema, and mx/defn."
  [analysis opts]
  (concat
   (for [u (:var-usages analysis)
         :let [kind (cond (and (contains? opts :spec) (spec-nses (:to u)) (= 'fdef (:name u))) :spec-fn
                          (and (contains? opts :malli) (= 'malli.core (:to u)) (= '=> (:name u))) :malli-fn
                          (and (contains? opts :malli) (= 'malli.experimental (:to u)) (= 'defn (:name u))) :mx-defn)]
         :when kind]
     (assoc (select-keys u [:filename :row :name-row :name-col]) :kind kind))
   (when (contains? opts :malli)
     (for [d (:var-definitions analysis)
           :let [s (get-in d [:meta :malli/schema])]
           :when s]
       {:filename (:filename d) :row (:row d) :name-row (:row d) :name-col (inc (:col d))
        :kind :malli-meta :id (keyword (str (:ns d)) (str (:name d))) :schema s}))))

(defn- read-forms [sites {:keys [form-at own-ns aliases]} _opts]
  (vec (keep identity
        (for [{:keys [kind row name-row name-col id schema]} sites
             :let [form (when-not (= kind :malli-meta) (form-at name-row name-col))]]
         (some-> (cond
                  ;; clj-kondo gives the metadata as data: no form to read
                  (= kind :malli-meta) (schema-of id schema)
                  (= form :unreadable) {:skipped "not readable as data"}
                  (not (seq? form)) {:skipped "not a call"}
                  (= kind :spec-fn) (fdef form own-ns aliases)
                  (= kind :mx-defn) (mx-defn form own-ns aliases)
                  :else (malli-fn form own-ns aliases))
                 (assoc :row row :kind (if (= :spec-fn kind) :spec-fn :malli-fn)))))))

(defn- describe [entry]
  (cond
    (:malli/fn entry) (str "fn  " (:malli/fn entry))
    (or (:spec/args entry) (:spec/ret entry))
    (str "fn  " (or (:spec/args entry) "?") " → " (or (:spec/ret entry) "?"))))

(def pack
  {:pack/id :fn-schema
   :pack/config-keys #{:spec :malli}
   :pack/kinds kinds
   :pack/vocabulary #{"spec" "malli"}
   :pack/var-meta [:malli/schema]
   ;; what malli's own exported clj-kondo config says
   :pack/kondo-config {:lint-as {'malli.experimental/defn 'schema.core/defn}}
   :pack/data-keys? false
   :pack/dataflow {:consumes [:spec/args-keys :malli/args-keys]
                   :produces [:spec/ret-keys :malli/ret-keys]}
   :pack/find find-forms
   :pack/read read-forms
   :pack/describe describe})
