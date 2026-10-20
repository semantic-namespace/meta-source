(ns shop.spec
  (:require [clojure.spec.alpha :as s]))

(s/def :user/email (s/and string? #(re-find #"@" %)))
(s/def :order/lines (s/coll-of map?))
(s/def :order/state #{:paying :paid})
