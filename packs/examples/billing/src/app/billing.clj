(ns app.billing
  (:require [app.mw :as mw]))

(defn charge [billing]
  (fn [{{:charge/keys [amount]} :body-params}]
    {:status 201 :body {:charge/id (str (hash [amount (:stripe billing)]))}}))

(defn routes [billing]
  [["/api/billing/charge" {:name :api.billing/charge
                           :middleware [mw/wrap-auth]
                           :auth/roles #{:billing-admin}
                           :post {:handler (charge billing)
                                  :parameters {:body [:map :charge/amount]}
                                  :responses {201 {:body [:map :charge/id]}}}}]])
