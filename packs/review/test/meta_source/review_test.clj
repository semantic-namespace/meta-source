(ns meta-source.review-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [meta-source.review :as review]
            [sdiff.decorate :as d]
            [sdiff.git :as git]
            [sdiff.render.text :as text]))

(deftest delta-compares-what-not-where
  (let [f (fn [line & {:as m}] (merge {:severity :info :problem :data-key-without-schema :dev-id :fn/x
                                       :keys [:a/b] :definition {:file "src/x.clj" :line line}} m))]
    (testing "a finding that only moved is neither introduced nor fixed"
      (is (= {:introduced [] :fixed []} (review/delta [(f 3)] [(f 30)]))))
    (testing "a key added to an existing finding is introduced alone"
      (is (= [[:a/c]] (map :keys (:introduced (review/delta [(f 3)] [(f 3 :keys [:a/b :a/c])]))))))
    (testing "the fixed side keeps its own location"
      (is (= 3 (get-in (first (:fixed (review/delta [(f 3)] []))) [:definition :line]))))))

(defn- git! [dir & args] (apply p/shell {:dir (str dir) :out :string :err :string} "git" args))

(defn- shop-repo
  "A git repository of meta-source's shop fixture, then a commit that fixes
   the :user/emial typo and makes place-order read a key its contract doesn't
   name. Returns [dir base head]."
  []
  (let [dir (fs/create-temp-dir {:prefix "ms-review"})]
    (fs/copy-tree "../test/fixtures/shop" dir)
    (git! dir "init" "-q")
    (git! dir "add" "-A")
    (git! dir "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-qm" "base")
    (let [base (str/trim (:out (git! dir "rev-parse" "HEAD")))
          edit (fn [f from to] (let [f (fs/file dir f)] (spit f (str/replace (slurp f) from to))))]
      (edit "src/shop/spec.clj" " :user/emial]" "]")
      (edit "src/shop/handlers.clj" "(:order/discount ctx 0))}" "(:order/discount ctx 0) (count (:user/name ctx \"\")))}")
      (git! dir "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-qam" "head")
      [(str dir) base (str/trim (:out (git! dir "rev-parse" "HEAD")))])))

(deftest a-local-range
  (let [[dir base head] (shop-repo)
        {:keys [introduced fixed]} (review/compare-commits {:git dir} base head)]
    (is (= [[:keys-outside-contract :fn/place-order [:user/name]]]
           (map (juxt :problem :dev-id :keys) introduced)))
    (is (= [[:schema-key-without-shape :shop.spec/user [:user/emial]]]
           (map (juxt :problem :dev-id :keys) fixed)))
    (testing "printed by sdiff: the header, and the finding on the form it is in"
      (review/install!)
      (let [r (assoc (git/report dir base head :merge-base? true) ::review/repo dir)
            out (with-out-str (binding [d/*ctx* (@d/context-fn r)] (text/print-report r)))]
        (is (str/includes? out "1 finding introduced · 1 fixed"))
        (is (re-find #"(?s)place-order\n.*introduces.*:user/name is used here but is not in :fn/place-order's contract" out))
        (is (re-find #"(?s)s/def ::user.*fixes.*:user/emial" out))))))

(deftest a-ref-that-moves-is-read-again
  (let [[dir base _] (shop-repo)
        r {::review/repo dir :base base :head "master"}
        _ (git! dir "branch" "-M" "master")
        before (::review/delta (review/context-for r))
        edit (fs/file dir "src/shop/handlers.clj")]
    (spit edit (str/replace (slurp edit) "(count (:user/name ctx \"\"))" ""))
    (git! dir "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-qam" "drop it")
    (is (= 1 (count (:introduced before))))
    (is (empty? (:introduced (::review/delta (review/context-for r)))))))
