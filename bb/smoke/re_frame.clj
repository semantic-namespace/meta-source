(ns smoke.re-frame
  (:require [smoke.support :as s :refer [check problems]]
            [meta-source.hover :as hover]
            [meta-source.read]
            [meta-source.index :as index]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(defn run []
 (let [{:keys [cfg]} @s/shop]
    ;; ---- re-frame: src/shop/ui.cljs
    (let [rf (index/build (assoc-in cfg [:packs :re-frame] {:provided [:http-xhrio]}))
          entry #(:entry (index/where rf %))
          found #(set (map :dev-id (problems rf %)))]
      (check "every registration and reference is read, in a .cljs file, with the ids held in literal vectors"
             (= {:read 26 :not-read 0} (:re-frame (:packs (index/summary rf)))))
      (check "an event: its handler, what its effects dispatch (a callback among them), its effects and writes"
             (= #:re-frame{:registered #{:event} :handler :fx :dispatched true :held true
                           :dispatches [:shop.ui/pay :shop.ui/paid] :effects [:http-xhrio] :writes [:order/state]}
                (entry :shop.ui/checkout)))
      (check ":fx entries: an effect and a dispatch"
             (= [[:shop.ui/clear-cart] [:notify]] ((juxt :re-frame/dispatches :re-frame/effects) (entry :shop.ui/paid))))
      (check "a subscription's :<- signals are references"
             (= [{:dev-id :shop.ui/line-count :via :re-frame/signals}]
                (map #(select-keys % [:dev-id :via]) (:referenced-by (index/uses rf :shop.ui/lines)))))
      (check "its primary definition is the registration, not a place that refers to it"
             (= 9 (:line (:definition (index/where rf :shop.ui/checkout)))))
      (check "a dispatch and a subscription to ids nothing registers, where they are written"
             (= [[:sub-not-registered :shop.ui/line-cuont 31] [:event-not-registered :shop.ui/pay 14]]
                (for [p (index/check rf) :when (#{:sub-not-registered :event-not-registered} (:problem p))]
                  [(:problem p) (:dev-id p) (:line (:definition p))])))
      (check "registered and referred to nowhere: reported only with :report-unused; :provided and library effects never"
             (let [unused (index/build (assoc-in cfg [:packs :re-frame] {:report-unused true}))
                   found-unused #(set (map :dev-id (problems unused %)))]
               (and (empty? (found :event-never-dispatched))
                    (empty? (found :sub-never-subscribed))
                    (= #{:shop.ui/log-out} (found-unused :event-never-dispatched))
                    (= #{:shop.ui/line-count} (found-unused :sub-never-subscribed))
                    (empty? (found :effect-not-registered))
                    ;; day8.re-frame/http-fx's :http-xhrio needs no :provided
                    (empty? (problems (index/build (assoc-in cfg [:packs :re-frame] {})) :effect-not-registered)))))
      (check "one key across four registries: a spec shapes it, EDN calls it PII, a resolver and a subscription read it"
             (let [email (index/data-key rf :user/email)]
               (and (= [:fn/register-user :shop.resolvers/user-by-email :shop.ui/email] (map :dev-id (:consumers email)))
                    (some #{:shop.ui/set-email} (map :dev-id (:producers email)))
                    (not-any? #{:shop.ui/log-out} (map :dev-id (:producers email))))))
      (check "a key an event writes that no schema shapes"
             (contains? (set (map :keys (problems rf :data-key-without-schema))) [:order/state]))
      (check "the editor underlines ::pay where it is written"
             (some #(= {:line 14 :col 17 :end-col 22 :problem "event-not-registered"} (select-keys % [:line :col :end-col :problem]))
                   (hover/diagnostics rf "src/shop/ui.cljs")))
      (check "the hover describes an event"
             (str/includes? (:eldoc (hover/lookup rf :shop.ui/checkout)) "event (fx)")))

    ;; ---- re-frame: a project's own registration and dispatch fns
    (let [dir (str (fs/create-temp-dir))]
      (fs/create-dirs (str dir "/src/app"))
      (spit (str dir "/src/app/rf.clj")
            (str "(ns app.rf (:require [re-frame.core :as rf]))\n"
                 "(def reg-event-fx rf/reg-event-fx)\n"
                 "(def dispatch rf/dispatch)\n"
                 "(defn reg-root-sub [id k] (rf/reg-sub id (fn [db _] (get db k))))\n"
                 "(defmacro defevent [n m & body] `(defn ~n ~@body))\n"))
      (spit (str dir "/src/app/ui.clj")
            (str "(ns app.ui (:require [re-frame.core :as re-frame] [app.rf :as arf]))\n"
                 "(arf/reg-event-fx ::load (fn [_ _] {:fx [[:dispatch [::loaded]]]}))\n"
                 "(arf/defevent loaded {:events [::loaded]} [cofx] {})\n"
                 "(arf/reg-root-sub ::user :user)\n"
                 "(defn view [] (arf/dispatch [::load]) @(re-frame/subscribe [::user]))\n"
                 ";; :fx a local names\n"
                 "(arf/reg-event-fx ::batch (fn [_ _] (let [requests [[:dispatch [::load]]]] {:fx requests})))\n"
                 "(defn go [] (arf/dispatch [::batch]))\n"))
      (let [cfg {:root dir :src ["src"] :registry [] :packs {:re-frame {}}}
            plain (index/build cfg)
            declared (index/build (assoc-in cfg [:packs :re-frame :wrappers]
                                            '{app.rf/reg-event-fx reg-event-fx app.rf/dispatch dispatch
                                              app.rf/reg-root-sub reg-sub
                                              app.rf/defevent {:registers :event :ids-in :events}}))
            warned (fn [idx] (set (map :dev-id (filter #(#{:event-not-registered :sub-not-registered} (:problem %)) (index/check idx)))))
            suggested (set (keep #(second (re-find #"^(\S+) passes its argument" (str (:reason %))))
                                 (problems plain :pack-form-not-read)))]
        (check "re-frame: without :wrappers, a sub the project registers through its own fn looks unregistered"
               (= #{:app.ui/user} (warned plain)))
        (check "re-frame: a def or defn passing its first argument to re-frame is suggested as a wrapper, with the line to add"
               (= #{"app.rf/reg-event-fx" "app.rf/dispatch" "app.rf/reg-root-sub"} suggested))
        (check "re-frame: with :wrappers declared, their calls register and dispatch; a macro's :events register; nothing is suggested"
               (and (empty? (warned declared))
                    (= #{:event} (get-in (index/where declared :app.ui/loaded) [:entry :re-frame/registered]))
                    (true? (get-in (index/where declared :app.ui/load) [:entry :re-frame/dispatched]))
                    (not-any? #(re-find #"passes its argument" (str (:reason %))) (problems declared :pack-form-not-read))))
        (check "re-frame: an :fx a local names doesn't fail the file"
               (and (= #{:event} (get-in (index/where declared :app.ui/batch) [:entry :re-frame/registered]))
                    (not-any? #(re-find #"failed reading" (str (:reason %))) (problems declared :pack-form-not-read)))))
      (fs/delete-tree dir))))
