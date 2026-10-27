(ns app.system
  "Each component's init-key destructures its config, and hands each call
   only what it needs (I5)."
  (:require [integrant.core :as ig]
            [reitit.ring :as ring]
            [app.billing :as billing]
            [app.public :as public]
            [app.users :as users]))

(defmethod ig/init-key :app/db [_ {:keys [url]}] {:url url})
(defmethod ig/halt-key! :app/db [_ _] nil)

(defmethod ig/init-key :app/stripe [_ {:keys [api-key]}] {:api-key api-key})

(defmethod ig/init-key :app/users [_ {:keys [db]}] {:db db})

(defmethod ig/init-key :app/billing [_ {:keys [db stripe]}] {:db db :stripe stripe})

;; a route fn per component it is handed: each route reaches what its fn is given
(defmethod ig/init-key :app/router [_ {:keys [users billing]}]
  (ring/router [(public/routes) (users/routes users) (billing/routes billing)]))
