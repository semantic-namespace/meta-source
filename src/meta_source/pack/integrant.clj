(ns meta-source.pack.integrant
  "EXPERIMENTAL (see README.md) — the integrant pack: a system's
   components, read as data from its config files and from the code that
   implements them. Nothing is loaded or evaluated.

   `pack` is what meta-source.index runs (see the Packs section of
   meta-source.index), enabled by :integrant under meta-source.edn's
   :packs:

     :integrant {:configs [\"resources/config\"]  ; config files or dirs of .edn
                 :vars [app.system/config]        ; configs written in code
                 :provided [:duct/logger]}        ; keys something else adds (a duct module)

   A component is keyed by its config key. A composite key is keyed by its
   name, the keyword no other config key uses, the others being what it
   derives from: :app/web in duct's [:duct/server :app/web], and
   :password.slack/webhook in [:password.slack/webhook :app/rotable-password]
   when every password shares :app/rotable-password. When that doesn't single
   one out, the last keyword, as duct writes them. Its entry, in the pack's
   own vocabulary:

     :integrant/refs        the keys it refers to: #ig/ref, #ig/refset, (ig/ref k)
     :integrant/derives     a composite key's other keywords
     :integrant/env         the environment variables its config reads (#env)
     :integrant/config-in   each \"file:line\" that configures it
     :integrant/varies      true when those files configure it differently
                            (a .mock.edn beside the real one)
     :integrant/profiles    the duct profiles that configure it
     :integrant/isa         what (derive k parent) calls in the code derive it
                            from, in files that use integrant
     :integrant/init-key, :integrant/halt-key!, ...
                            true when the code has that defmethod for it

   Config files are read all at once, so a key several files configure
   (variants of one system) is one entry with every file listed, not a
   conflict. Tags no reader knows (aero's #env, #or, #profile) are kept as
   data; refs inside any branch count. A duct profile key
   (:duct.profile/base {...}) holds components: each is read as one, at the
   profile's line."
  (:require [meta-source.read :as read]
            [clojure.set]
            [clojure.string :as str]))

(def kind :integrant)

(def ^:private ig-methods
  '#{init-key halt-key! resume-key suspend-key! prep-key expand-key resolve-key assert-key pre-init-spec})

;; =============================================================================
;; Reading a config
;; =============================================================================

(defn- nodes
  "Every value inside `v`, through collections and tagged literals."
  [v]
  (tree-seq #(or (coll? %) (tagged-literal? %))
            #(if (tagged-literal? %) [(:form %)] (seq %))
            v))

(defn- ref-target [x]
  (cond
    (and (tagged-literal? x) (#{'ig/ref 'ig/refset} (:tag x))) (:form x)
    ;; a config written in code: (ig/ref :k), (integrant.core/refset :k)
    (and (seq? x) (symbol? (first x)) (namespace (first x)) (#{"ref" "refset"} (name (first x))))
    (second x)))

(defn- composite? [k] (and (vector? k) (seq k) (every? qualified-keyword? k)))

(defn- ref-uses
  "Each [:ref target] or [:refset target] in `v`, a target being a keyword
   or a composite (a vector of keywords)."
  [v]
  (distinct (for [x (nodes v)
                  :let [t (ref-target x)]
                  :when (or (qualified-keyword? t) (composite? t))]
              [(if (or (and (tagged-literal? x) (= 'ig/refset (:tag x)))
                       (and (seq? x) (= "refset" (name (first x)))))
                 :refset :ref)
               t])))

(defn- refs [v]
  (vec (sort (distinct (for [x (nodes v)
                             :let [t (ref-target x)]
                             k (if (vector? t) [(last t)] [t])
                             :when (qualified-keyword? k)]
                         k)))))

(defn- only-refsets
  "The keys `v` names only through a refset."
  [v]
  (let [uses (ref-uses v)
        plain (set (for [[k t] uses :when (= :ref k)] t))]
    (vec (sort (distinct (for [[k t] uses :when (and (= :refset k) (keyword? t) (not (plain t)))] t))))))

(defn- composite-refs
  "The composite keys `v` refers to with a ref: [[:group/a :handler/greet]]."
  [v]
  (vec (sort-by pr-str (distinct (for [[k t] (ref-uses v)
                                       ;; [:k] is :k, as integrant reads it
                                       :when (and (= :ref k) (vector? t) (next t))]
                                   t)))))

(defn- env-vars [v]
  (vec (sort (distinct (for [x (nodes v)
                             :when (and (tagged-literal? x) (#{"env" "duct/env"} (str (:tag x))))
                             :let [f (:form x)]
                             n (if (sequential? f) [(first f)] [f])
                             :when (or (symbol? n) (string? n))]
                         (str n))))))

(defn- component-id
  "A config key's id. `shared` is how many composite keys each keyword is in."
  ([k] (component-id k {}))
  ([k shared]
   (cond
     (qualified-keyword? k) k
     (composite? k) (let [own (filter #(= 1 (get shared % 1)) k)]
                      (if (= 1 (count own)) (first own) (last k))))))

(defn- combine
  "One result per component from `defs`, [{:key :value :file :path :line}]
   for every file or var that configures it."
  [defs]
  (let [shared (frequencies (apply concat (distinct (filter composite? (map :key defs)))))]
    (vec (for [[id ds] (sort-by key (group-by #(component-id (:key %) shared) defs))
             :when id
             :let [ds (sort-by (juxt :path :line) ds)
                   k (:key (first ds))
                   values (set (map :value ds))]]
         {:file (:file (first ds)) :row (:line (first ds)) :kind kind :dev-id id
          :entry (cond-> {:integrant/config-in (vec (for [d ds] (str (:path d) ":" (:line d))))}
                   (seq (refs (map :value ds))) (assoc :integrant/refs (refs (map :value ds)))
                   ;; a refset may match nothing (an empty set), a ref may not
                   (seq (only-refsets (map :value ds))) (assoc :integrant/refsets (only-refsets (map :value ds)))
                   (seq (composite-refs (map :value ds))) (assoc :integrant/composite-refs (composite-refs (map :value ds)))
                   ;; every composite merged into this id, not only the first:
                   ;; [:a :done] and [:b :done] both make :done derive
                   (some (comp vector? :key) ds)
                   (assoc :integrant/derives (vec (distinct (for [d ds :when (vector? (:key d)) x (:key d) :when (not= id x)] x))))
                   (seq (env-vars (map :value ds))) (assoc :integrant/env (env-vars (map :value ds)))
                   (> (count values) 1) (assoc :integrant/varies true)
                   (seq (keep :profile ds)) (assoc :integrant/profiles (vec (sort (distinct (keep :profile ds)))))
                   (seq (mapcat :alternatives ds))
                   (assoc :integrant/alternatives (vec (sort (distinct (keep component-id (mapcat :alternatives ds)))))))}))))

(defn- profile? [k v]
  (and (qualified-keyword? k) (= "duct.profile" (namespace k)) (map? v)))

(defn- key-choices
  "The config keys `k` stands for: itself, or each branch of a tag that
   chooses one (#profile{:dev ::a :prod ::b}, #or [::a ::b])."
  [k]
  (if (tagged-literal? k)
    (let [f (:form k)] (filter component-id (cond (map? f) (vals f) (sequential? f) f :else [f])))
    [k]))

(defn- config-defs
  "Each component a config's [k v line]s configure, duct profiles opened,
   a key a tag chooses read as each of its branches."
  [kvs file path]
  (for [[k v line] kvs
        [k v profile] (if (profile? k v) (for [[ik iv] v] [ik iv k]) [[k v nil]])
        :let [choices (key-choices k)]
        k choices
        :when (component-id k)]
    (cond-> {:key k :value v :file file :path path :line line}
      profile (assoc :profile profile)
      ;; one of a tag's branches: the others are its alternatives
      (next choices) (assoc :alternatives (vec (sort-by pr-str (remove #{k} choices)))))))

(defn- system-holders
  "The top-level keys of a config holding the system itself, as an aero
   config does under one of its keys ({:port 3000 :app/system {...}}): those
   `:system-keys` names, and any whose map refers to a key of its own that
   is configured there (its value a map) and at the top level of no config
   read (`top`): a component's option named like the key it refers to
   ({:app/datasets #ig/ref :app/datasets}) is no system. A duct profile is
   opened, not held."
  [kvs top opts]
  (let [named (set (get-in opts [:integrant :system-keys]))]
    (set (for [[k v _ inner] kvs
               :when (and inner (map? v) (not (profile? k v))
                          (or (named k)
                              (some #(and (map? (get v %)) (not (top %))) (refs [v]))))]
           k))))

(def ^:private hierarchy-file #"(^|/)(duct_hierarchy|integrant/hierarchy)\.edn$")

(defn- hierarchy-results
  "A duct_hierarchy.edn (or integrant/hierarchy.edn): {child [parent ...]},
   derive calls written as data."
  [kvs file]
  (for [[child parents line] kvs
        :when (and (qualified-keyword? child) (sequential? parents))]
    {:file file :row line :kind kind :dev-id child
     :entry {:integrant/isa (vec (sort (filter qualified-keyword? parents)))}}))

(defn- read-configs
  "Every config file at once: [{:file :path :text}]. A hierarchy file says
   what derives from what; a config whose system sits under some of its
   keys is read from there."
  [files opts]
  (let [parsed (for [{:keys [file path text]} files]
                 {:file file :path path
                  :kvs (try (read/keyed-lines text {:tags :keep :inner true}) (catch Exception ex ex))})
        ;; every key some config defines at its top level, profiles opened
        top (set (for [{:keys [kvs path]} parsed
                       :when (and (sequential? kvs) (not (re-find hierarchy-file (str path))))
                       [k v] kvs
                       k (if (profile? k v) (keys v) [k])]
                   k))
        read (for [{:keys [file path kvs]} parsed]
                 (cond
                   (instance? Exception kvs) {:skipped {:file file :row 1 :kind kind :skipped (str "not readable: " (ex-message kvs))}}
                   (nil? kvs) {:skipped {:file file :row 1 :kind kind :skipped "not a map of config keys"}}
                   (re-find hierarchy-file (str path)) {:isa (hierarchy-results kvs file)}
                   :else (let [held (system-holders kvs top opts)]
                           {:defs (config-defs (if (seq held) (mapcat #(nth % 3) (filter (comp held first) kvs)) kvs)
                                               file path)})))]
    (-> (combine (mapcat :defs read))
        (into (mapcat :isa read))
        (into (keep :skipped read)))))

;; =============================================================================
;; Reading the code
;; =============================================================================

(defn- find-forms
  "Each defmethod form of an integrant multimethod, and each config var the
   options name. The site is the defmethod form itself: clj-kondo's
   :dispatch-val-str gives ::keywords the wrong namespace, so the form is
   read with the file's."
  [analysis opts]
  (let [vars (set (get-in opts [:integrant :vars]))
        ;; derive also builds hierarchies of exceptions and the like: only
        ;; files that use integrant say something about components
        ig-files (set (for [u (:var-usages analysis) :when (= 'integrant.core (:to u))] (:filename u)))
        ig-rows (set (for [u (:var-usages analysis)
                           :when (and (:defmethod u) (= 'integrant.core (:to u)) (ig-methods (:name u)))]
                       [(:filename u) (:row u)]))]
    (concat
     (for [u (:var-usages analysis)
           :when (and (= 'clojure.core (:to u)) (= 'defmethod (:name u)) (ig-rows [(:filename u) (:row u)]))]
       (assoc (select-keys u [:filename :row :name-row :name-col]) :method true))
     (for [u (:var-usages analysis)
           :when (and (= 'clojure.core (:to u)) (= 'derive (:name u)) (= 2 (:arity u)) (ig-files (:filename u)))]
       (assoc (select-keys u [:filename :row :name-row :name-col]) :derive true))
     (for [d (:var-definitions analysis)
           :when (contains? vars (symbol (str (:ns d)) (str (:name d))))]
       {:filename (:filename d) :row (:row d) :name-row (:row d) :name-col (inc (:col d)) :config-var (:name d)}))))

(defn- written-keys
  "The config keys a method's body writes as keys of map literals, sorted:
   what an expand-key returns, or what a duct module's init-key merges."
  [body]
  (vec (sort (distinct (for [m (tree-seq coll? seq body)
                             :when (map? m)
                             k (keys m)
                             :let [id (component-id k)]
                             :when id]
                         id)))))

(defn- read-forms [sites {:keys [form-at own-ns]} _opts]
  (let [{methods-sites :method derive-sites :derive var-sites :var}
        (group-by #(cond (:method %) :method (:derive %) :derive :else :var) sites)]
    (vec (concat
          ;; one result per derived key in this file, its parents together
          (for [[child ds] (group-by :child
                                     (for [{:keys [row name-row name-col]} derive-sites
                                           :let [[_ child parent] (form-at name-row name-col)]
                                           :when (and (qualified-keyword? child) (qualified-keyword? parent))]
                                       {:row row :child child :parent parent}))]
            {:row (:row (first ds)) :kind kind :dev-id child
             :entry {:integrant/isa (vec (sort (distinct (map :parent ds))))}})
          (for [{:keys [row name-row name-col]} methods-sites
                :let [form (form-at name-row name-col)
                      [_ mm dispatch & body] (when (seq? form) form)
                      id (component-id dispatch)
                      method (when (symbol? mm) (name mm))
                      adds (when id (written-keys body))]]
            (if (and id method (ig-methods (symbol method)))
              {:row row :kind kind :dev-id id
               :entry (cond-> {(keyword "integrant" method) true}
                        ;; what a key that expands writes, its keys or a duct module's
                        (and (seq adds) (#{"expand-key" "prep-key"} method)) (assoc :integrant/expands-to adds)
                        (and (seq adds) (= "init-key" method)) (assoc :integrant/init-writes adds))}
              {:row row :kind kind :skipped (str "dispatch value " (pr-str dispatch) " is not a config key")}))
          (for [{:keys [row name-row name-col config-var]} var-sites
                :let [form (form-at name-row name-col)
                      m (when (and (seq? form) (>= (count form) 3)) (last form))]
                r (if (map? m)
                    (combine (for [[k v] m :when (component-id k)] {:key k :value v :path (str own-ns "/" config-var) :line row}))
                    [{:kind kind :skipped "the config var's value is not a map"}])]
            (assoc r :row row))))))

;; =============================================================================
;; Checks and the hover
;; =============================================================================

;; the keys a duct module adds to the system at prep time, when configured
(def ^:private duct-web
  #{:duct/router :duct.router/cascading :duct.router/reitit :duct.handler/root :duct.handler/file
    :duct.handler/resource :duct.handler.reitit/default :duct.server/http :duct.server.http/jetty
    :duct.session-store/cookie
    :duct.handler.static/bad-request :duct.handler.static/not-found :duct.handler.static/method-not-allowed
    :duct.handler.static/not-acceptable :duct.handler.static/internal-server-error
    :duct.middleware.web/defaults :duct.middleware.web/format :duct.middleware.web/hiccup
    :duct.middleware.web/hide-errors :duct.middleware.web/log-errors :duct.middleware.web/log-requests
    :duct.middleware.web/not-found :duct.middleware.web/stacktrace :duct.middleware.web/webjars})

(def ^:private duct-isa
  "What duct's libraries derive their keys from, in their own
   duct_hierarchy.edn or integrant/hierarchy.edn, which ship in their jars:
   migrator.ragtime, database.sql(.hikaricp), logger.timbre, server.http.jetty,
   handler.sql, module.ataraxy, scheduler.simple."
  {:duct.migrator/ragtime [:duct/migrator]
   :duct.database.sql/hikaricp [:duct.database/sql]
   :duct.database/sql [:duct/database]
   :duct.logger/timbre [:duct/logger]
   :duct.logger/simple [:duct/logger]
   :duct.server.http/jetty [:duct.server/http]
   :duct.server/http [:duct/server]
   :duct/server [:duct/daemon]
   :duct.handler/sql [:duct/handler]
   :duct.handler.sql/query [:duct.handler/sql]
   :duct.handler.sql/query-one [:duct.handler/sql]
   :duct.handler.sql/insert [:duct.handler/sql]
   :duct.handler.sql/execute [:duct.handler/sql]
   :duct.router/ataraxy [:duct/router]
   :duct.module/ataraxy [:duct/module]
   :duct.scheduler/simple [:duct/daemon]})

(defn- ancestors-in
  "Every key `ks` derive from through `isa` ({child [parent ...]})."
  [isa ks]
  (loop [todo (vec ks) seen #{}]
    (if-let [k (peek todo)]
      (let [ps (remove seen (isa k))]
        (recur (into (pop todo) ps) (into seen ps)))
      seen)))

(def ^:private duct-modules
  "Read from the modules' sources, old (0.7, duct.core) and new (expand-key)."
  {:duct.module/logging #{:duct/logger :duct.logger/timbre :duct.logger/simple}
   :duct.module/sql #{:duct.database/sql :duct.database.sql/hikaricp :duct.migrator/ragtime}
   :duct.module/web duct-web
   :duct.module.web/api duct-web
   :duct.module.web/site duct-web})

(defn- ref-cycles
  "The cycles of `edges` ({id #{id}}): each strongly connected group of two
   or more, or a key referring to itself, as a path starting at its least
   id and back to it."
  [edges]
  (let [index (atom 0) stack (atom []) on (atom #{}) idx (atom {}) low (atom {}) out (atom [])]
    (letfn [(visit [v]
              (swap! idx assoc v @index) (swap! low assoc v @index) (swap! index inc)
              (swap! stack conj v) (swap! on conj v)
              (doseq [w (sort (edges v))]
                (cond (not (contains? @idx w)) (do (visit w) (swap! low update v min (@low w)))
                      (@on w) (swap! low update v min (@idx w))))
              (when (= (@low v) (@idx v))
                (let [group (loop [g #{}] (let [w (peek @stack)] (swap! stack pop) (swap! on disj w)
                                            (if (= w v) (conj g w) (recur (conj g w)))))]
                  (when (or (> (count group) 1) (contains? (edges v) v))
                    (swap! out conj group)))))]
      (doseq [v (sort (keys edges)) :when (not (contains? @idx v))] (visit v))
      ;; the shortest cycle through the group's least id, along real edges
      (sort (for [group @out
                  :let [start (first (sort group))]]
              (loop [frontier [[start]] seen #{}]
                (let [[path & more] frontier
                      at (peek path)
                      nexts (sort (filter group (edges at)))]
                  (if (some #{start} nexts)
                    (conj path start)
                    (let [fresh (remove seen nexts)]
                      (recur (concat more (for [n fresh] (conj path n))) (into seen fresh)))))))))))

(defn- checks
  "- :ref-to-undefined (warning): a component refers to a key no config
     read defines, directly or as a composite key's parent, that no
     configured duct module adds (:duct.module/logging adds :duct/logger,
     :duct.module/sql :duct.database/sql), and that :provided doesn't list.
     Integrant can't init the system unless something adds it;
   - :ref-maybe-from-module (info): such a ref in the namespace of a key
     that expands into others (an expand-key or prep-key method, or a duct
     module), which may add it: what it adds is read where its code writes
     the keys as data, and not otherwise;
   - :ref-ambiguous (warning): a ref (not a refset) that more than one
     key configured in one config file matches, by being it or deriving
     from it (a composite key, a derive, a hierarchy file): integrant
     can't choose, and init fails. Keys in different files, or in duct
     profiles that aren't active together, are alternatives, not rivals;
   - :ref-cycle (warning): components that refer to one another in a
     circle, through refs and refsets: integrant can't order them;
   - :init-key-without-config (info): the code has an init-key method for
     a key no config defines, no composite key derives from, and no code
     calls by its id (:calls-by-id): unused, or configured somewhere not
     read. A key some configured component
     derives from, by a composite key or a derive call, counts as configured."
  [{:keys [of-kinds loc opts]}]
  (let [components (sort-by key (of-kinds #{kind}))
        configured (filter #(get-in (val %) [:entry :integrant/config-in]) components)
        parents #(concat (get-in (val %) [:entry :integrant/derives]) (get-in (val %) [:entry :integrant/isa]))
        ;; a key some code calls by its id (:calls-by-id): a workflow engine
        ;; running (ig/init-key k deps) itself
        called (for [[id e] components :when (get-in e [:entry :meta-source/called-by-id])] id)
        ;; keys that expand into others: an expand-key (or the deprecated
        ;; prep-key), or a duct module (deriving :duct/module) whose init-key
        ;; merges a config
        modules (for [c configured
                      :let [entry (:entry (val c))]
                      :when (or (:integrant/expand-key entry) (:integrant/prep-key entry)
                                (some #{:duct/module} (parents c)))]
                  c)
        written (mapcat (fn [c] (let [entry (:entry (val c))]
                                  (concat (:integrant/expands-to entry)
                                          (when (some #{:duct/module} (parents c)) (:integrant/init-writes entry)))))
                        modules)
        added (set (concat (map key configured) called written (mapcat duct-modules (map key configured))))
        defined (-> added
                    (into (mapcat parents) configured)
                    ;; what duct's own keys derive from, as their jars say
                    (into (ancestors-in duct-isa added))
                    (into (get-in opts [:integrant :provided])))
        ;; what each configured key is, or derives from: integrant matches a
        ;; ref to every key whose lineage holds it
        isa (merge-with into duct-isa (into {} (for [[id e] components :let [ps (get-in e [:entry :integrant/isa])] :when ps] [id ps])))
        lineage (into {} (for [[id e] configured
                               :let [own (into #{id} (get-in e [:entry :integrant/derives]))]]
                           [id (into own (ancestors-in isa own))]))
        candidates (fn [target] (let [ks (if (vector? target) target [target])]
                                  (sort (for [[id ls] lineage :when (every? ls ks)] id))))
        files (fn [id] (set (for [c (get-in (get (into {} configured) id) [:entry :integrant/config-in])]
                              (str/replace c #":\d+$" ""))))
        profiles (fn [id] (set (get-in (get (into {} configured) id) [:entry :integrant/profiles])))
        ;; two keys of one file are rivals unless duct profiles keep them apart
        alternatives (fn [id] (set (get-in (get (into {} configured) id) [:entry :integrant/alternatives])))
        together? (fn [a b] (let [pa (profiles a) pb (profiles b)]
                              (and (not ((alternatives a) b)) (not ((alternatives b) a))
                                   (or (empty? pa) (empty? pb) (pa :duct.profile/base) (pb :duct.profile/base)
                                       (seq (clojure.set/intersection pa pb))))))
        rivals (fn [cs] (distinct (for [a cs b cs :when (and (neg? (compare a b)) (seq (clojure.set/intersection (files a) (files b)))
                                                             (together? a b))]
                                    [a b])))
        ;; each component's refs, as the components they match
        edges (into {} (for [[id e] configured
                             :let [entry (:entry e)]]
                         [id (set (mapcat candidates (concat (:integrant/refs entry) (:integrant/composite-refs entry))))]))]
    (concat
     (for [[id e] configured
           :let [entry (:entry e)
                 refsets (set (:integrant/refsets entry))
                 composite-lasts (set (map last (:integrant/composite-refs entry)))
                 amb (for [t (concat (remove (into refsets composite-lasts) (:integrant/refs entry)) (:integrant/composite-refs entry))
                           :let [cs (candidates t) rs (rivals cs)]
                           :when (seq rs)]
                       [t (vec (sort (distinct (flatten rs))))])]
           [t cs] amb]
       {:severity :warning :problem :ref-ambiguous :dev-id id :keys [t] :candidates cs :definition (loc e)})
     ;; strongly connected components of the ref graph, one finding each
     (for [cycle (ref-cycles edges)]
       {:severity :warning :problem :ref-cycle :dev-id (first cycle) :cycle cycle
        :definition (loc (get (into {} configured) (first cycle)))})
     ;; a composite ref no configured key matches
     (for [[id e] configured
           ;; matched by no configured key, and not every keyword of it is
           ;; one something else adds (a module, :provided)
           :let [missing (vec (remove #(or (seq (candidates %)) (every? defined %))
                                      (get-in e [:entry :integrant/composite-refs])))]
           :when (seq missing)]
       {:severity :warning :problem :ref-to-undefined :dev-id id :keys missing :definition (loc e)})
     ;; what a module adds that its code doesn't write as data (a key it
     ;; builds) isn't read: a missing ref in its namespace it may well add
     (let [own-modules (map key modules)
           module-for (fn [k] (some #(when (= (namespace %) (namespace k)) %) own-modules))]
       (for [[id e] configured
             :let [entry (:entry e)
                   ;; a refset may match nothing; a composite is checked whole, above
                   plain (remove (into (set (:integrant/refsets entry)) (map last (:integrant/composite-refs entry)))
                                 (:integrant/refs entry))
                   missing (remove defined plain)
                   {maybe true missing false} (group-by #(boolean (module-for %)) missing)]
             p [(when (seq missing)
                  {:severity :warning :problem :ref-to-undefined :dev-id id :keys (vec missing) :definition (loc e)})
                (when (seq maybe)
                  {:severity :info :problem :ref-maybe-from-module :dev-id id :keys (vec maybe)
                   :module (module-for (first maybe)) :definition (loc e)})]
             :when p]
         p))
     (for [[id e] components
           :when (and (get-in e [:entry :integrant/init-key]) (not (contains? defined id)))]
       {:severity :info :problem :init-key-without-config :dev-id id :definition (loc e)}))))

(defn- describe [entry]
  (when (some #(= "integrant" (namespace %)) (keys entry))
    (let [ms (sort (for [[k v] entry :when (and (true? v) (ig-methods (symbol (name k))))] (name k)))
          n (count (:integrant/config-in entry))]
      (str/join "  ·  " (remove nil? [(str "component  refs " (count (:integrant/refs entry)))
                                       (when (seq ms) (str/join " " ms))
                                       (when (pos? n) (str "in " n " config" (when (> n 1) "s")
                                                           (when (:integrant/varies entry) ", varies")))
                                       (when (seq (:integrant/env entry)) (str "env " (str/join " " (:integrant/env entry))))])))))

(def pack
  {:pack/id :integrant
   :pack/config-keys #{:integrant}
   :pack/kinds #{kind}
   :pack/vocabulary #{"integrant"}
   :pack/data-keys? false
   :pack/find find-forms
   :pack/read read-forms
   ;; the configs, and where duct keeps derive calls written as data
   :pack/data-files (fn [opts] (concat (get-in opts [:integrant :configs])
                                       (get-in opts [:integrant :hierarchies])
                                       ["src/duct_hierarchy.edn" "resources/duct_hierarchy.edn"
                                        "src/integrant/hierarchy.edn" "resources/integrant/hierarchy.edn"]))
   :pack/read-data read-configs
   :pack/checks checks
   :pack/describe describe})
