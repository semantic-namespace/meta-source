(ns shop.handlers)

(defn ^{:meta-source/id :fn/register-user} register-user
  [{:user/keys [email name phone]}]
  {:user/id (random-uuid) :user/email email :user/name name :user/phone phone})

(defn ^{:meta-source/id :fn/place-order} place-order
  [{:user/keys [id] :order/keys [lines] :as ctx}]
  {:order/id (random-uuid)
   :order/total (- (reduce + (map :qty lines)) (:order/discount ctx 0))})

;; the meta key, and nothing else says anything about it yet
(defn ^{:meta-source/id :fn/cancel-order} cancel-order [{:order/keys [id]}]
  {:order/id id})
