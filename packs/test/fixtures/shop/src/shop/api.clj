(ns shop.api)

(defn list-users [_req] {:status 200 :body []})

(defn get-user [req]
  {:status 200 :body {:user/id (-> req :parameters :path :user/id) :user/email "a@b.c"}})

(defn debug [_req] {:status 200})
