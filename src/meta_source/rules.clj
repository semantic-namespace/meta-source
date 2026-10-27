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
     :matches {:k \"regex\"}     its entry's :k is a string the regex finds
                               (for :=, :in and :matches, :k may be a path
                               of keys: [:reitit/data :require-privilege])
     :id-ns \"shop.admin\"       its id's namespace is this or under it
     :data-key true            it is a data key (a schema shapes it)
     :not cond                 cond doesn't hold
     :or [cond ...]            one of them holds
     :id :k or #{:k ...}       it is one of these entities
     :consumers, :producers    a data key's consumers or producers,
     :deps, :used-by           what its entry names, or what names it,
     :calls, :called-by        whose linked var its code calls, or whose code calls its,
     :provided-by, :provides   the entity whose code builds the var it is defined
                               in (a route's component), or what it so builds:
                               {:all cond}, {:some cond} or {:none cond}
     :reaches {q cond}         everything it depends on, transitively, through
                               :deps, :calls and :provided-by (or the ones
                               :via names): {:reaches {:none {:id :infra/db}}},
                               {:reaches {:via #{:deps} :some {:kind :spec}}};
                               a violation names the path to each offender
     :reached-by {q cond}      everything that depends on it, transitively, the
                               reverse of :reaches, with :via the same:
                               {:reached-by {:none {:kind :reitit :matches
                               {:reitit/path \"^/public\"}}}}; a violation names
                               the path from each offender to it
     :handed {q cond}          what the component that builds it hands it, one
                               hop (a route built in an init-key: the components
                               behind the config keys passed on)
     :has-in [:k ...]          its entry has a value at this path of keys:
                               {:has-in [:reitit/data :auth/roles]}
     :refs {:k q}              what its entry's :k names, q as above:
                               {:refs {:fn/deps {:all {:has :tier/x}}}}
     :referenced-by {:k q}     the entities whose :k names it
     :resolves :k or [:k ...]  everything its entry's :k names is an
                               entity some source defines

   :when selects the entities; each :require condition is checked on every
   one of them. A rule that fails on an entity is a :rule-violated finding
   at the entity, naming the rule, the clauses that failed and, for a
   relation, the entities that broke it, with where each is defined.")

(def ^:private relations #{:consumers :producers :deps :used-by :calls :called-by :provided-by :provides :handed})
(def ^:private depends-on
  "What :reaches follows by default: each a relation from an entity to what it
   depends on."
  #{:deps :calls :provided-by})
(def ^:private quantifiers #{:all :some :none})
(def ^:private property-relations #{:refs :referenced-by})
(def ^:private condition-keys
  (into #{:kind :has :has-in :missing := :in :matches :id-ns :id :data-key :not :or :resolves :reaches :reached-by} (concat relations property-relations)))

(defn- many [x] (if (or (sequential? x) (set? x)) x [x]))

(defn- at
  "The value at `k` in `entry`, or at a path of keys when `k` is a vector:
   [:reitit/data :require-privilege]."
  [entry k]
  (if (vector? k) (get-in entry k) (get entry k)))

(defn names
  "The ids `entry`'s `k` names: the qualified keywords in its value, a
   keyword or a collection of them, nested (a map's values, not its keys)."
  [entry k]
  (letfn [(walk [x] (cond (qualified-keyword? x) [x]
                          (map? x) (mapcat walk (vals x))
                          (coll? x) (mapcat walk x)
                          :else []))]
    (vec (distinct (walk (at entry k))))))

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
     (for [r property-relations :when (contains? c r)
           p (let [m (get c r)]
               (if (and (map? m) (seq m))
                 (for [[k q] m
                       p (if (and (map? q) (= 1 (count q)) (quantifiers (key (first q))))
                           (problems-in (val (first q)))
                           [(str (pr-str r) " " (pr-str k) " takes {:all cond}, {:some cond} or {:none cond}")])]
                   p)
                 [(str (pr-str r) " takes {:key {:all cond}}, a key, and a clause on what it names")]))]
       p)
     (for [r [:reaches :reached-by] :when (contains? c r)
           p (let [q (get c r)
                   via (when (map? q) (:via q))
                   qs (when (map? q) (dissoc q :via))]
               (concat
                (if (and (map? qs) (= 1 (count qs)) (quantifiers (key (first qs))))
                  (problems-in (val (first qs)))
                  [(str (pr-str r) " takes {:all cond}, {:some cond} or {:none cond}, and optionally :via")])
                (when (and via (not (and (coll? via) (every? depends-on via))))
                  [(str (pr-str r) " :via takes some of " (pr-str (sort depends-on)))])))]
       p)
     (when (and (contains? c :has-in) (not (and (vector? (:has-in c)) (seq (:has-in c)))))
       [":has-in takes a vector of keys, a path"])
     (when (contains? c :resolves)
       (when-not (every? keyword? (many (:resolves c)))
         [":resolves takes a key or a vector of keys"]))
     (for [k [:= :in :matches] :when (and (contains? c k) (not (map? (get c k))))] (str (pr-str k) " takes a map"))
     (when (map? (:matches c))
       (for [[k re] (:matches c)
             :let [bad (if (string? re) (try (re-pattern re) nil (catch Exception ex (ex-message ex))) "not a string")]
             :when bad]
         (str ":matches " (pr-str k) ": " bad))))))

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

(defn- quantified
  "For {quantifier cond} over `ids`: nil when it holds, otherwise the ids
   that break it (empty when :some found none)."
  [view q ids]
  (let [[quantifier c] (first q)
        ok? #(holds? view c %)]
    (case quantifier
      :all (let [bad (vec (remove ok? ids))] (when (seq bad) bad))
      :none (let [bad (vec (filter ok? ids))] (when (seq bad) bad))
      :some (when-not (some ok? ids) []))))

(defn- breakers
  "For a relation clause {quantifier cond}: nil when it holds on `id`,
   otherwise the related ids that break it (empty when :some found none)."
  [view relation q id]
  (quantified view q (related view relation id)))

(defn- property-breakers
  "For {:k {quantifier cond}} under :refs or :referenced-by: nil when every
   key's clause holds on `id`, otherwise the ids that break them."
  [view relation m id]
  (let [entry (get-in view [:entities id :entry])
        broken (for [[k q] m
                     :let [ids (case relation
                                 :refs (names entry k)
                                 :referenced-by ((:referrers view) k id))
                           b (quantified view q ids)]
                     :when b]
                 b)]
    (when (seq broken) (vec (distinct (apply concat broken))))))

(defn reachable
  "{reached-id path} for what `id` depends on through the relations `via`,
   transitively; path is the ids from `id`'s first step to the reached one,
   one shortest path each."
  [view via id]
  ;; through :provided-by, what is reached is what the builder hands over
  (let [via (map #(get {:provided-by :handed} % %) via)]
  (loop [frontier [[id []]] seen {id []}]
    (if-let [[[x path] & more] (seq frontier)]
      (let [nexts (for [r via n (related view r x) :when (not (contains? seen n))] n)
            nexts (distinct nexts)
            seen (reduce #(assoc %1 %2 (conj path %2)) seen nexts)]
        (recur (concat more (for [n nexts] [n (conj path n)])) seen))
      (dissoc seen id)))))

(defn reverse-edges
  "{id #{id ...}}: for each entity, those that depend on it through the
   relations `via`, one step (what :reaches follows, reversed)."
  [view via]
  (let [via (map #(get {:provided-by :handed} % %) via)]
    (reduce (fn [m x] (reduce (fn [m n] (update m n (fnil conj #{}) x)) m (for [r via n (related view r x)] n)))
            {} (keys (:entities view)))))

(defn reachers
  "{reacher path} for what depends on `id` through the relations `via`,
   transitively: the reverse of `reachable`. Path is the ids from the
   reacher to `id`, both included, one shortest path each."
  [view via id]
  (let [rev (if-let [f (:reverse-edges view)] (f via) (reverse-edges view via))]
    (loop [frontier [[id [id]]] seen {id [id]}]
      (if-let [[[x chain] & more] (seq frontier)]
        (let [prevs (distinct (for [y (sort (get rev x)) :when (not (contains? seen y))] y))
              seen (reduce #(assoc %1 %2 (into [%2] chain)) seen prevs)]
          (recur (concat more (for [y prevs] [y (into [y] chain)])) seen))
        (dissoc seen id)))))

(defn- reached-by-breakers
  "For {:reached-by {q cond}}: nil when it holds on `id`, otherwise the
   entities reaching it that break it."
  [view q id]
  (let [via (or (:via q) depends-on)]
    (quantified view (dissoc q :via) (sort (keys (reachers view via id))))))

(defn- reach-breakers
  "For {:reaches {q cond}}: nil when it holds on `id`, otherwise the reached
   ids that break it (empty when :some found none)."
  [view q id]
  (let [via (or (:via q) depends-on)]
    (quantified view (dissoc q :via) (sort (keys (reachable view via id))))))

(defn- unresolved
  "The ids `id`'s entry names under the :resolves keys that no source
   defines, or nil when there are none."
  [view ks id]
  (let [entry (get-in view [:entities id :entry])
        bad (vec (distinct (for [k (many ks) n (names entry k)
                                 :when (not (contains? (:entities view) n))]
                             n)))]
    (when (seq bad) bad)))

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
                := (every? (fn [[ek want]] (holds-value? (at entry ek) want)) v)
                :in (every? (fn [[ek allowed]] (contains? (set allowed) (at entry ek))) v)
                :matches (every? (fn [[ek re]] (let [x (at entry ek)] (and (string? x) (re-find (re-pattern re) x)))) v)
                :id (contains? (set (many v)) id)
                :reaches (nil? (reach-breakers view v id))
                :reached-by (nil? (reached-by-breakers view v id))
                :has-in (some? (get-in entry v))
                :id-ns (when-let [n (and (keyword? id) (namespace id))]
                         (or (= n v) (.startsWith ^String n (str v "."))))
                :data-key (= (boolean v) (boolean ((:data-key? view) e)))
                :not (not (holds? view v id))
                :or (some #(holds? view % id) v)
                :resolves (nil? (unresolved view v id))
                (:refs :referenced-by) (nil? (property-breakers view k v id))
                (nil? (breakers view k v id))))
            c)))

(defn- unsure
  "Why `c`, on `id`, can't be told: the #{{:pattern :at ...}} that make what
   it follows through code not exact (docs/pattern.md), or nil. :handed
   needs `id`'s own hand-off; :reaches every hand-off on the way; :reached-by
   every entity's, since any of them might reach `id`."
  [view c id]
  (let [inexact (or (:inexact view) (constantly nil))
        rs (concat
            (when (contains? c :handed) (inexact id))
            (when (contains? c :reaches)
              (mapcat inexact (cons id (keys (reachable view (or (:via (:reaches c)) depends-on) id)))))
            (when (contains? c :reached-by) (when-let [f (:inexact-all view)] (f)))
            (when (contains? c :not) (unsure view (:not c) id))
            (when (contains? c :or) (mapcat #(unsure view % id) (:or c))))]
    (when (seq rs) (set rs))))

(defn- failures
  "The :require clauses of `rule` that fail on `id`: [{:clause c :offenders [...]}]."
  [view rule id]
  (for [c (:require rule)
        :when (not (unsure view c id))
        :let [own (apply dissoc c :resolves :reaches :reached-by (concat relations property-relations))
              own-fails? (and (seq own) (not (holds? view own id)))
              ;; each related clause: nil when it holds, else who breaks it
              results (concat (for [r relations :when (contains? c r)] (breakers view r (get c r) id))
                              (for [r property-relations :when (contains? c r)] (property-breakers view r (get c r) id))
                              (when (contains? c :resolves) [(unresolved view (:resolves c) id)])
                              (when (contains? c :reaches) [(reach-breakers view (:reaches c) id)])
                              (when (contains? c :reached-by) [(reached-by-breakers view (:reached-by c) id)]))
              broken (vec (distinct (apply concat (remove nil? results))))
              ;; how each offender a :reaches clause names is reached
              paths (when (seq broken)
                      (merge (when (contains? c :reaches)
                               (let [r (reachable view (or (:via (:reaches c)) depends-on) id)]
                                 (into {} (for [b broken :when (contains? r b)] [b (get r b)]))))
                             ;; from each offender to this entity
                             (when (contains? c :reached-by)
                               (let [r (reachers view (or (:via (:reached-by c)) depends-on) id)]
                                 (into {} (for [b broken :when (contains? r b)] [b (get r b)]))))))]
        :when (or own-fails? (some some? results))
        ;; where `id`'s entry was given each offender (or a :reaches path's
        ;; first step), when a unioned key carries it: a relation drawn in
        ;; another place than the entity's own definition
        :let [given (get-in view [:entities id :given-by])
              at (fn [ks v] (distinct (for [[k by-v] (sort-by (comp str key) given)
                                            :when (or (nil? ks) (contains? ks k))
                                            d (get by-v v)]
                                        d)))
              given-at (vec (distinct
                             (for [o broken
                                   d (concat (when (contains? c :refs) (at (set (keys (:refs c))) o))
                                             (when (contains? c :deps) (at nil o))
                                             (when-let [path (get paths o)] (at nil (or (first path) o))))]
                               {:offender o :at d})))]]
    (cond-> {:clause c}
      (seq broken) (assoc :offenders broken)
      (seq paths) (assoc :paths paths)
      (seq given-at) (assoc :given-at given-at))))

(defn check
  "The findings `rules` give over `view`: {:entities, :data-key? (fn [e]),
   :consumers :producers :deps :used-by (fn [id] → ids), :loc (fn [e])}.
   `rules` is [{:id :rule :file :line}]."
  [view rules]
  (let [ids (sort (keys (:entities view)))
        ;; {:k {id [ids whose :k names it]}}, built for a key when a rule asks
        by-key (memoize (fn [k] (reduce (fn [m [from e]] (reduce #(update %1 %2 (fnil conj []) from) m (names (:entry e) k)))
                                        {} (sort-by key (:entities view)))))
        view (assoc view :referrers (fn [k id] (get (by-key k) id)))
        ;; :reached-by's reversed graph, built once per :via
        view (assoc view :reverse-edges (memoize (fn [via] (reverse-edges view via))))]
    (for [{:keys [id rule file line]} rules
          p (if-let [ps (seq (rule-problems rule))]
              [{:severity :error :problem :rule-not-understood :rule id :reasons (vec ps) :file file :line line}]
              (for [e-id ids
                    ;; whether the rule applies, or a clause holds, can't be
                    ;; told through code off the pattern: said, never passed
                    :let [when-unsure (unsure view (:when rule) e-id)]
                    :when (or when-unsure (holds? view (:when rule) e-id))
                    :let [unsure-clauses (when-not when-unsure (filterv #(unsure view % e-id) (:require rule)))
                          because (or when-unsure (some->> (seq unsure-clauses) (mapcat #(unsure view % e-id)) set))
                          fs (when-not when-unsure (vec (failures view rule e-id)))]
                    p (concat
                       (when because
                         [(cond-> {:severity :warning :problem :rule-unverifiable :rule id :dev-id e-id
                                   :because (vec (sort-by pr-str because))
                                   :rule-at {:file file :line line}
                                   :definition ((:loc view) (get (:entities view) e-id))}
                            (:doc rule) (assoc :doc (:doc rule))
                            when-unsure (assoc :applies :unknown)
                            (seq unsure-clauses) (assoc :clauses unsure-clauses))])
                       (when (seq fs) [fs]))
                    :let [fs (when (vector? p) p)]]
                (if-not fs
                  p
                (cond-> {:severity (:severity rule :warning) :problem :rule-violated :rule id :dev-id e-id
                         :failed (mapv :clause fs) :rule-at {:file file :line line}
                         :definition ((:loc view) (get (:entities view) e-id))}
                  (:doc rule) (assoc :doc (:doc rule))
                  (some :paths fs) (assoc :paths (apply merge (keep :paths fs)))
                  ;; where each offender was given: the place an editor marks first
                  (some :given-at fs)
                  (assoc :given-at (vec (distinct (for [{:keys [offender at]} (mapcat :given-at fs)
                                                        :let [l ((:loc view) at)] :when l]
                                                    (assoc l :offender offender)))))
                  (some :offenders fs)
                  (assoc :offenders (vec (distinct (mapcat :offenders fs)))
                         ;; where each is defined: an editor marks them too
                         :definitions (vec (keep #(some-> (get (:entities view) %) ((:loc view)))
                                                 (distinct (mapcat :offenders fs)))))))))]
      p)))
