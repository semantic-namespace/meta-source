(ns smoke
  "The core's checks: the index, rules, the editor, the pack contract
   through packs written here (bb/smoke/packs.clj), on test/fixtures/core
   with no library pack. The packs' checks are packs/'s bb smoke."
  (:require [smoke.support :as s]
            [smoke.core]))

(def suites
  [["core" smoke.core/run]])

(defn -main [& names]
  (let [unknown (remove (set (map first suites)) names)]
    (when (seq unknown)
      (println "smoke: no suite" (pr-str (vec unknown)) "- suites:" (pr-str (mapv first suites)))
      (System/exit 2))
    (doseq [[n run] suites :when (or (empty? names) (some #{n} names))]
      (println (str "---- " n))
      (run))
    (if (zero? @s/failures)
      (println "smoke:" @s/checks "checks passed")
      (do (println "smoke:" @s/failures "of" @s/checks "failed") (System/exit 1)))))
