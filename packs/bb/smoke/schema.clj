(ns smoke.schema
  (:require [smoke.support :as s :refer [check problems]]
            [meta-source.hover :as hover]
            [meta-source.read]
            [meta-source.index :as index]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn run []
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
    (check "the hover shows the fdef"
           (str/includes? (:eldoc (hover/lookup fc :shop.checkout/total)) "fn  (s/cat :order :shop.checkout/order) → :shop.checkout/receipt")))

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
    (check "a key in a map written in a call is read, unless :schema-fns says the call names a schema"
           (and (= {:fn/run [:k/a]} (outside-of {}))
                (= {} (outside-of {:malli {:schema-fns '[app.core/check]}}))))
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

  ;; ---- reading forms: ::keywords resolved by the reader, strings untouched
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (str dir "/src/app"))
    (spit (str dir "/src/app/spec.clj")
          (str "(ns app.spec (:require [clojure.spec.alpha :as s] [app.model :as m]))\n"
               ";; ::note in a comment\n"
               "(s/def ::status (s/and string? #(not= % \"::draft\")))\n"
               "(s/def ::owner (s/keys :req [::m/user]))\n"))
    (let [idx (index/build {:root dir :src ["src"] :registry [] :cache nil :packs {:spec {}}})]
      (check "reading: a string holding ::name is left as written"
             (str/includes? (get-in idx [:entities :app.spec/status :entry :spec/form]) "\"::draft\""))
      (check "reading: ::name and ::alias/name resolve to the file's namespace and its aliases"
             (= [:app.model/user] (get-in idx [:entities :app.spec/owner :entry :spec/req]))))
    (fs/delete-tree dir)))
