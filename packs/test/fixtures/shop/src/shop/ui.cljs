(ns shop.ui
  (:require [re-frame.core :as rf] [reitit.frontend.easy :as rfe]))

(rf/reg-fx :notify (fn [msg] (js/alert msg)))

(rf/reg-event-db ::set-email
  (fn [db [_ email]] (assoc-in db [:user/email] email)))

(rf/reg-event-fx ::checkout
  (fn [{:keys [db]} _]
    {:db (assoc db :order/state :paying)
     :http-xhrio {:method :post :uri "/orders" :on-success [::paid]}
     ;; ::pay is not registered: ::paid is
     :dispatch [::pay]}))

(rf/reg-event-fx ::paid
  (fn [_ _] {:fx [[:notify "paid"] [:dispatch [::clear-cart]]]}))

(rf/reg-event-db ::clear-cart (fn [db _] (dissoc db :order/lines)))

;; registered, dispatched from nowhere
(rf/reg-event-db ::log-out (fn [db _] (dissoc db :user/email)))

(rf/reg-sub ::email (fn [db _] (:user/email db)))
(rf/reg-sub ::lines (fn [db _] (get db :order/lines)))
(rf/reg-sub ::line-count :<- [::lines] (fn [lines _] (count lines)))

(defn checkout-button []
  [:button {:style #js {:color "green"}
            :on-click #(rf/dispatch [::checkout])}
   "Pay " @(rf/subscribe [::line-cuont]) " items to " @(rf/subscribe [::email])])

(defn email-input []
  [:input {:on-change #(rf/dispatch [::set-email (.. % -target -value)])}])

(defn user-link [id]
  [:a {:href (rfe/href :shop.routes/user {:id id})} "user"])

;; :shop.routes/userz names no route
(defn users-link []
  [:a {:href (rfe/href :shop.routes/userz)} "users"])
