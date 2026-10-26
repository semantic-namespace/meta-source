(ns system-snapshot-check
  "Runs meta-source.pack.integrant/runtime-entities and
   meta-source.pack.reitit/runtime-entities against real integrant and
   reitit (test/fixtures/system-snapshot/make.clj, on the JVM) and checks
   what they give. A change in either library's shapes, or in the
   snapshot fns, fails it. Needs the clojure CLI."
  (:require [babashka.process :as p]
            [clojure.edn :as edn]))

(def expected
  {:integrant {:app/db {:integrant/in-system true}
               :app/redis {:integrant/in-system true :integrant/derives [:app/kv]}
               :app/svc {:integrant/in-system true :integrant/refs [:app/db :app/kv :app/plugin]
                         :integrant/refsets [:app/plugin]}}
   :reitit {:api/user {:reitit/path "/api/users/:id" :reitit/methods [:get]
                       :reitit/handlers ['user/handler]
                       :reitit/middleware ['user/wrap]}
            :page/about {:reitit/path "/about" :reitit/methods [:get]}}})

(defn -main [& _]
  (let [{:keys [out]} (p/shell {:out :string}
                               "clojure" "-Sdeps"
                               (pr-str {:paths ["src"]
                                        :deps {'integrant/integrant {:mvn/version "1.0.1"}
                                               'metosin/reitit-ring {:mvn/version "0.7.2"}}})
                               "-M" "test/fixtures/system-snapshot/make.clj")
        got (edn/read-string out)]
    (if (= expected got)
      (println "system-snapshot-check: real integrant and reitit give what the snapshots expect")
      (do (println "system-snapshot-check: they differ")
          (println " expected" (pr-str expected))
          (println " got     " (pr-str got))
          (System/exit 1)))))
