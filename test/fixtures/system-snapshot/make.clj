;; Prints what meta-source.pack.integrant/runtime-entities and
;; meta-source.pack.reitit/runtime-entities give for a real integrant config
;; and a real reitit router. bb system-snapshot-check runs it on the JVM and
;; compares it with what is expected.
(require '[integrant.core :as ig]
         '[reitit.ring :as ring]
         '[meta-source.pack.integrant :as mig]
         '[meta-source.pack.reitit :as mr])

(defn handler [_] {:status 200})
(defn wrap [h] h)

(def config
  (ig/read-string
   (str "{:app/db {}\n"
        " [:app/kv :app/redis] {}\n"
        " :app/svc {:db #ig/ref :app/db :kv #ig/ref :app/kv :plugins #ig/refset :app/plugin}}")))

(def router
  (ring/router
   [["/api" {:middleware [[#'wrap]]}
     ["/users/:id" {:name :api/user :get {:handler #'handler}}]
     ["/login" {:post {:handler handler}}]]
    ["/about" {:name :page/about :get handler}]]))

(prn {:integrant (mig/runtime-entities config)
      :reitit (mr/runtime-entities router)})
