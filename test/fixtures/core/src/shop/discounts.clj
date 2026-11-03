(ns shop.discounts)

;; destructures a key its contract doesn't read
(defn ^{:meta-source/id :fn/apply-discount} apply-discount
  [{:order/keys [lines] :user/keys [id phone]}]
  {:order/discount (if (and id phone) (count lines) 0)})
