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
  (:require [clojure.string :as str]))

(def kind :overarch)

(defn- relation? [m] (and (contains? m :from) (contains? m :to)))
(defn- view? [m] (some-> (:el m) name (str/ends-with? "-view")))

(defn- elements
  "Every element map in `x` with an :id. A view's :ct holds refs, not
   elements."
  [x]
  (cond
    (and (map? x) (:id x)) (cons x (when-not (view? x) (mapcat elements (:ct x))))
    (map? x) nil
    (coll? x) (mapcat elements x)))

(defn- some-keys
  "The element's own facts under :overarch/*, leaving out what isn't given."
  [m ks]
  (into {} (for [k ks :let [v (get m k)] :when (some? v)]
             [(keyword "overarch" (name k)) (if (set? v) (vec (sort-by str v)) v)])))

(defn- results
  "One file's results: an entity per element, relation and view, and for
   each relation a place on the element it starts from."
  [file text find-row]
  (let [x (try (binding [*read-eval* false *default-data-reader-fn* tagged-literal] (read-string text))
               (catch Exception ex ex))]
    (cond
      (instance? Exception x) [{:file file :row 1 :kind kind :skipped (str "not readable: " (ex-message x))}]
      (not (coll? x)) [{:file file :row 1 :kind kind :skipped "not a collection of Overarch elements"}]
      :else
      (vec (for [m (elements x)
                 :let [id (:id m)
                       row (or (find-row (str ":id " (pr-str id)) 1) (find-row (pr-str id) 1) 1)]
                 r (cond
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
                     [{:file file :row row :kind kind :dev-id id
                       :entry (cond-> (some-keys m [:el :name :desc :tech :tags :external :subtype])
                                (seq (keep :id (:ct m))) (assoc :overarch/contains (vec (keep :id (:ct m)))))}])]
             r)))))

(defn- read-data
  "The EDN files and directories :models names: Overarch model and view
   files alike."
  [files _opts]
  (vec (for [{:keys [file text]} files
             r (let [lines (str/split-lines text)
                     find-row (fn [t from] (some #(when (str/includes? (nth lines (dec %)) t) %)
                                                 (range from (inc (count lines)))))]
                 (results file text find-row))]
         r)))

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

(defn- checks
  "- :relation-to-nothing (error): a relation's :from or :to names no
     element of the model; Overarch can't draw it;
   - :view-of-nothing (error): a view refers to an id the model doesn't
     define;
   - :element-defined-twice (error): two elements of the model share an id;
   - :element-in-no-view (info): no view shows the element, its ancestors or
     its descendants. Not reported when a view selects by criteria, which
     Overarch resolves when it renders;
   - :model-relation-not-declared (warning): the model relates two elements
     that other sources describe too, and nothing else describing the first
     names the second: the diagram says something the system doesn't;
   - :relation-missing-from-model (info): another source describing an
     element names a second element of the model, and the model has no
     relation from the first to the second."
  [{:keys [of-kinds loc entities]}]
  (let [model (into {} (filter (fn [[_ e]] (seq (own-defs e)))) (of-kinds #{kind}))
        el-of #(get-in model [% :entry :overarch/el])
        rel? (fn [id] (some? (get-in model [id :entry :overarch/from])))
        view-of? (fn [id] (contains? (get-in model [id :entry]) :overarch/shows))
        sel-view? (some #(contains? (:entry %) :overarch/selection) (vals model))
        elements (into {} (remove (fn [[id e]] (or (rel? id) (view-of? id) (contains? (:entry e) :overarch/selection)))) model)
        shown (set (mapcat #(get-in % [:entry :overarch/shows]) (vals model)))
        parent (into {} (for [[id e] elements c (get-in e [:entry :overarch/contains])] [c id]))
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
           :when (and other (described-elsewhere? other) (not (contains? (mentions (:entry whole)) to)))]
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
   :pack/merge {:overarch/relates-to :union}
   :pack/data-files (fn [opts] (get-in opts [:overarch :models]))
   :pack/read-data read-data
   :pack/checks checks
   :pack/describe describe})
