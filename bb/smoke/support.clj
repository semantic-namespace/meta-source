(ns smoke.support
  "What the suites share: check, and the failures it counts."
  (:require [meta-source.index :as index]))

(def failures (atom 0))
(def checks (atom 0))

(defn check [what ok?]
  (swap! checks inc)
  (println (if ok? "ok  " "FAIL") what)
  (when-not ok? (swap! failures inc)))

(defn problems [idx problem]
  (filter #(= problem (:problem %)) (index/check idx)))
