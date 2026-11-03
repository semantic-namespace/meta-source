(ns smoke.overarch
  (:require [smoke.support :as s :refer [check problems]]
            [meta-source.read]
            [meta-source.index :as index]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(defn run []
  ;; ---- the overarch pack: an architecture model read as data
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/models/shop"))
    (fs/create-dirs (str dir "/registry"))
    (spit (str dir "/models/shop/model.edn")
          (str "#{{:el :person :id :shop/customer :name \"Customer\"}\n"
               "  {:el :system :id :shop/system :name \"Shop\"\n"
               "   :ct #{{:el :container :id :shop/api :name \"API\" :tech \"Clojure\"}\n"
               "         {:el :container :id :shop/orders-db :name \"Orders DB\"}\n"
               "         {:el :container :id :shop/mailer :name \"Mailer\"}\n"
               "         {:el :container :id :shop/audit-log :name \"Audit log\"}}}\n"
               "  {:el :rel :id :shop/api-uses-db :from :shop/api :to :shop/orders-db :name \"reads and writes\"}\n"
               "  {:el :rel :id :shop/api-uses-mailer :from :shop/api :to :shop/mailer :name \"sends\"}\n"
               "  {:el :rel :id :shop/api-uses-cache :from :shop/api :to :shop/cache :name \"caches\"}\n"
               "  {:el :container :id :shop/projector :name \"Order projector\"}\n"
               "  {:el :rel :id :shop/api-feeds-projector :from :shop/api :to :shop/projector :name \"order-placed\"}}\n"))
    (spit (str dir "/models/shop/views.edn")
          (str "#{{:el :container-view :id :shop/containers :title \"Shop containers\"\n"
               "   :ct [{:ref :shop/api} {:ref :shop/orders-db} {:ref :shop/mailer} {:ref :shop/projector} {:ref :shop/ghost}]}}\n"))
    ;; what the system itself says, in its own words
    (spit (str dir "/registry/app.edn")
          (str "{:shop/api {:app/calls [:shop/orders-db :shop/audit-log :shop/payments] :app/emits [:shop/order-placed]}\n"
               " :shop/projector {:app/reads [:shop/order-placed]}\n"
               " :shop/orders-db {:app/kind :db}\n"
               " :shop/mailer {:app/kind :smtp}\n"
               " :shop/audit-log {:app/kind :log}}\n"))
    (let [oi (index/build {:root dir :src [] :registry ["registry"] :packs {:overarch {:models ["models"]}}
                                  :dataflow {:consumes [:app/reads] :produces [:app/emits]}})
          ps (fn [p] (set (map (juxt :dev-id :to (comp :line :definition)) (problems oi p))))]
      (check "overarch: a relation to no element, at the relation"
             (= #{[:shop/api-uses-cache :shop/cache 9]} (ps :relation-to-nothing)))
      (check "overarch: a view of an id the model doesn't define"
             (= #{[:shop/containers :shop/ghost 1]} (ps :view-of-nothing)))
      (check "overarch: an element no view shows, itself, its ancestors or its descendants, is info"
             (= #{:shop/customer :shop/audit-log} (set (map :dev-id (problems oi :element-in-no-view)))))
      (check "overarch: the model relates what the system doesn't (api -> mailer), as a warning at the relation; api -> projector is declared through the data api emits and projector reads"
             (= #{[:shop/api :shop/mailer 8]} (ps :model-relation-not-declared)))
      (check "overarch: the system names a model element the model doesn't relate to (api -> audit-log)"
             (= #{[:shop/api :shop/audit-log 3]} (ps :relation-missing-from-model)))
      (check "overarch: an element is an entity; a relation is a fact on the element it starts from"
             (and (= #{:shop/orders-db :shop/mailer :shop/cache :shop/projector}
                     (set (get-in (index/where oi :shop/api) [:entry :overarch/relates-to])))
                  (contains? (set (get-in (index/where oi :shop/system) [:entry :overarch/contains])) :shop/api)
                  ;; a picture of the system: the registry's entry is the primary definition
                  (str/ends-with? (get-in (index/where oi :shop/api) [:definition :file]) "registry/app.edn")
                  (= :db (get-in (index/where oi :shop/orders-db) [:entry :app/kind]))))
      (check "overarch: uses on an element lists the relations reaching it and the views showing it"
             (= #{:shop/api :shop/api-uses-db :shop/containers :shop/system}
                (set (map :dev-id (:referenced-by (index/uses oi :shop/orders-db)))))))
    (fs/delete-tree dir))

  ;; ---- the overarch pack: ids Overarch generates, containment, what it rejects
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/models"))
    (spit (str dir "/models/model.edn")
          (str "#{{:el :system :id :g/shop :name \"Shop\" :desc \"the shop\"\n"
               "   :ct #{{:el :container :name \"Order API\"}\n"
               "         {:ref :g/db}}}\n"
               "  {:el :container :id :g/db :name \"DB\"}\n"
               "  {:el :rel :from :g/shop-order-api-container :to :g/db}\n"
               "  {:el :rel :to :g/db}\n"
               "  {:el :container :name \"Orphan\"}\n"
               "  {:el :system :id :g/other :name \"Other\"}\n"
               "  {:el :contained-in :from :g/db :to :g/other}}\n"))
    ;; Overarch merges a relation written the same in two files
    (spit (str dir "/models/more.edn") "#{{:el :rel :from :g/shop-order-api-container :to :g/db}}\n")
    (let [oi (index/build {:root dir :src [] :registry [] :packs {:overarch {:models ["models"] :desc true}}})
          ps (fn [p] (set (map (juxt :dev-id :line) (map #(assoc % :line (or (:line %) (get-in % [:definition :line]))) (problems oi p)))))]
      (check "overarch: a child node without :id gets Overarch's generated id, contained in its parent"
             (and (= :container (get-in (index/where oi :g/shop-order-api-container) [:entry :overarch/el]))
                  (= #{:g/shop-order-api-container :g/db} (set (get-in (index/where oi :g/shop) [:entry :overarch/contains])))))
      (check "overarch: a relation without :id gets Overarch's generated id, each at its own line; the same relation written twice is one"
             (and (= :g/db (get-in (index/where oi :g/shop-order-api-container-rel-db) [:entry :overarch/to]))
                  (= 5 (get-in (index/where oi :g/shop-order-api-container-rel-db) [:definition :line]))
                  (empty? (problems oi :element-defined-twice))))
      (check "overarch: what Overarch rejects on load is an error where it's written: a relation kind with no :from, a top-level node with no :id"
             (and (= #{[nil 6]} (ps :relation-missing-end))
                  (= #{[nil 7]} (ps :missing-id))))
      (check "overarch: a :ref in a :ct and a :contained-in relation both contain: two parents is a parent override"
             (= #{[:g/db 4]} (ps :parent-override)))
      (check "overarch: with :desc true, a node without :desc is info"
             (= #{:g/db :g/other :g/shop-order-api-container} (set (map :dev-id (problems oi :missing-desc))))))
    (fs/delete-tree dir))

  ;; ---- the overarch pack: a decision, risk, goal… says what it concerns
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/models"))
    (spit (str dir "/models/model.edn")
          (str "#{{:el :system :id :p/sys :name \"Sys\"}\n"
               "  {:el :decision :id :p/lang :name \"Language\"}\n"
               "  {:el :decision :id :p/db :name \"Database\"}\n"
               "  {:el :decision-for :id :p/db-for-sys :from :p/db :to :p/sys}\n"
               "  {:el :risk :id :p/scale :name \"Scale\"}\n"
               "  {:el :rel :id :p/scale-rel :from :p/scale :to :p/sys}\n"
               "  {:el :stakeholder :id :p/ops :name \"Ops\"}\n"
               "  {:el :stakeholder-of :id :p/ops-of :from :p/ops :to :p/sys}\n"
               "  {:el :non-goal :id :p/ui :name \"UI\"}}\n"))
    (let [oi (index/build {:root dir :src [] :registry [] :packs {:overarch {:models ["models"]}}})]
      (check "overarch: a decision, risk or non-goal with no relation of its kind from it concerns nothing, at the element; any other relation isn't its kind"
             (= #{[:p/lang :decision-for 2] [:p/scale :risk-for 5] [:p/ui :non-goal-for 9]}
                (set (map (juxt :dev-id :rel (comp :line :definition)) (problems oi :concerns-nothing))))))
    (fs/delete-tree dir))

  ;; ---- the overarch pack: a view's spec keys, in :spec or on the view
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/models"))
    (spit (str dir "/models/model.edn")
          (str "#{{:el :system :id :s/sys :name \"Sys\"}\n"
               "  {:el :container-view :id :s/top :selection {:namespace \"s\"} :ct []}\n"
               "  {:el :context-view :id :s/laid-out :spec {:layout :left-right}}}\n"))
    (fs/create-dirs (str dir "/plain"))
    (spit (str dir "/plain/model.edn")
          (str "#{{:el :system :id :s/sys :name \"Sys\"}\n"
               "  {:el :context-view :id :s/laid-out :spec {:layout :left-right}}}\n"))
    (let [oi (index/build {:root dir :src [] :registry [] :packs {:overarch {:models ["models"]}}})
          plain (index/build {:root dir :src [] :registry [] :packs {:overarch {:models ["plain"]}}})]
      (check "overarch: a :selection on the view itself is a selection, as Overarch merges :spec into the view; a :spec that only lays out selects nothing"
             (and (= {:namespace "s"} (get-in (index/where oi :s/top) [:entry :overarch/selection]))
                  (nil? (get-in (index/where oi :s/laid-out) [:entry :overarch/selection]))))
      (check "overarch: a view listing nothing is a view, not an element no view shows"
             (= #{:s/sys} (set (map :dev-id (problems plain :element-in-no-view))))))
    (fs/delete-tree dir))

  ;; ---- the overarch and namespaces packs: the model against what the code requires
  (let [dir (str (fs/create-temp-dir))]
    (doseq [d ["src/app" "models"]] (fs/create-dirs (str dir "/" d)))
    (spit (str dir "/src/app/api.clj") "(ns app.api\n  (:require [app.db] [app.audit]))\n")
    (spit (str dir "/src/app/db.clj") "(ns app.db)\n")
    (spit (str dir "/src/app/mailer.clj") "(ns app.mailer)\n")
    (spit (str dir "/src/app/audit.clj") "(ns app.audit)\n")
    (spit (str dir "/models/model.edn")
          (str "#{{:el :container :id :shop/api :name \"API\"}\n"
               "  {:el :container :id :shop/db :name \"DB\"}\n"
               "  {:el :container :id :shop/mailer :name \"Mailer\"}\n"
               "  {:el :container :id :shop/audit :name \"Audit\"}\n"
               "  {:el :rel :id :shop/api-db :from :shop/api :to :shop/db}\n"
               "  {:el :rel :id :shop/api-mailer :from :shop/api :to :shop/mailer}}\n"))
    (let [ix (index/build {:root dir :src ["src"] :registry []
                           :packs {:namespaces {:roots {"app" "shop"}} :overarch {:models ["models"]}}})
          ps (fn [p] (set (map (juxt :dev-id :to (comp :line :definition)) (problems ix p))))]
      (check "overarch + namespaces: a relation the model draws and the code doesn't require is a warning at the relation; a require the model doesn't draw is info"
             (and (= #{[:shop/api :shop/mailer 6]} (ps :model-relation-not-declared))
                  (= #{[:shop/api :shop/audit 1]} (ps :relation-missing-from-model))))
      (check "overarch + namespaces: the namespace is the component's primary definition, the model a picture of it"
             (str/ends-with? (get-in (index/where ix :shop/api) [:definition :file]) "src/app/api.clj")))
    (fs/delete-tree dir)))
