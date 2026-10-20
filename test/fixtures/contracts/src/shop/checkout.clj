(ns shop.checkout
  (:require [clojure.spec.alpha :as s]
            [malli.core :as m]))

(s/def :order/id uuid?)
(s/def :order/lines (s/coll-of map?))
(s/def :order/discount number?)
(s/def :order/total number?)
(s/def :user/loyalty-tier #{:gold :silver})
(s/def ::order (s/keys :req [:order/id :order/lines] :opt [:order/discount]))
(s/def ::receipt (s/keys :req [:order/id :order/total]))

;; reads :user/loyalty-tier, which its :args spec doesn't name
(defn total [order]
  {:order/id (:order/id order)
   :order/total (- (count (:order/lines order))
                   (:order/discount order 0)
                   (if (= :gold (:user/loyalty-tier order)) 1 0))})

(s/fdef total
  :args (s/cat :order ::order)
  :ret ::receipt)

;; within its contract: every key it reads is in ::order
(defn discounted? [order] (pos? (:order/discount order 0)))

(s/fdef discounted? :args (s/cat :order ::order) :ret boolean?)

;; reads :order/lines, which its m/=> map doesn't name
(defn line-count [order] (count (:order/lines order)))

(m/=> line-count [:=> [:cat [:map :order/id :order/discount]] :int])

;; names a spec, reads no key; ::by-tier is an s/alt tag, not a spec
(defn gold? [tier] (s/valid? :user/loyalty-tier tier))

(s/fdef gold? :args (s/alt ::by-tier keyword?) :ret boolean?)
