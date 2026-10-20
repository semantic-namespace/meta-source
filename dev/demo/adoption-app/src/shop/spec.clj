(ns shop.spec
  (:require [clojure.spec.alpha :as s]))

(s/def :user/id uuid?)
(s/def :user/email (s/and string? #(re-find #"@" %)))
(s/def :user/name string?)
(s/def :user/loyalty-tier #{:gold :silver})
(s/def :order/id uuid?)
(s/def :order/lines (s/coll-of map?))
(s/def :order/discount number?)

(s/def ::user (s/keys :req [:user/id :user/email] :opt [:user/name :user/emial]))
