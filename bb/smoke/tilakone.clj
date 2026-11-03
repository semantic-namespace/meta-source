(ns smoke.tilakone
  (:require [smoke.support :as s :refer [check problems]]
            [meta-source.read]
            [meta-source.index :as index]
            [babashka.fs :as fs]))

(defn run []
  ;; ---- the tilakone pack: state machines read as data
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/src/shop"))
    (spit (str dir "/src/shop/order.clj")
          (str "(ns shop.order\n  (:require [tilakone.core :as tk :refer [_]]))\n\n"
               "(def fsm\n"
               "  {::tk/states [{::tk/name ::cart\n"
               "                 ::tk/transitions [{::tk/on ::checkout ::tk/to ::paying}]}\n"
               "                {::tk/name ::paying\n"
               "                 ::tk/transitions [{::tk/on ::paid ::tk/to ::shiped}\n"
               "                                   {::tk/on _ ::tk/to ::cart}]}\n"
               "                {::tk/name ::shipped}\n"
               "                {::tk/name ::cart}]\n"
               "   ::tk/state ::cart})\n\n"
               "(def bad-start {::tk/states [{::tk/name ::a}] ::tk/state ::b})\n"))
    (let [ti (index/build {:root dir :src ["src"] :registry [] :packs {:tilakone {}}})
          ps (fn [p] (set (map (juxt :dev-id :state :to (comp :line :definition)) (problems ti p))))]
      (check "tilakone: a transition to no state, at the transition's line"
             (= #{[:shop.order/fsm :shop.order/paying :shop.order/shiped 8]} (ps :transition-to-no-state)))
      (check "tilakone: an initial state that is no state, and a state defined twice"
             (and (= #{[:shop.order/bad-start nil :shop.order/b 14]} (ps :initial-state-not-a-state))
                  (= #{[:shop.order/fsm :shop.order/cart nil 11]} (ps :state-defined-twice))))
      (check "tilakone: a state no transition reaches is info, at the state"
             (= #{[:shop.order/fsm :shop.order/shipped nil 10]} (ps :state-unreachable)))
      (check "tilakone: a state is an entity; what goes to it uses it; _ is :tilakone/any"
             (and (= #{:shop.order/fsm} (set (get-in (index/where ti :shop.order/paying) [:entry :tilakone/machines])))
                  (contains? (set (get-in (index/where ti :shop.order/paying) [:entry :tilakone/on])) :tilakone/any)
                  (= #{:shop.order/cart :shop.order/fsm}
                     (set (map :dev-id (:referenced-by (index/uses ti :shop.order/paying))))))))
    (fs/delete-tree dir)))
