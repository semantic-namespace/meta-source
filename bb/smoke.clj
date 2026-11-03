(ns smoke
  "The checks, one suite each: core (the index, rules, the editor, the pack
   contract through packs written here) and one per pack. bb smoke runs them
   all; bb smoke core reitit, those named."
  (:require [smoke.support :as s]
            [smoke.core]
            [smoke.shop]
            [smoke.schema]
            [smoke.integrant]
            [smoke.reitit]
            [smoke.re-frame]
            [smoke.datascript]
            [smoke.aero]
            [smoke.tilakone]
            [smoke.grain]
            [smoke.overarch]
            [smoke.pattern]))

(def suites
  [["core" smoke.core/run]
   ["shop" smoke.shop/run]
   ["schema" smoke.schema/run]
   ["integrant" smoke.integrant/run]
   ["reitit" smoke.reitit/run]
   ["re-frame" smoke.re-frame/run]
   ["datascript" smoke.datascript/run]
   ["aero" smoke.aero/run]
   ["tilakone" smoke.tilakone/run]
   ["grain" smoke.grain/run]
   ["overarch" smoke.overarch/run]
   ["pattern" smoke.pattern/run]])

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
