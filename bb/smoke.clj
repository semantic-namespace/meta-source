(ns smoke
  "Runs meta-source.index with the schema and Pathom packs over the shop
   fixture (test/fixtures/shop): clojure.spec s/def calls and a
   malli registry, Pathom 3 and 2 resolvers, plain annotated defns, and EDN
   files that give the functions' contracts and a data key's privacy class."
  (:require [meta-source.cli :as cli]
            [meta-source.hover :as hover]
            [meta-source.read]
            [meta-source.index :as index]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def failures (atom 0))

(defn- check [what ok?]
  (println (if ok? "ok  " "FAIL") what)
  (when-not ok? (swap! failures inc)))

(def root "test/fixtures/shop")

(def job-pack
  "A project's own pack: the shop's defjob registry. Enabled by its
   qualified symbol under :packs; a job reads the data keys :job/reads lists."
  {:pack/id :jobs
   :pack/kinds #{:job}
   :pack/vocabulary #{"job"}
   :pack/dataflow {:consumes [:job/reads]}
   :pack/find (fn [analysis _opts]
                (for [u (:var-usages analysis)
                      :when (and (= 'shop.scheduler (:to u)) (= 'defjob (:name u)))]
                  (select-keys u [:filename :row :name-row :name-col])))
   :pack/read (fn [sites {:keys [form-at own-ns]} _opts]
                (vec (for [{:keys [row name-row name-col]} sites
                           :let [[_ nm opts] (form-at name-row name-col)]]
                       {:row row :kind :job :dev-id (keyword own-ns (name nm)) :entry opts})))
   :pack/describe (fn [entry] (when-let [s (:job/schedule entry)] (str "job  " s)))})

(def pathom-again
  "A pack claiming a kind the Pathom pack gives."
  {:pack/id :pathom-again :pack/kinds #{:pathom} :pack/find (fn [_ _] []) :pack/read (fn [_ _ _] [])})

(defn- pathom-part
  "An entry's :pathom/* keys: :shop.resolvers/seed is also, deliberately,
   an integrant key."
  [entry]
  (into {} (filter #(= "pathom" (namespace (key %)))) entry))

(def broken-pack
  "A project's pack that fails on every file it finds."
  {:pack/id :broken :pack/kinds #{:broken}
   :pack/find (fn [analysis _] (for [u (:var-usages analysis) :when (= 'defjob (:name u))]
                                 (select-keys u [:filename :row :name-row :name-col])))
   :pack/read (fn [_ _ _] (throw (ex-info "boom" {})))})

(def tagged-pack
  "A pack whose library links vars with its own meta key, :app/id, and
   reports a var it links that nothing else describes."
  {:pack/id :tagged :pack/kinds #{:tagged} :pack/id-key :app/id
   :pack/find (fn [_ _] []) :pack/read (fn [_ _ _] [])
   :pack/checks (fn [{:keys [entities linked-by loc impl]}]
                  (for [[id e] (sort-by key entities)
                        :when (and (every? #(= :binding (:kind %)) (:definitions e)) (= :tagged (linked-by id)))]
                    {:severity :error :problem :tagged-without-entry :dev-id id :impl (impl id) :definition (loc e)}))})

(defn- notes-pack
  "A pack giving a note on every key the shop's system config has, as
   atlas's registry describes components: describing, or not."
  [describes?]
  {:pack/id :notes :pack/kinds #{:note} :pack/describes? describes?
   :pack/data-files (fn [_] ["resources/system.edn"])
   :pack/read-data (fn [files _]
                     (vec (for [{:keys [file text]} files
                                [k _ line] (meta-source.read/keyed-lines text {:tags :keep})
                                :when (qualified-keyword? k)]
                            {:file file :row line :kind :note :dev-id k :entry {:note/text "described"}})))})

(def describing-pack (notes-pack true))
(def declaring-pack (notes-pack false))

(def flow-pack
  "A pack deriving dataflow from what was read: an entry with :flow/in-key
   names the key that lists what an entity type consumes, as atlas's
   ontologies name :dataflow/context-key."
  {:pack/id :flow :pack/kinds #{:flow}
   :pack/find (fn [_ _] []) :pack/read (fn [_ _ _] [])
   :pack/dataflow-of (fn [entries] {:consumes (vec (keep :flow/in-key (vals entries)))})})

(def rules-pack
  "A pack deriving rules from what was read: an entry's :rule/as-data is a
   rule, as atlas's invariants carry :invariant/rule."
  {:pack/id :rules-of :pack/kinds #{:rules-of}
   :pack/find (fn [_ _] []) :pack/read (fn [_ _ _] [])
   :pack/rules-of (fn [entries] (into {} (for [[id e] entries :when (:rule/as-data e)] [id (:rule/as-data e)])))})

(defn- problems [idx problem]
  (filter #(= problem (:problem %)) (index/check idx)))

(defn -main [& _]
  (let [with-runtime (assoc (edn/read-string (slurp (str root "/meta-source.edn"))) :root root)
        ;; static reading alone: the checks below until "runtime snapshot"
        cfg (update-in with-runtime [:packs :pathom] dissoc :snapshot)
        idx (index/build cfg)
        email (index/data-key idx :user/email)]
    (check "every s/def and registry entry is read"
           (= {:spec {:read 7 :not-read 0} :malli {:read 2 :not-read 0}}
              (select-keys (:packs (index/summary idx)) [:spec :malli])))
    (check "what a pack gives is valid in the library's own vocabulary"
           (empty? (problems idx :entity-without-type)))
    (check "no errors: a data key with only a privacy entry is fine"
           (not-any? #(= :error (:severity %)) (index/check idx)))

    (check "a spec merges with what EDN says about the same key"
           (= {:spec/form "(s/and string? #(re-find #\"@\" %1))" :data/privacy :pii}
              (:entry (index/where idx :user/email))))
    (check "its primary definition is the s/def"
           (= {:file "src/shop/spec.clj" :line 5 :pack :spec} (:definition (index/where idx :user/email))))
    (check "data: shape, other props, the schemas it is a key of, producers"
           (and (= [:spec] (map :pack (:shape email)))
                (= {:data/privacy :pii} (:props email))
                (= [:shop.spec/user] (map :dev-id (:member-of email)))
                (= [:fn/register-user :shop.resolvers/order-buyer :shop.resolvers/user-by-id] (map :dev-id (:producers email)))))
    (check "a .cljc s/def is read with its reader conditional as :clj"
           (= "decimal?" (:form (first (:shape (index/data-key idx :order/total))))))
    (check "a registry's unqualified key (:map, overriding malli's own) is not a data key"
           (nil? (index/where idx :map)))
    (check "a malli registry entry, with its :map keys"
           (= {:malli/form "[:vector [:map [:product/id :uuid] [:qty :int]]]"
               :malli/keys [:product/id] :malli/unqualified-keys [:qty]}
              (:entry (index/where idx :order/lines))))

    (check "every defresolver and defmutation is read, Pathom 3 and 2"
           (= {:read 10 :not-read 1} (:pathom (:packs (index/summary idx)))))
    (check "the one not read is the pco/defresolver inside the wrapper macro, whose name is ~name"
           (= [[:pathom "src/shop/connect.clj" 7]] (map (juxt :pack :file :line) (problems idx :pack-form-not-read))))
    (check "an option naming a def in the same file is read through it"
           (= [:user/id :user/name :user/email] (get-in (index/where idx :shop.resolvers/user-by-id) [:entry :pathom/output])))
    (check "and inside a join"
           (= [:order/buyer :user/id :user/name :user/email]
              (get-in (index/where idx :shop.resolvers/order-buyer) [:entry :pathom/output])))
    (check "a project's own wrapper macro, named in :resolver-macros"
           (= #:pathom{:op :resolver :input [:product/id] :output [:product/price] :inferred #{:input :output}}
              (:entry (index/where idx :shop.wrapped/product-price))))
    (check "an option written as code is not guessed, and check says so"
           (and (= #:pathom{:op :resolver :not-read #{:output}} (pathom-part (:entry (index/where idx :shop.resolvers/seed))))
                (= [[:shop.resolvers/seed [:output]]] (map (juxt :dev-id :options) (problems idx :operation-option-not-read)))))
    (check "while an output is written as code (seed's), no input is reported as unproduced: seed may produce it"
           (empty? (problems idx :input-without-producer)))
    (check "declared input and output"
           (= #:pathom{:op :resolver :input [:user/email] :output [:user/id :user/name]}
              (:entry (index/where idx :shop.resolvers/user-by-email))))
    (check "inferred input from the destructuring, output from the returned map"
           (= #:pathom{:op :resolver :input [:order/lines] :output [:order/total] :inferred #{:input :output}}
              (:entry (index/where idx :shop.resolvers/order-total))))
    (check "a join's key and the keys inside it are outputs; env is not the input"
           (= #:pathom{:op :resolver :input [:user/id] :output [:user/orders :order/id :order/total] :inferred #{:input}}
              (:entry (index/where idx :shop.resolvers/user-orders))))
    (check "a mutation's params are what it consumes"
           (= #:pathom{:op :mutation :params [:user/id :order/lines] :output [:order/id]}
              (:entry (index/where idx :shop.resolvers/place-order!))))
    (check "Pathom 2: a set input"
           (= [:product/id] (get-in (index/where idx :shop.resolvers2/product-name) [:entry :pathom/input])))
    (check "one data key across three registries: an EDN contract and a resolver produce it, a resolver consumes it, a spec shapes it"
           (and (= [:fn/register-user :shop.resolvers/order-buyer :shop.resolvers/user-by-id] (map :dev-id (:producers email)))
                (= [:fn/register-user :shop.resolvers/user-by-email] (map :dev-id (:consumers email)))))
    (check "resolver outputs count as producers, next to function contracts"
           (= [:fn/place-order :shop.resolvers/order-total :shop.resolvers/user-orders]
              (map :dev-id (:producers (index/data-key idx :order/total)))))
    (check "a resolver input nothing produces, once every output is known (seed's from the Pathom snapshot)"
           (= [[:shop.resolvers/order-total [:order/lines]]
               [:shop.resolvers2/product-name [:product/id]]
               [:shop.wrapped/product-price [:product/id]]]
              (map (juxt :dev-id :keys) (problems (index/build with-runtime) :input-without-producer))))
    (check "hover: a resolver's inputs and outputs"
           (str/includes? (:eldoc (hover/lookup idx :shop.resolvers/order-total))
                          "resolver  [:order/lines] → [:order/total]  (inferred: input, output)"))
    (check ":dataflow in meta-source.edn makes a project's own keys consume data"
           (let [own (index/build (-> cfg (update :registry conj "registry-jobs") (update-in [:dataflow :consumes] conj :job/reads)))]
             (and (= [:fn/register-user :job/nightly-digest :shop.resolvers/user-by-email]
                     (map :dev-id (:consumers (index/data-key own :user/email))))
                  (empty? (:consumers (index/data-key idx :order/total))))))

    (let [own (index/build (assoc-in cfg [:packs 'smoke/job-pack] {}))]
      (check "a project's own pack, named by its symbol, reads its own macro"
             (= {:job/reads [:user/email :order/total] :job/schedule "0 3 * * *"}
                (:entry (index/where own :shop.jobs/nightly-digest))))
      (check "its :pack/dataflow makes the job a consumer next to functions and resolvers"
             (= [:fn/register-user :shop.jobs/nightly-digest :shop.resolvers/user-by-email]
                (map :dev-id (:consumers (index/data-key own :user/email)))))
      (check "its entities are valid as they are, and it counts its reads"
             (and (empty? (problems own :entity-without-type))
                  (= {:read 1 :not-read 0} (:job (:packs (index/summary own))))))
      (check "the hover uses its :pack/describe"
             (str/includes? (:eldoc (hover/lookup own :shop.jobs/nightly-digest)) "job  0 3 * * *")))
    (check "two packs giving the same kind are refused"
           (= {:kinds [:pathom]}
              (try (index/build (assoc-in cfg [:packs 'smoke/pathom-again] {})) nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))))

    ;; ---- integrant: resources/*.edn and shop.system
    (let [ig #(:entry (index/where idx %))
          ig-part #(into {} (filter (fn [[k _]] (= "integrant" (namespace k)))) (ig %))]
      (check "every config component, defmethod and derive is read"
             (= {:read 13 :not-read 0} (:integrant (:packs (index/summary idx)))))
      (check "a component's refs, from #ig/ref, nested in its config"
             (= [:shop/db :shop/mailer :shop/metrics] (:integrant/refs (ig :shop/handler))))
      (check "variant files configuring one key: one entity, every file listed, :varies"
             (= #:integrant{:config-in ["resources/system.edn:2" "resources/system.test.edn:2"]
                            :env ["DATABASE_URL"] :varies true :init-key true :halt-key! true}
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
    (check "a :refer of a pack's macro is not read as a form"
           (not-any? #(= "src/shop/resolvers.clj" (:file %)) (problems idx :pack-form-not-read)))
    (check "a pack that throws costs its files, not the index"
           (let [b (index/build (assoc-in cfg [:packs 'smoke/broken-pack] {}))]
             (some #(and (= :broken (:pack %)) (str/includes? (:reason %) "boom")) (problems b :pack-form-not-read))))

    ;; ---- re-frame: src/shop/ui.cljs
    (let [rf (index/build (assoc-in cfg [:packs :re-frame] {:provided [:http-xhrio]}))
          entry #(:entry (index/where rf %))
          found #(set (map :dev-id (problems rf %)))]
      (check "every registration and reference is read, in a .cljs file, with the ids held in literal vectors"
             (= {:read 26 :not-read 0} (:re-frame (:packs (index/summary rf)))))
      (check "an event: its handler, what its effects dispatch (a callback among them), its effects and writes"
             (= #:re-frame{:registered #{:event} :handler :fx :dispatched true :held true
                           :dispatches [:shop.ui/pay :shop.ui/paid] :effects [:http-xhrio] :writes [:order/state]}
                (entry :shop.ui/checkout)))
      (check ":fx entries: an effect and a dispatch"
             (= [[:shop.ui/clear-cart] [:notify]] ((juxt :re-frame/dispatches :re-frame/effects) (entry :shop.ui/paid))))
      (check "a subscription's :<- signals are references"
             (= [{:dev-id :shop.ui/line-count :via :re-frame/signals}]
                (map #(select-keys % [:dev-id :via]) (:referenced-by (index/uses rf :shop.ui/lines)))))
      (check "its primary definition is the registration, not a place that refers to it"
             (= 9 (:line (:definition (index/where rf :shop.ui/checkout)))))
      (check "a dispatch and a subscription to ids nothing registers, where they are written"
             (= [[:sub-not-registered :shop.ui/line-cuont 31] [:event-not-registered :shop.ui/pay 14]]
                (for [p (index/check rf) :when (#{:sub-not-registered :event-not-registered} (:problem p))]
                  [(:problem p) (:dev-id p) (:line (:definition p))])))
      (check "registered and referred to nowhere: reported only with :report-unused; :provided and library effects never"
             (let [unused (index/build (assoc-in cfg [:packs :re-frame] {:report-unused true}))
                   found-unused #(set (map :dev-id (problems unused %)))]
               (and (empty? (found :event-never-dispatched))
                    (empty? (found :sub-never-subscribed))
                    (= #{:shop.ui/log-out} (found-unused :event-never-dispatched))
                    (= #{:shop.ui/line-count} (found-unused :sub-never-subscribed))
                    (empty? (found :effect-not-registered))
                    ;; day8.re-frame/http-fx's :http-xhrio needs no :provided
                    (empty? (problems (index/build (assoc-in cfg [:packs :re-frame] {})) :effect-not-registered)))))
      (check "one key across four registries: a spec shapes it, EDN calls it PII, a resolver and a subscription read it"
             (let [email (index/data-key rf :user/email)]
               (and (= [:fn/register-user :shop.resolvers/user-by-email :shop.ui/email] (map :dev-id (:consumers email)))
                    (some #{:shop.ui/set-email} (map :dev-id (:producers email)))
                    (not-any? #{:shop.ui/log-out} (map :dev-id (:producers email))))))
      (check "a key an event writes that no schema shapes"
             (contains? (set (map :keys (problems rf :data-key-without-schema))) [:order/state]))
      (check "the editor underlines ::pay where it is written"
             (some #(= {:line 14 :col 17 :end-col 22 :problem "event-not-registered"} (select-keys % [:line :col :end-col :problem]))
                   (hover/diagnostics rf "src/shop/ui.cljs")))
      (check "the hover describes an event"
             (str/includes? (:eldoc (hover/lookup rf :shop.ui/checkout)) "event (fx)")))

    ;; ---- diagnostics: what an editor underlines, per file
    (let [diags #(set (map (juxt :line :col :end-col :severity :problem) (hover/diagnostics idx %)))]
      (check "diagnostics: a key used outside the contract, at the key in the code"
             (contains? (diags "src/shop/handlers.clj") [10 49 64 "note" "keys-outside-contract"]))
      (check "diagnostics: an absolute path, as editors send"
             (= (diags "src/shop/handlers.clj")
                (diags (.getCanonicalPath (java.io.File. (str root "/src/shop/handlers.clj"))))))
      (check "diagnostics: a key only destructured, at its name, within its own form (seed's output from the Pathom snapshot)"
             (contains? (set (map (juxt :line :col :end-col :severity :problem)
                                  (hover/diagnostics (index/build with-runtime) "src/shop/resolvers.clj")))
                        [13 45 50 "note" "input-without-producer"]))
      (check "diagnostics: a dev-id written as the defining form's symbol"
             (contains? (diags "src/shop/resolvers.clj") [48 18 22 "warning" "id-in-several-packs"]))
      (check "diagnostics: severities follow check's"
             (contains? (diags "resources/system.edn") [10 84 97 "warning" "ref-to-undefined"]))
      (check "diagnostics: a file with nothing to report"
             (empty? (diags "src/shop/order.cljc"))))

    ;; ---- check: most severe first; --brief, one line each
    (check "check lists errors, then warnings, then info"
           (let [ranks (map #(get {:error 0 :warning 1} (:severity %) 2) (index/check idx))]
             (= ranks (sort ranks))))
    (check "check --brief: severity, file:line, and the editor's message"
           (some #(re-matches #"warning +resources/system\.edn:10 +no config defines :shop/metrics" %) (hover/brief idx)))

    ;; ---- the meta key, and the merge
    (check "a var with the meta key and no entry: a binding-only entity, valid, reported as info"
           (and (= {:file "src/shop/handlers.clj" :line 13 :binding true} (:definition (index/where idx :fn/cancel-order)))
                (= [[:fn/cancel-order :info]] (map (juxt :dev-id :severity) (problems idx :binding-only)))
                (= "binding" (second (re-find #"^:fn/cancel-order  (\S+)" (:eldoc (hover/lookup idx :fn/cancel-order)))))))
    (check "the meta key is configurable: :id-key"
           (and (nil? (index/where idx :fn/legacy-report))
                (= 'shop.legacy/legacy-report
                   (get-in (index/where (index/build (assoc cfg :id-key :shop/id)) :fn/legacy-report) [:impl :var]))))
    (check "two files giving one key different sets: a conflict, unless :merge says :union"
           (let [tags (update cfg :registry conj "registry-tags")]
             (and (= [[:user/email :data/tags]] (map (juxt :dev-id :key) (problems (index/build tags) :conflicting-values)))
                  (= #{:contact :pii} (get-in (index/where (index/build (assoc tags :merge {:data/tags :union})) :user/email) [:entry :data/tags])))))

    (check "data keys are not dependencies"
           (empty? (:depends-on (index/dependencies idx :fn/place-order))))
    (check "a typo in s/keys: a key with no spec of its own"
           (= [{:dev-id :shop.spec/user :keys [:user/emial]}]
              (map #(select-keys % [:dev-id :keys]) (problems idx :schema-key-without-shape))))
    (check "unqualified malli keys, which dataflow can't follow"
           (= [[:order/lines [:qty]]] (map (juxt :dev-id :keys) (problems idx :unqualified-schema-keys))))
    (check "a contract key no schema shapes"
           (= [[:fn/register-user [:user/phone]]
               [:shop.resolvers/order-buyer [:order/buyer]]
               [:shop.resolvers/user-discount [:user/loyalty-tier]]
               [:shop.resolvers/user-orders [:user/orders]]
               [:shop.resolvers2/product-name [:product/name]]
               [:shop.wrapped/product-price [:product/price]]]
              (map (juxt :dev-id :keys) (problems idx :data-key-without-schema))))
    (check "code reading a spec'd key that no contract names"
           (= [[:fn/place-order [:order/discount]]] (map (juxt :dev-id :keys) (problems idx :keys-outside-contract))))

    (let [h (hover/lookup idx :user/email)]
      (check "hover: a shaped key is shown as a data key, spec and privacy on the eldoc line"
             (and (= "data-key" (:kind h))
                  (str/includes? (:eldoc h) "spec (s/and string?")
                  (str/includes? (:eldoc h) ":data/privacy :pii")))
      (check "hover: M-. goes to the s/def first"
             (str/ends-with? (:file (first (:definitions h))) "src/shop/spec.clj")))

    (let [plain (index/build (dissoc cfg :packs))]
      (check "the pack is opt-in: without :packs no s/def becomes an entity"
             (and (nil? (index/where plain :shop.spec/user))
                  (empty? (problems plain :data-key-without-schema)))))

    ;; ---- runtime snapshot: snapshots/pathom.edn, written from real Pathom 3
    ;; and 2 environments by make_pathom_snapshot.clj
    (let [rt (index/build with-runtime)]
      (check "the snapshot is read: 10 operations the running environments registered"
             (= 10 (get-in (index/summary rt) [:pack-snapshots :pathom :entities])))
      (check "code and the running environments agree on every operation both have"
             (empty? (problems rt :runtime-disagrees)))
      (check "an output written as code is filled from the running environment"
             (= #:pathom{:op :resolver :output [:user/loyalty-tier] :from-runtime #{:output}}
                (pathom-part (:entry (index/where rt :shop.resolvers/seed)))))
      (check "so the input it produces is no longer unproduced, and nothing is left unread"
             (and (not-any? #(= :shop.resolvers/user-discount (:dev-id %)) (problems rt :input-without-producer))
                  (empty? (problems rt :operation-option-not-read))
                  (not-any? :unread-outputs (problems rt :input-without-producer))))
      (check "a resolver built as a value: only the running environment has it"
             (and (= [:shop.resolvers/list-price] (map :dev-id (problems rt :runtime-only)))
                  (= [:shop.resolvers/list-price] (map :dev-id (:producers (index/data-key rt :product/list-price))))))
      (check "a resolver defined in the code and never registered"
             (= [:shop.resolvers/order-buyer] (map :dev-id (problems rt :not-in-runtime))))
      (check "where lists the running environment as a source"
             (some :runtime-as-of (:sources (index/where rt :shop.resolvers/user-by-email))))
      (check "the hover says where an entity only the running environment has comes from"
             (str/includes? (:describe (hover/lookup rt :shop.resolvers/list-price)) "running process: snapshot")))

    ;; ---- code edited after the snapshot, and a snapshot that disagrees with
    ;; unchanged code: on a copy, with mtimes set
    (let [dir (fs/create-temp-dir {:prefix "meta-source-smoke"})
          _ (fs/copy-tree root dir)
          snap (str (fs/path dir "snapshots" "pathom.edn"))
          past (- (System/currentTimeMillis) 60000)
          data (edn/read-string (slurp snap))]
      (try
        (doseq [f (fs/glob dir "**")] (.setLastModified (fs/file f) past))
        ;; the snapshot was taken now, after every file; one entity disagrees
        (spit snap (pr-str (-> data
                               (assoc :written (+ past 30000))
                               (update-in [:entities :shop.wrapped/product-price :pathom/output] conj :product/currency))))
        ;; then the code changes: user-by-email now also outputs :user/phone
        (let [f (str (fs/path dir "src" "shop" "resolvers.clj"))]
          (spit f (str/replace-first (slurp f) "::pco/output [:user/id :user/name]}" "::pco/output [:user/id :user/name :user/phone]}"))
          (.setLastModified (fs/file f) (System/currentTimeMillis)))
        (let [idx (index/build (assoc with-runtime :root (str dir)))
              ds (into {} (map (juxt :dev-id identity)) (problems idx :runtime-disagrees))]
          (check "a disagreement names the slot and which side has each key"
                 (and (= {:pathom/output {:only-in-code [:user/phone]}} (get-in ds [:shop.resolvers/user-by-email :diff]))
                      (= {:pathom/output {:only-at-runtime [:product/currency]}} (get-in ds [:shop.wrapped/product-price :diff]))))
          (check "and says when the file changed after the snapshot, so the snapshot may be stale"
                 (and (true? (get-in ds [:shop.resolvers/user-by-email :changed-since-snapshot]))
                      (false? (get-in ds [:shop.wrapped/product-price :changed-since-snapshot]))))
          (check "a disagreement is a warning"
                 (every? #(= :warning (:severity %)) (vals ds))))
        (finally (fs/delete-tree dir))))

    ;; ---- reitit: src/shop/routes.clj, links in src/shop/ui.cljs
    (let [ri (index/build (assoc-in cfg [:packs :reitit] {}))
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
      (check "a route consumes its parameter keys, next to resolvers and functions"
             (some #{:shop.routes/user} (map :dev-id (:consumers (index/data-key ri :user/id))))))

    ;; ---- datascript: src/shop/db.clj, schema/datomic.edn
    (let [di (index/build (assoc-in cfg [:packs :datascript] {:schemas ["schema"]}))
          found (fn [p] (map (juxt :dev-id (comp :line :definition) :severity) (problems di p)))]
      (check "a schema map in a def, and a Datomic-style schema file, attribute by attribute"
             (and (= {:read 11 :not-read 0} (:datascript (:packs (index/summary di))))
                  (= #:datascript{:value-type :db.type/ref :cardinality :db.cardinality/many}
                     (select-keys (:entry (index/where di :user/orders)) [:datascript/value-type :datascript/cardinality]))
                  (= #:datascript{:value-type :db.type/string :unique :db.unique/identity :doc "A product's stock-keeping unit"}
                     (:entry (index/where di :product/sku)))))
      (check "an attribute is a data key: its Datascript shape beside its spec"
             (= [:datascript :spec] (map :pack (:shape (index/data-key di :user/name)))))
      (check "a pull join on an attribute that isn't a ref; a reverse read of one"
             (and (= [[:order/lines 15 :warning]] (found :pull-join-not-ref))
                  (= [[:order/lines 25 :warning]] (found :reverse-not-ref))))
      (check "a reverse read and a join in a quoted query's pull, on a ref: nothing"
             (not-any? #{:user/orders} (map :dev-id (concat (problems di :pull-join-not-ref) (problems di :reverse-not-ref)))))
      (check "a lookup ref on an attribute no schema read gives: info"
             (= [[:user/id 19 :info]] (found :lookup-ref-not-unique)))
      (check "many in the Datascript schema, one string? in its spec"
             (= [[:user/name 9 :warning]] (found :schema-disagrees))))

    ;; ---- aero: test/fixtures/aero
    (let [ai (index/build (assoc (edn/read-string (slurp "test/fixtures/aero/meta-source.edn")) :root "test/fixtures/aero"))
          entry #(:entry (index/where ai %))
          found (fn [p] (map (juxt :dev-id (comp :line :definition) :severity) (problems ai p)))]
      (check "every top-level key's tags read, and the readers the code defines"
             (= {:read 16 :not-read 0} (:aero (:packs (index/summary ai)))))
      (check "an #env: its default from an #or, what it is read as, whether a deploy file sets it"
             (and (= #:aero{:env true :defaults #{"jdbc:postgresql://localhost/app"} :set-in-deploy true} (entry :env/DB_URL))
                  (= #{:long} (:aero/coerced (entry :env/DB_POOL)))))
      (check "in #or [#env A #env B], A falls back to B; B, with no default, is required"
             (and (not (:aero/required (entry :env/PRIMARY_ID))) (:aero/required (entry :env/SECONDARY_ID))))
      (check "a required variable no :env-sources file sets: info"
             (= [[:env/API_KEY 4 :info]] (found :env-not-set)))
      (check "an #include found nowhere is a warning; one :provided lists, or that exists, isn't"
             (= [[:aero.include/nowhere.edn 9 :warning]] (found :include-not-found)))
      (check "a tag with no reader is a warning; one a defmethod of aero.core/reader defines isn't"
             (= [[:aero.tag/duration 16 :warning]] (found :tag-without-reader)))
      (check "a #profile no configured profile reads, and one that reads nil"
             (and (= [[:ttl [:dev :staging]]] (map (juxt :dev-id :keys) (problems ai :profile-never-read)))
                  (= [[:ttl [:prod :test]]] (map (juxt :dev-id :keys) (problems ai :profile-without-branch)))))
      (check "a #ref to a path the config doesn't hold"
             (= [[:broken [[:db :host]]]] (map (juxt :dev-id :paths) (problems ai :ref-to-nothing))))
      (check "#long over a default that isn't a number, at the tag"
             (= [[:db 3 :warning]] (found :not-a-number))))

    ;; ---- the project's own rules: rules/shop.edn, rules-broken/broken.edn
    (let [ri (index/build (assoc cfg :rules ["rules"]))
          by (fn [i rule] (filter #(= rule (:rule %)) (index/check i)))
          pii (first (by ri :rule/pii-readers-audited))]
      (check "a rule over a relation: a PII key's consumers must be audited, naming the ones that aren't"
             (and (= :warning (:severity pii)) (= :user/email (:dev-id pii))
                  (= [:fn/register-user :shop.resolvers/user-by-email] (:offenders pii))
                  (= {:file "rules/shop.edn" :line 2} (:rule-at pii))))
      (check "the editor marks the offenders too, where they are defined"
             (some #(= "rule-violated" (:problem %)) (hover/diagnostics ri "src/shop/resolvers.clj")))
      (check "a rule over a pack's facts, at its own severity"
             (= #{:shop/http-server :shop.system/const :shop.system/unused}
                (set (map :dev-id (filter #(= :info (:severity %)) (by ri :rule/components-halt))))))
      (check "a rule that holds gives nothing"
             (empty? (by ri :rule/functions-say-what-they-need)))
      (check "a rule it can't understand is an error at the rule, saying why"
             (= [{:problem :rule-not-understood :file "rules-broken/broken.edn" :line 1 :reasons ["unknown condition :kynd"]}]
                (map #(select-keys % [:problem :file :line :reasons])
                     (by (index/build (assoc cfg :rules ["rules-broken"])) :rule/misspelled))))
      (check ":not, :id-ns, :in, :or, :used-by :some, :deps :none, :producers :some with a set of kinds"
             (= {:more/resolvers-outside-their-ns [:shop.resolvers2/product-name :shop.wrapped/product-price]
                 :more/components-someone-refers-to [:shop/base-path :shop/cache :shop.resolvers/seed]}
                (update-vals (group-by :rule (filter :rule (index/check (index/build (assoc cfg :rules ["rules-more"])))))
                             #(mapv :dev-id %))))
      (check "a form after a rules file's map is an error, not ignored"
             (let [dir (str (fs/create-temp-dir))]
               (spit (str dir "/r.edn") "{:r/a {:when {:has :x} :require [{:has :y}]}}\n :r/b {:when {:has :x} :require []}}")
               (try (= [[:unreadable-rules-file "something after the map, at line 2"]]
                       (for [p (index/check (index/build {:root dir :src [] :registry [] :rules ["r.edn"]}))]
                         [(:problem p) (:message p)]))
                    (finally (fs/delete-tree dir)))))
      (check "the brief line names the rule's doc, the entity and the offenders"
             (some #(str/includes? % "Whatever reads a PII key is audited: :user/email (:fn/register-user :shop.resolvers/user-by-email)")
                   (hover/brief ri))))

    ;; ---- function contracts the code writes: test/fixtures/contracts, s/fdef and m/=>
    (let [fc (index/build (assoc (edn/read-string (slurp "test/fixtures/contracts/meta-source.edn"))
                                 :root "test/fixtures/contracts"))
          outside (into {} (map (juxt :dev-id :keys)) (problems fc :keys-outside-contract))]
      (check "an s/fdef: an entity keyed by its var, its :args and :ret keys what it consumes and produces"
             (= #:spec{:args "(s/cat :order :shop.checkout/order)" :args-keys [:shop.checkout/order]
                       :ret ":shop.checkout/receipt" :ret-keys [:shop.checkout/receipt]}
                (:entry (index/where fc :shop.checkout/total))))
      (check "its var implements it, with no meta key"
             (= 'shop.checkout/total (:var (:impl (index/where fc :shop.checkout/total)))))
      (check "an m/=>: the keys of its :=> input and output"
             (= [:order/discount :order/id] (:malli/args-keys (:entry (index/where fc :shop.checkout/line-count)))))
      (check "a key of an :args spec's s/keys is within the contract; a key outside it is reported"
             (= {:shop.checkout/total [:user/loyalty-tier] :shop.checkout/line-count [:order/lines]
                 :shop.checkout/paid? [:order/state] :shop.checkout/tier-discount [:user/loyalty-tier]}
                outside))
      (check "a defn's {:malli/schema ...} metadata, read from clj-kondo's analysis"
             (= #:malli{:fn "[:=> [:cat [:map :order/id :order/total]] :boolean]" :args-keys [:order/id :order/total]}
                (:entry (index/where fc :shop.checkout/paid?))))
      (check "mx/defn's :- annotations, as [:=> [:cat ...] ret]; clj-kondo knows it defines the var"
             (and (= #:malli{:fn "[:=> [:cat [:map :order/total]] :order/discount]"
                             :args-keys [:order/total] :ret-keys [:order/discount]}
                     (:entry (index/where fc :shop.checkout/tier-discount)))
                  (= 'shop.checkout/tier-discount (:var (:impl (index/where fc :shop.checkout/tier-discount))))))
      (check "a function is not a data key, and consumes the spec its :args names"
             (= [:shop.checkout/discounted? :shop.checkout/total]
                (map :dev-id (:consumers (index/data-key fc :shop.checkout/order)))))
      (check "a spec named in s/valid? is not a key read; an s/alt tag is not a spec the fn consumes"
             (and (not (contains? outside :shop.checkout/gold?))
                  (nil? (:spec/args-keys (:entry (index/where fc :shop.checkout/gold?))))))
      (check "a schema named in a call to a fn :schema-fns lists is not a key read"
             (not (contains? outside :shop.checkout/receipt?)))
      (check "the hover shows the fdef"
             (str/includes? (:eldoc (hover/lookup fc :shop.checkout/total)) "fn  (s/cat :order :shop.checkout/order) → :shop.checkout/receipt")))

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

    ;; ---- a spec defined twice, differently
    (let [dir (str (fs/create-temp-dir))]
      (fs/create-dirs (str dir "/src/app"))
      (spit (str dir "/src/app/a.clj") "(ns app.a (:require [clojure.spec.alpha :as s]))\n(s/def :user/id string?)\n")
      (spit (str dir "/src/app/b.clj") "(ns app.b (:require [clojure.spec.alpha :as s]))\n(s/def :user/id uuid?)\n")
      (let [ci (index/build {:root dir :src ["src"] :registry [] :packs {:spec {}}})]
        (check "two specs of one key conflict, and its shape shows each, where it is written"
               (and (= [:spec/form] (map :key (problems ci :conflicting-values)))
                    (= #{["string?" "src/app/a.clj"] ["uuid?" "src/app/b.clj"]}
                       (set (map (juxt :form :file) (:shape (index/data-key ci :user/id))))))))
      (fs/delete-tree dir))

    ;; ---- dataflow a pack derives from what was read
    (let [dir (str (fs/create-temp-dir))]
      (fs/create-dirs (str dir "/registry"))
      (spit (str dir "/registry/r.edn") "{:ont/widget {:flow/in-key :widget/takes}\n :fn/w {:widget/takes [:user/id]}}")
      (let [fi (index/build {:root dir :src [] :registry ["registry"] :packs {'smoke/flow-pack {}}})]
        (check "a pack's :pack/dataflow-of: a key an ontology entry names lists what its entities consume"
               (= [:fn/w] (map :dev-id (:consumers (index/data-key fi :user/id))))))
      (fs/delete-tree dir))

    ;; ---- the tilakone pack: state machines read as data
    (let [dir (str (fs/create-temp-dir))]
      (fs/create-dirs (str dir "/src/shop"))
      (spit (str dir "/src/shop/order.clj")
            (str "(ns shop.order\n  (:require [tilakone.core :as tk :refer [_]]))\n\n"
                 "(def fsm\n"
                 "  {::tk/states [{::tk/name ::cart\n"
                 "                 ::tk/transitions [{::tk/on ::checkout ::tk/to ::paying}]}\n"
                 "                {::tk/name ::paying\n"
                 "                 ::tk/transitions [{::tk/on ::paid ::tk/to ::shiped}\n"
                 "                                   {::tk/on _ ::tk/to ::cart}]}\n"
                 "                {::tk/name ::shipped}\n"
                 "                {::tk/name ::cart}]\n"
                 "   ::tk/state ::cart})\n\n"
                 "(def bad-start {::tk/states [{::tk/name ::a}] ::tk/state ::b})\n"))
      (let [ti (index/build {:root dir :src ["src"] :registry [] :packs {:tilakone {}}})
            ps (fn [p] (set (map (juxt :dev-id :state :to (comp :line :definition)) (problems ti p))))]
        (check "tilakone: a transition to no state, at the transition's line"
               (= #{[:shop.order/fsm :shop.order/paying :shop.order/shiped 8]} (ps :transition-to-no-state)))
        (check "tilakone: an initial state that is no state, and a state defined twice"
               (and (= #{[:shop.order/bad-start nil :shop.order/b 14]} (ps :initial-state-not-a-state))
                    (= #{[:shop.order/fsm :shop.order/cart nil 11]} (ps :state-defined-twice))))
        (check "tilakone: a state no transition reaches is info, at the state"
               (= #{[:shop.order/fsm :shop.order/shipped nil 10]} (ps :state-unreachable)))
        (check "tilakone: a state is an entity; what goes to it uses it; _ is :tilakone/any"
               (and (= #{:shop.order/fsm} (set (get-in (index/where ti :shop.order/paying) [:entry :tilakone/machines])))
                    (contains? (set (get-in (index/where ti :shop.order/paying) [:entry :tilakone/on])) :tilakone/any)
                    (= #{:shop.order/cart :shop.order/fsm}
                       (set (map :dev-id (:referenced-by (index/uses ti :shop.order/paying))))))))
      (fs/delete-tree dir))

    ;; ---- re-frame: a project's own registration and dispatch fns
    (let [dir (str (fs/create-temp-dir))]
      (fs/create-dirs (str dir "/src/app"))
      (spit (str dir "/src/app/rf.clj")
            (str "(ns app.rf (:require [re-frame.core :as rf]))\n"
                 "(def reg-event-fx rf/reg-event-fx)\n"
                 "(def dispatch rf/dispatch)\n"
                 "(defn reg-root-sub [id k] (rf/reg-sub id (fn [db _] (get db k))))\n"
                 "(defmacro defevent [n m & body] `(defn ~n ~@body))\n"))
      (spit (str dir "/src/app/ui.clj")
            (str "(ns app.ui (:require [re-frame.core :as re-frame] [app.rf :as arf]))\n"
                 "(arf/reg-event-fx ::load (fn [_ _] {:fx [[:dispatch [::loaded]]]}))\n"
                 "(arf/defevent loaded {:events [::loaded]} [cofx] {})\n"
                 "(arf/reg-root-sub ::user :user)\n"
                 "(defn view [] (arf/dispatch [::load]) @(re-frame/subscribe [::user]))\n"
                 ";; :fx a local names\n"
                 "(arf/reg-event-fx ::batch (fn [_ _] (let [requests [[:dispatch [::load]]]] {:fx requests})))\n"
                 "(defn go [] (arf/dispatch [::batch]))\n"))
      (let [cfg {:root dir :src ["src"] :registry [] :packs {:re-frame {}}}
            plain (index/build cfg)
            declared (index/build (assoc-in cfg [:packs :re-frame :wrappers]
                                            '{app.rf/reg-event-fx reg-event-fx app.rf/dispatch dispatch
                                              app.rf/reg-root-sub reg-sub
                                              app.rf/defevent {:registers :event :ids-in :events}}))
            warned (fn [idx] (set (map :dev-id (filter #(#{:event-not-registered :sub-not-registered} (:problem %)) (index/check idx)))))
            suggested (set (keep #(second (re-find #"^(\S+) passes its argument" (str (:reason %))))
                                 (problems plain :pack-form-not-read)))]
        (check "re-frame: without :wrappers, a sub the project registers through its own fn looks unregistered"
               (= #{:app.ui/user} (warned plain)))
        (check "re-frame: a def or defn passing its first argument to re-frame is suggested as a wrapper, with the line to add"
               (= #{"app.rf/reg-event-fx" "app.rf/dispatch" "app.rf/reg-root-sub"} suggested))
        (check "re-frame: with :wrappers declared, their calls register and dispatch; a macro's :events register; nothing is suggested"
               (and (empty? (warned declared))
                    (= #{:event} (get-in (index/where declared :app.ui/loaded) [:entry :re-frame/registered]))
                    (true? (get-in (index/where declared :app.ui/load) [:entry :re-frame/dispatched]))
                    (not-any? #(re-find #"passes its argument" (str (:reason %))) (problems declared :pack-form-not-read))))
        (check "re-frame: an :fx a local names doesn't fail the file"
               (and (= #{:event} (get-in (index/where declared :app.ui/batch) [:entry :re-frame/registered]))
                    (not-any? #(re-find #"failed reading" (str (:reason %))) (problems declared :pack-form-not-read)))))
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
      (let [ri (index/build {:root dir :src [] :registry ["registry"] :packs {'smoke/rules-pack {}}})]
        (check "a pack's :pack/rules-of: a rule an entry carries is checked, located at that entry"
               (= [[:fn/w :inv/audited {:file "registry/r.edn" :line 2}]]
                  (map (juxt :dev-id :rule :rule-at) (problems ri :rule-violated)))))
      (fs/delete-tree dir))

    ;; ---- a pack that describes what others declare
    (let [ig-only {:integrant {:configs ["resources"]}}
          with (fn [p] (index/build (assoc cfg :packs (assoc ig-only p {}))))
          shared #(set (map :dev-id (problems % :id-in-several-packs)))]
      (check "a pack describing integrant's components under their keys: no :id-in-several-packs, integrant stays primary"
             (let [d (with 'smoke/describing-pack)]
               (and (not (contains? (shared d) :shop/db))
                    (= :integrant (:pack (:definition (index/where d :shop/db))))
                    (= "described" (:note/text (:entry (index/where d :shop/db)))))))
      (check "the same pack, declaring: one id in two packs is a warning"
             (contains? (shared (with 'smoke/declaring-pack)) :shop/db)))

    ;; ---- a pack's own meta key
    (let [dir (str (fs/create-temp-dir))]
      (fs/create-dirs (str dir "/src/app")) (fs/create-dirs (str dir "/registry"))
      (spit (str dir "/src/app/core.clj")
            "(ns app.core)\n(defn ^{:app/id :x/linked} linked [x] x)\n(defn ^{:app/id :x/bare} bare [x] x)\n(defn ^{:meta-source/id :x/plain} plain [x] x)\n")
      (spit (str dir "/registry/x.edn") "{:x/linked {:x/doc \"described\"}}")
      (let [ti (index/build {:root dir :src ["src"] :registry ["registry"] :packs {'smoke/tagged-pack {}}})]
        (check "a pack's :pack/id-key links a var with no :id-key configured"
               (= 'app.core/linked (:var (:impl (index/where ti :x/linked)))))
        (check "a var a pack's key links with no source is the pack's to report, not :binding-only"
               (and (= [:x/bare] (map :dev-id (problems ti :tagged-without-entry)))
                    (= [:x/plain] (map :dev-id (problems ti :binding-only))))))
      (fs/delete-tree dir))

    ;; ---- serve: what the editor talks to, with meta-source.edn edited while it runs
    (let [dir (fs/create-temp-dir {:prefix "meta-source-serve"})
          _ (fs/copy-tree root dir)
          conf (str (fs/path dir "meta-source.edn"))
          proc (p/process ["bb" "--config" "bb.edn" "meta-source" "serve" "--root" (str dir)] {:err :inherit})
          in (io/writer (:in proc))
          out (io/reader (:out proc))
          ask (fn [id file]
                (.write in (str (json/generate-string {:id id :op "diagnostics" :file file}) "\n"))
                (.flush in)
                (set (map :problem (:result (json/parse-string (.readLine out) true)))))]
      (try
        (spit conf "{:packs {:integrant {:configs [\"resources\"]}}}")
        (let [before (ask 1 "src/shop/resolvers.clj")]
          (spit conf "{:packs {:integrant {:configs [\"resources\"]} :pathom {}}}")
          (.setLastModified (fs/file conf) (+ 2000 (System/currentTimeMillis)))
          (let [after (ask 2 "src/shop/resolvers.clj")]
            (check "serve reads meta-source.edn again when it changes: a pack enabled while it runs takes effect"
                   (and (not (contains? before "id-in-several-packs"))
                        (contains? after "id-in-several-packs")))))
        (finally (.close in) (p/destroy proc) (fs/delete-tree dir))))

    (if (zero? @failures)
      (println "smoke: all checks passed")
      (do (println "smoke:" @failures "failed") (System/exit 1)))))
