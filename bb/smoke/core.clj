(ns smoke.core
  (:require [smoke.support :as s :refer [check problems]]
            [meta-source.cli :as cli]
            [meta-source.hover :as hover]
            [meta-source.read]
            [meta-source.index :as index]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(defn run []
  ;; ---- packs from a library: its meta-source/packs.edn, on the classpath through :deps
  (let [dir (str (fs/create-temp-dir))
        pack (fn [id ks] (str "{:pack/id " id " :pack/kinds #{" id "} :pack/config-keys " ks
                              " :pack/find (fn [& _] nil) :pack/read (fn [& _] [])}"))]
    (fs/create-dirs (str dir "/packs/src/libpack"))
    (fs/create-dirs (str dir "/packs/resources/meta-source"))
    (spit (str dir "/packs/deps.edn") "{:paths [\"src\" \"resources\"]}")
    (spit (str dir "/packs/resources/meta-source/packs.edn")
          "[{:pack libpack.core/pack :config-keys #{:libpack}} {:pack libpack.core/drift :config-keys #{:drift}}]")
    (spit (str dir "/packs/src/libpack/core.clj")
          (str "(ns libpack.core)\n(def pack " (pack :libpack "#{:libpack}") ")\n"
               "(def drift " (pack :drift "#{:other}") ")\n"))
    (spit (str dir "/meta-source.edn") "{:deps {my/packs {:local/root \"packs\"}} :packs {:libpack {}}}")
    (let [c (cli/config {:root dir})]
      (check "a library's packs are found through its meta-source/packs.edn, once :deps puts it on the classpath"
             (and (some #{'libpack.core/pack} (map :pack (index/available-packs)))
                  (= [:libpack] (map :pack/id (index/enabled-packs (:packs c))))))
      (check "packs.edn and the pack disagreeing on its config keys is an error, not a guess"
             (= #{:drift} (:resource (try (index/enabled-packs {:drift {}}) nil
                                          (catch clojure.lang.ExceptionInfo e (ex-data e)))))))
    (fs/delete-tree dir))

  ;; ---- the project's clj-kondo config: test/fixtures/kondo-config, read from here, not its root
  (let [show #(first (filter (comp #{'show} :name) (mapcat :vars (vals (:code %)))))
        kws #(set (map :name (mapcat :keywords (vals (:code %)))))
        dir (str (fs/create-temp-dir))
        _ (fs/copy-tree "test/fixtures/kondo-config" dir)
        cache (str dir "/.meta-source/index.transit")
        build #(index/build {:root dir :src ["src"] :registry [] :cache cache})
        before (build)]
    (check "a macro the project's .clj-kondo config lints as defn defines a var, its meta key and keywords read"
           (and (= :fn/show (:dev-id (show before))) (contains? (kws before) "email")))
    (spit (str dir "/.clj-kondo/config.edn") "{}")
    (let [after (build)]
      (check "the config edited: the cached analysis is not reused"
             (and (pos? (:analysed after)) (nil? (show after)))))
    (fs/delete-tree dir))

  ;; ---- dataflow a pack derives from what was read
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/registry"))
    (spit (str dir "/registry/r.edn") "{:ont/widget {:flow/in-key :widget/takes}\n :fn/w {:widget/takes [:user/id]}}")
    (let [fi (index/build {:root dir :src [] :registry ["registry"] :packs {'smoke.packs/flow-pack {}}})]
      (check "a pack's :pack/dataflow-of: a key an ontology entry names lists what its entities consume"
             (= [:fn/w] (map :dev-id (:consumers (index/data-key fi :user/id))))))
    (fs/delete-tree dir))

  ;; ---- :deps in meta-source.edn: a relative :local/root is the project's
  (let [r (str (fs/create-temp-dir))
        out ((requiring-resolve 'meta-source.cli/under-root)
             {'app/packs {:local/root "tools/packs"} 'lib/x {:mvn/version "1.0"} 'abs/p {:local/root "/opt/p"}} r)]
    (check "a relative :local/root in :deps resolves against the project's root, wherever meta-source runs"
           (and (= (str (fs/canonicalize (fs/path r "tools/packs") {:nofollow-links false})) (get-in out ['app/packs :local/root]))
                (= {:mvn/version "1.0"} (get out 'lib/x))
                (= "/opt/p" (get-in out ['abs/p :local/root]))))
    (fs/delete-tree r))

  ;; ---- an implementation located by rows: an inline fn in a top-level register!
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/src/app"))
    (spit (str dir "/src/app/reg.clj") "(ns app.reg)\n(defn register! [id entry f] f)\n")
    (spit (str dir "/src/app/core.clj")
          (str "(ns app.core (:require [app.reg :as reg]))\n"
               "(defn ^{:meta-source/id :fn/load} load-impl [] {:k/a 1})\n"
               "(reg/register! :fn/load {:fn/returns [:k/a]} nil)\n"
               "(reg/register! :fn/other {:fn/returns [:k/b]} nil)\n"
               ";; inline: calls load-impl, which its entry doesn't name, and returns :k/b\n"
               "(reg/register! :fn/run {:fn/returns [:k/a]}\n"
               "  (fn [_] (load-impl) {:k/a 1 :k/b 2}))\n"))
    (let [ii (index/build {:root dir :src ["src"] :registry [] :packs {'smoke.packs/inline-pack {}}
                           :dataflow {:produces [:fn/returns]}})]
      (check "an inline implementation: its undeclared call is reported, from the entity whose rows hold it"
             (= #{[:fn/run :fn/load]} (set (map (juxt :dev-id :calls) (problems ii :undeclared-call)))))
      (check "an inline implementation: a key it returns outside its contract is reported"
             (= [[:fn/run [:k/b]]] (map (juxt :dev-id :keys) (problems ii :keys-outside-contract))))
      (check "an inline implementation: where says where, uses says who calls from it"
             (and (:inline (:impl (index/where ii :fn/run)))
                  (some #(= :fn/run (:dev-id %)) (:called-from (index/uses ii :fn/load))))))
    (fs/delete-tree dir))

  ;; ---- a keyword passed as a value is not a key read
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/src/app"))
    (spit (str dir "/src/app/reg.clj") "(ns app.reg)\n(defn register! [id entry f] f)\n")
    (spit (str dir "/src/app/core.clj")
          (str "(ns app.core (:require [app.reg :as reg]))\n"
               "(defn fetch [& _] nil)\n"
               "(reg/register! :fn/other {:fn/returns [:k/a :k/b :k/c :k/d :k/e :k/f :k/g]} nil)\n"
               "(defn ^{:meta-source/id :fn/run} run-impl [{:keys [:k/a] m :k/b}]\n"
               "  {:x (fetch m :k/c) :y ((fetch [:k/d]) :k/e) :z (:k/f m) :w (assoc m :k/g 1)})\n"
               "(reg/register! :fn/run {:fn/returns [:k/x]} nil)\n"))
    (let [ii (index/build {:root dir :src ["src"] :registry [] :packs {'smoke.packs/inline-pack {}}
                           :dataflow {:produces [:fn/returns]}})]
      (check "keys-outside-contract: keys destructured, read, assoc'd count; keywords passed as arguments don't"
             (= [[:fn/run [:k/a :k/b :k/f :k/g]]] (map (juxt :dev-id :keys) (problems ii :keys-outside-contract)))))
    (fs/delete-tree dir))

  ;; ---- declared otherwise: a key the entry names, a dep's input, a contract written as code
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/src/app"))
    (spit (str dir "/src/app/reg.clj") "(ns app.reg)\n(defn register! [id entry f] f)\n")
    (spit (str dir "/src/app/core.clj")
          (str "(ns app.core (:require [app.reg :as reg]))\n"
               "(defn fetch [& _] nil)\n"
               "(reg/register! :fn/other {:fn/returns [:k/a :k/c]} nil)\n"
               "(reg/register! :fn/callee {:fn/reads [:k/in]} nil)\n"
               ";; :k/a is in its deps, :k/in is the input of :fn/callee it names\n"
               "(defn ^{:meta-source/id :fn/run} run-impl [{:keys [:k/a]}] (fetch {:k/in a}) {:k/x 1})\n"
               "(reg/register! :fn/run {:fn/deps [:k/a :fn/callee] :fn/returns [:k/x]} nil)\n"
               ";; its returns are code: not read, nothing to compare with\n"
               "(defn ^{:meta-source/id :fn/code} code-impl [m] {:k/c (:k/a m)})\n"
               "(reg/register! :fn/code {:fn/returns (into [] [:k/z])} nil)\n"
               ";; the control: reads :k/a, declared nowhere\n"
               "(defn ^{:meta-source/id :fn/bare} bare-impl [m] {:k/x (:k/a m)})\n"
               "(reg/register! :fn/bare {:fn/returns [:k/x]} nil)\n"))
    (let [ii (index/build {:root dir :src ["src"] :registry [] :packs {'smoke.packs/inline-pack {}}
                           :dataflow {:produces [:fn/returns] :consumes [:fn/reads]}})]
      (check "keys-outside-contract: a key the entry names and a dep's input are declared; a contract written as code isn't compared"
             (= [[:fn/bare [:k/a]]] (map (juxt :dev-id :keys) (problems ii :keys-outside-contract)))))
    (fs/delete-tree dir))

  ;; ---- :libs: a library's registrations count, its own findings aren't reported
  (let [dir (str (fs/create-temp-dir))
        reg "(ns app.reg)\n(defn register! [id entry f] f)\n"]
    (fs/create-dirs (str dir "/src/app"))
    (fs/create-dirs (str dir "/lib/src/lib"))
    (spit (str dir "/deps.edn") "{:deps {my/lib {:local/root \"lib\"}}}")
    (spit (str dir "/lib/deps.edn") "{:paths [\"src\"]}")
    (spit (str dir "/src/app/reg.clj") reg)
    (spit (str dir "/lib/src/lib/tools.clj")
          (str "(ns lib.tools (:require [app.reg :as reg]))\n"
               ";; the lib's own: returns :k/b, outside its contract\n"
               "(defn ^{:meta-source/id :lib/tool} tool-impl [m] {:k/a 1 :k/b 2})\n"
               "(reg/register! :lib/tool {:fn/returns [:k/a]} nil)\n"
               "(reg/register! :lib/other {:fn/returns [:k/b]} nil)\n"))
    (spit (str dir "/src/app/core.clj")
          (str "(ns app.core (:require [app.reg :as reg]))\n"
               "(defn ^{:meta-source/id :fn/run} run-impl [m] {:k/a 1 :k/b 2})\n"
               "(reg/register! :fn/run {:fn/returns [:k/a] :fn/uses [:lib/tool]} nil)\n"))
    (let [libs ((requiring-resolve 'meta-source.libs/materialize) dir '[my/lib])
          ii (index/build {:root dir :src (into ["src"] libs) :lib-src libs :registry []
                           :packs {'smoke.packs/inline-pack {}} :dataflow {:produces [:fn/returns]}})]
      (check ":libs: a library's sources are copied under .meta-source/libs and its entities are known"
             (and (= [".meta-source/libs/my/lib@local"] libs) (index/where ii :lib/tool)))
      (check ":libs: what is found in a library's own code isn't reported; the project's is"
             (= [[:fn/run [:k/b]]] (map (juxt :dev-id :keys) (problems ii :keys-outside-contract)))))
    (fs/delete-tree dir))

  ;; ---- what the index doesn't cover: other languages, libraries no pack reads
  (let [build (fn [files packs]
                (let [dir (str (fs/create-temp-dir))]
                  (doseq [[p text] files] (fs/create-dirs (fs/parent (str dir "/" p))) (spit (str dir "/" p) text))
                  (try (index/build {:root dir :src ["src"] :registry [] :packs packs})
                       (finally (fs/delete-tree dir)))))
        files {"src/app/web.clj" (str "(ns app.web (:require [io.pedestal.http.route :as route]))\n"
                                      "(def routes (route/expand-routes #{[\"/\" :get (fn [_] {}) :route-name ::home]}))\n")
               "core/src/main/kotlin/app/Node.kt" "class Node\n"
               "core/src/main/kotlin/app/Base.kt" "class Base\n"
               "web/node_modules/lib/index.js" "module.exports = 1\n"
               "target/classes/Gen.java" "class Gen {}\n"}
        idx (build files {})
        nc (problems idx :not-covered)
        plain (problems (build {"src/app/core.clj" "(ns app.core)\n(defn f [] 1)\n"} {}) :not-covered)
        own-pack (problems (build files {'smoke.packs/pedestal-pack {}}) :not-covered)
        stated (let [dir (str (fs/create-temp-dir))]
                 (doseq [[p text] files] (fs/create-dirs (fs/parent (str dir "/" p))) (spit (str dir "/" p) text))
                 (try (problems (index/build {:root dir :src ["src"] :registry [] :packs {}
                                              :not-covered {:languages ["Kotlin"] :libraries ["Pedestal"]}})
                                :not-covered)
                      (finally (fs/delete-tree dir))))]
    (check "not covered: the project's sources in another language, outside dependencies and build output, are said"
           (= [{:language "Kotlin" :files 2 :dirs ["core/src"]}]
              (map #(select-keys % [:language :files :dirs]) (filter :language nc))))
    (check "not covered: a library declaring routes that no pack reads, with the files calling it"
           (= [{:library "Pedestal" :files ["src/app/web.clj"]}]
              (map #(select-keys % [:library :files]) (filter :library nc))))
    (check "not covered: nothing to say on a Clojure-only project calling no such library (control)"
           (empty? plain))
    (check "not covered: a library an enabled pack covers (:pack/covers) isn't said"
           (empty? (filter :library own-pack)))
    (check "not covered: what meta-source.edn's :not-covered states the project knows isn't said again"
           (empty? stated)))

  ;; ---- rules: a violation through a unioned key is located where the value was given
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/registry"))
    (fs/create-dirs (str dir "/rules"))
    (spit (str dir "/registry/app.edn")
          (str "{:app/domain {:app/layer :domain :app/deps [:app/util]}\n"
               " :app/util {:app/layer :domain}\n"
               " :app/adapter {:app/layer :adapter}}\n"))
    ;; another file draws what the domain depends on
    (spit (str dir "/registry/wiring.edn")
          (str "{:app/other {:app/layer :domain}\n"
               " :app/domain {:app/deps [:app/adapter]}}\n"))
    (spit (str dir "/rules/layers.edn")
          (str "{:rule/refs {:when {:id :app/domain} :require [{:refs {:app/deps {:none {:= {:app/layer :adapter}}}}}]}\n"
               " :rule/deps {:when {:id :app/domain} :require [{:deps {:none {:= {:app/layer :adapter}}}}]}\n"
               " :rule/reaches {:when {:id :app/domain} :require [{:reaches {:none {:= {:app/layer :adapter}}}}]}}\n"))
    (let [build (fn [merge] (index/build {:root dir :src [] :registry ["registry"] :rules ["rules"] :cache nil :merge merge}))
          at (fn [idx] (into {} (for [p (problems idx :rule-violated)]
                                  [(:rule p) (set (map (juxt :file :line :offender) (:given-at p)))])))
          unioned (build {:app/deps :union})]
      (check "rules: through a unioned key, :refs, :deps and :reaches each say where the offending value was given"
             (= {:rule/refs #{["registry/wiring.edn" 2 :app/adapter]}
                 :rule/deps #{["registry/wiring.edn" 2 :app/adapter]}
                 :rule/reaches #{["registry/wiring.edn" 2 :app/adapter]}}
                (at unioned)))
      (check "rules: the editor marks that place too, as well as the entity's own definition"
             (let [ls (set (map :line (hover/diagnostics unioned "registry/wiring.edn")))]
               (contains? ls 2)))
      ;; one file, the key not unioned: the value is in the entity's own definition
      (fs/create-dirs (str dir "/single"))
      (spit (str dir "/single/app.edn")
            (str "{:app/domain {:app/layer :domain :app/deps [:app/adapter]}\n"
                 " :app/adapter {:app/layer :adapter}}\n"))
      (let [single (index/build {:root dir :src [] :registry ["single"] :rules ["rules"] :cache nil})]
        (check "rules: a key not unioned gives no such place: its value is in the entity's own definition"
               (and (= #{:rule/refs :rule/deps :rule/reaches} (set (keys (at single))))
                    (every? empty? (vals (at single)))))))
    (fs/delete-tree dir))

  ;; ---- the namespaces pack: a project's namespaces as entities, by a stated convention
  (let [dir (str (fs/create-temp-dir))]
    (doseq [d ["src/app/web" "src/lib" "rules"]] (fs/create-dirs (str dir "/" d)))
    (spit (str dir "/src/app.clj") "(ns app)\n")
    (spit (str dir "/src/app/core.clj") "(ns app.core\n  (:require [app.db :as db]\n            [clojure.string :as str]\n            [lib.x]))\n")
    (spit (str dir "/src/app/db.clj") "(ns app.db)\n")
    (spit (str dir "/src/app/ui.cljc") "(ns app.ui (:require [app.db]))\n")
    (spit (str dir "/src/app/web/handler.clj") "(ns app.web.handler\n  (:require [app.core] [app.db]))\n")
    (spit (str dir "/src/lib/x.clj") "(ns lib.x)\n")
    (spit (str dir "/rules/layers.edn")
          (str "{:layers/web-through-core\n"
               " {:doc \"web reaches the database through core only\"\n"
               "  :when {:id-ns \"shop.web\"}\n"
               "  :require [{:deps {:none {:id :shop/db}}}]}}\n"))
    (let [ix (index/build {:root dir :src ["src"] :registry [] :rules ["rules"]
                           :packs {:namespaces {:roots {"app" "shop"} :pattern true}}})
          by-longest (index/build {:root dir :src ["src"] :registry []
                                   :packs {:namespaces {:roots {"app" "shop" "app.web" "web"}}}})]
      (check "namespaces: a namespace under a root is an entity, its id the rest of its name; what it requires under a root, by id; a library's isn't named"
             (and (= "app.core" (get-in (index/where ix :shop/core) [:entry :meta-source/ns]))
                  (= #{:shop/db} (set (get-in (index/where ix :shop/core) [:entry :meta-source/requires])))
                  (= "app.web.handler" (get-in (index/where ix :shop.web/handler) [:entry :meta-source/ns]))
                  (= "app.ui" (get-in (index/where ix :shop/ui) [:entry :meta-source/ns]))
                  (nil? (index/where ix :shop/x))
                  (not-any? #(= "lib.x" (get-in % [:entry :meta-source/ns])) (vals (:entities ix)))))
      (check "namespaces: uses lists what requires a namespace"
             (= #{:shop/core :shop/ui :shop.web/handler}
                (set (map :dev-id (:referenced-by (index/uses ix :shop/db))))))
      (check "namespaces: a rule says which may require which, at the namespace breaking it, naming what it requires and not again where"
             (and (= #{[:shop.web/handler 1]}
                     (set (map (juxt :dev-id (comp :line :definition)) (problems ix :rule-violated))))
                  (every? #(and (str/includes? (hover/message ix % (:dev-id %)) "(:shop/db)")
                                (not (str/includes? (hover/message ix % (:dev-id %)) "given at")))
                          (problems ix :rule-violated))))
      (check "namespaces: with :pattern true, a root namespace, with no name under the root, is off-pattern"
             (= [:pattern/namespace-under-root] (map :pattern (problems ix :off-pattern))))
      (check "namespaces: the longest root a namespace is under maps it"
             (and (some? (index/where by-longest :web/handler)) (nil? (index/where by-longest :shop.web/handler)))))
    (fs/delete-tree dir))

  ;; ---- check --fail-on: which problems fail a CI run
  (let [ps [{:severity :error} {:severity :warning} {:severity :info}]]
    (check "--fail-on error fails on errors only; warning on warnings and errors; info on all"
           (= [1 2 3] (map #(count (cli/failing ps %)) ["error" "warning" "info"])))
    (check "--fail-on with an unknown severity is an error, not a pass"
           (try (cli/failing ps "fatal") false (catch clojure.lang.ExceptionInfo _ true))))

  ;; ---- rules over one property: :refs, :referenced-by, :resolves
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/registry"))
    (fs/create-dirs (str dir "/rules"))
    (spit (str dir "/registry/r.edn")
          (str "{:fn/pure {:kind/fn true :tag/pure true :fn/deps #{:db/main}}\n"
               " :fn/ok {:kind/fn true :tag/pure true :fn/calls [:db/main] :fn/deps #{:fn/helper}}\n"
               " :fn/helper {:kind/fn true}\n"
               " :db/main {:kind/component true}\n"
               " :fn/dangling {:kind/fn true :fn/deps #{:db/gone :db/main}}\n"
               " :pattern/used {:kind/pattern true}\n"
               " :pattern/unused {:kind/pattern true}\n"
               " :vp/a {:kind/vp true :vp/pattern :pattern/used}}"))
    (spit (str dir "/rules/r.edn")
          (str "{:r/pure-no-components {:when {:has :tag/pure} :require [{:refs {:fn/deps {:none {:has :kind/component}}}}]}\n"
               " :r/deps-resolve {:when {:has :kind/fn} :require [{:resolves :fn/deps}]}\n"
               " :r/pattern-used {:when {:has :kind/pattern} :require [{:referenced-by {:vp/pattern {:some {:has :kind/vp}}}}]}\n"
               " :r/bad {:when {:has :kind/fn} :require [{:refs {:fn/deps :oops}} {:resolves \"x\"}]}}"))
    (let [ri (index/build {:root dir :src [] :registry ["registry"] :rules ["rules"]})
          v (fn [rule] (set (for [p (problems ri :rule-violated) :when (= rule (:rule p))] [(:dev-id p) (:offenders p)])))]
      (check ":refs: only what that property names, offenders named (:fn/ok names :db/main by :fn/calls, not :fn/deps)"
             (= #{[:fn/pure [:db/main]]} (v :r/pure-no-components)))
      (check ":resolves: a dep no source defines, named"
             (= #{[:fn/dangling [:db/gone]]} (v :r/deps-resolve)))
      (check ":referenced-by: a pattern no value proposition names through :vp/pattern"
             (= #{[:pattern/unused nil]} (v :r/pattern-used)))
      (check "a malformed :refs or :resolves is a rule not understood, saying why"
             (= [[":refs :fn/deps takes {:all cond}, {:some cond} or {:none cond}" ":resolves takes a key or a vector of keys"]]
                (for [p (problems ri :rule-not-understood) :when (= :r/bad (:rule p))] (:reasons p)))))
    (fs/delete-tree dir))

  ;; ---- rules a pack derives from what was read
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/registry"))
    (spit (str dir "/registry/r.edn")
          (str "{:fn/w {:widget/takes [:user/id]}\n"
               " :inv/audited {:rule/as-data {:doc \"A widget is audited\" :when {:has :widget/takes} :require [{:has :audit/logged}]}}}"))
    (let [ri (index/build {:root dir :src [] :registry ["registry"] :packs {'smoke.packs/rules-pack {}}})]
      (check "a pack's :pack/rules-of: a rule an entry carries is checked, located at that entry"
             (= [[:fn/w :inv/audited {:file "registry/r.edn" :line 2}]]
                (map (juxt :dev-id :rule :rule-at) (problems ri :rule-violated)))))
    (fs/delete-tree dir))

  ;; ---- a pack's own meta key
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/src/app")) (fs/create-dirs (str dir "/registry"))
    (spit (str dir "/src/app/core.clj")
          "(ns app.core)\n(defn ^{:app/id :x/linked} linked [x] x)\n(defn ^{:app/id :x/bare} bare [x] x)\n(defn ^{:meta-source/id :x/plain} plain [x] x)\n")
    (spit (str dir "/registry/x.edn") "{:x/linked {:x/doc \"described\"}}")
    (let [ti (index/build {:root dir :src ["src"] :registry ["registry"] :packs {'smoke.packs/tagged-pack {}}})]
      (check "a pack's :pack/id-key links a var with no :id-key configured"
             (= 'app.core/linked (:var (:impl (index/where ti :x/linked)))))
      (check "a var a pack's key links with no source is the pack's to report, not :binding-only"
             (and (= [:x/bare] (map :dev-id (problems ti :tagged-without-entry)))
                  (= [:x/plain] (map :dev-id (problems ti :binding-only))))))
    (fs/delete-tree dir)))
