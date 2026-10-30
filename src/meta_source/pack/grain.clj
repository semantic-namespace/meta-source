(ns meta-source.pack.grain
  "EXPERIMENTAL (see README.md) — the grain pack: a grain app's event model,
   read as data from the macros grain declares it with
   (https://github.com/ObneyAI/grain). Nothing is loaded or evaluated.

   Enabled by :grain under meta-source.edn's :packs:

     :grain {}

   Grain keeps a registry per kind, so one name may be a query and a read
   model (:example/counters). Each block is an entity under its name with
   the kind before the namespace, as atlas's grain ontology names them:
   :command.example/create-counter, :query.example/counters,
   :read-model.example/counters, :processor..., :periodic..., :event...,
   :screen..., :flow.... A schema defschemas registers keeps its own name:
   grain's malli registry is one. References (:grain/reads, :produces,
   :consumes, a screen's queries and commands) are those ids.

     (defcommand :example create-counter {opts}? \"doc\"? [ctx] body)
     defquery, defreadmodel, defprocessor, defperiodic: the same shape
     (defevent :example/counter-created \"doc\" {opts})
     (defschemas commands {:example/create-counter [:map ...] ...})
     (defeventmodel :example {:commands {id {...}} :events {...} ...})

   A block's def-site options and its area's event model say the same
   things, and grain's boot guard refuses to start when they disagree. Both
   are read into the same keys, so where they differ meta-source reports it
   (:conflicting-values), before anything runs:

     :grain/type         :command :query :read-model :todo-processor
                         :periodic-task :event :screen :flow
     :grain/area         the block's area (:example)
     :grain/reads        read models it reads    (:grain.event-model/reads, :reads)
     :grain/produces     what it produces        (:grain.event-model/produces, :produces)
     :grain/consumes     events it consumes      (a read model's :events, a
                                                  processor's :topics; :consumes,
                                                  :subscribes in the model)
     :grain/schema       its malli schema        (:schema, defschemas)
     :grain/version, :grain/schedule
     :grain/queries, :grain/commands, :grain/steps   a screen's, a flow's
     :grain/doc          the def-site docstring or defevent's description
     :grain.model/description   the model's description (worded on its own:
                                not compared with :grain/doc)
     :grain/name         the block's grain name (:example/create-counter)
     :grain/defined      true from the def site; :grain/in-model true from the model

   The same keys as atlas's grain ontology (:grain/produces, :grain/consumes,
   :grain/reads). An option written as code (:authorized? (constantly true))
   is dropped, and listed so (:dropped); a form whose options or model aren't
   a literal map is not read, and said so.

   Checks, each while every grain form of the kinds it needs was read
   (otherwise :checks-not-run says which didn't run, and why):
   - :grain-not-in-model (warning): a block defined in an area that has an
     event model, which the model doesn't list. Grain's boot guard requires
     full coverage;
   - :grain-model-only (warning): a block the model lists that no def site
     defines;
   - :grain-reads-undefined (warning): a block reading a read model nothing
     defines;
   - :grain-event-not-produced (info): an event a read model or processor
     consumes that no block declares producing: undeclared, or appended by
     code outside a block (grain's control plane does);
   - :grain-event-not-consumed (info): an event a block declares producing
     that no read model or processor consumes."
  (:require [clojure.set :as set]))

(def kind :grain)

(def ^:private macros
  "{macro-var type}: grain's declaring macros, v1 and v2."
  {'ai.obney.grain.command-processor-v2.interface/defcommand :command
   'ai.obney.grain.command-processor.interface/defcommand :command
   'ai.obney.grain.query-processor.interface/defquery :query
   'ai.obney.grain.read-model-processor-v2.interface/defreadmodel :read-model
   'ai.obney.grain.read-model-processor.interface/defreadmodel :read-model
   'ai.obney.grain.todo-processor-v2.interface/defprocessor :todo-processor
   'ai.obney.grain.periodic-task.interface/defperiodic :periodic-task
   'ai.obney.grain.event-store-v3.interface/defevent :event
   'ai.obney.grain.event-store-v3.interface.event-definition/defevent :event
   'ai.obney.grain.schema-util.interface/defschemas :schemas
   'ai.obney.grain.event-model.interface/defeventmodel :model})

(def ^:private covers (set (map (comp symbol namespace) (keys macros))))

(defn- find-forms [analysis _opts]
  (let [;; (comment ...) blocks are REPL scratch, not declarations
        comments (group-by :filename (for [u (:var-usages analysis)
                                           :when (and (= 'clojure.core (:to u)) (= 'comment (:name u)) (:end-row u))]
                                       u))
        in-comment? (fn [u] (some #(and (<= (:row %) (:row u) (:end-row %))
                                        (not (and (= (:row %) (:row u)) (= (:col %) (:col u)))))
                                  (get comments (:filename u))))]
    (for [u (:var-usages analysis)
          :let [role (macros (symbol (str (:to u)) (str (:name u))))]
          ;; grain's own macros calling one another aren't declarations
          :when (and role (not (covers (:from u))) (not (in-comment? u)))]
      (assoc (select-keys u [:filename :row :name-row :name-col]) :role role))))

(defn- code? [v] (or (seq? v) (fn? v)))

(def ^:private prefix
  {:command "command" :query "query" :read-model "read-model" :todo-processor "processor"
   :periodic-task "periodic" :event "event" :screen "screen" :flow "flow"})

(defn- id-of
  "The entity id of grain name `n` (:example/counters) of `type`."
  [type n]
  (when (qualified-keyword? n) (keyword (str (prefix type) "." (namespace n)) (name n))))

(defn- refs
  "The entry's references as entity ids: what each points at depends on the
   key and, for :grain/produces, on who produces (a processor dispatches
   commands; the others emit events)."
  [type entry]
  (let [as (fn [t xs] (when (coll? xs) (set (keep #(id-of t %) xs))))]
    (cond-> entry
      (contains? entry :grain/reads) (update :grain/reads #(as :read-model %))
      (contains? entry :grain/consumes) (update :grain/consumes #(as :event %))
      (contains? entry :grain/produces) (update :grain/produces #(as (if (= :todo-processor type) :command :event) %))
      (contains? entry :grain/queries) (update :grain/queries #(as :query %))
      (contains? entry :grain/commands) (update :grain/commands #(as :command %)))))

(defn- data-of
  "A map's entries that are data, and the keys written as code."
  [m]
  {:data (into {} (remove (comp code? val)) m)
   :dropped (vec (sort (for [[k v] m :when (code? v)] k)))})

(def ^:private option-keys
  "A def site's option keys, as the pack's."
  {:grain.event-model/reads :grain/reads
   :grain.event-model/produces :grain/produces
   :grain.event-model/consumes :grain/consumes
   :events :grain/consumes
   :topics :grain/consumes
   :schema :grain/schema
   :version :grain/version
   :schedule :grain/schedule})

(def ^:private model-keys
  "An event model entry's keys, as the pack's."
  {:description :grain.model/description
   :reads :grain/reads
   :produces :grain/produces
   :consumes :grain/consumes
   :subscribes :grain/consumes
   :schema :grain/schema
   :version :grain/version
   :schedule :grain/schedule
   :queries :grain/queries
   :commands :grain/commands
   :steps :grain/steps})

(def ^:private model-sections
  {:commands :command :events :event :read-models :read-model :queries :query
   :todo-processors :todo-processor :periodic-tasks :periodic-task :screens :screen :flows :flow})

(defn- rename [m ks] (into {} (for [[k v] m] [(get ks k k) v])))

(defn- block-result
  "(defcommand :area name opts? doc? [args] body), and the others like it."
  [row type form]
  (let [[_ area nm & more] form
        [opts more] (if (map? (first more)) [(first more) (rest more)] [nil more])
        doc (when (string? (first more)) (first more))]
    (cond
      (not (and (keyword? area) (or (symbol? nm) (keyword? nm))))
      {:row row :kind kind :skipped "the block's area and name aren't literal"}

      (and (nil? opts) (not (or (string? (first more)) (vector? (first more)))))
      {:row row :kind kind :skipped "the block's options aren't a literal map"}

      :else
      (let [{:keys [data dropped]} (data-of opts)]
        (cond-> {:row row :kind kind :dev-id (id-of type (keyword (name area) (name nm)))
                 :entry (cond-> (refs type (assoc (rename data option-keys)
                                                  :grain/type type :grain/area (keyword (name area)) :grain/defined true
                                                  :grain/name (keyword (name area) (name nm))))
                          doc (assoc :grain/doc doc))}
          (seq dropped) (assoc :dropped dropped))))))

(defn- event-result
  "(defevent :area/name \"doc\" {opts})"
  [row form]
  (let [[_ id doc opts] form]
    (if (and (qualified-keyword? id) (or (nil? opts) (map? opts)))
      (let [{:keys [data dropped]} (data-of opts)]
        (cond-> {:row row :kind kind :dev-id (id-of :event id)
                 :entry (cond-> (refs :event (assoc (rename data option-keys)
                                                    :grain/type :event :grain/area (keyword (namespace id)) :grain/defined true
                                                    :grain/name id))
                          (string? doc) (assoc :grain/doc doc))}
          (seq dropped) (assoc :dropped dropped)))
      {:row row :kind kind :skipped "the event's id or options aren't literal"})))

(defn- schemas-results
  "(defschemas name {id schema ...}): each id's schema."
  [row form find-row]
  (let [[_ _ m] form]
    (if (map? m)
      (for [[id schema] m :when (qualified-keyword? id)]
        {:row (or (find-row (str id) row) row) :kind kind :dev-id id :refers true
         :entry {:grain/schema schema}})
      [{:row row :kind kind :skipped "the schemas aren't a literal map"}])))

(defn- model-results
  "(defeventmodel :area {:commands {id {...}} ...}): each entry it lists."
  [row form find-row]
  (let [[_ area m] form]
    (if (and (keyword? area) (map? m))
      (for [[section type] model-sections
            :let [entries (get m section)]
            :when (map? entries)
            [id e] entries
            :when (and (qualified-keyword? id) (map? e))
            :let [{:keys [data dropped]} (data-of e)]]
        (cond-> {:row (or (find-row (str id) row) row) :kind kind :dev-id (id-of type id) :refers true
                 :entry (refs type (assoc (rename data model-keys) :grain/type type :grain/area area :grain/in-model true
                                          :grain/name id))}
          (seq dropped) (assoc :dropped dropped)))
      [{:row row :kind kind :skipped "the event model isn't a literal map"}])))

(defn- read-forms [sites {:keys [form-at find-row]} _opts]
  (let [find (fn [s from] (when find-row (find-row s from)))]
    (vec (mapcat (fn [{:keys [row name-row name-col role]}]
                   (let [form (form-at name-row name-col)]
                     ;; what wasn't read says what it would have declared
                     (for [r (if-not (seq? form)
                               [{:row row :kind kind :skipped "the form isn't data"}]
                               (case role
                                 :schemas (schemas-results row form find)
                                 :model (model-results row form find)
                                 :event [(event-result row form)]
                                 [(block-result row role form)]))]
                       (cond-> r (:skipped r) (assoc :grain/type role)))))
                 sites))))

(def ^:private needs
  "Each check, and the block types it needs all of: one of them not read,
   and the check doesn't run."
  {:grain-not-in-model #{:model}
   :grain-model-only #{:command :query :read-model :todo-processor :periodic-task :event}
   :grain-reads-undefined #{:read-model}
   :grain-event-not-produced #{:command :todo-processor :periodic-task}
   :grain-event-not-consumed #{:read-model :todo-processor}})

(defn- checks
  [{:keys [of-kinds loc skipped]}]
  (let [unread (skipped kind)
        unread-types (set (map :grain/type unread))
        runs? (fn [check] (empty? (set/intersection (needs check) unread-types)))
        not-run (vec (sort (remove runs? (keys needs))))
        es (into (sorted-map) (of-kinds #{kind}))
        entry #(get-in es [% :entry])
        typed (fn [t] (set (for [[id e] es :when (= t (get-in e [:entry :grain/type]))] id)))
        read-models (typed :read-model)
        events (typed :event)
        modelled-areas (set (for [[_ e] es :when (get-in e [:entry :grain/in-model])] (get-in e [:entry :grain/area])))
        produced (set (mapcat #(get-in (val %) [:entry :grain/produces]) es))
        consumed (set (mapcat #(get-in (val %) [:entry :grain/consumes]) es))
        when-runs (fn [check xs] (when (runs? check) xs))]
    (concat
     (when (seq not-run)
       [{:severity :info :problem :checks-not-run :pack kind :checks not-run
         :because (vec (for [r unread] (cond-> {:reason (:skipped r) :type (:grain/type r)}
                                         (:file r) (assoc :file (:file r))
                                         (:row r) (assoc :line (:row r)))))}])
     (when-runs :grain-not-in-model
       (for [[id e] es
             :let [{:grain/keys [defined in-model area type]} (:entry e)]
             :when (and defined (not in-model) (modelled-areas area) (some? type))]
         {:severity :warning :problem :grain-not-in-model :dev-id id :definition (loc e)
          :message (str id ": defined, and " area "'s event model doesn't list it")}))
     (when-runs :grain-model-only
       (for [[id e] es
             :let [{:grain/keys [defined in-model type]} (:entry e)]
             :when (and in-model (not defined) (not (#{:screen :flow} type)))]
         {:severity :warning :problem :grain-model-only :dev-id id :definition (loc e)
          :message (str id ": the event model lists it, and no def site defines it")}))
     (when-runs :grain-reads-undefined
       (for [[id e] es
             :let [missing (set/difference (set (get-in e [:entry :grain/reads])) read-models)]
             :when (seq missing)]
         {:severity :warning :problem :grain-reads-undefined :dev-id id :keys (vec (sort missing)) :definition (loc e)}))
     (when-runs :grain-event-not-produced
       (for [ev (sort (set/difference consumed produced))
             :when (or (events ev) (not (entry ev)))]
         {:severity :info :problem :grain-event-not-produced :dev-id ev
          :message (str ev ": consumed, and no block declares producing it (code outside a block may append it)")
          :consumers (vec (sort (for [[id e] es :when (some #{ev} (get-in e [:entry :grain/consumes]))] id)))
          :definition (some-> (get es ev) loc)}))
     (when-runs :grain-event-not-consumed
       (for [ev (sort (set/difference (set/intersection produced events) consumed))]
         {:severity :info :problem :grain-event-not-consumed :dev-id ev :definition (loc (get es ev))})))))

(defn- describe [entry]
  (when-let [t (:grain/type entry)]
    (str "grain " (name t)
         (when-let [r (seq (:grain/reads entry))] (str "  · reads " (pr-str (vec (sort r)))))
         (when-let [p (seq (:grain/produces entry))] (str "  · produces " (pr-str (vec (sort p)))))
         (when-let [c (seq (:grain/consumes entry))] (str "  · consumes " (pr-str (vec (sort c))))))))

(def pack
  {:pack/id :grain
   :pack/config-keys #{:grain}
   :pack/kinds #{kind}
   :pack/vocabulary #{"grain" "grain.model"}
   :pack/data-keys? false
   :pack/covers covers
   :pack/find find-forms
   :pack/read read-forms
   :pack/checks checks
   :pack/describe describe})
