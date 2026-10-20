(ns shop.routes
  (:require [reitit.ring :as ring]
            [shop.api :as api]
            [shop.middleware :as mw]))

;; a route def, read in place where the tree names it
(def admin-routes
  ["/admin" {:middleware [mw/require-admin] :roles #{:admin}}
   ["/users" {:name ::users :get {:handler api/list-users}}]])

(def dev? false)

(def router
  (ring/router
   [["/api" {:middleware [mw/token-auth]}
     ["/users/:id" {:name ::user
                    :get {:handler api/get-user
                          :parameters {:path {:user/id uuid?}}
                          :responses {200 {:body :shop.spec/user}}}}]
     ;; shop.api defines no login
     ["/login" {:post {:handler api/login :public true}}]
     admin-routes
     (when dev? ["/debug" {:get {:handler api/debug}}])]]))
