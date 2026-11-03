(ns smoke.grain
  (:require [smoke.support :as s :refer [check]]
            [meta-source.read]
            [meta-source.index :as index]
            [babashka.fs :as fs]))

(defn run []
  ;; ---- grain: the event model, from the def sites and defeventmodel
  (let [dir (str (fs/create-temp-dir))
        files {"src/app/commands.clj"
               (str "(ns app.commands (:require [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]))\n"
                    "(defcommand :shop place-order\n"
                    "  {:authorized? (constantly true)\n"
                    "   :grain.event-model/produces #{:shop/order-placed :shop/order-logged}\n"
                    "   :grain.event-model/reads #{:shop/orders :shop/stock}}\n"
                    "  \"Places an order.\"\n"
                    "  [ctx] {})\n")
               "src/app/read_models.clj"
               (str "(ns app.read-models (:require [ai.obney.grain.read-model-processor-v2.interface :refer [defreadmodel]]\n"
                    "                              [ai.obney.grain.query-processor.interface :refer [defquery]]))\n"
                    "(defreadmodel :shop orders {:events #{:shop/order-placed} :version 1} \"Orders.\" [state event] state)\n"
                    "(defquery :shop orders {:grain.event-model/reads #{:shop/orders}} \"All orders.\" [ctx] {})\n")
               "src/app/events.clj"
               (str "(ns app.events (:require [ai.obney.grain.event-store-v3.interface :as es]\n"
                    "                         [ai.obney.grain.schema-util.interface :refer [defschemas]]))\n"
                    "(def audit-type :shop/audited)\n"
                    "(es/defevent :shop/order-placed \"An order was placed.\" {:schema [:map [:id :uuid]]})\n"
                    "(es/defevent :shop/order-logged \"An order was logged.\" {:schema [:map]})\n"
                    "(es/defevent audit-type \"Audited.\" {:schema [:map]})\n"
                    "(comment\n  (defschemas scratch {:shop/order-placed [:map]}))\n")
               "src/app/model.clj"
               (str "(ns app.model (:require [ai.obney.grain.event-model.interface :refer [defeventmodel]]))\n"
                    "(defeventmodel :shop\n"
                    "  {:commands {:shop/place-order {:produces #{:shop/order-placed :shop/order-logged} :reads #{:shop/orders :shop/stock}}}\n"
                    "   :read-models {:shop/orders {:consumes #{:shop/order-placed} :version 2}}\n"
                    "   :queries {:shop/orders {:reads #{:shop/orders}}}\n"
                    "   :events {:shop/order-placed {:schema [:map [:id :uuid]]}}})\n")}]
    (doseq [[p text] files] (fs/create-dirs (fs/parent (str dir "/" p))) (spit (str dir "/" p) text))
    (let [idx (try (index/build {:root dir :src ["src"] :registry [] :packs {:grain {}}})
                   (finally (fs/delete-tree dir)))
          entry #(:entry (index/where idx %))
          ps (index/check idx)
          of #(filter (fn [p] (= % (:problem p))) ps)]
      (check "grain: blocks are entities under kind-prefixed ids, references resolved to them, code options dropped"
             (and (= :command (:grain/type (entry :command.shop/place-order)))
                  (= #{:read-model.shop/orders :read-model.shop/stock} (:grain/reads (entry :command.shop/place-order)))
                  (= #{:event.shop/order-placed} (:grain/consumes (entry :read-model.shop/orders)))
                  (= :query (:grain/type (entry :query.shop/orders)))
                  (not (contains? (entry :command.shop/place-order) :authorized?))))
      (check "grain: where a block's options and the event model disagree, the values conflict"
             (some #(and (= :read-model.shop/orders (:dev-id %)) (= :grain/version (:key %)))
                   (concat (of :conflicting-values) (:read-problems idx))))
      (check "grain: a read model nothing defines, and an event nothing consumes"
             (and (= [[:read-model.shop/stock]] (map :keys (of :grain-reads-undefined)))
                  (= [:event.shop/order-logged] (map :dev-id (of :grain-event-not-consumed)))))
      (check "grain: an event named by a var isn't read; only the checks needing every event stand down, and say so"
             (and (= [:grain-model-only] (:checks (first (of :checks-not-run))))
                  (= [:event] (map :type (:because (first (of :checks-not-run)))))))
      (check "grain: a form inside (comment ...) isn't read"
             (nil? (index/where idx :shop/order-placed)))))

  ;; ---- grain: an area that declares no producers is one finding, not one per event
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/src/app"))
    (spit (str dir "/src/app/blocks.clj")
          (str "(ns app.blocks (:require [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]\n"
               "                         [ai.obney.grain.read-model-processor-v2.interface :refer [defreadmodel]]))\n"
               "(defcommand :crm add-lead {:authorized? (constantly true)} \"Adds a lead.\" [ctx] {})\n"
               "(defcommand :crm drop-lead {} \"Drops a lead.\" [ctx] {})\n"
               "(defreadmodel :crm leads {:events #{:crm/lead-added :crm/lead-dropped :ext/synced}} \"Leads.\" [s e] s)\n"))
    (let [ps (try (index/check (index/build {:root dir :src ["src"] :registry [] :packs {:grain {}}}))
                  (finally (fs/delete-tree dir)))
          of #(filter (fn [p] (= % (:problem p))) ps)]
      (check "grain: an area none of whose producers declares :produces is one finding, with its events; others stay per event"
             (and (= [[:crm 2 [:event.crm/lead-added :event.crm/lead-dropped]]]
                     (map (juxt :dev-id :blocks :events) (of :grain-area-declares-no-producers)))
                  (= [:event.ext/synced] (map :dev-id (of :grain-event-not-produced))))))))
