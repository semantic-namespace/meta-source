(ns meta-source.pack.overarch
  "EXPERIMENTAL (see README.md) — the Overarch pack: an architecture model,
   read as data from the EDN files that write it. Nothing is loaded or run.

   `pack` is what meta-source.index runs (see the Packs section of
   meta-source.index), enabled by :overarch under meta-source.edn's :packs:

     {:packs {:overarch {:models [\"models/shop\"]}}}

   An Overarch model (github.com/soulspace-org/overarch) is EDN files of
   element maps. Each element is an entity under its :id:

     :overarch/el        what it is: :system, :container, :component, :person…
     :overarch/name, :overarch/desc, :overarch/tech, :overarch/tags
     :overarch/contains  the elements its :ct holds

   A relation is an element with :from and :to (:rel, :uses, :dataflow…):
   an entity with :overarch/from and :overarch/to, and on the element it
   starts from, :overarch/relates-to (unioned across relations). So `deps`
   on an element lists what the model says it relates to, and `uses` the
   elements relating to it and the views showing it. A view is an element
   whose :el ends in -view: :overarch/shows, the ids its :ct refers to, and
   :overarch/selection, a selection Overarch resolves when it renders.

   The pack describes its elements, as atlas describes an integrant key: an
   element's id is often another registry's id (the atlas adapter exports
   atlas ids as they are), whose declaration stays primary. That is what
   lets `check` compare the model with what the code and registries say."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def kind :overarch)

;; Overarch's element kinds, as org.soulspace.overarch.domain.element defines
;; them (v0.43.0): its model-relation-types and view-types.
(def relation-types
  #{:aggregation :artifact-of :association :collaborates-with :composition :constraint-for
    :contained-in :control-for :dataflow :decision-for :dependency :deployed-to :extends
    :generalizes :goal-for :granted-for :has :implementation :implements :include
    :inheritance :input-of :instance-of :is-a :link :non-goal-for :output-of :part-of
    :permission-of :publish :regulation-for :rel :request :required-for :response
    :responsible-for :risk-for :role-in :send :stakeholder-of :step :subscribe :test-for
    :transition :triggers :uses :version-of})

(def view-types
  #{:code-view :component-view :concept-view :container-view :context-view
    :deployment-architecture-view :deployment-structure-view :deployment-view :domain-view
    :dynamic-view :glossary-view :model-view :organization-structure-view :process-view
    :state-machine-view :system-landscape-view :system-structure-view :use-case-view})

(defn- relation? [m] (and (some? (:from m)) (some? (:to m))))
(defn- view? [m] (or (contains? view-types (:el m))
                     (some-> (:el m) name (str/ends-with? "-view"))))

;; Ids Overarch generates when the model leaves them out, as
;; org.soulspace.overarch.domain.element does: a relation's from its ends, a
;; node's from its parent's :id (as written) and its name.
(defn- relation-id [{:keys [el from to]}]
  (keyword (namespace from) (str (name from) "-" (name el) "-" (name to))))

(defn- node-id [m parent-id]
  (when (and parent-id (:name m))
    (keyword (namespace parent-id)
             (str (name parent-id) "-" (str/replace (str/lower-case (:name m)) " " "-") "-" (name (:el m))))))

(defn- elements
  "Every element in `x`, as Overarch's input model reader takes them: each
   {:m :id :parent}, :parent the id the element's parent was written with,
   when that parent is a node (its :ct is containment); a reference in a
   node's :ct is {:ref id :parent}. An element Overarch can't identify is
   {:m :problem}. A view's :ct holds what it shows; elements written in it
   are read, not contained."
  ([x] (elements x nil))
  ([x p]
   (cond
     (map? x)
     (cond
       (:ref x) (when (and p (not (view? p)) (:el p)) [{:ref (:ref x) :parent (:id p)}])
       (not (:el x)) nil
       (and (contains? relation-types (:el x)) (not (relation? x)))
       [{:m x :problem :relation-missing-end}]
       :else
       (let [id (cond (:id x) (:id x)
                      (relation? x) (relation-id x)
                      (view? x) nil
                      :else (node-id x (:id p)))
             container (when (and p (:id p) (not (view? p)) (not (relation? p))) (:id p))]
         (cons (if id {:m x :id id :parent container :generated (not (:id x))} {:m x :problem :missing-id})
               (mapcat #(elements % x) (:ct x)))))
     (coll? x) (mapcat #(elements % p) x))))

(defn- some-keys
  "The element's own facts under :overarch/*, leaving out what isn't given."
  [m ks]
  (into {} (for [k ks :let [v (get m k)] :when (some? v)]
             [(keyword "overarch" (name k)) (if (set? v) (vec (sort-by str v)) v)])))

(defn- results
  "One file's results: an entity per element, relation and view, and for
   each relation a place on the element it starts from; a finding for an
   element Overarch rejects."
  [file x lines]
  (let [taken (atom #{})
        has? (fn [r t] (and (<= 1 r (count lines)) (str/includes? (nth lines (dec r)) t)))
        ;; the first line not yet taken holding `t`, with `near` within 4 lines
        claim (fn [t near]
                (when-let [r (some #(when (and (not (@taken %)) (has? % t)
                                               (or (nil? near) (some (fn [d] (has? (+ % d) near)) (range -4 5))))
                                      %)
                                   (range 1 (inc (count lines))))]
                  (swap! taken conj r) r))]
  ;; the surest lines first: an :id as written, then a relation's ends
  (vec (for [{:keys [m id parent ref problem generated]} (sort-by (fn [{:keys [m id ref problem generated]}]
                                                                     (cond (and id (not generated)) 0 ref 1
                                                                           (and (not problem) (relation? m)) 2
                                                                           (not problem) 3 :else 4))
                                                                   (elements x))
             :let [row (or (when (and id (not generated)) (claim (str ":id " (pr-str id)) nil))
                           (when ref (claim (str ":ref " (pr-str ref)) nil))
                           (when (relation? m) (claim (str ":from " (pr-str (:from m))) (pr-str (:to m))))
                           (when (:name m) (claim (str ":name " (pr-str (:name m))) nil))
                           ;; a relation missing an end: the end it has
                           (some #(when-let [v (get m %)] (claim (str % " " (pr-str v)) nil)) [:from :to])
                           (when id (claim (pr-str id) nil))
                           1)]
             r (cond
                 problem
                 [(cond-> {:file file :row row :kind kind :problem problem :el (:el m)
                          :severity :error :name (:name m)}
                   (= problem :relation-missing-end) (assoc :missing (if (some? (:from m)) :to :from)))]
                 ref
                 [{:file file :row row :kind kind :dev-id parent :refers true
                   :entry {:overarch/contains [ref]}}]
                 (relation? m)
                 [{:file file :row row :kind kind :dev-id id
                   :entry (merge (some-keys m [:el :name :desc :tech :tags])
                                 {:overarch/from (:from m) :overarch/to (:to m)})}
                  {:file file :row row :kind kind :dev-id (:from m) :refers true
                   :entry {:overarch/relates-to #{(:to m)}}}]
                 (view? m)
                 [{:file file :row row :kind kind :dev-id id
                   :entry (cond-> (some-keys m [:el :title :desc])
                            (seq (keep :ref (:ct m))) (assoc :overarch/shows (vec (distinct (keep :ref (:ct m)))))
                            (:spec m) (assoc :overarch/selection (get-in m [:spec :selection] (:spec m))))}]
                 :else
                 (cond-> [{:file file :row row :kind kind :dev-id id
                           :entry (some-keys m [:el :name :desc :tech :tags :external :subtype])}]
                   parent (conj {:file file :row row :kind kind :dev-id parent :refers true
                                 :entry {:overarch/contains [id]}})))]
         r))))

(defn- read-data
  "The EDN files and directories :models names: Overarch model and view
   files alike. A relation or view written the same in two files is one, as
   Overarch merges it."
  [files _opts]
  (let [seen (atom #{})]
    (vec (for [{:keys [file text]} files
               r (let [lines (str/split-lines text)
                       x (try (binding [*read-eval* false *default-data-reader-fn* tagged-literal] (read-string text))
                              (catch Exception ex ex))]
                   (cond
                     (instance? Exception x) [{:file file :row 1 :kind kind :skipped (str "not readable: " (ex-message x))}]
                     (not (coll? x)) [{:file file :row 1 :kind kind :skipped "not a collection of Overarch elements"}]
                     :else (remove (fn [r] (let [k [(:dev-id r) (:entry r)]]
                                             (and (not (:refers r)) (:dev-id r)
                                                  (or (:overarch/from (:entry r)) (:overarch/shows (:entry r)))
                                                  (or (@seen k) (do (swap! seen conj k) false)))))
                                   (results file x lines))))]
           r))))

;; =============================================================================
;; Checks and the hover
;; =============================================================================

(defn- own-defs
  "An entity's definitions this pack gave, not counting the places that
   only refer to it."
  [e]
  (filter #(and (= kind (:kind %)) (not (:refers %))) (:definitions e)))

(defn- described-elsewhere?
  "True when another source than the model says something about the
   entity: a registry file, another pack."
  [e]
  (some #(and (not= kind (:kind %)) (not (:refers %)) (not (:runtime %))) (:definitions e)))

(defn- mentions
  "The keywords anywhere in what other sources than the model say about it."
  [entry]
  (into #{} (filter keyword?)
        (tree-seq coll? seq (vals (into {} (remove #(= "overarch" (namespace (key %)))) entry)))))

(def ^:private concern-relations
  "Overarch's process concepts and the relation that links each to the
   element it concerns (doc/usage.md, Process Model)."
  {:decision :decision-for, :goal :goal-for, :non-goal :non-goal-for,
   :risk :risk-for, :stakeholder :stakeholder-of})

(defn- checks
  "- :relation-to-nothing (error): a relation's :from or :to names no
     element of the model; Overarch can't draw it;
   - :concerns-nothing (info): a :decision, :goal, :non-goal, :risk or
     :stakeholder has no relation of its kind (:decision-for, …) from it:
     the model doesn't say what it is about;
   - :missing-id (error, while reading): a node or view with no :id and
     none Overarch can generate (a node's needs a parent written with an :id
     and a :name); :relation-missing-end (error): a relation kind without
     :from or :to;
   - :parent-override (error): an element contained in two parents, by
     :ct, a :ref in a :ct or a :contained-in relation;
   - :missing-desc (info, when :desc true in the pack's options): a node
     without :desc;
   - :view-of-nothing (error): a view refers to an id the model doesn't
     define;
   - :element-defined-twice (error): two elements of the model share an id;
   - :element-in-no-view (info): no view shows the element, its ancestors or
     its descendants. Not reported when a view selects by criteria, which
     Overarch resolves when it renders;
   - :model-relation-not-declared (warning): the model relates two elements
     that other sources describe too, nothing else describing the first
     names the second, and no data key the first produces is one the second
     consumes (an event a read model reads): the diagram says something the
     system doesn't;
   - :relation-missing-from-model (info): another source describing an
     element names a second element of the model, and the model has no
     relation from the first to the second."
  [{:keys [of-kinds loc entities flow opts]}]
  (let [model (into {} (filter (fn [[_ e]] (seq (own-defs e)))) (of-kinds #{kind}))
        el-of #(get-in model [% :entry :overarch/el])
        rel? (fn [id] (some? (get-in model [id :entry :overarch/from])))
        view-of? (fn [id] (contains? (get-in model [id :entry]) :overarch/shows))
        sel-view? (some #(contains? (:entry %) :overarch/selection) (vals model))
        elements (into {} (remove (fn [[id e]] (or (rel? id) (view-of? id) (contains? (:entry e) :overarch/selection)
                                                   (#{:theme :view-spec} (el-of id)))))
                       model)
        shown (set (mapcat #(get-in % [:entry :overarch/shows]) (vals model)))
        ;; containment: by :ct and a :ref in a :ct, and :contained-in relations
        parents (reduce (fn [m [c p]] (update m c (fnil conj (sorted-set-by #(compare (str %1) (str %2)))) p)) {}
                        (concat (for [[id e] elements c (get-in e [:entry :overarch/contains])] [c id])
                                (for [[id e] model :when (and (rel? id) (= :contained-in (el-of id)))
                                      :let [{:overarch/keys [from to]} (:entry e)]]
                                  [from to])))
        parent (into {} (for [[c ps] parents] [c (first ps)]))
        ancestors (fn [id] (take-while some? (rest (iterate parent id))))
        descendants (fn descendants [id] (mapcat #(cons % (descendants %)) (get-in elements [id :entry :overarch/contains])))
        at (fn [e] (loc (first (own-defs e))))
        ;; where the model draws from → to: the first relation that does
        drawn (reduce (fn [m [id e]] (let [{:overarch/keys [from to]} (:entry e)]
                                       (cond-> m (and (rel? id) (not (m [from to]))) (assoc [from to] e))))
                      {} (sort-by key model))]
    (concat
     (for [[id e] (sort-by key model)
           :let [{:overarch/keys [from to]} (:entry e)]
           :when (rel? id)
           [end x] [[:from from] [:to to]]
           :when (not (contains? elements x))]
       {:severity :error :problem :relation-to-nothing :dev-id id :end end :to x :definition (at e)})
     (let [linked (set (for [[id e] model
                             :when (rel? id)
                             :let [{:overarch/keys [el from]} (:entry e)]
                             :when (= el (concern-relations (el-of from)))]
                         from))]
       (for [[id e] (sort-by key elements)
             :let [rel (concern-relations (el-of id))]
             :when (and rel (not (linked id)))]
         {:severity :info :problem :concerns-nothing :dev-id id :rel rel :definition (at e)}))
     (for [[id ps] (sort-by (comp str key) parents)
           :when (and (> (count ps) 1) (contains? elements id))]
       {:severity :error :problem :parent-override :dev-id id :parents (vec ps) :definition (at (elements id))})
     (when (get-in opts [:overarch :desc])
       (for [[id e] (sort-by key elements)
             :when (not (get-in e [:entry :overarch/desc]))]
         {:severity :info :problem :missing-desc :dev-id id :definition (at e)}))
     (for [[id e] (sort-by key model)
           x (get-in e [:entry :overarch/shows])
           :when (not (contains? model x))]
       {:severity :error :problem :view-of-nothing :dev-id id :to x :definition (at e)})
     (for [[id e] (sort-by key model)
           :let [ds (own-defs e)]
           :when (> (count ds) 1)]
       {:severity :error :problem :element-defined-twice :dev-id id :definition (loc (last ds))})
     (when-not sel-view?
       (for [[id e] (sort-by key elements)
             :when (not (some shown (concat [id] (ancestors id) (descendants id))))]
         {:severity :info :problem :element-in-no-view :dev-id id :definition (at e)}))
     ;; the model against what the code and the registries say
     (for [[id e] (sort-by key elements)
           :let [whole (get entities id)]
           :when (and whole (described-elsewhere? whole))
           to (sort-by str (get-in whole [:entry :overarch/relates-to]))
           :let [other (get entities to)]
           :when (and other (described-elsewhere? other)
                      (not (contains? (mentions (:entry whole)) to))
                      ;; or through data: what the first produces, the second consumes
                      (empty? (set/intersection (:produces (flow (:entry whole)))
                                                (:consumes (flow (:entry other))))))]
       {:severity :warning :problem :model-relation-not-declared :dev-id id :to to
        :definition (at (or (drawn [id to]) e))})
     (for [[id e] (sort-by key elements)
           :let [whole (get entities id)]
           :when (and whole (described-elsewhere? whole))
           to (sort-by str (mentions (:entry whole)))
           :when (and (not= to id) (contains? elements to)
                      (not (contains? (set (get-in whole [:entry :overarch/relates-to])) to)))]
       {:severity :info :problem :relation-missing-from-model :dev-id id :to to :definition (at e)}))))

(defn- describe [entry]
  (when-let [el (:overarch/el entry)]
    (let [{:overarch/keys [name tech from to title shows]} entry]
      (str "overarch " (clojure.core/name el)
           (cond
             from (str "  " (pr-str from) " → " (pr-str to))
             title (str "  " title (when (seq shows) (str "  ·  " (count shows) " elements")))
             :else (str (when name (str "  " name)) (when tech (str "  ·  " tech))))))))

(def pack
  {:pack/id :overarch
   :pack/config-keys #{:overarch}
   :pack/kinds #{kind}
   :pack/vocabulary #{"overarch"}
   :pack/data-keys? false
   ;; an element is often another registry's id: an atlas entity, a component
   :pack/describes? true
   ;; a picture of the system: what the system says of an id comes first
   :pack/depicts? true
   :pack/merge {:overarch/relates-to :union :overarch/contains :union}
   :pack/data-files (fn [opts] (get-in opts [:overarch :models]))
   :pack/read-data read-data
   :pack/checks checks
   :pack/describe describe})
