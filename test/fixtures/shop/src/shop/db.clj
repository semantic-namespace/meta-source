(ns shop.db
  (:require [datascript.core :as d]))

(def schema
  {:user/email  {:db/unique :db.unique/identity}
   :user/orders {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :order/lines {:db/cardinality :db.cardinality/many}
   ;; many here, one string? in its spec
   :user/name   {:db/cardinality :db.cardinality/many}})

(def conn (d/create-conn schema))

;; :order/lines is not a ref: the join's subpattern is ignored
(defn user-by-email [db email]
  (d/pull db [:user/name {:user/orders [:order/id]} {:order/lines [:product/id]}]
          [:user/email email]))

;; :user/id is not :db/unique: Datascript refuses the lookup ref
(defn user-by-id [db id] (d/entity db [:user/id id]))

(defn orders [db]
  (d/q '[:find (pull ?o [:order/id :user/_orders]) :where [?o :order/id]] db))

;; :order/lines is not a ref: it can't be read in reverse
(defn order-of-line [db line] (d/pull db [:order/_lines] line))
