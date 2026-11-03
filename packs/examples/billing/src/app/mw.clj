(ns app.mw)

(defn wrap-auth [handler]
  (fn [req] (when (:identity req) (handler req))))
