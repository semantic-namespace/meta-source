(ns shop.views
  (:require [re-frame.core :as rf]
            [shop.events :as events]
            [shop.subs :as subs]))

(defn email-input []
  [:input {:value     @(rf/subscribe [::subs/email])
           :on-change #(rf/dispatch [::events/set-email (.. % -target -value)])}])

(defn checkout-button []
  [:button {:on-click #(rf/dispatch [::events/checkout])}
   "Pay for " @(rf/subscribe [::subs/line-cuont]) " items"])

(defn main []
  (rf/dispatch-sync [::events/initialize])
  [:div [email-input] [checkout-button]])
