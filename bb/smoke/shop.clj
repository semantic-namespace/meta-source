(ns smoke.shop
  (:require [smoke.support :as s :refer [check problems root]]
            [meta-source.hover :as hover]
            [meta-source.read]
            [meta-source.index :as index]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- pathom-part
  "An entry's :pathom/* keys: :shop.resolvers/seed is also, deliberately,
   an integrant key."
  [entry]
  (into {} (filter #(= "pathom" (namespace (key %)))) entry))

(def pathom-again
  "A pack claiming a kind the Pathom pack gives."
  {:pack/id :pathom-again :pack/kinds #{:pathom} :pack/find (fn [_ _] []) :pack/read (fn [_ _ _] [])})

(defn run []
  (let [{:keys [with-runtime cfg idx email]} @s/shop]
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

    (check "two packs giving the same kind are refused"
           (= {:kinds [:pathom]}
              (try (index/build (assoc-in cfg [:packs 'smoke.shop/pathom-again] {})) nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))))


    (check "a :refer of a pack's macro is not read as a form"
           (not-any? #(= "src/shop/resolvers.clj" (:file %)) (problems idx :pack-form-not-read)))

    ;; ---- diagnostics: what an editor underlines, per file
    (let [diags #(set (map (juxt :line :col :end-col :severity :problem) (hover/diagnostics idx %)))]
      (check "diagnostics: a key used outside the contract, at the key in the code"
             (contains? (diags "src/shop/handlers.clj") [10 49 64 "note" "keys-outside-contract"]))
      (check "diagnostics: a key only destructured, at its name, within its own form (seed's output from the Pathom snapshot)"
             (contains? (set (map (juxt :line :col :end-col :severity :problem)
                                  (hover/diagnostics (index/build with-runtime) "src/shop/resolvers.clj")))
                        [13 45 50 "note" "input-without-producer"]))
      (check "diagnostics: a dev-id written as the defining form's symbol"
             (contains? (diags "src/shop/resolvers.clj") [48 18 22 "warning" "id-in-several-packs"]))
      (check "diagnostics: severities follow check's"
             (contains? (diags "resources/system.edn") [10 84 97 "warning" "ref-to-undefined"]))
)

    ;; ---- check: most severe first; --brief, one line each
    (check "check --brief: severity, file:line, and the editor's message"
           (some #(re-matches #"warning +resources/system\.edn:10 +no config defines :shop/metrics" %) (hover/brief idx)))

    ;; ---- the meta key, and the merge

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
      (check ":not, :id-ns, :in, :or, :used-by :some, :deps :none, :producers :some with a set of kinds"
             (= {:more/resolvers-outside-their-ns [:shop.resolvers2/product-name :shop.wrapped/product-price]
                 :more/components-someone-refers-to [:shop/base-path :shop/cache :shop.resolvers/seed]}
                (update-vals (group-by :rule (filter :rule (index/check (index/build (assoc cfg :rules ["rules-more"])))))
                             #(mapv :dev-id %))))
      (check "the brief line names the rule's doc, the entity and the offenders"
             (some #(str/includes? % "Whatever reads a PII key is audited: :user/email (:fn/register-user :shop.resolvers/user-by-email)")
                   (hover/brief ri))))

))
