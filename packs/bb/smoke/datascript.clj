(ns smoke.datascript
  (:require [smoke.support :as s :refer [check problems]]
            [meta-source.read]
            [meta-source.index :as index]))

(defn run []
    ;; ---- datascript: src/shop/db.clj, schema/datomic.edn
    (let [{:keys [cfg]} @s/shop
          di (index/build (assoc-in cfg [:packs :datascript] {:schemas ["schema"]}))
          found (fn [p] (map (juxt :dev-id (comp :line :definition) :severity) (problems di p)))]
      (check "a schema map in a def, and a Datomic-style schema file, attribute by attribute"
             (and (= {:read 11 :not-read 0} (:datascript (:packs (index/summary di))))
                  (= #:datascript{:value-type :db.type/ref :cardinality :db.cardinality/many}
                     (select-keys (:entry (index/where di :user/orders)) [:datascript/value-type :datascript/cardinality]))
                  (= #:datascript{:value-type :db.type/string :unique :db.unique/identity :doc "A product's stock-keeping unit"}
                     (:entry (index/where di :product/sku)))))
      (check "an attribute is a data key: its Datascript shape beside its spec"
             (= [:datascript :spec] (map :pack (:shape (index/data-key di :user/name)))))
      (check "a pull join on an attribute that isn't a ref; a reverse read of one"
             (and (= [[:order/lines 15 :warning]] (found :pull-join-not-ref))
                  (= [[:order/lines 25 :warning]] (found :reverse-not-ref))))
      (check "a reverse read and a join in a quoted query's pull, on a ref: nothing"
             (not-any? #{:user/orders} (map :dev-id (concat (problems di :pull-join-not-ref) (problems di :reverse-not-ref)))))
      (check "a lookup ref on an attribute no schema read gives: info"
             (= [[:user/id 19 :info]] (found :lookup-ref-not-unique)))
      (check "many in the Datascript schema, one string? in its spec"
             (= [[:user/name 9 :warning]] (found :schema-disagrees)))))
