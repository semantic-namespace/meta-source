(ns shop.notes
  (:require [shop.scheduler :refer [defjob]]))

;; REPL scratch: nothing inside is read
(comment
  (defjob scratch {:job/schedule "* * * * *"} nil))
