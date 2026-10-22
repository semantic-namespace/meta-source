(ns shop.price)

(defn ^{:meta-source/id :fn/price} price
  "The order's total from its lines."
  [lines]
  (reduce + (map :line/amount lines)))

(defn ^{:meta-source/id :fn/round-cents} round-cents
  [amount]
  (/ (Math/round (* 100.0 amount)) 100.0))
