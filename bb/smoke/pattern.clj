(ns smoke.pattern
  (:require [smoke.support :as s :refer [check problems]]
            [meta-source.hover :as hover]
            [meta-source.read]
            [meta-source.index :as index]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def reitit-unnamed
  "The Reitit pack, naming none of the edges it is involved in."
  (-> @(requiring-resolve 'meta-source.pack.reitit/pack)
      (dissoc :pack/inexact)
      (assoc :pack/id :reitit-unnamed)))

(defn run []
 (let [{:keys [cfg]} @s/shop]
;; ---- reach: routes built by a component, and what they depend on, transitively
    (let [dir (str (fs/create-temp-dir))
          files {"resources/system.edn"
                 (str "{:app.routes/drivers {:user-service #ig/ref :core.services/user}\n"
                      " :core.services/user {:repo #ig/ref :app.repos/user}\n"
                      " :app.repos/user {:node #ig/ref :infra.db/xtdb}\n"
                      " :infra.db/xtdb {}}\n")
                 "src/app/auth.clj"
                 (str "(ns app.auth)\n"
                      "(defn ->auth-routes [svc]\n"
                      "  [[\"/login\" {:name :auth/login :get {:handler (fn [_] (svc))}}]])\n")
                 "src/app/routes.clj"
                 (str "(ns app.routes (:require [integrant.core :as ig] [reitit.ring :as ring] [app.auth :as auth]))\n"
                      "(defmethod ig/init-key :app.routes/drivers [_ {:keys [user-service]}]\n"
                      "  (ring/router [(auth/->auth-routes user-service)]))\n")
                 "src/app/fns.clj"
                 (str "(ns app.fns)\n"
                      "(defn ^{:meta-source/id :fn/b} b [] 1)\n"
                      "(defn ^{:meta-source/id :fn/a} a [] (b))\n")
                 "rules/reach.edn"
                 (str "{:rule/routes-no-db {:doc \"No route reaches the database\"\n"
                      "                     :when {:kind :reitit} :require [{:reaches {:none {:id :infra.db/xtdb}}}]}\n"
                      " :rule/routes-no-db-by-entries {:when {:kind :reitit}\n"
                      "                                :require [{:reaches {:via #{:deps} :none {:id :infra.db/xtdb}}}]}\n"
                      " :rule/a-calls-b {:when {:id :fn/a} :require [{:calls {:some {:id :fn/b}}}]}\n"
                      " :rule/b-calls-nothing {:when {:id :fn/b} :require [{:called-by {:none {:id :fn/a}}}]}\n"
                      " :rule/bad-reach {:when {:id :fn/a} :require [{:reaches {:via #{:nope} :some {:id :fn/b}}}]}\n"
                      " :rule/db-reached-by-no-route {:doc \"No route reaches the database\"\n"
                      "                               :when {:id :infra.db/xtdb} :require [{:reached-by {:none {:kind :reitit}}}]}\n"
                      " :rule/routes-handed-services {:when {:kind :reitit} :require [{:handed {:all {:id-ns \"core\"}}}]}\n"
                      " :rule/routes-handed-no-db {:when {:kind :reitit} :require [{:handed {:some {:id :infra.db/xtdb}}}]}\n"
                      " :rule/routes-have-roles {:when {:kind :reitit} :require [{:has-in [:reitit/data :auth/roles]}]}\n"
                      " :rule/bad-has-in {:when {:kind :reitit} :require [{:has-in :auth/roles}]}}\n")}]
      (doseq [[p text] files] (fs/create-dirs (fs/parent (str dir "/" p))) (spit (str dir "/" p) text))
      (let [idx (index/build {:root dir :src ["src"] :registry [] :rules ["rules"]
                              :packs {:integrant {:configs ["resources"]} :reitit {}}})
            e (index/edges idx)
            violated (fn [rule] (filter #(= rule (:rule %)) (problems idx :rule-violated)))]
        (check "reach: a route built in a fn a component's init-key calls is provided by that component"
               (= #{:app.routes/drivers} (get-in e [:provided-by :auth/login])))
        (check "reach: a linked var calling another's is a call between their entities"
               (= #{:fn/b} (get-in e [:calls :fn/a])))
        (check "reach: :reaches follows provided-by and refs, and names the path"
               (= [[:auth/login [:core.services/user :app.repos/user :infra.db/xtdb]]]
                  (for [f (violated :rule/routes-no-db)] [(:dev-id f) (get (:paths f) :infra.db/xtdb)])))
        (check "reach: :via narrows what :reaches follows"
               (empty? (violated :rule/routes-no-db-by-entries)))
        (check "reach: :calls and :called-by as relations"
               (and (empty? (violated :rule/a-calls-b)) (= [:fn/b] (map :dev-id (violated :rule/b-calls-nothing)))))
        (check "reach: an unknown :via is a rule not understood"
               (some #(= :rule/bad-reach (:rule %)) (problems idx :rule-not-understood)))
        (check "reached-by: what depends on an entity, transitively, with the path from each to it"
               (let [r (index/reached-by idx :infra.db/xtdb)]
                 ;; through what its builder hands it, not through the builder
                 (and (= [:auth/login :core.services/user :app.repos/user :infra.db/xtdb]
                         (:path (first (filter #(= :auth/login (:dev-id %)) (:reached-by r)))))
                      (= [:app.repos/user] (map :dev-id (take 1 (:reached-by r)))))))
        (check "reached-by: as a rule, the offenders and the path from each"
               (= [[:infra.db/xtdb [:auth/login] [:auth/login :core.services/user :app.repos/user :infra.db/xtdb]]]
                  (for [f (violated :rule/db-reached-by-no-route)] [(:dev-id f) (:offenders f) (get (:paths f) :auth/login)])))
        (check "handed: one hop, what the builder hands a route"
               (and (empty? (violated :rule/routes-handed-services))
                    (= [:auth/login] (map :dev-id (violated :rule/routes-handed-no-db)))))
        (check "has-in: a value at a path; a path that isn't a vector is a rule not understood"
               (and (= [:auth/login] (map :dev-id (violated :rule/routes-have-roles)))
                    (some #(= :rule/bad-has-in (:rule %)) (problems idx :rule-not-understood))))
        (check "reach: the brief line shows the path to what is reached"
               (some #(re-find #"No route reaches the database: :auth/login \(:core.services/user → :app.repos/user → :infra.db/xtdb\)" %)
                     (hover/brief idx))))
      (fs/delete-tree dir))

;; ---- reach: a route depends on what its builder hands it, not all the builder holds
    (let [reached (fn [routes-clj]
                    (let [dir (str (fs/create-temp-dir))
                          files {"resources/system.edn"
                                 (str "{:app.routes/drivers {:user-service #ig/ref :svc/user :article-service #ig/ref :svc/article}\n"
                                      " :svc/user {} :svc/article {}}\n")
                                 "src/app/auth.clj" "(ns app.auth)\n(defn ->auth-routes [svc]\n  [[\"/login\" {:name :auth/login :get {:handler (fn [_] (svc))}}]])\n"
                                 "src/app/home.clj" "(ns app.home)\n(defn ->home-routes []\n  [[\"/\" {:name :home/get :get {:handler (fn [_] 1)}}]])\n"
                                 "src/app/routes.clj" (str "(ns app.routes (:require [integrant.core :as ig] [reitit.ring :as ring] [app.auth :as auth] [app.home :as home]))\n"
                                                           routes-clj)}]
                      (doseq [[p text] files] (fs/create-dirs (fs/parent (str dir "/" p))) (spit (str dir "/" p) text))
                      (try (let [idx (index/build {:root dir :src ["src"] :registry [] :packs {:integrant {:configs ["resources"]} :reitit {}}})]
                             {:login (set (map :dev-id (:reaches (index/reach idx :auth/login))))
                              :home (set (map :dev-id (:reaches (index/reach idx :home/get))))
                              :login-not-exact (set (map :pattern (:not-exact (index/reach idx :auth/login))))})
                           (finally (fs/delete-tree dir)))))
          ;; what a route is claimed to reach, and why it isn't exact
          claimed #(select-keys % [:login :login-not-exact])]
      (let [r (reached (str "(defmethod ig/init-key :app.routes/drivers [_ {:keys [user-service article-service]}]\n"
                            "  (ring/router [(auth/->auth-routes user-service) (home/->home-routes)]))\n"))]
        (check "reach: a route reaches what its builder is handed, not all the component holds"
               (= #{:svc/user} (:login r)))
        (check "reach: a builder handed nothing gives its routes nothing"
               (= #{} (:home r))))
      (check "reach: the whole config used in a call, even as (:user-service config): nothing claimed, and said not exact (I5)"
             (= {:login #{} :login-not-exact #{:pattern/integrant-destructure}}
                (claimed (reached (str "(defmethod ig/init-key :app.routes/drivers [_ config]\n"
                                      "  (ring/router [(auth/->auth-routes (:user-service config))]))\n")))))
      (check "reach: a value an as-> binds isn't followed: nothing claimed, and said not exact, never a silent nothing"
             (= {:login #{} :login-not-exact #{:pattern/integrant-destructure}}
                (claimed (reached (str "(defmethod ig/init-key :app.routes/drivers [_ {:keys [user-service]}]\n"
                                      "  (as-> user-service svc (ring/router [(auth/->auth-routes svc)])))\n")))))
      (check "reach: {svc :user-service} destructuring names the key"
             (= #{:svc/user} (:login (reached (str "(defmethod ig/init-key :app.routes/drivers [_ {svc :user-service}]\n"
                                                    "  (ring/router [(auth/->auth-routes svc)]))\n")))))
      (check "reach: the whole config handed on: nothing claimed, and said not exact"
             (= {:login #{} :login-not-exact #{:pattern/integrant-destructure}} (claimed (reached (str "(defmethod ig/init-key :app.routes/drivers [_ config]\n"
                                                                 "  (ring/router [(auth/->auth-routes config)]))\n")))))
      (check "reach: a value rebound inside isn't followed: nothing claimed, and said not exact"
             (= {:login #{} :login-not-exact #{:pattern/integrant-destructure}} (claimed (reached (str "(defmethod ig/init-key :app.routes/drivers [_ {:keys [user-service]}]\n"
                                                                 "  (let [svc user-service] (ring/router [(auth/->auth-routes svc)])))\n"))))))

    ;; ---- runtime snapshots of an integrant system and a reitit router, read
    ;; beside the files (written here as the snapshot! fns write them)
    (let [dir (str (fs/create-temp-dir))]
      (doseq [[p text] {"resources/config.edn" "{:app/h {:log #ig/ref :app/logger :db #ig/ref :app/db}\n :app/db {}\n :app/old {}}\n"
                        "src/app/routes.clj" (str "(ns app.routes (:require [reitit.ring :as ring]))\n"
                                                  "(def routes [[\"/users\" {:name :api/users :get {:handler identity}}]])\n")
                        "src/app/ui.cljs" "(ns app.ui (:require [reitit.frontend.easy :as rfe]))\n(defn a [] (rfe/href :page/about))\n"
                        ".meta-source/packs/integrant.edn"
                        (pr-str {:pack :integrant :written 1
                                 :entities {:app/h {:integrant/in-system true :integrant/refs [:app/db :app/logger :app/metrics]}
                                            :app/db {:integrant/in-system true}
                                            ;; a module added it: no file configures it
                                            :app/logger {:integrant/in-system true}
                                            :app/metrics {:integrant/in-system true}}})
                        ".meta-source/packs/reitit.edn"
                        (pr-str {:pack :reitit :written 1
                                 :entities {:api/users {:reitit/path "/api/users" :reitit/methods [:get]}
                                            ;; built by code the pack couldn't read
                                            :page/about {:reitit/path "/about"}}})}]
        (fs/create-dirs (fs/parent (str dir "/" p))) (spit (str dir "/" p) text))
      (let [idx (index/build {:root dir :src ["src"] :registry [] :packs {:integrant {:configs ["resources"]} :reitit {}}})
            by (fn [problem] (set (map :dev-id (problems idx problem))))]
        (check "snapshot: a key only the running system has is defined; a ref the system has and the file doesn't is a difference"
               (and (empty? (by :ref-to-undefined))
                    (contains? (by :runtime-only) :app/logger)
                    (= {:integrant/refs {:only-at-runtime [:app/metrics]}}
                       (:diff (first (filter #(= :app/h (:dev-id %)) (problems idx :runtime-disagrees)))))))
        (check "snapshot: a configured key the running system doesn't have"
               (contains? (by :not-in-runtime) :app/old))
        (check "snapshot: a route only the running router has defines the name a link uses; a path that differs is a difference"
               (and (empty? (by :route-not-defined))
                    (= {:reitit/path {:code "/users" :runtime "/api/users"}}
                       (:diff (first (filter #(= :api/users (:dev-id %)) (problems idx :runtime-disagrees))))))))
      (fs/delete-tree dir))

    ;; ---- examples/billing: the pattern, the facts and the rules; clean, and
    ;; each rule catching the defect planted for it
    (let [planted (fn [edits]
                    (let [dir (str (fs/create-temp-dir))]
                      (fs/copy-tree "examples/billing" dir {:replace-existing true})
                      (doseq [[f from to] edits]
                        (let [p (str dir "/" f) text (slurp p)]
                          (when-not (str/includes? text from) (throw (ex-info "edit not found" {:f f :from from})))
                          (spit p (str/replace text from to))))
                      (try (let [idx (index/build {:root dir :src ["src"] :registry ["registry"] :rules ["rules"]
                                                   :packs {:integrant {:configs ["resources"] :pattern true} :reitit {:pattern true}}})]
                             (set (for [p (index/check idx)] [(:problem p) (or (:rule p) (:pattern p)) (:dev-id p)])))
                           (finally (fs/delete-tree dir)))))]
      (check "example: the billing app follows the pattern and its rules: nothing to report"
             (empty? (planted [])))
      (check "example: users routes handed billing: a route outside billing reaches Stripe"
             (let [r (planted [["src/app/system.clj" "(users/routes users)" "(users/routes users billing)"]
                               ["src/app/users.clj" "(defn routes [users]" "(defn routes [users billing]"]
                               ["src/app/users.clj" "(get-user users)" "(get-user [users billing])"]])]
               (and (contains? r [:rule-violated :rule/billing-boundary :api.users/get])
                    (contains? r [:rule-violated :rule/stripe-reached-only-from-billing :app/stripe]))))
      (check "example: a route reaching payment data with no roles"
             (contains? (planted [["src/app/billing.clj" ":auth/roles #{:billing-admin}" ""]])
                        [:rule-violated :rule/pci-guarded :api.billing/charge]))
      (check "example: public routes handed the database: off the db rule, and an API route handed a foundation component"
             (let [r (planted [["resources/system.edn" ":app/router  {" ":app/router  {:db #ig/ref :app/db "]
                               ["src/app/system.clj" "{:keys [users billing]}" "{:keys [users billing db]}"]
                               ["src/app/system.clj" "(public/routes)" "(public/routes db)"]
                               ["src/app/public.clj" "(defn routes []" "(defn routes [db]"]
                               ["src/app/public.clj" ":handler health" ":handler (partial health db)"]])]
               (and (contains? r [:rule-violated :rule/public-off-db :public/health])
                    (contains? r [:rule-violated :rule/api-through-services :public/health]))))
      (check "example: the router handing on its whole config leaves the pattern"
             (contains? (planted [["src/app/system.clj" "[_ {:keys [users billing]}]\n  (ring/router [(public/routes) (users/routes users) (billing/routes billing)])"
                                   "[_ config]\n  (ring/router [(public/routes) (users/routes (:users config)) (billing/routes (:billing config))])"]])
                        [:off-pattern :pattern/integrant-destructure :app/router])))

    ;; ---- the pattern (docs/pattern.md): :off-pattern, when a pack's options ask
    (let [pat (index/build (-> cfg (assoc-in [:packs :reitit] {:pattern true}) (assoc-in [:packs :integrant :pattern] true)))
          off (fn [idx] (sort (map (juxt :pattern :dev-id :line) (map #(merge % (select-keys (:definition %) [:line])) (problems idx :off-pattern)))))]
      (check "pattern: off by default, a child route written as code is a form not read"
             (and (empty? (problems (index/build (assoc-in cfg [:packs :reitit] {})) :off-pattern))
                  (seq (problems (index/build (assoc-in cfg [:packs :reitit] {})) :pack-form-not-read))))
      (check "pattern: with :pattern true, a route tree's code child and an unnamed handler route leave it"
             (and (= [[:pattern/reitit-literal-tree nil 14] [:pattern/reitit-named-route :shop.routes/route.post.api.login 21]] (off pat))
                  (not-any? #(= :reitit (:pack %)) (problems pat :pack-form-not-read)))))
    (let [dir (str (fs/create-temp-dir))
          files {"resources/config.edn" "{:app/router {:users #ig/ref :app/users :db #ig/ref :app/db}\n :app/users {}\n :app/db {}\n :app/api {:users #ig/ref :app/users}}\n"
                 "src/app/system.clj" (str "(ns app.system (:require [integrant.core :as ig] [app.routes :as r]))\n"
                                           "(defmethod ig/init-key :app/router [_ config] (r/router config))\n"
                                           "(defmethod ig/init-key :app/api [_ {:keys [users]}] (r/api users))\n"
                                           "(defmethod ig/init-key :app/users [_ _] {})\n(defmethod ig/init-key :app/db [_ _] {})\n")
                 "src/app/routes.clj" (str "(ns app.routes (:require [reitit.ring :as ring] [app.users :as users]))\n"
                                           "(defn router [cfg] (ring/router [[\"/u\" {:name ::u :get {:handler (fn [_] cfg)}}]]))\n"
                                           "(defn api [us] (ring/router [[\"/a\" {:name ::a :get {:handler (users/get-user us)}}]\n"
                                           "                            [\"/b\" {:name ::b :get {:handler (partial users/list-users us)}}]]))\n")
                 "src/app/users.clj" "(ns app.users)\n(defn get-user [us] (fn [_] us))\n(defn list-users [us _] us)\n"}]
      (doseq [[p text] files] (fs/create-dirs (fs/parent (str dir "/" p))) (spit (str dir "/" p) text))
      (let [idx (index/build {:root dir :src ["src"] :registry [] :packs {:integrant {:configs ["resources"] :pattern true} :reitit {:pattern true}}})
            off (sort (map (juxt :pattern :dev-id) (problems idx :off-pattern)))]
        (check "pattern: an init-key handing on its whole config, and an inline fn handler, leave it; handlers built by a call don't"
               (= [[:pattern/integrant-destructure :app/router] [:pattern/reitit-handler-var :app.routes/u]] off))
        (check "reitit: a handler built by a call, or by partial, names the fn building it"
               (and (= '[app.users/get-user] (get-in idx [:entities :app.routes/a :entry :reitit/handler-built-by]))
                    (= '[app.users/list-users] (get-in idx [:entities :app.routes/b :entry :reitit/handler-built-by]))
                    (not (get-in idx [:entities :app.routes/a :entry :reitit/handler-code])))))
      (fs/delete-tree dir))

    ;; ---- the pattern, R4: components reach routes only through route fns; what
    ;; wraps them otherwise is off the pattern, said, never guessed nor passed
    (let [reach-of (fn reach-of
                     ([files] (reach-of files {:integrant {:configs ["resources"] :pattern true} :reitit {}}))
                     ([files packs]
                     (let [dir (str (fs/create-temp-dir))]
                       (doseq [[p text] files] (fs/create-dirs (fs/parent (str dir "/" p))) (spit (str dir "/" p) text))
                       (try (let [idx (index/build {:root dir :src ["src"] :registry [] :rules ["rules"]
                                                    :packs packs})]
                              {:reach (fn [id] (let [r (index/reach idx id)]
                                                 {:claimed (set (map :dev-id (:reaches r)))
                                                  :not-exact (set (map (juxt :pattern :at) (:not-exact r)))}))
                               :off (set (map (juxt :pattern :dev-id) (problems idx :off-pattern)))
                               :unverifiable (set (map (juxt :rule :dev-id) (problems idx :rule-unverifiable)))
                               :violated (set (map (juxt :rule :dev-id) (problems idx :rule-violated)))
                               :lost (into {} (map (juxt (juxt :pattern :dev-id) :lost)) (problems idx :off-pattern))})
                            (finally (fs/delete-tree dir))))))
          ;; a policy over the routes: never passed for a route it can't check
          off-db {"rules/policy.edn" "{:rule/off-db {:when {:kind :reitit} :require [{:reaches {:none {:id :svc/db}}}]}}\n"}
          builders {"src/app/auth.clj" "(ns app.auth)\n(defn ->auth-routes [svc]\n  [[\"/login\" {:name :auth/login :get {:handler (fn [_] (svc))}}]])\n"
                    "src/app/home.clj" "(ns app.home)\n(defn ->home-routes []\n  [[\"/\" {:name :home/get :get {:handler (fn [_] 1)}}]])\n"
                    "src/app/art.clj" "(ns app.art)\n(defn ->art-routes [svc]\n  [[\"/articles\" {:name :art/list :get {:handler (fn [_] (svc))}}]])\n"}]
      ;; conduit: a router component takes every route (a refset on a key the
      ;; route component derives from) and wraps them with middleware built
      ;; from its own deps
      (let [conduit (merge builders off-db
                                   {"resources/system.edn"
                                    (str "{:app.routes/drivers {:user-service #ig/ref :svc/user :article-service #ig/ref :svc/article}\n"
                                         " :infra.router/core {:routes #ig/refset :app/routes :session-store #ig/ref :svc/session}\n"
                                         " :svc/user {} :svc/article {} :svc/session {}}\n")
                                    "src/app/routes.clj"
                                    (str "(ns app.routes (:require [integrant.core :as ig] [app.auth :as auth] [app.home :as home] [app.art :as art]))\n"
                                         "(defmethod ig/init-key :app.routes/drivers [_ {:keys [user-service article-service]}]\n"
                                         "  [(home/->home-routes) (auth/->auth-routes user-service) (art/->art-routes article-service)])\n"
                                         "(derive :app.routes/drivers :app/routes)\n")
                                    "src/app/router.clj"
                                    (str "(ns app.router (:require [integrant.core :as ig] [reitit.ring :as ring]))\n"
                                         "(defn wrap-session [store] (fn [h] h))\n"
                                         "(defmethod ig/init-key :infra.router/core [_ {:keys [routes session-store]}]\n"
                                         "  (ring/router routes {:data {:middleware [(wrap-session session-store)]}}))\n")})
            {:keys [reach off unverifiable]} (reach-of conduit)
            unnamed (reach-of conduit {:integrant {:configs ["resources"] :pattern true} 'smoke.pattern/reitit-unnamed {}})]
        (check "pattern R4b: a component taking routes and holding another (a middleware's) is off the pattern"
               (contains? off [:pattern/reitit-router-holds-routes :infra.router/core]))
        (check "pattern R4b: its routes claim what their route fn is handed, and say what isn't exact"
               (and (= {:claimed #{} :not-exact #{[:pattern/reitit-router-holds-routes :infra.router/core]}} (reach :home/get))
                    (= #{:svc/user} (:claimed (reach :auth/login)))))
        (check "pattern R4b: a policy over those routes is unverifiable, not passed"
               (= #{[:rule/off-db :home/get] [:rule/off-db :auth/login] [:rule/off-db :art/list]} unverifiable))
        (check "an edge no pack names (:pack/inexact) is still off the pattern, under its reason, saying what is lost"
               (and (contains? (:off unnamed) [:inexact/holder-wraps :infra.router/core])
                    (= #{[:inexact/holder-wraps :infra.router/core]} (:not-exact ((:reach unnamed) :home/get)))
                    (str/includes? (get (:lost unnamed) [:inexact/holder-wraps :infra.router/core]) "may wrap it")
                    (= #{[:rule/off-db :home/get] [:rule/off-db :auth/login] [:rule/off-db :art/list]} (:unverifiable unnamed)))))
      ;; tubo: the builder's own init-key uses its config outside the
      ;; builder's call, in a middleware: every route it builds may get it
      (let [{:keys [reach off unverifiable]} (reach-of (merge builders off-db
                                   {"resources/system.edn"
                                    "{:app.routes/handler {:datasource #ig/ref :svc/db :user-service #ig/ref :svc/user}\n :svc/db {} :svc/user {}}\n"
                                    "src/app/routes.clj"
                                    (str "(ns app.routes (:require [integrant.core :as ig] [reitit.ring :as ring] [app.auth :as auth] [app.home :as home]))\n"
                                         "(defn wrap-datasource [h ds] h)\n"
                                         "(defmethod ig/init-key :app.routes/handler [_ {:keys [datasource user-service]}]\n"
                                         "  (ring/ring-handler (ring/router [(home/->home-routes) (auth/->auth-routes user-service)])\n"
                                         "                     {:middleware [[wrap-datasource datasource]]}))\n")}))]
        (check "pattern R4a: a builder using its config outside the route fns' calls (a middleware) is off the pattern"
               (contains? off [:pattern/reitit-hands-route-fns :app.routes/handler]))
        (check "pattern R4a: its routes say they aren't exact, and a policy over them is unverifiable"
               (and (= #{[:pattern/reitit-hands-route-fns :app.routes/handler]} (:not-exact (reach :home/get)))
                    (= #{[:rule/off-db :home/get] [:rule/off-db :auth/login]} unverifiable))))
      ;; routes written in the init-key itself, in a router call: exact when
      ;; it uses no config, off the pattern when an inline handler could
      ;; close over some
      (let [inline (fn [params uses]
                     (reach-of (merge off-db
                                      {"resources/system.edn" "{:app/router {:db #ig/ref :svc/db}\n :svc/db {}}\n"
                                       "src/app/router.clj"
                                       (str "(ns app.router (:require [integrant.core :as ig] [reitit.ring :as ring]))\n"
                                            "(defmethod ig/init-key :app/router [_ " params "]\n"
                                            "  (ring/router [[\"/ping\" {:name :app/ping :get {:handler (fn [_] " uses ")}}]]))\n")})))
            quiet (inline "_" "1")
            uses (inline "{:keys [db]}" "db")]
        (check "pattern R4: routes written in an init-key that uses no config: provided by it, and exact"
               (and (= {:claimed #{} :not-exact #{}} ((:reach quiet) :app/ping)) (empty? (:unverifiable quiet))))
        (check "pattern R4: written in one that uses its config: off the pattern, and a policy over them unverifiable"
               (and (contains? (:off uses) [:pattern/reitit-routes-in-route-fns :app/router])
                    (= #{[:rule/off-db :app/ping]} (:unverifiable uses)))))
      ;; on the pattern: the same policy is checked, and holds or breaks
      (let [{:keys [reach off unverifiable violated]}
            (reach-of (merge builders off-db
                             {"resources/system.edn"
                              "{:app.routes/drivers {:user-service #ig/ref :svc/user :article-service #ig/ref :svc/article}\n :svc/user {:db #ig/ref :svc/db} :svc/article {} :svc/db {}}\n"
                              "src/app/routes.clj"
                              (str "(ns app.routes (:require [integrant.core :as ig] [reitit.ring :as ring] [app.auth :as auth] [app.home :as home] [app.art :as art]))\n"
                                   "(defmethod ig/init-key :app.routes/drivers [_ {:keys [user-service article-service]}]\n"
                                   "  (ring/router [(home/->home-routes) (auth/->auth-routes user-service) (art/->art-routes article-service)]))\n")}))]
        (check "pattern: on it, nothing is off, every answer exact, and the policy breaks only where a route reaches the db"
               (and (empty? off) (empty? unverifiable)
                    (= #{[:rule/off-db :auth/login]} violated)
                    (= {:claimed #{} :not-exact #{}} (reach :home/get))))))))
