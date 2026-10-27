(ns app.users
  (:require [app.mw :as mw]))

(defn get-user [users]
  (fn [{{:keys [id]} :path-params}] {:status 200 :body {:user/id id :db (:db users)}}))

(defn routes [users]
  [["/api/users/:id" {:name :api.users/get
                      :middleware [mw/wrap-auth]
                      :get {:handler (get-user users)
                            :parameters {:path [:map :user/id]}
                            :responses {200 {:body [:map :user/id :user/email]}}}}]])
