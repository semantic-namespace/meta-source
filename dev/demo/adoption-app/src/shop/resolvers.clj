(ns shop.resolvers
  (:require [com.wsscode.pathom3.connect.operation :as pco]))

(pco/defresolver user-by-email [{:user/keys [email]}]
  {::pco/input  [:user/email]
   ::pco/output [:user/id :user/name]}
  {:user/id (random-uuid) :user/name "someone"})

(def user-properties [:user/id :user/name :user/email])

(pco/defresolver user-by-id [{:user/keys [id]}]
  {::pco/output user-properties}
  {})

(pco/defresolver order-total [{:order/keys [lines]}]
  {:order/total (reduce + (map :qty lines))})

(pco/defresolver user-discount [{:user/keys [loyalty-tier]}]
  {:order/discount (if (= loyalty-tier :gold) 10 0)})

(pco/defmutation place-order! [env {:user/keys [id] :order/keys [lines]}]
  {::pco/params [:user/id :order/lines]
   ::pco/output [:order/id]}
  {:order/id (random-uuid)})
