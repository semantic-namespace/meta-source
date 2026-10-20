(ns shop.events
  (:require [re-frame.core :as rf]))

(rf/reg-event-db ::initialize
  (fn [_ _] {:order/lines []}))

(rf/reg-event-db ::set-email
  (fn [db [_ email]] (assoc db :user/email email)))

(rf/reg-event-fx ::checkout
  (fn [{:keys [db]} _]
    {:db         (assoc db :order/state :paying)
     :http-xhrio {:method     :post
                  :uri        "/orders"
                  :on-success [::paid]
                  :on-failure [::payment-failed]}}))

(rf/reg-event-fx ::paid
  (fn [{:keys [db]} _]
    {:db (assoc db :order/lines [])
     :fx [[:dispatch [::show-receipt]]]}))

(rf/reg-event-db ::show-receipt
  (fn [db _] (assoc db :order/state :paid)))
