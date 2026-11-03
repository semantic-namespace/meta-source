(ns app.public)

(defn health [_] {:status 200 :body "ok"})

(defn routes []
  [["/public/health" {:name :public/health :get {:handler health}}]])
