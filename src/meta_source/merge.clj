(ns meta-source.merge
  "Merging what several sources say about the same entities.

   A source gives a map of id to entry, and an entity may be described by
   several of them, each giving the keys it owns. Keys are qualified, so
   they say who owns them, and the merge does not depend on the order the
   sources are read in:

   - different keys from different sources are combined;
   - a key given twice with the same value is fine;
   - any other key given with different values is a conflict, reported with
     every source that gave it: never a silent last-write-wins;
   - unless the key's merge policy says otherwise. :union takes the union of
     every source's set (a set of tags, say). Sets are not unioned by
     default: a union would hide a deletion, a dep removed in one file and
     still listed in another.")

(defn- labels
  "A set of source labels, sorted by how they print: a label may be a file
   path (string) or a symbol, which don't compare."
  [xs]
  (into (sorted-set-by #(compare (pr-str %1) (pr-str %2))) xs))

(defn merge-sources
  "Merges `sources`, each {:source label :entities {id entry}}, into
   {:entities   {id entry}
    :provenance {id {key #{label ...}}}
    :value-provenance {id {key {value #{label ...}}}}, for a unioned key: who
                 gave each of its values
    :conflicts  [{:problem :conflicting-values :dev-id :key :values [{:value :sources}]}]}.
   `policy` is {key :union}, the keys whose values are unioned. A
   conflicting key is left out of its entity; the rest of the entity is kept."
  ([sources] (merge-sources sources {}))
  ([sources policy]
   (let [given (for [{:keys [source entities]} sources
                     [id entry] entities
                     [k v] entry]
                 {:id id :k k :v v :source source})
         by-key (group-by (juxt :id :k) given)
         merged-value (fn [[_ k] gs]
                        (if (= :union (get policy k))
                          {:value (into #{} (mapcat :v) gs)}
                          (let [by-value (group-by :v gs)]
                            (if (= 1 (count by-value))
                              {:value (:v (first gs))}
                              {:conflict (vec (sort-by (comp pr-str :sources)
                                                       (for [[v vgs] by-value]
                                                         {:value v :sources (labels (map :source vgs))})))}))))
         results (into {} (for [[idk gs] by-key] [idk (merged-value idk gs)]))
         conflicts (vec (for [[[id k] {:keys [conflict]}] (sort-by (comp pr-str key) results) :when conflict]
                          {:problem :conflicting-values :dev-id id :key k :values conflict}))]
     {:entities (reduce (fn [acc [[id k] {:keys [value conflict]}]]
                          (cond-> (update acc id #(or % {}))
                            (not conflict) (assoc-in [id k] value)))
                        {}
                        results)
      :provenance (reduce (fn [acc [[id k] gs]] (assoc-in acc [id k] (labels (map :source gs))))
                          {}
                          by-key)
      :value-provenance (reduce (fn [acc {:keys [id k v source]}]
                                  (if (and (= :union (get policy k)) (coll? v))
                                    (reduce #(update-in %1 [id k %2] (fnil conj (labels [])) source) acc v)
                                    acc))
                                {}
                                given)
      :conflicts conflicts})))

(defn sources-of
  "The labels of every source that gave `id` something, sorted."
  [provenance id]
  (vec (labels (apply concat (vals (get provenance id))))))
