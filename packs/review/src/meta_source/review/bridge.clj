(ns meta-source.review.bridge
  "EXPERIMENTAL. What sdiff's call graph cannot see and meta-source reads:
   dependencies through keywords. Each becomes an edge of sdiff's dependency
   graph, so who calls a changed form, and the risk order, count them:

   - a form that names an entity's keyword (a dispatch, an `#ig/ref` in code,
     a spec key) depends on the forms that define it (its registration, its
     `init-key`, its linked var), when the form is in the sources
     meta-source.edn names: elsewhere (a script, another fixture) the same
     keyword may well be another thing;
   - a place a pack says refers to an entity (a re-frame dispatch) depends on
     it the same way;
   - an entity's entry naming another (integrant refs, atlas deps) makes the
     forms defining the first depend on those defining the second, wherever
     the entry is written, EDN included;
   - an entity that consumes a data key depends on those of its own kind
     that produce it (a Pathom resolver's input on the resolvers giving it,
     an atlas function's context on the functions responding with it), when
     at most `max-producers` do. Across kinds a shared key says nothing
     about who calls whom: a handler's argument is not a resolver's output.

   The index is the commit's own, read with its meta-source.edn (or the
   repository's fallback, see meta-source.review): a commit that has neither
   adds no edge."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [meta-source.cli :as cli]
            [meta-source.index :as index]
            [meta-source.review :as review]
            [sdiff.deps :as deps]))

(def max-producers
  "A data key more entities produce than this says little about which one a
  consumer needs, and is left out."
  5)

(defn- code? [file] (boolean (re-find #"\.clj[cs]?$" (str file))))

(defn edges
  "[[from to] ...] in sdiff's nodes, from meta-source's index `idx` of a tree
   holding the same sources as `root`, sdiff's: `form-at` turns a file of root
   and a row into a node."
  [idx {:keys [analysis form-at root]}]
  (let [tree (str (:root idx) "/")
        node (fn [file row]
               (when (and (code? file) row)
                 (let [f (str file)
                       f (if (str/starts-with? f tree) (str root "/" (subs f (count tree))) f)]
                   (form-at f row))))
        defs (fn [e] (remove :refers (:definitions e)))
        defined (into {} (for [[id e] (:entities idx)]
                           [id (set (keep #(node (:file %) (:line %)) (defs e)))]))
        impl (merge-with into
                         (into {} (for [[id vs] (index/linked-vars idx)]
                                    [id (set (keep #(node (:filename %) (:row %)) vs))]))
                         (into {} (for [[id r] (:impl-ranges idx)]
                                    [id (set (keep identity [(node (:filename r) (:row r))]))])))
        ;; every form that defines or implements it: what a keyword names
        sites (merge-with into defined impl)
        ;; what does the work: an entity's implementation when it has one, as
        ;; one register! form may define many entities
        doer (fn [id] (or (not-empty (impl id)) (defined id)))
        across (fn [froms tos] (for [f froms t tos :when (not= f t)] [f t]))
        roots (for [d (:src (:config idx))] (str (io/file root d)))
        in-src? (fn [f] (some #(or (= % (str f)) (str/starts-with? (str f) (str % "/"))) roots))
        mentions (for [{:keys [filename row ns name]} (:keywords analysis)
                       :when (and ns (in-src? filename))
                       :let [k (keyword (str ns) name) to (sites k) from (form-at filename row)]
                       :when (and (seq to) from (not (contains? to from)))
                       t to]
                   [from t])
        refers (for [[id e] (:entities idx) d (:definitions e) :when (:refers d)
                     :let [from (node (:file d) (:line d))] :when from
                     t (sites id) :when (not= from t)]
                 [from t])
        entries (for [id (keys (:entities idx))
                      {dep :dev-id} (:depends-on (index/dependencies idx id))
                      e (across (doer id) (doer dep))]
                  e)
        kinds (fn [id] (set (map :kind (get-in idx [:entities id :definitions]))))
        contracts (into {} (for [[id e] (:entities idx)] [id (index/contract-keys idx (:entry e))]))
        producers (reduce (fn [m [id {:keys [produces]}]] (reduce #(update %1 %2 (fnil conj #{}) id) m produces))
                          {} contracts)
        flows (for [[id {:keys [consumes]}] contracts
                    k consumes
                    :let [ps (set (filter #(some (kinds id) (kinds %)) (disj (producers k #{}) id)))]
                    :when (<= 1 (count ps) max-producers)
                    p ps
                    e (across (doer id) (doer p))]
                e)]
    (vec (distinct (concat mentions refers entries flows)))))

(defn- index-for
  "The index for the graph sdiff is building: the pull request's commit, or,
   for a directory analysed on its own (no repo), that directory when it has a
   meta-source.edn."
  [{:keys [root repo sha]}]
  (cond repo (review/commit-index repo sha)
        (.exists (io/file root "meta-source.edn")) (index/build (cli/config {:root root}))))

(defn bridge [ctx]
  (if-let [idx (try (index-for ctx)
                    (catch Exception e
                      (binding [*out* *err*] (println "meta-source bridge: no edges:" (ex-message e)))))]
    (edges idx ctx)
    []))

(defn install! [] (deps/add-bridge! ::keywords bridge))
