(ns meta-source.rules
  "EXPERIMENTAL (see README.md) — a project's own invariants, written as
   data and checked over the index next to what the packs check.

   A rules file (the paths under :rules in meta-source.edn) maps rule ids
   to rules:

     {:rule/pii-readers-audited
      {:doc \"Whatever reads a PII key is audited\"
       :severity :warning                          ; :error, :warning (default), :info
       :when {:= {:data/privacy :pii}}             ; the entities it is about
       :require [{:consumers {:all {:has :audit/logged}}}]}}

   A condition is a map; every key in it must hold:

     :kind :integrant          the entity has a source of this kind (or a set of kinds)
     :has :k or [:k ...]       its entry has these keys
     :missing :k or [:k ...]   its entry has none of them
     := {:k v}                 its entry's :k is v, or contains v
     :in {:k #{v ...}}         its entry's :k is one of these
     :id-ns \"shop.admin\"       its id's namespace is this or under it
     :data-key true            it is a data key (a schema shapes it)
     :not cond                 cond doesn't hold
     :or [cond ...]            one of them holds
     :consumers, :producers    a data key's consumers or producers,
     :deps, :used-by           what its entry names, or what names it:
                               {:all cond}, {:some cond} or {:none cond}

   :when selects the entities; each :require condition is checked on every
   one of them. A rule that fails on an entity is a :rule-violated finding
   at the entity, naming the rule, the clauses that failed and, for a
   relation, the entities that broke it, with where each is defined.")

(def ^:private relations #{:consumers :producers :deps :used-by})
(def ^:private quantifiers #{:all :some :none})
(def ^:private condition-keys (into #{:kind :has :missing := :in :id-ns :data-key :not :or} relations))

(defn- many [x] (if (or (sequential? x) (set? x)) x [x]))

(defn- holds-value? [v want]
  (or (= v want) (and (coll? v) (some #{want} v))))

(defn problems-in
  "What is wrong with condition `c`, as strings; empty when it is understood."
  [c]
  (cond
    (not (map? c)) [(str "a condition is a map, not " (pr-str c))]
    (empty? c) ["an empty condition"]
    :else
    (concat
     (for [k (keys c) :when (not (condition-keys k))] (str "unknown condition " (pr-str k)))
     (when (contains? c :not) (problems-in (:not c)))
     (when (contains? c :or)
       (if (sequential? (:or c)) (mapcat problems-in (:or c)) [":or takes a vector of conditions"]))
     (for [r relations :when (contains? c r)
           p (let [q (get c r)]
               (if (and (map? q) (= 1 (count q)) (quantifiers (key (first q))))
                 (problems-in (val (first q)))
                 [(str (pr-str r) " takes {:all cond}, {:some cond} or {:none cond}")]))]
       p)
     (for [k [:= :in] :when (and (contains? c k) (not (map? (get c k))))] (str (pr-str k) " takes a map")))))

(defn rule-problems
  "What is wrong with `rule`, as strings."
  [rule]
  (cond
    (not (map? rule)) ["a rule is a map with :when and :require"]
    :else (concat
           (when-not (contains? rule :when) ["no :when"])
           (when-not (sequential? (:require rule)) [":require takes a vector of conditions"])
           (when-not (contains? #{nil :error :warning :info} (:severity rule)) [(str "unknown severity " (pr-str (:severity rule)))])
           (when (contains? rule :when) (problems-in (:when rule)))
           (when (sequential? (:require rule)) (mapcat problems-in (:require rule))))))

(defn- related
  "The ids `relation` relates `id` to, in `view`."
  [view relation id]
  ((get view relation) id))

(declare holds?)

(defn- breakers
  "For a relation clause {quantifier cond}: nil when it holds on `id`,
   otherwise the related ids that break it (empty when :some found none)."
  [view relation q id]
  (let [[quantifier c] (first q)
        ids (related view relation id)
        ok? #(holds? view c %)]
    (case quantifier
      :all (let [bad (vec (remove ok? ids))] (when (seq bad) bad))
      :none (let [bad (vec (filter ok? ids))] (when (seq bad) bad))
      :some (when-not (some ok? ids) []))))

(defn holds?
  "True when condition `c` holds on entity `id`."
  [view c id]
  (let [e (get (:entities view) id)
        entry (:entry e)
        kinds (set (map :kind (:definitions e)))]
    (every? (fn [[k v]]
              (case k
                :kind (some kinds (many v))
                :has (every? #(contains? entry %) (many v))
                :missing (not-any? #(contains? entry %) (many v))
                := (every? (fn [[ek want]] (holds-value? (get entry ek) want)) v)
                :in (every? (fn [[ek allowed]] (contains? (set allowed) (get entry ek))) v)
                :id-ns (when-let [n (and (keyword? id) (namespace id))]
                         (or (= n v) (.startsWith ^String n (str v "."))))
                :data-key (= (boolean v) (boolean ((:data-key? view) e)))
                :not (not (holds? view v id))
                :or (some #(holds? view % id) v)
                (nil? (breakers view k v id))))
            c)))

(defn- failures
  "The :require clauses of `rule` that fail on `id`: [{:clause c :offenders [...]}]."
  [view rule id]
  (for [c (:require rule)
        :let [rel (first (filter #(contains? c %) relations))
              own (dissoc c :consumers :producers :deps :used-by)
              own-fails? (and (seq own) (not (holds? view own id)))
              broken (when rel (breakers view rel (get c rel) id))]
        :when (or own-fails? broken)]
    (cond-> {:clause c}
      (seq broken) (assoc :offenders broken))))

(defn check
  "The findings `rules` give over `view`: {:entities, :data-key? (fn [e]),
   :consumers :producers :deps :used-by (fn [id] → ids), :loc (fn [e])}.
   `rules` is [{:id :rule :file :line}]."
  [view rules]
  (let [ids (sort (keys (:entities view)))]
    (for [{:keys [id rule file line]} rules
          p (if-let [ps (seq (rule-problems rule))]
              [{:severity :error :problem :rule-not-understood :rule id :reasons (vec ps) :file file :line line}]
              (for [e-id ids
                    :when (holds? view (:when rule) e-id)
                    :let [fs (vec (failures view rule e-id))]
                    :when (seq fs)]
                (cond-> {:severity (:severity rule :warning) :problem :rule-violated :rule id :dev-id e-id
                         :failed (mapv :clause fs) :rule-at {:file file :line line}
                         :definition ((:loc view) (get (:entities view) e-id))}
                  (:doc rule) (assoc :doc (:doc rule))
                  (some :offenders fs)
                  (assoc :offenders (vec (distinct (mapcat :offenders fs)))
                         ;; where each is defined: an editor marks them too
                         :definitions (vec (keep #(some-> (get (:entities view) %) ((:loc view)))
                                                 (distinct (mapcat :offenders fs))))))))]
      p)))
