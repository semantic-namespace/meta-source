(ns smoke.integrant
  (:require [smoke.support :as s :refer [check problems]]
            [meta-source.hover :as hover]
            [meta-source.read]
            [meta-source.index :as index]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(defn run []
  (let [{:keys [cfg idx]} @s/shop]
    ;; ---- integrant: resources/*.edn and shop.system
    (let [ig #(:entry (index/where idx %))
          ig-part #(into {} (filter (fn [[k _]] (= "integrant" (namespace k)))) (ig %))]
      (check "every config component, defmethod and derive is read"
             (= {:read 13 :not-read 0} (:integrant (:packs (index/summary idx)))))
      (check "a component's refs, from #ig/ref, nested in its config"
             (= [:shop/db :shop/mailer :shop/metrics] (:integrant/refs (ig :shop/handler))))
      (check "variant files configuring one key: one entity, every file listed, :varies"
             (= #:integrant{:config-in ["resources/system.edn:2" "resources/system.test.edn:2"]
                            :env ["DATABASE_URL"] :varies true :init-key true :halt-key! true
                            :uses [[:url []]]}
                (ig-part :shop/db)))
      (check "a composite key, named by the keyword no other key uses, deriving from the rest"
             (= [:shop/http-server] (:integrant/derives (ig :shop.http/main))))
      (check "a duct profile's components"
             (= [:duct.profile/base] (:integrant/profiles (ig :shop/cache))))
      (check "a ::keyword defmethod, with the file's namespace"
             (true? (:integrant/init-key (ig :shop.system/const))))
      (check "a derive call in a file that uses integrant"
             (= [:shop.system/const] (:integrant/isa (ig :shop/base-path))))
      (check "a ref to a key no config defines"
             (= [[:shop/handler [:shop/metrics]]] (map (juxt :dev-id :keys) (problems idx :ref-to-undefined))))
      (check ":provided lists keys something else adds"
             (empty? (problems (index/build (assoc-in cfg [:packs :integrant :provided] [:shop/metrics])) :ref-to-undefined)))
      (check "an init-key for a key no config has; a composite's parent and a derived-from key count as configured"
             (= [:shop.system/unused] (map :dev-id (problems idx :init-key-without-config))))
      (check "a component an EDN contract depends on"
             (= [:shop/mailer] (map :dev-id (:depends-on (index/dependencies idx :fn/register-user)))))
      (check "a spec under a component's key is its shape, not a second thing: no collision, still a dependency"
             (and (= "map?" (:spec/form (ig :shop/mailer)))
                  (not-any? #(= :shop/mailer (:dev-id %)) (problems idx :id-in-several-packs))))
      (check "one keyword two packs declare things under: a collision"
             (= [[:shop.resolvers/seed [:integrant :pathom]]] (map (juxt :dev-id :packs) (problems idx :id-in-several-packs))))
      (check "who refers to a component"
             (= [:shop.http/main] (map :dev-id (:referenced-by (index/uses idx :shop/handler)))))
      (check "hover: a component's methods, configs and env"
             (str/includes? (:eldoc (hover/lookup idx :shop/db)) "component  refs 0  ·  halt-key! init-key  ·  in 2 configs, varies  ·  env DATABASE_URL")))

    ;; ---- calls by id (:calls-by-id): (exec :fn/x) calls the entity :fn/x
    (let [dir (str (fs/create-temp-dir))]
      (fs/create-dirs (str dir "/src/app")) (fs/create-dirs (str dir "/registry"))
      (spit (str dir "/registry/fns.edn")
            (str "{:fn/load {:fn/deps []}\n"
                 " :fn/save {:fn/deps []}\n"
                 " :fn/run {:fn/deps [:fn/load]}\n"
                 ;; a step whose entry names the init-key it runs
                 " :fn/fetch {:fn/step :app.step/fetch}}\n"))
      (spit (str dir "/src/app/core.clj")
            (str "(ns app.core (:require [integrant.core :as ig]))\n"
                 "(defn exec [k & args] k)\n"
                 "(defmethod ig/init-key :app.step/fetch [_ _] identity)\n"
                 "(defn ^{:meta-source/id :fn/load} load-impl [] 1)\n"
                 "(defn ^{:meta-source/id :fn/save} save-impl [] 2)\n"
                 ";; declared, by id; undeclared, by id; and a direct call\n"
                 "(defn ^{:meta-source/id :fn/run} run-impl []\n"
                 "  (exec :fn/load) (exec :fn/save) (save-impl))\n"
                 "(def ^{:meta-source/id :fn/fetch} fetch-impl (exec :app.step/fetch))\n"))
      (let [cfg {:root dir :src ["src"] :registry ["registry"] :packs {:integrant {}}}
            calls (fn [idx] (set (map (juxt :calls :by-id) (problems idx :undeclared-call))))
            plain (index/build cfg)
            by-id (index/build (assoc cfg :calls-by-id '[app.core/exec]))]
        (check "calls by id: without :calls-by-id only the direct call is undeclared"
               (= #{[:fn/save nil]} (calls plain)))
        (check "calls by id: with it, (exec :fn/save) is an undeclared call too; (exec :fn/load) and the step its entry names are declared"
               (= #{[:fn/save nil] [:fn/save true]} (calls by-id)))
        (check "calls by id: uses lists where it is called by id"
               (some #(and (:by-id %) (= :fn/run (:dev-id %))) (:called-from (index/uses by-id :fn/load))))
        (check "calls by id: an init-key code calls by its id is used, not unconfigured"
               (and (seq (problems plain :init-key-without-config))
                    (empty? (problems by-id :init-key-without-config)))))
      (fs/delete-tree dir))

    ;; ---- integrant: composite keys sharing their last keyword
    (let [dir (str (fs/create-temp-dir))]
      (fs/create-dirs (str dir "/resources")) (fs/create-dirs (str dir "/src/app"))
      (spit (str dir "/resources/system.edn")
            (str "{[:app/reset :app/done] {}\n"
                 " [:app/schedule :app/done] {}\n"
                 " [:app/reset :app/failed] {}\n"
                 " [:app/schedule :app/failed] {}}\n"))
      (spit (str dir "/src/app/core.clj")
            (str "(ns app.core (:require [integrant.core :as ig]))\n"
                 "(defmethod ig/init-key :app/reset [_ _] :reset)\n"
                 "(defmethod ig/init-key :app/schedule [_ _] :schedule)\n"))
      (let [ii (index/build {:root dir :src ["src"] :registry [] :packs {:integrant {:configs ["resources"]}}})]
        (check "integrant: composite keys merged into one id each make their other keywords configured"
               (empty? (problems ii :init-key-without-config))))
      (fs/delete-tree dir))

;; ---- integrant: (defmethod ig/init-key :default ...) builds every key with no method of its own
    (let [build (fn [default?]
                  (let [dir (str (fs/create-temp-dir))]
                    (fs/create-dirs (str dir "/resources")) (fs/create-dirs (str dir "/src/app"))
                    (spit (str dir "/resources/system.edn") "{:app/clock {} :app/db {:clock #ig/ref :app/clock}}\n")
                    (spit (str dir "/src/app/core.clj")
                          (str "(ns app.core (:require [integrant.core :as ig]))\n"
                               "(defn start [config] (ig/init config))\n"
                               "(defmethod ig/init-key :app/db [_ v] v)\n"
                               (when default? "(defmethod ig/init-key :default [_ v] v)\n")))
                    (try (index/build {:root dir :src ["src"] :registry [] :packs {:integrant {:configs ["resources"]}}})
                         (finally (fs/delete-tree dir)))))
          with (build true) without (build false)]
      (check "integrant: a :default init-key builds every key with no method of its own: no key is unbuilt, and the method is read"
             (and (empty? (problems with :no-init-key))
                  (empty? (problems with :init-key-without-config))
                  (not-any? #(re-find #"dispatch value :default" (str (:reason %))) (problems with :pack-form-not-read))
                  (true? (get-in (index/where with :integrant/default) [:entry :integrant/init-key]))))
      (check "integrant: without it, a key with no init-key is unbuilt"
             (= [:app/clock] (map :dev-id (problems without :no-init-key)))))

    ;; ---- integrant: a config not read stops the checks that need all of it
    (let [build (fn [config-src vars]
                  (let [dir (str (fs/create-temp-dir))]
                    (fs/create-dirs (str dir "/src/app"))
                    (spit (str dir "/src/app/core.clj")
                          (str "(ns app.core (:require [integrant.core :as ig]))\n"
                               config-src
                               "(defn start [env] (ig/init (system env)))\n"
                               "(defmethod ig/init-key :app/db [_ v] v)\n"
                               "(defmethod ig/init-key :app/web [_ v] v)\n"
                               "(defmethod ig/init-key :app/unused [_ v] v)\n"))
                    (try (index/build {:root dir :src ["src"] :registry []
                                       :packs {:integrant {:vars vars}}})
                         (finally (fs/delete-tree dir)))))
          in-fn (build "(defn system [env] (merge {:app/db {} :app/web {:db (ig/ref :app/db)}} (when env {:app/x {}})))\n"
                       '[app.core/system])
          absent (build "(def system {:app/db {} :app/web {:db (ig/ref :app/db)}})\n" '[app.core/sistem])
          as-map (build "(def system {:app/db {} :app/web {:db (ig/ref :app/db)}})\n" '[app.core/system])]
      (check "integrant: a config var not read (a fn) concludes nothing about what no config defines, and says which checks didn't run"
             (and (empty? (problems in-fn :init-key-without-config))
                  (= [{:reason "the config var's value is not a map" :file "src/app/core.clj" :line 2}]
                     (:because (first (problems in-fn :checks-not-run))))))
      (check "integrant: a config var :vars names that isn't there is a warning, and stops those checks too"
             (and (= ['app.core/sistem] (map :var (problems absent :config-var-not-found)))
                  (empty? (problems absent :init-key-without-config))
                  (seq (problems absent :checks-not-run))))
      (check "integrant: with the config read, the checks run (control)"
             (and (= [:app/unused] (map :dev-id (problems as-map :init-key-without-config)))
                  (empty? (problems as-map :checks-not-run)))))

    ;; ---- (comment ...) blocks are REPL scratch: no pack reads a form inside one
    (let [dir (str (fs/create-temp-dir))]
      (fs/create-dirs (str dir "/src/app"))
      (spit (str dir "/src/app/core.clj")
            (str "(ns app.core (:require [integrant.core :as ig]))\n"
                 "(defmethod ig/init-key :app/db [_ v] v)\n"
                 "(comment\n  (defmethod ig/init-key :app/scratch [_ v] v))\n"))
      (let [idx (try (index/build {:root dir :src ["src"] :registry [] :packs {:integrant {}}})
                     (finally (fs/delete-tree dir)))]
        (check "a form inside (comment ...) isn't read by any pack; one outside is"
               (and (index/where idx :app/db) (nil? (index/where idx :app/scratch))))))

    ;; ---- integrant: config shapes from real systems that read as refs to nothing
    (let [ig-check (fn [files]
                     (let [dir (str (fs/create-temp-dir))]
                       (doseq [[p text] files] (fs/create-dirs (fs/parent (str dir "/" p))) (spit (str dir "/" p) text))
                       (try (let [idx (index/build {:root dir :src ["src"] :registry [] :packs {:integrant {:configs ["resources"]}}})]
                              {:refs (problems idx :ref-to-undefined) :maybe (problems idx :ref-maybe-from-module)
                               :amb (problems idx :ref-ambiguous) :cycles (problems idx :ref-cycle)
                               :invalid (problems idx :ref-invalid) :unbound (problems idx :var-unbound)
                               :no-init (problems idx :no-init-key) :removed (problems idx :removed-method)
                               :unused (problems idx :init-key-without-config)
                               :ids (set (keys (:entities idx)))
                               :not-read (:not-read (:integrant (:packs (index/summary idx))))})
                            (finally (fs/delete-tree dir)))))
          ig-ns "(ns app.core (:require [integrant.core :as ig]))\n"]
      (let [r (ig-check {"resources/config.edn"
                         (str "{:app/port 3000\n"
                              " :app/system\n"
                              " {:app/db {:url \"x\"}\n"
                              "  :app/handler {:db #ig/ref :app/db}}}\n")})]
        (check "integrant: a system nested under a key of an aero config, its refs to its own keys"
               (empty? (:refs r))))
      (let [r (ig-check {"resources/config.edn" "{:app/handler {:db #ig/ref :app/db}\n :app/db {:url \"x\"}}\n"
                         "resources/wrapped.edn" "{:ig/system {:app/cache {:db #ig/ref :app/db}}}\n"})]
        (check "integrant: a top-level key holding refs to keys other configs define is a component, not a system"
               (and (contains? (:ids r) :ig/system) (not (contains? (:ids r) :app/cache)))))
      (let [r (ig-check {"resources/load.edn"
                         "{:app.etl/load {:app.etl/datasets #ig/ref :app.etl/datasets :app.etl/page-size 100}}\n"
                         "resources/config.edn" "{:app.etl/datasets {}}\n"})]
        (check "integrant: an option named like the key it refers to, defined in another config, is no nested system"
               (and (contains? (:ids r) :app.etl/load) (empty? (:refs r)))))
      ;; what ig/init throws on, each confirmed against integrant 0.13.1
      (let [amb (fn [r] (map (juxt :dev-id :keys :candidates) (:amb r)))]
        (check "integrant: a ref matching a key and a composite deriving from it is ambiguous"
               (= [[:app/h [:app/kv] [:app/kv :app/redis]]]
                  (amb (ig-check {"resources/config.edn" "{:app/kv {}\n [:app/kv :app/redis] {}\n :app/h {:kv #ig/ref :app/kv}}\n"}))))
        (check "integrant: a ref two composites match is ambiguous"
               (= [[:app/h [:app/kv] [:app/mem :app/redis]]]
                  (amb (ig-check {"resources/config.edn" "{[:app/kv :app/redis] {}\n [:app/kv :app/mem] {}\n :app/h {:kv #ig/ref :app/kv}}\n"}))))
        (check "integrant: a ref two derived keys match is ambiguous"
               (= [[:app/h [:app/store] [:app/mem :app/redis]]]
                  (amb (ig-check {"resources/config.edn" "{:app/redis {}\n :app/mem {}\n :app/h {:s #ig/ref :app/store}}\n"
                                  "src/app/store.clj" (str "(ns app.store (:require [integrant.core :as ig]))\n"
                                                           "(derive :app/redis :app/store)\n(derive :app/mem :app/store)\n"
                                                           "(defmethod ig/init-key :app/redis [_ v] v)\n")}))))
        (let [r (ig-check {"resources/config.edn" "{[:app/kv :app/redis] {}\n [:app/kv :app/mem] {}\n :app/h {:kv #ig/refset :app/kv :plugins #ig/refset :app/plugin}}\n"})]
          (check "integrant: a refset matching several is fine, and so is one matching nothing"
                 (and (empty? (amb r)) (empty? (:refs r)))))
        (check "integrant: the branches of a key a tag chooses are alternatives, not rivals"
               (empty? (amb (ig-check {"resources/config.edn"
                                       (str "{:app/handler {:email #ig/ref :app.email/service}\n"
                                            " #profile{:dev :app.email/logging :prod :app.email/sendgrid} {}}\n")
                                       "src/app/email.clj" (str "(ns app.email (:require [integrant.core :as ig]))\n"
                                                                "(derive ::logging ::service)\n(derive ::sendgrid ::service)\n"
                                                                "(defmethod ig/init-key ::sendgrid [_ v] v)\n")}))))
        (check "integrant: keys in different config files are alternatives, not rivals"
               (empty? (amb (ig-check {"resources/system.edn" "{[:app/kv :app/redis] {}\n :app/h {:kv #ig/ref :app/kv}}\n"
                                       "resources/system.test.edn" "{[:app/kv :app/mem] {}\n :app/h {:kv #ig/ref :app/kv}}\n"}))))
        (check "integrant: keys in duct profiles not active together are alternatives; base and dev are rivals"
               (and (empty? (amb (ig-check {"resources/config.edn"
                                            (str "{:duct.profile/base {:app/h {:kv #ig/ref :app/kv}}\n"
                                                 " :duct.profile/dev {[:app/kv :app/mem] {}}\n"
                                                 " :duct.profile/prod {[:app/kv :app/redis] {}}}\n")})))
                    (= [[:app/h [:app/kv] [:app/mem :app/redis]]]
                       (amb (ig-check {"resources/config.edn"
                                       (str "{:duct.profile/base {:app/h {:kv #ig/ref :app/kv} [:app/kv :app/redis] {}}\n"
                                            " :duct.profile/dev {[:app/kv :app/mem] {}}}\n")}))))))
      (let [r (ig-check {"resources/config.edn"
                         (str "{[:g/a :app/kv] {}\n [:g/b :app/kv] {}\n"
                              " :app/h {:a #ig/ref [:g/a :app/kv] :c #ig/ref [:g/c :app/kv]}}\n")})]
        (check "integrant: a composite ref matches a key deriving from all of it; one nothing matches is undefined"
               (and (empty? (:amb r)) (= [[:app/h [[:g/c :app/kv]]]] (map (juxt :dev-id :keys) (:refs r))))))
      (let [cycles (fn [files] (map :cycle (:cycles (ig-check files))))]
        (check "integrant: refs going round in a circle are a cycle, once, from its least key"
               (= [[:a/x :a/y :a/z :a/x]]
                  (cycles {"resources/config.edn" "{:a/x {:r #ig/ref :a/y}\n :a/y {:r #ig/ref :a/z}\n :a/z {:r #ig/ref :a/x}}\n"})))
        (check "integrant: a key referring to itself is a cycle"
               (= [[:a/x :a/x]] (cycles {"resources/config.edn" "{:a/x {:r #ig/ref :a/x}}\n"})))
        (check "integrant: a refset closes a cycle too"
               (= 1 (count (cycles {"resources/config.edn" "{[:s/a :a/x] {:r #ig/refset :a/y}\n :a/y {:r #ig/ref :s/a}}\n"}))))
        (check "integrant: a chain is no cycle"
               (empty? (cycles {"resources/config.edn" "{:a/x {:r #ig/ref :a/y}\n :a/y {:r #ig/ref :a/z}\n :a/z {}}\n"}))))
      ;; ---- integrant 1.0: what ig/ref, ig/init and the removed methods refuse
      (let [r (ig-check {"resources/config.edn" "{:app/pg {}\n :app/h {:db #ig/ref :app/db}}\n"
                         "src/app/db.clj" (str ig-ns "(derive :app/pg :app/sql)\n(derive :app/sql :app/db)\n"
                                               "(defmethod ig/init-key :app/db [_ v] v)\n")})]
        (check "integrant: a ref matched through two derives is defined, and the grandparent's init-key used"
               (and (empty? (:refs r)) (empty? (:unused r)) (empty? (:no-init r)))))
      (check "integrant: a derive in a namespace that doesn't use integrant counts, without defining the key"
             (let [r (ig-check {"resources/config.edn" "{:app/pg {}\n :app/h {:db #ig/ref :app/db}}\n"
                                "src/app/h.clj" "(ns app.h)\n(derive :app/pg :app/db)\n(derive :app.err/x :app.err/base)\n"})]
               (and (empty? (:refs r)) (contains? (:ids r) :app.err/x))))
      (check "integrant: a ref integrant refuses, unqualified or a vector with an unqualified keyword"
             (= [[:app/h [:db [:app/a :b]]]]
                (map (juxt :dev-id :refs) (:invalid (ig-check {"resources/config.edn" "{:app/h {:db #ig/ref :db :x #ig/ref [:app/a :b]}}\n"})))))
      (let [r (ig-check {"resources/duct.edn" "{:vars {port {:env PORT}}\n :system {:app/server {:port #ig/var port :host #ig/var host}}}\n"
                         "src/app/server.clj" (str ig-ns "(defmethod ig/init-key :app/server [_ v] v)\n")})]
        (check "integrant: Duct's :system is read, and an #ig/var its :vars doesn't bind is unbound"
               (and (contains? (:ids r) :app/server) (= [[:app/server ["host"]]] (map (juxt :dev-id :vars) (:unbound r))))))
      (let [r (ig-check {"resources/config.edn" "{:app/a {}\n :app/b {}\n :app/c {}\n :lib.x/y {}\n [:app/d :app/dd] {}}\n"
                         "src/app/core.clj" (str ig-ns "(defmethod ig/init-key :app/b [_ v] v)\n(derive :app/c :app/b)\n")
                         "src/app.clj" "(ns app)\n(defn dd [x] x)\n"})]
        (check "integrant: a key nothing builds; not one whose parent has an init-key, a fn named like a part, or a library's"
               (= [:app/a] (map :dev-id (:no-init r)))))
      (let [files {"resources/config.edn" "{:app/a {}}\n"
                   "src/app/core.clj" (str ig-ns "(defmethod ig/init-key :app/a [_ v] v)\n"
                                           "(defmethod ig/prep-key :app/a [_ v] v)\n(defmethod ig/pre-init-spec :app/a [_] nil)\n")}
            sev (fn [r] (sort (map (juxt :method :severity) (:removed r))))]
        (check "integrant: prep-key and pre-init-spec are removed methods: info, a warning on a version without them, nothing on one with them"
               (and (= [["pre-init-spec" :info] ["prep-key" :info]] (sev (ig-check files)))
                    (= [["pre-init-spec" :warning]]
                       (sev (ig-check (assoc files "deps.edn" "{:deps {integrant/integrant {:mvn/version \"0.13.1\"}}}"))))
                    (empty? (sev (ig-check (assoc files "deps.edn" "{:deps {integrant {:mvn/version \"0.8.0\"} integrant/repl {:mvn/version \"0.3.2\"}}}"))))
                    (= [["pre-init-spec" :warning] ["prep-key" :warning]]
                       (sev (ig-check (assoc files "deps.edn" "{:deps {integrant/integrant {:mvn/version \"1.0.1\"}}}")))))))
      (let [r (ig-check {"resources/config.edn"
                         (str "{:app/handler {:email #ig/ref :app.email/service}\n"
                              " #profile{:dev :app.email/logging :prod :app.email/sendgrid} {:key \"k\"}}\n")
                         "src/app/email.clj" (str "(ns app.email (:require [integrant.core :as ig]))\n"
                                                  "(derive ::logging ::service)\n(derive ::sendgrid ::service)\n"
                                                  "(defmethod ig/init-key ::sendgrid [_ opts] opts)\n")})]
        (check "integrant: a key chosen by #profile configures each of its branches"
               (empty? (:refs r))))
      (let [r (ig-check {"resources/config.edn"
                         (str "{:duct.module/sql {}\n :duct.module/logging {}\n"
                              " :app/repo {:db #ig/ref :duct.database.sql/hikaricp :logger #ig/ref :duct.logger/timbre}}\n")})]
        (check "integrant: the keys duct's sql and logging modules add, under their own names"
               (empty? (:refs r))))
      (let [r (ig-check {"resources/config.edn"
                         (str "{:duct.profile/base\n"
                              " {:app/router {:routes [#ig/ref [:app.handler/app]]}\n"
                              "  :app.handler/module {}}}\n")
                         "src/duct_hierarchy.edn" "{:app.handler/module [:duct/module]}\n"})]
        (check "integrant: a ref the project's own duct module may add, in its namespace, is an info naming it"
               (and (empty? (:refs r)) (= [[:app/router [:app.handler/app] :app.handler/module]]
                                          (map (juxt :dev-id :keys :module) (:maybe r))))))
      (let [r (ig-check {"resources/config.edn"
                         (str "{:duct.profile/base\n"
                              " {:app/router {:routes [#ig/ref [:app.handler/app]]}\n"
                              "  :app.handler/module {}}}\n")
                         "src/duct_hierarchy.edn" "{:app.handler/module [:duct/module]}\n"
                         "src/app/handler.clj"
                         (str "(ns app.handler (:require [integrant.core :as ig] [duct.core :as duct]))\n"
                              "(defmethod ig/init-key ::module [_ options]\n"
                              "  #(duct/merge-configs % {::app {} [:duct/const ::options] options}))\n")})]
        (check "integrant: the keys a duct module's init-key merges are in the system"
               (and (empty? (:refs r)) (empty? (:maybe r)))))
      (let [r (ig-check {"resources/config.edn"
                         (str "{:duct.migrator/ragtime {}\n :duct.server.http/jetty {}\n"
                              " :app/fixtures {:migrator #ig/ref :duct/migrator :daemon #ig/ref :duct/daemon}}\n")})]
        (check "integrant: what duct's libraries derive their keys from, through their chains"
               (empty? (:refs r))))
      (let [r (ig-check {"resources/config.edn"
                         "{:app.module/greet {:name \"Alice\"}\n :app/server {:handler #ig/ref :app.handler/greet}}\n"
                         "src/app/module.clj"
                         (str "(ns app.module (:require [integrant.core :as ig]))\n"
                              "(defmethod ig/expand-key :app.module/greet [_ {:keys [name]}]\n"
                              "  {:app.handler/greet {:name name}})\n")})]
        (check "integrant: the keys an expand-key writes are in the system"
               (and (empty? (:refs r)) (empty? (:maybe r)))))
      (let [r (ig-check {"resources/config.edn"
                         (str "{:app/handler {:middleware ^:replace [#ig/ref :app/mw]\n"
                              "               :db ^:displace #ig/ref :app/db}\n"
                              " :app/mw {}\n :app/db {}}\n")})]
        (check "integrant: duct's merge metadata on a tag (^:replace #ig/ref) reads"
               (and (zero? (or (:not-read r) 0)) (empty? (:refs r))))))

    ;; ---- :pack/derive: facts a pack derives from what the others read
    (let [dir (str (fs/create-temp-dir))]
      (fs/create-dirs (str dir "/resources")) (fs/create-dirs (str dir "/registry"))
      (spit (str dir "/resources/config.edn") "{:app/db {}\n :app/billing {:db #ig/ref :app/db}}\n")
      (spit (str dir "/registry/facts.edn") "{:app/billing {:app/tier :service}}\n")
      (let [idx (index/build {:root dir :src [] :registry ["registry"]
                              :packs {:integrant {:configs ["resources"]} 'smoke.packs/derive-pack {}}})]
        (check "derive: a pack's derived fact fills a key no source gave, listed with :derived-by"
               (and (= :foundation (get-in idx [:entities :app/db :entry :app/tier]))
                    (some #(= :tiers (:derived-by %)) (:sources (index/where idx :app/db)))))
        (check "derive: never over a source's, and no conflict"
               (and (= :service (get-in idx [:entities :app/billing :entry :app/tier]))
                    (empty? (problems idx :conflicting-values))
                    (empty? (problems idx :id-in-several-packs)))))
      (fs/delete-tree dir))

    ;; ---- a pack that describes what others declare
    (let [ig-only {:integrant {:configs ["resources"]}}
          with (fn [p] (index/build (assoc cfg :packs (assoc ig-only p {}))))
          shared #(set (map :dev-id (problems % :id-in-several-packs)))]
      (check "a pack describing integrant's components under their keys: no :id-in-several-packs, integrant stays primary"
             (let [d (with 'smoke.packs/describing-pack)]
               (and (not (contains? (shared d) :shop/db))
                    (= :integrant (:pack (:definition (index/where d :shop/db))))
                    (= "described" (:note/text (:entry (index/where d :shop/db)))))))
      (check "the same pack, declaring: one id in two packs is a warning"
             (contains? (shared (with 'smoke.packs/declaring-pack)) :shop/db)))))
