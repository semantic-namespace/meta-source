(ns meta-source.review.bridge-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [meta-source.review.bridge :as bridge]
            [sdiff.deps :as deps]))

(defn- graph-edges
  "The edges the bridge adds to sdiff's graph of `root`, as
   #{[\"file:row name\" \"file:row name\"]}."
  [root]
  (let [root (.getCanonicalPath (io/file root))
        without (do (swap! @#'deps/bridges (fn [bs] (vec (remove #(= ::bridge/keywords (first %)) bs))))
                    (deps/graph root deps/defaults))
        _ (bridge/install!)
        g (deps/graph root deps/defaults)
        nm (fn [[f r]] (str f ":" r " " (let [{:keys [var name]} (get-in g [:forms [f r]])] (or var name))))]
    (set (for [[from tos] (:calls g) t tos
               :when (not (contains? (get-in without [:calls from]) t))]
           [(nm from) (nm t)]))))

(defn- project [files]
  (let [root (str (fs/create-temp-dir {:prefix "ms-bridge"}))]
    (doseq [[p s] files] (let [f (io/file root p)] (io/make-parents f) (spit f s)))
    root))

(deftest the-shop
  (let [es (graph-edges "../test/fixtures/shop")]
    (testing "a resolver's input on the resolver giving it"
      (is (contains? es ["src/shop/resolvers.clj:22 pco/defresolver user-discount" "src/shop/resolvers.clj:48 pco/defresolver seed"])))
    (testing "what an EDN entry says a function needs, on the form defining it"
      (is (contains? es ["src/shop/handlers.clj:3 shop.handlers/register-user" "src/shop/system.clj:18 s/def :shop/mailer"])))
    (testing "a handler's argument is not a resolver's output"
      (is (not-any? (fn [[from to]] (and (re-find #"handlers" from) (re-find #"defresolver" to))) es)))))

(def re-frame-app
  {"meta-source.edn" "{:packs {:re-frame {}}}"
   "src/app/events.cljs" "(ns app.events (:require [re-frame.core :as rf]))
(rf/reg-event-db ::set-email (fn [db [_ e]] (assoc db :email e)))
(rf/reg-event-fx ::save (fn [_ _] {:dispatch [::saved]}))
(rf/reg-event-db ::saved (fn [db _] db))"
   "src/app/subs.cljs" "(ns app.subs (:require [re-frame.core :as rf]))
(rf/reg-sub ::email (fn [db _] (:email db)))"
   "src/app/views.cljs" "(ns app.views (:require [re-frame.core :as rf] [app.events :as events] [app.subs :as subs]))
(defn email-input []
  [:input {:value @(rf/subscribe [::subs/email])
           :on-change #(rf/dispatch [::events/set-email (.. % -target -value)])}])
(defn save-button [] [:button {:on-click #(rf/dispatch [::events/save])} \"save\"])"
   "dev/scratch.cljs" "(ns scratch (:require [re-frame.core :as rf]))
(defn try-it [] (rf/dispatch [:app.events/save]))"})

(deftest a-re-frame-app
  (let [es (graph-edges (project re-frame-app))]
    (testing "a view on the events it dispatches and the subscriptions it reads"
      (is (contains? es ["src/app/views.cljs:2 app.views/email-input" "src/app/events.cljs:2 rf/reg-event-db ::set-email"]))
      (is (contains? es ["src/app/views.cljs:2 app.views/email-input" "src/app/subs.cljs:2 rf/reg-sub ::email"]))
      (is (contains? es ["src/app/views.cljs:5 app.views/save-button" "src/app/events.cljs:3 rf/reg-event-fx ::save"])))
    (testing "an event on the event it dispatches"
      (is (contains? es ["src/app/events.cljs:3 rf/reg-event-fx ::save" "src/app/events.cljs:4 rf/reg-event-db ::saved"])))
    (testing "a form outside meta-source.edn's sources adds no edge: there the keyword may be something else"
      (is (not-any? #(re-find #"^dev/" (first %)) es)))))

(deftest no-meta-source-edn-no-edges
  (is (empty? (graph-edges (project (dissoc re-frame-app "meta-source.edn"))))))
