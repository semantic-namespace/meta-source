(ns meta-source.pack.tilakone
  "EXPERIMENTAL (see README.md) — the tilakone pack: state machines, read
   as data from the defs that write them. Nothing is loaded or evaluated.

   `pack` is what meta-source.index runs (see the Packs section of
   meta-source.index), enabled by :tilakone under meta-source.edn's :packs.

   A machine is a map with :tilakone.core/states, in a def or defn (the
   value, or anywhere in it). It is an entity keyed by its var
   (:app.order/fsm), several in one def by their place (:app.order/fsm.2):

     :tilakone/states       its states' names, in order
     :tilakone/initial      its :tilakone.core/state, when written
     :tilakone/transitions  [from on to] for each transition with a :to;
                            :on is :tilakone/any for tilakone's _

   Each state is an entity too, under its name, in every machine that has
   it (names are often shared: an error state, an exit, a workflow step
   another registry declares). The pack describes it, as atlas describes an
   integrant key: another pack's declaration stays primary, and sharing the
   id isn't reported.

     :tilakone/machines     the machines it is a state of
     :tilakone/on           the signals it has transitions on
     :tilakone/to           the states it goes to

   So `uses` on a signal lists the states that transition on it, and on a
   state the states that go to it."
  (:require [clojure.string :as str]))

(def kind :tilakone)

(defn- tk [k] (keyword "tilakone.core" (name k)))

