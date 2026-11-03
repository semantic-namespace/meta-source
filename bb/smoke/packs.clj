(ns smoke.packs
  "Packs written for the checks: a project's own, named by their symbol
   under :packs (smoke.packs/job-pack), each exercising one part of the
   pack contract."
  (:require [meta-source.read]))

;; a library registering entities with their implementation inline:
;; (register! :fn/x {...} (fn [...] ...)), whose rows the pack reports
(def inline-pack
  {:pack/id :inline-reg
   :pack/kinds #{:inline-reg}
   :pack/vocabulary #{"fn"}
   :pack/find (fn [analysis _]
                (for [u (:var-usages analysis)
                      :when (and (= 'app.reg (:to u)) (= 'register! (:name u)))]
                  (select-keys u [:filename :row :end-row :name-row :name-col])))
   :pack/read (fn [sites {:keys [form-at]} _]
                (vec (for [{:keys [row end-row name-row name-col]} sites
                           :let [[_ id entry] (form-at name-row name-col)
                                 ;; a prop written as code, (into [] ..), is not read
                                 code (vec (sort (keep (fn [[k v]] (when (seq? v) k)) entry)))]]
                       (cond-> {:row row :kind :inline-reg :dev-id id
                                :entry (apply dissoc entry code)
                                :impl-range {:row row :end-row end-row}}
                         (seq code) (assoc :dropped code)))))})


(def job-pack
  "A project's own pack: the shop's defjob registry. Enabled by its
   qualified symbol under :packs; a job reads the data keys :job/reads lists."
  {:pack/id :jobs
   :pack/kinds #{:job}
   :pack/vocabulary #{"job"}
   :pack/dataflow {:consumes [:job/reads]}
   :pack/find (fn [analysis _opts]
                (for [u (:var-usages analysis)
                      :when (and (= 'shop.scheduler (:to u)) (= 'defjob (:name u)))]
                  (select-keys u [:filename :row :name-row :name-col])))
   :pack/read (fn [sites {:keys [form-at own-ns]} _opts]
                (vec (for [{:keys [row name-row name-col]} sites
                           :let [[_ nm opts] (form-at name-row name-col)]]
                       {:row row :kind :job :dev-id (keyword own-ns (name nm)) :entry opts})))
   :pack/describe (fn [entry] (when-let [s (:job/schedule entry)] (str "job  " s)))
   :pack/messages {:job-unscheduled (fn [_ id _] (str id " has no schedule"))}})

(def conforms-pack
  "A project's own pack that only says which calls name a schema."
  {:pack/id :conforms :pack/kinds #{:conforms} :pack/find (fn [_ _] []) :pack/read (fn [_ _ _] [])
   :pack/schema-call? (fn [{:keys [to name]} _] (= 'app.core/check (symbol (str to) (str name))))})




(def broken-pack
  "A project's pack that fails on every file it finds."
  {:pack/id :broken :pack/kinds #{:broken}
   :pack/find (fn [analysis _] (for [u (:var-usages analysis) :when (= 'defjob (:name u))]
                                 (select-keys u [:filename :row :name-row :name-col])))
   :pack/read (fn [_ _ _] (throw (ex-info "boom" {})))})

(def tagged-pack
  "A pack whose library links vars with its own meta key, :app/id, and
   reports a var it links that nothing else describes."
  {:pack/id :tagged :pack/kinds #{:tagged} :pack/id-key :app/id
   :pack/find (fn [_ _] []) :pack/read (fn [_ _ _] [])
   :pack/checks (fn [{:keys [entities linked-by loc impl]}]
                  (for [[id e] (sort-by key entities)
                        :when (and (every? #(= :binding (:kind %)) (:definitions e)) (= :tagged (linked-by id)))]
                    {:severity :error :problem :tagged-without-entry :dev-id id :impl (impl id) :definition (loc e)}))})

(defn- notes-pack
  "A pack giving a note on every key a data file has, as atlas's registry
   describes integrant's components: describing, or not."
  [describes? file]
  {:pack/id :notes :pack/kinds #{:note} :pack/describes? describes?
   :pack/data-files (fn [_] [file])
   :pack/read-data (fn [files _]
                     (vec (for [{:keys [file text]} files
                                [k _ line] (meta-source.read/keyed-lines text {:tags :keep})
                                :when (qualified-keyword? k)]
                            {:file file :row line :kind :note :dev-id k :entry {:note/text "described"}})))})

(def describing-jobs-pack (notes-pack true "notes/jobs.edn"))
(def declaring-jobs-pack (notes-pack false "notes/jobs.edn"))

(def job-again
  "A pack claiming the kind the job pack gives."
  {:pack/id :job-again :pack/kinds #{:job} :pack/find (fn [_ _] []) :pack/read (fn [_ _ _] [])})

(def component-tier-pack
  "A pack deriving a fact from what was read: each component the shop
   names in EDN (:component/kind) a :tier, unless a source says one."
  {:pack/id :component-tiers
   :pack/kinds #{:component-tiers}
   :pack/find (fn [_ _] [])
   :pack/read (fn [_ _ _] [])
   :pack/derive (fn [entities _opts]
                  (into {} (for [[id {:keys [entry]}] entities :when (:component/kind entry)] [id {:app/tier :foundation}])))})

(def flow-pack
  "A pack deriving dataflow from what was read: an entry with :flow/in-key
   names the key that lists what an entity type consumes, as atlas's
   ontologies name :dataflow/context-key."
  {:pack/id :flow :pack/kinds #{:flow}
   :pack/find (fn [_ _] []) :pack/read (fn [_ _ _] [])
   :pack/dataflow-of (fn [entries] {:consumes (vec (keep :flow/in-key (vals entries)))})})

(def pedestal-pack
  "A project's own pack reading Pedestal's routes (here, nothing): it names
   the namespaces it covers."
  {:pack/id :pedestal :pack/kinds #{:pedestal} :pack/covers #{'io.pedestal.http.route}
   :pack/find (fn [_ _] []) :pack/read (fn [_ _ _] [])})

(def rules-pack
  "A pack deriving rules from what was read: an entry's :rule/as-data is a
   rule, as atlas's invariants carry :invariant/rule."
  {:pack/id :rules-of :pack/kinds #{:rules-of}
   :pack/find (fn [_ _] []) :pack/read (fn [_ _ _] [])
   :pack/rules-of (fn [entries] (into {} (for [[id e] entries :when (:rule/as-data e)] [id (:rule/as-data e)])))})
