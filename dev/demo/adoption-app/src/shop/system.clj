(ns shop.system
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :shop/db [_ {:keys [url]}] {:url url})
(defmethod ig/halt-key! :shop/db [_ _] nil)

(defmethod ig/init-key :shop/http-server [_ opts] opts)

(defmethod ig/init-key ::const [_ v] v)
(derive :shop/base-path ::const)
