(ns shop.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::email (fn [db _] (:user/email db)))
(rf/reg-sub ::lines (fn [db _] (:order/lines db)))
(rf/reg-sub ::line-count :<- [::lines] (fn [lines _] (count lines)))