(defn- find-forms
  "Each def or defn with a :tilakone.core/states keyword in it, with the
   rows of the :name and :to keywords there, in order: they place the
   states and transitions read from the form."
  [analysis _opts]
  (let [kws (group-by :filename (:keywords analysis))
        tk-rows (fn [file row end-row k]
                  (vec (sort (keep #(when (and (:row %) (<= row (:row %) end-row)
                                               (= 'tilakone.core (:ns %)) (= k (:name %)))
                                      (:row %))
                                   (get kws file)))))]
    (for [d (:var-definitions analysis)
          :when (and ('#{clojure.core/def clojure.core/defn} (:defined-by d)) (:end-row d))
          :let [rows #(tk-rows (:filename d) (:row d) (:end-row d) %)]
          :when (seq (rows "states"))]
      {:filename (:filename d) :row (:row d) :name-row (:row d) :name-col (inc (:col d))
       :var (symbol (str (:ns d)) (str (:name d)))
       :name-rows (rows "name") :to-rows (rows "to")})))

(defn- machines-in [form]
  (filter #(and (map? %) (contains? % (tk :states))) (tree-seq coll? seq form)))

(defn- signal [on] (if (keyword? on) on :tilakone/any))

(defn- machine-results
  "The machine's result and one per state, `n` its place in the def (1-based)."
  [m n {:keys [var row]} name-rows to-rows]
  (let [id (keyword (namespace var) (str (name var) (when (> n 1) (str "." n))))
        states (vec (get m (tk :states)))
        names (mapv #(get % (tk :name)) states)
        transitions (vec (for [s states t (get s (tk :transitions)) :when (contains? t (tk :to))]
                           [(get s (tk :name)) (signal (get t (tk :on))) (get t (tk :to))]))
        initial (get m (tk :state))]
    (if-not (every? keyword? names)
      [{:row row :kind kind :skipped (str var ": a state named by code, not read")}]
      (cons {:row row :kind kind :dev-id id
             :entry (cond-> {:tilakone/states names
                             :tilakone/transitions transitions
                             ;; where each state and each transition is written
                             :tilakone/state-rows (vec (take (count names) (concat name-rows (repeat row))))
                             :tilakone/transition-rows (vec (take (count transitions) (concat to-rows (repeat row))))}
                      (some? initial) (assoc :tilakone/initial initial))}
            (for [[s r] (map vector states (concat name-rows (repeat row)))
                  :let [ts (get s (tk :transitions))]]
              {:row r :kind kind :dev-id (get s (tk :name))
               :entry (cond-> {:tilakone/machines [id]}
                        (seq ts) (assoc :tilakone/on (vec (distinct (map (comp signal #(get % (tk :on))) ts))))
                        (some #(keyword? (get % (tk :to))) ts)
                        (assoc :tilakone/to (vec (distinct (filter keyword? (map #(get % (tk :to)) ts))))))})))))

(defn- read-forms [sites {:keys [form-at]} _opts]
  (vec (for [{:keys [row name-row name-col name-rows to-rows] :as site} sites
             :let [form (form-at name-row name-col)]
             r (cond
                 (= form :unreadable) [{:row row :kind kind :skipped "not readable as data"}]
                 (not (seq? form)) nil
                 :else
                 ;; several machines in one def take the rows in turn
                 (loop [[m & more] (machines-in (drop 2 form)) n 1 nr name-rows tr to-rows acc []]
                   (if-not m
                     acc
                     (let [k (count (get m (tk :states)))
                           t (count (for [s (get m (tk :states)) x (get s (tk :transitions)) :when (contains? x (tk :to))] x))]
                       (recur more (inc n) (drop k nr) (drop t tr)
                              (into acc (machine-results m n site nr tr)))))))]
         r)))

;; =============================================================================
;; Checks and the hover
;; =============================================================================

(defn- reachable [initial transitions]
  (let [next-of (reduce (fn [g [from _ to]] (update g from (fnil conj #{}) to)) {} transitions)]
    (loop [seen #{initial} todo [initial]]
      (if-let [x (first todo)]
        (let [new (remove seen (next-of x))]
          (recur (into seen new) (into (vec (rest todo)) new)))
        seen))))

(defn- checks
  "- :transition-to-no-state (error): a transition's :to names no state of
     its machine; tilakone throws when that signal is applied;
   - :initial-state-not-a-state (error): the machine's :state is none of
     its states;
   - :state-defined-twice (error): two states of one machine share a name;
     tilakone takes the first;
   - :state-unreachable (info): no transition from the initial state leads
     to it. Code that sets the state directly may still enter it."
  [{:keys [of-kinds loc]}]
  (let [machines (for [[id e] (sort-by key (of-kinds #{kind}))
                       :when (get-in e [:entry :tilakone/states])]
                   [id e])
        at (fn [e line] (assoc (loc e) :line line))]
    (apply concat
           (for [[id e] machines
                 :let [{:tilakone/keys [states transitions initial state-rows transition-rows]} (:entry e)
                       names (set states)]]
             (concat
              (for [[[from on to] r] (map vector transitions transition-rows)
                    :when (not (names to))]
                {:severity :error :problem :transition-to-no-state :dev-id id :state from :on on :to to :definition (at e r)})
              (when (and initial (not (names initial)))
                [{:severity :error :problem :initial-state-not-a-state :dev-id id :to initial :definition (loc e)}])
              (for [[s c] (frequencies states) :when (> c 1)
                    :let [r (nth state-rows (last (keep-indexed #(when (= s %2) %1) states)))]]
                {:severity :error :problem :state-defined-twice :dev-id id :state s :definition (at e r)})
              (when (and initial (names initial))
                (let [seen (reachable initial transitions)]
                  (for [[s r] (map vector states state-rows) :when (not (seen s))]
                    {:severity :info :problem :state-unreachable :dev-id id :state s :from initial :definition (at e r)}))))))))

(defn- describe [entry]
  (cond
    (:tilakone/states entry)
    (str "state machine  ·  " (count (:tilakone/states entry)) " states"
         (when-let [i (:tilakone/initial entry)] (str ", from " i)))
    (:tilakone/machines entry)
    (str/join "  ·  " (remove nil? [(str "state of " (str/join " " (:tilakone/machines entry)))
                                     (when-let [to (seq (:tilakone/to entry))] (str "to " (str/join " " to)))]))))

(def pack
  {:pack/id :tilakone
   :pack/config-keys #{:tilakone}
   :pack/kinds #{kind}
   :pack/vocabulary #{"tilakone"}
   :pack/data-keys? false
   ;; a state is often another registry's id: a workflow step, a spec
   :pack/describes? true
   :pack/merge {:tilakone/machines :union :tilakone/on :union :tilakone/to :union}
   :pack/find find-forms
   :pack/read read-forms
   :pack/checks checks
   :pack/describe describe})
