(ns smoke.core
  (:require [smoke.support :as s :refer [check problems]]
            [meta-source.cli :as cli]
            [meta-source.hover :as hover]
            [meta-source.read]
            [meta-source.index :as index]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private core-packs
  "What the core repo ships: the namespaces pack, the reference pack."
  '#{meta-source.pack.namespaces/pack})

(defn- as-core
  "Runs `f` seeing only the packs a project using meta-source alone has:
   the core's, and any a library on its :deps brings. A check here that
   enables another of meta-source's packs fails, as it would in the core
   repo."
  [f]
  (let [available index/available-packs]
    (with-redefs [index/available-packs
                  (fn [] (remove #(and (str/starts-with? (namespace (:pack %)) "meta-source.pack.")
                                       (not (core-packs (:pack %))))
                                 (available)))]
      (f))))

(def ^:private core-root "test/fixtures/core")

(defn- fixture-checks
  "The core fixture (test/fixtures/core): the shop's plain functions, the
   meta key and its own words, with no library pack."
  []
  (let [cfg (assoc (edn/read-string (slurp (str core-root "/meta-source.edn"))) :root core-root)
        idx (index/build cfg)
        diags (fn [i f] (set (map (juxt :line :col :end-col :severity :problem) (hover/diagnostics i f))))]
    (check "core: the index reads EDN sources and linked vars with no pack, and finds no error"
           (and (= {:binding 1 :edn 6} (:entities-by-source (index/summary idx)))
                (empty? (:packs (index/summary idx)))
                (not-any? #(= :error (:severity %)) (index/check idx))))
    (check "core: a contract in EDN gives its function's data and needs"
           (and (= [:fn/register-user] (map :dev-id (:consumers (index/data-key idx :user/email))))
                (= [:fn/register-user] (map :dev-id (:producers (index/data-key idx :user/email))))
                (= [:fn/register-user] (map :dev-id (:referenced-by (index/uses idx :shop/mailer))))))
    (check "core: code reading a key some contract names, outside its own contract, or only destructuring it"
           (= [[:fn/apply-discount [:user/phone]] [:fn/place-order [:order/discount]]]
              (sort (map (juxt :dev-id :keys) (problems idx :keys-outside-contract)))))
    (check "core diagnostics: a key only destructured, at its name, within its own form"
           (contains? (diags idx "src/shop/discounts.clj") [5 40 45 "note" "keys-outside-contract"]))

    ;; ---- the meta key, and the merge
    (check "core: a var with the meta key and no entry: a binding-only entity, valid, reported as info"
           (and (= {:file "src/shop/handlers.clj" :line 13 :binding true} (:definition (index/where idx :fn/cancel-order)))
                (= [[:fn/cancel-order :info]] (map (juxt :dev-id :severity) (problems idx :binding-only)))
                (= "binding" (second (re-find #"^:fn/cancel-order  (\S+)" (:eldoc (hover/lookup idx :fn/cancel-order)))))))
    (check "core: the meta key is configurable: :id-key"
           (and (nil? (index/where idx :fn/legacy-report))
                (= 'shop.legacy/legacy-report
                   (get-in (index/where (index/build (assoc cfg :id-key :shop/id)) :fn/legacy-report) [:impl :var]))))
    (check "core: two files giving one key different sets: a conflict, unless :merge says :union"
           (let [tags (update cfg :registry conj "registry-tags")]
             (and (= [[:user/email :data/tags]] (map (juxt :dev-id :key) (problems (index/build tags) :conflicting-values)))
                  (= #{:contact :pii} (get-in (index/where (index/build (assoc tags :merge {:data/tags :union})) :user/email) [:entry :data/tags])))))
    (check "core: :dataflow in meta-source.edn makes a project's own keys consume data"
           (= [:fn/register-user :job/nightly-digest]
              (map :dev-id (:consumers (index/data-key (index/build (-> cfg (update :registry conj "registry-jobs")
                                                                         (update-in [:dataflow :consumes] conj :job/reads)))
                                                       :user/email)))))

    ;; ---- diagnostics: what an editor underlines, per file
    (check "core diagnostics: a key used outside the contract, at the key in the code"
           (contains? (diags idx "src/shop/handlers.clj") [10 49 64 "note" "keys-outside-contract"]))
    (check "core diagnostics: an absolute path, as editors send"
           (= (diags idx "src/shop/handlers.clj")
              (diags idx (.getCanonicalPath (java.io.File. (str core-root "/src/shop/handlers.clj"))))))
    (check "core diagnostics: a dev-id at the meta key that names it"
           (contains? (diags idx "src/shop/handlers.clj") [13 25 41 "note" "binding-only"]))
    (check "core diagnostics: a file with nothing to report"
           (empty? (diags idx "src/shop/legacy.clj")))

    ;; ---- the project's own rules: rules/shop.edn, rules-broken/, rules-more/
    (let [ri (index/build (assoc cfg :rules ["rules"]))
          by (fn [i rule] (filter #(= rule (:rule %)) (index/check i)))
          pii (first (by ri :rule/pii-readers-audited))]
      (check "core rules: a rule over a relation: a PII key's consumers must be audited, naming the ones that aren't"
             (and (= :warning (:severity pii)) (= :user/email (:dev-id pii))
                  (= [:fn/register-user] (:offenders pii))
                  (= {:file "rules/shop.edn" :line 2} (:rule-at pii))))
      (check "core rules: the editor marks the entity and its offenders, at check's severity"
             (and (contains? (diags ri "registry/privacy.edn") [2 2 13 "warning" "rule-violated"])
                  (some #(= ["warning" "rule-violated"] ((juxt :severity :problem) %)) (hover/diagnostics ri "registry/functions.edn"))))
      (check "core rules: a rule at its own severity; one that holds gives nothing"
             (and (= [[:shop/metrics :info]] (map (juxt :dev-id :severity) (by ri :rule/components-stop)))
                  (empty? (by ri :rule/functions-say-what-they-need))))
      (check "core rules: a rule it can't understand is an error at the rule, saying why"
             (= [{:problem :rule-not-understood :file "rules-broken/broken.edn" :line 1 :reasons ["unknown condition :kynd"]}]
                (map #(select-keys % [:problem :file :line :reasons])
                     (by (index/build (assoc cfg :rules ["rules-broken"])) :rule/misspelled))))
      (check "core rules: :not, :id-ns, :in, :or, :used-by :some, :deps :none, :producers :some"
             (= {:more/fns-outside-shop-ns [:fn/apply-discount :fn/place-order :fn/register-user]
                 :more/components-someone-needs [:shop/metrics]
                 :more/no-fn-depends-on-pii [:fn/register-user]}
                (update-vals (group-by :rule (filter :rule (index/check (index/build (assoc cfg :rules ["rules-more"])))))
                             #(mapv :dev-id %))))
      (check "core: check lists errors, then warnings, then info"
             (let [ranks (map #(get {:error 0 :warning 1} (:severity %) 2) (index/check ri))]
               (and (apply distinct? (dedupe ranks)) (= ranks (sort ranks)))))
      (check "core: check --brief: severity, file:line, the rule's doc, the entity and the offenders"
             (some #(re-matches #"warning +registry/privacy\.edn:2 +Whatever reads a PII key is audited: :user/email \(:fn/register-user\)  \[:rule/pii-readers-audited\]" %)
                   (hover/brief ri))))

    ;; ---- the pack contract, through packs written here (smoke.packs)
    (let [own (index/build (assoc-in cfg [:packs 'smoke.packs/job-pack] {}))]
      (check "core: a project's own pack, named by its symbol, reads its own macro; not inside (comment ...)"
             (and (= {:job/reads [:user/email :order/total] :job/schedule "0 3 * * *"}
                     (:entry (index/where own :shop.jobs/nightly-digest)))
                  (nil? (index/where own :shop.notes/scratch))))
      (check "core: its :pack/dataflow makes the job a consumer next to functions"
             (= [:fn/register-user :shop.jobs/nightly-digest] (map :dev-id (:consumers (index/data-key own :user/email)))))
      (check "core: its entities are valid as they are, and it counts its reads; a :refer of its macro is not a form"
             (and (empty? (problems own :entity-without-type))
                  (= {:read 1 :not-read 0} (:job (:packs (index/summary own))))
                  (empty? (problems own :pack-form-not-read))))
      (check "core: the hover uses its :pack/describe"
             (str/includes? (:eldoc (hover/lookup own :shop.jobs/nightly-digest)) "job  0 3 * * *"))
      (check "core: a problem says what its pack's :pack/messages says, while the pack is enabled"
             (let [p {:problem :job-unscheduled :dev-id :shop.jobs/x :severity :warning}]
               (and (= "meta-source: :shop.jobs/x has no schedule" (hover/message own p nil))
                    (= "meta-source: job-unscheduled :shop.jobs/x" (hover/message idx p nil)))))
      (check "core: a pack describing what another declares shares its ids, and the declaring one stays primary; declaring too, it's a warning"
             (let [with (fn [p] (index/build (update cfg :packs assoc 'smoke.packs/job-pack {} p {})))
                   shared #(set (map :dev-id (problems % :id-in-several-packs)))
                   d (with 'smoke.packs/describing-jobs-pack)]
               (and (not (contains? (shared d) :shop.jobs/nightly-digest))
                    (= :job (:pack (:definition (index/where d :shop.jobs/nightly-digest))))
                    (= "described" (:note/text (:entry (index/where d :shop.jobs/nightly-digest))))
                    (contains? (shared (with 'smoke.packs/declaring-jobs-pack)) :shop.jobs/nightly-digest)))))
    (check "core: two packs giving the same kind are refused"
           (= {:kinds [:job]}
              (try (index/build (update cfg :packs assoc 'smoke.packs/job-pack {} 'smoke.packs/job-again {})) nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))))
    ;; ---- a call that names a schema: a key written in it, in a key's place, is no key read
    (let [dir (str (fs/create-temp-dir))
          outside-of (fn [packs] (into {} (map (juxt :dev-id :keys))
                                       (problems (index/build {:root dir :src ["src"] :registry ["registry"] :packs packs
                                                               :dataflow {:consumes [:fn/reads]}})
                                                 :keys-outside-contract)))]
      (fs/create-dirs (str dir "/src/app")) (fs/create-dirs (str dir "/registry"))
      (spit (str dir "/registry/fns.edn") "{:fn/run {:fn/reads []} :fn/other {:fn/reads [:k/a]}}")
      (spit (str dir "/src/app/core.clj")
            (str "(ns app.core)\n(defn check [schema x] x)\n"
                 "(defn ^{:meta-source/id :fn/run} run [m] (check {:k/a :int} m))\n"))
      (check "core: a key in a map written in a call is read, unless a pack's :pack/schema-call? says the call names a schema"
             (and (= {:fn/run [:k/a]} (outside-of {}))
                  (= {} (outside-of {'smoke.packs/conforms-pack {}}))))
      (fs/delete-tree dir))
    (check "core rules: a form after a rules file's map is an error, not ignored"
             (let [dir (str (fs/create-temp-dir))]
               (spit (str dir "/r.edn") "{:r/a {:when {:has :x} :require [{:has :y}]}}\n :r/b {:when {:has :x} :require []}}")
               (try (= [[:unreadable-rules-file "something after the map, at line 2"]]
                       (for [p (index/check (index/build {:root dir :src [] :registry [] :rules ["r.edn"]}))]
                         [(:problem p) (:message p)]))
                    (finally (fs/delete-tree dir)))))
    (check "core: a :packs key no pack on the classpath is enabled by is an error, naming it"
           (= [:nope] (:keys (try (index/build (assoc-in cfg [:packs :nope] {})) nil
                                  (catch clojure.lang.ExceptionInfo e (ex-data e))))))
    (check "core: a pack that throws costs its files, not the index"
           (let [b (index/build (assoc-in cfg [:packs 'smoke.packs/broken-pack] {}))]
             (and (some #(and (= :broken (:pack %)) (str/includes? (:reason %) "boom")) (problems b :pack-form-not-read))
                  (index/where b :fn/register-user))))
    (let [d (index/build (assoc-in cfg [:packs 'smoke.packs/component-tier-pack] {}))]
      (check "core derive: a pack's derived fact fills a key no source gave, listed with :derived-by"
             (and (= :foundation (get-in d [:entities :shop/mailer :entry :app/tier]))
                  (some #(= :component-tiers (:derived-by %)) (:sources (index/where d :shop/mailer)))))
      (check "core derive: never over a source's, and no conflict"
             (and (= :service (get-in d [:entities :shop/metrics :entry :app/tier]))
                  (empty? (problems d :conflicting-values)))))))

(defn- calls-checks
  "Calls between linked vars, by id with :calls-by-id, and rules that
   follow them: no pack."
  []
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/src/app")) (fs/create-dirs (str dir "/registry")) (fs/create-dirs (str dir "/rules"))
    (spit (str dir "/registry/fns.edn")
          (str "{:fn/load {:fn/deps [:db/main]}\n"
               " :fn/save {:fn/deps []}\n"
               " :fn/run {:fn/deps [:fn/load]}\n"
               " :db/main {:db/kind :sql}}\n"))
    (spit (str dir "/src/app/core.clj")
          (str "(ns app.core)\n"
               "(defn exec [k & args] k)\n"
               "(defn ^{:meta-source/id :fn/load} load-impl [] 1)\n"
               "(defn ^{:meta-source/id :fn/save} save-impl [] 2)\n"
               ";; declared, by id; undeclared, by id; and a direct call\n"
               "(defn ^{:meta-source/id :fn/run} run-impl []\n"
               "  (exec :fn/load) (exec :fn/save) (save-impl))\n"))
    (spit (str dir "/rules/r.edn")
          (str "{:rule/run-off-db {:doc \"Nothing run reaches the database\"\n"
               "                   :when {:id :fn/run} :require [{:reaches {:none {:id :db/main}}}]}\n"
               " :rule/run-calls-save {:when {:id :fn/run} :require [{:calls {:some {:id :fn/save}}}]}\n"
               " :rule/save-called-by-none {:when {:id :fn/save} :require [{:called-by {:none {:id :fn/run}}}]}\n"
               " :rule/db-reached-by-none {:when {:id :db/main} :require [{:reached-by {:none {:id :fn/run}}}]}}\n"))
    (try
      (let [cfg {:root dir :src ["src"] :registry ["registry"] :rules ["rules"]}
            calls (fn [idx] (set (map (juxt :calls :by-id) (problems idx :undeclared-call))))
            violated (fn [idx rule] (filter #(= rule (:rule %)) (problems idx :rule-violated)))
            plain (index/build cfg)
            by-id (index/build (assoc cfg :calls-by-id '[app.core/exec]))]
        (check "core calls by id: without :calls-by-id only the direct call is undeclared"
               (= #{[:fn/save nil]} (calls plain)))
        (check "core calls by id: with it, (exec :fn/save) is an undeclared call too; (exec :fn/load), declared, isn't"
               (= #{[:fn/save nil] [:fn/save true]} (calls by-id)))
        (check "core calls by id: uses lists where it is called by id"
               (some #(and (:by-id %) (= :fn/run (:dev-id %))) (:called-from (index/uses by-id :fn/load))))
        (check "core reach: a linked var calling another's is a call between their entities; :calls and :called-by as relations"
               (and (= #{:fn/save} (get-in (index/edges plain) [:calls :fn/run]))
                    (empty? (violated plain :rule/run-calls-save))
                    (= [:fn/save] (map :dev-id (violated plain :rule/save-called-by-none)))))
        (check "core reach: :reaches follows deps and names the path; reached-by, the other way"
               (and (= [[:fn/run [:fn/load :db/main]]]
                       (for [f (violated plain :rule/run-off-db)] [(:dev-id f) (get (:paths f) :db/main)]))
                    (= [:fn/run :fn/load :db/main]
                       (:path (first (filter #(= :fn/run (:dev-id %)) (:reached-by (index/reached-by plain :db/main))))))
                    (= [:db/main] (map :dev-id (violated plain :rule/db-reached-by-none)))))
        (check "core reach: the brief line shows the path to what is reached"
               (some #(re-find #"Nothing run reaches the database: :fn/run \(:fn/load → :db/main\)" %) (hover/brief plain))))
      (finally (fs/delete-tree dir)))))

(defn- serve-checks
  "serve, what the editor talks to, with meta-source.edn edited while it runs."
  []
  (let [dir (fs/create-temp-dir {:prefix "meta-source-serve"})
        _ (fs/copy-tree core-root dir)
        conf (str (fs/path dir "meta-source.edn"))
        proc (p/process ["bb" "--config" "bb.edn" "meta-source" "serve" "--root" (str dir)] {:err :inherit})
        in (io/writer (:in proc))
        out (io/reader (:out proc))
        ask (fn [id file]
              (.write in (str (json/generate-string {:id id :op "diagnostics" :file file}) "\n"))
              (.flush in)
              (set (map :problem (:result (json/parse-string (.readLine out) true)))))]
    (try
      (spit conf "{:registry [\"registry\"] :src [\"src\"]}")
      (let [before (ask 1 "src/shop/jobs.clj")]
        (spit conf "{:registry [\"registry\"] :src [\"src\"] :packs {smoke.packs/broken-pack {}}}")
        (.setLastModified (fs/file conf) (+ 2000 (System/currentTimeMillis)))
        (let [after (ask 2 "src/shop/jobs.clj")]
          (check "core serve: reads meta-source.edn again when it changes: a pack enabled while it runs takes effect"
                 (and (not (contains? before "pack-form-not-read"))
                      (contains? after "pack-form-not-read")))))
      (finally (.close in) (p/destroy proc) (fs/delete-tree dir)))))

(defn- checks []
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

(defn run [] (as-core (fn [] (fixture-checks) (calls-checks) (checks) (serve-checks))))
