(ns smoke.aero
  (:require [smoke.support :as s :refer [check problems]]
            [meta-source.read]
            [meta-source.index :as index]
            [clojure.edn :as edn]))

(defn run []
  ;; ---- aero: test/fixtures/aero
  (let [ai (index/build (assoc (edn/read-string (slurp "test/fixtures/aero/meta-source.edn")) :root "test/fixtures/aero"))
        entry #(:entry (index/where ai %))
        found (fn [p] (map (juxt :dev-id (comp :line :definition) :severity) (problems ai p)))]
    (check "every top-level key's tags read, and the readers the code defines"
           (= {:read 16 :not-read 0} (:aero (:packs (index/summary ai)))))
    (check "an #env: its default from an #or, what it is read as, whether a deploy file sets it"
           (and (= #:aero{:env true :defaults #{"jdbc:postgresql://localhost/app"} :set-in-deploy true} (entry :env/DB_URL))
                (= #{:long} (:aero/coerced (entry :env/DB_POOL)))))
    (check "in #or [#env A #env B], A falls back to B; B, with no default, is required"
           (and (not (:aero/required (entry :env/PRIMARY_ID))) (:aero/required (entry :env/SECONDARY_ID))))
    (check "a required variable no :env-sources file sets: info"
           (= [[:env/API_KEY 4 :info]] (found :env-not-set)))
    (check "an #include found nowhere is a warning; one :provided lists, or that exists, isn't"
           (= [[:aero.include/nowhere.edn 9 :warning]] (found :include-not-found)))
    (check "a tag with no reader is a warning; one a defmethod of aero.core/reader defines isn't"
           (= [[:aero.tag/duration 16 :warning]] (found :tag-without-reader)))
    (check "a #profile no configured profile reads, and one that reads nil"
           (and (= [[:ttl [:dev :staging]]] (map (juxt :dev-id :keys) (problems ai :profile-never-read)))
                (= [[:ttl [:prod :test]]] (map (juxt :dev-id :keys) (problems ai :profile-without-branch)))))
    (check "a #ref to a path the config doesn't hold"
           (= [[:broken [[:db :host]]]] (map (juxt :dev-id :paths) (problems ai :ref-to-nothing))))
    (check "#long over a default that isn't a number, at the tag"
           (= [[:db 3 :warning]] (found :not-a-number)))))
