(ns smoke.reitit
  (:require [smoke.support :as s :refer [check problems]]
            [meta-source.read]
            [meta-source.index :as index]
            [babashka.fs :as fs]))

(defn run []
    ;; ---- reitit: src/shop/routes.clj, links in src/shop/ui.cljs
    (let [{:keys [cfg]} @s/shop
          ri (index/build (assoc-in cfg [:packs :reitit] {}))
          entry #(:entry (index/where ri %))
          line #(:line (:definition (index/where ri %)))]
      (check "every route in the tree, a route def read in place, a child written as code reported"
             (and (= {:read 5 :not-read 1} (:reitit (:packs (index/summary ri))))
                  (= [{:pack :reitit :line 14 :reason "1 child route(s) written as code, not read"}]
                     (map #(select-keys % [:pack :line :reason]) (filter #(= :reitit (:pack %)) (problems ri :pack-form-not-read))))))
      (check "a named route: its path joined from its parents, handler, inherited middleware, parameter and response keys"
             (= #:reitit{:path "/api/users/:id" :ns "shop.routes" :methods [:get] :handlers ['shop.api/get-user]
                         :middleware ['shop.middleware/token-auth] :parameter-keys [:user/id]
                         :response-keys [:shop.spec/user] :linked true}
                (entry :shop.routes/user)))
      (check "middleware adds to the parent's; a project's own route data is kept"
             (and (= ['shop.middleware/token-auth 'shop.middleware/require-admin] (:reitit/middleware (entry :shop.routes/users)))
                  (= {:roles #{:admin}} (:reitit/data (entry :shop.routes/users)))))
      (check "an unnamed route is keyed by its method and path"
             (= {:public true} (:reitit/data (entry :shop.routes/route.post.api.login))))
      (check "each route is at the line its path is written on, an inlined def's at the def"
             (= [16 9 21] (map line [:shop.routes/user :shop.routes/users :shop.routes/route.post.api.login])))
      (check "a link to a route name nothing defines is info while a child route is written as code; a handler its namespace doesn't define is a warning"
             (and (= [[:shop.routes/userz 41 :info 1]] (map (juxt :dev-id (comp :line :definition) :severity :unread-trees) (problems ri :route-not-defined)))
                  (= [[:shop.routes/route.post.api.login 'shop.api/login]] (map (juxt :dev-id :calls) (problems ri :handler-not-found)))))
      (let [dir (str (fs/create-temp-dir))]
        (fs/create-dirs (str dir "/src/app"))
        (spit (str dir "/src/app/routes.clj")
              (str "(ns app.routes (:require [reitit.ring :as ring]))\n"
                   "(def router (ring/router [[\"/users\" {:name ::users :get {:handler identity}}]]))\n"))
        (spit (str dir "/src/app/ui.cljs")
              (str "(ns app.ui (:require [reitit.frontend.easy :as rfe]))\n"
                   "(defn nav [] [:a {:href (rfe/href :app.routes/users)} \"users\"]\n"
                   "  [:a {:href (rfe/href :app.routes/userz)} \"typo\"])\n"))
        (check "with every route tree read, a link to a route name nothing defines is a warning, where it is written"
               (= [[:app.routes/userz 3 :warning]]
                  (map (juxt :dev-id (comp :line :definition) :severity)
                       (problems (index/build {:root dir :src ["src"] :registry [] :packs {:reitit {}}}) :route-not-defined))))
        (fs/delete-tree dir))
      (let [dir (str (fs/create-temp-dir))]
        (fs/create-dirs (str dir "/src/app"))
        (spit (str dir "/src/app/routes.clj")
              (str "(ns app.routes (:require [reitit.middleware :as rm] [reitit.interceptor :as ri] [app.other :as other]))\n"
                   "(def mw-router (rm/router [[\"/users\" {:name ::users :handler identity}]]))\n"
                   "(def ic-router (ri/router [[\"/orders\" {:name ::orders :handler identity}]]))\n"
                   "(def own-router (other/router [[\"/carts\" {:name ::carts :handler identity}]]))\n"))
        (let [ix (index/build {:root dir :src ["src"] :registry [] :packs {:reitit {}}})
              ids (set (keys (:entities ix)))]
          (check "reitit.middleware and reitit.interceptor routers are router calls: their trees are read"
                 (and (ids :app.routes/users) (ids :app.routes/orders)))
          (check "a project's own fn named router isn't reitit's: the tree handed to it isn't read as routes"
                 (not (ids :app.routes/carts))))
        (fs/delete-tree dir))
      (check "a route consumes its parameter keys, next to resolvers and functions"
             (some #{:shop.routes/user} (map :dev-id (:consumers (index/data-key ri :user/id)))))))
