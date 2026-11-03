(ns smoke.support
  "What every suite shares: check, the failures it counts, and the shop
   fixture (test/fixtures/shop) built once."
  (:require [meta-source.index :as index]
            [clojure.edn :as edn]))

(def failures (atom 0))
(def checks (atom 0))

(defn check [what ok?]
  (swap! checks inc)
  (println (if ok? "ok  " "FAIL") what)
  (when-not ok? (swap! failures inc)))

(defn problems [idx problem]
  (filter #(= problem (:problem %)) (index/check idx)))

(def root "test/fixtures/shop")

(def shop
  "The shop fixture: its meta-source.edn with the Pathom snapshot
   (:with-runtime), without it (:cfg, static reading alone), and :cfg's index."
  (delay
    (let [with-runtime (assoc (edn/read-string (slurp (str root "/meta-source.edn"))) :root root)
          cfg (update-in with-runtime [:packs :pathom] dissoc :snapshot)
          idx (index/build cfg)]
      {:with-runtime with-runtime :cfg cfg :idx idx :email (index/data-key idx :user/email)})))
