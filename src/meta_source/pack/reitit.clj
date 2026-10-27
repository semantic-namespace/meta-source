(ns meta-source.pack.reitit
  "EXPERIMENTAL (see README.md) — the reitit pack: routes, read as data
   from the route trees the code writes, and the links to route names.
   Nothing is loaded or evaluated.

   `pack` is what meta-source.index runs (see the Packs section of
   meta-source.index), enabled by :reitit under meta-source.edn's :packs.

   A route tree is read where it is written: the first argument of a
   reitit router call (reitit.core, reitit.ring, reitit.http,
   reitit.frontend), and any def or defn holding route data (a vector
   whose first element is a path, with route keys in it), since a tree is
   often assembled from routes defined in namespaces that never require
   reitit. A child written as a symbol naming a route def in the same file
   is read in place; one in another file is read where it is defined, its
   paths relative to that def.

   A route with a :name is keyed by it; an unnamed one with a handler by
   its method and path, under where it is written (:app.users/routes.get.id
   in the def app.users/routes, :app.server/route.get.api.health in a
   router call), since route defs repeat relative paths. Route data is
   inherited as reitit merges it: children add to their parents'
   :middleware and :interceptors, and the rest is the child's when it
   gives it. Its entry, in the pack's own vocabulary:

     :reitit/path         the path, joined from its parents in the tree
     :reitit/ns           the namespace it is written in
     :reitit/methods      the methods it answers: :get, :post, ...
     :reitit/handlers     the handler vars (symbols), or :reitit/handler-code
                          when a handler is written as a fn there
     :reitit/handler-built-by  the fns whose call builds a handler there:
                          (app.users/get-user users)
     :reitit/middleware   middleware and interceptors, inherited, by name
     :reitit/view         a frontend route's view var
     :reitit/parameter-keys  qualified keys its :parameters name: what it consumes
     :reitit/response-keys   qualified keys its :responses name: what it produces
     :reitit/data         the rest of its route data, a project's own keys
                          (:require-privilege, :permission), symbols
                          qualified, values written as code as :reitit/code
     :reitit/in           the def the route is written in, when not a router call

   Each literal route name in rfe/href, rfe/push-state, rfe/replace-state
   and match-by-name gives that name :reitit/linked."
  (:require [clojure.string :as str]))

(def kind :reitit)

(def ^:private router-nses '#{reitit.core reitit.ring reitit.http reitit.frontend})
(def ^:private link-fns '{reitit.frontend.easy #{href push-state replace-state}
                          reitit.core #{match-by-name match-by-name!}
                          reitit.frontend #{match-by-name}})
(def ^:private http-methods [:get :head :post :put :delete :connect :options :trace :patch])
(def ^:private route-keys (into #{:handler :view :controllers :name :middleware :interceptors :parameters :responses} http-methods))
(def ^:private stacked #{:middleware :interceptors})
(def ^:private read-keys
  "Route data the entry gives its own keys; the rest goes under :reitit/data."
  (into #{:name :handler :view :parameters :responses :middleware :interceptors :controllers :coercion} http-methods))

(defn- var-ref
  "The symbol of #'sym, or `x` itself."
  [x]
  (if (and (seq? x) (= 'var (first x)) (symbol? (second x))) (second x) x))

(defn- literal
  "`x` when it is data (keywords, strings, numbers, nil, symbols, #'vars,
   colls of them), its symbols qualified; :reitit/code when it is code."
  [x qualify]
  (let [x (var-ref x)]
    (cond
      (symbol? x) (qualify x)
      (map? x) (let [m (into {} (for [[k v] x] [(literal k qualify) (literal v qualify)]))]
                 (if (some #{:reitit/code} (concat (keys m) (vals m))) :reitit/code m))
      (or (vector? x) (set? x)) (let [c (into (empty x) (map #(literal % qualify)) x)]
                                  (if (some #{:reitit/code} c) :reitit/code c))
      (or (keyword? x) (string? x) (number? x) (boolean? x) (nil? x)) x
      :else :reitit/code)))

;; =============================================================================
;; Finding the trees
;; =============================================================================

(defn- find-forms
  "Each router call, each def or defn whose body has route keys in it,
   and each literal link to a route name."
  [analysis _opts]
  (let [kws (group-by :filename (:keywords analysis))
        route-key-rows (fn [file row end-row]
                         (some #(and (:row %) (<= row (:row %) end-row) (nil? (:ns %)) (route-keys (keyword (:name %))))
                               (get kws file)))]
    (concat
     (for [u (:var-usages analysis)
           :when (and (router-nses (:to u)) (= 'router (:name u)))]
       (assoc (select-keys u [:filename :row :name-row :name-col]) :site :router))
     (for [u (:var-usages analysis)
           :when (contains? (get link-fns (:to u)) (:name u))]
       (assoc (select-keys u [:filename :row :name-row :name-col]) :site :link))
     (for [d (:var-definitions analysis)
           :when (and ('#{clojure.core/def clojure.core/defn cljs.core/def cljs.core/defn} (:defined-by d))
                      (:end-row d)
                      (route-key-rows (:filename d) (:row d) (:end-row d)))]
       ;; the def form starts before the name: read it from the line of the def
       {:filename (:filename d) :row (:row d) :name-row (:row d) :name-col (inc (:col d))
        :site :def :var (symbol (str (:ns d)) (str (:name d))) :defined-by (:defined-by d)}))))

;; =============================================================================
;; Reading a tree
;; =============================================================================

(defn- route? [x] (and (vector? x) (string? (first x))))

(def ^:private core-names (set (map name (keys (ns-publics 'clojure.core)))))

(defn- composed?
  "A call to a project's own fn, (users/routes db): routes it returns are
   read where that fn writes them. A call to clojure.core's (into, when,
   concat) or a fn written here is code."
  [x]
  (and (seq? x) (symbol? (first x))
       (let [h (first x)]
         (if (namespace h)
           (not (#{"clojure.core" "cljs.core"} (namespace h)))
           (not (core-names (name h)))))))

(defn- routes?
  "True for a route, or a vector of routes some of whose children may be
   written as code ((when dev? [...])), which is skipped."
  [x]
  (or (route? x)
      (and (vector? x) (seq x) (some #(or (route? %) (routes? %)) x)
           (every? #(or (route? %) (routes? %) (symbol? %) (nil? %) (seq? %)) x))))

(defn- composition?
  "A tree made only of calls to a project's own route fns:
   [(users/routes users) (billing/routes billing)], or one such call."
  [x]
  (or (composed? x) (and (vector? x) (seq x) (every? composed? x))))

(defn- qualify [sym own-ns aliases]
  (when (symbol? sym)
    (symbol (if-let [n (namespace sym)] (get aliases n n) own-ns) (name sym))))

(defn- qualified-keywords [form]
  (vec (sort (distinct (filter qualified-keyword? (tree-seq coll? seq form))))))

(defn- merge-data
  "A child's route data over its parent's: :middleware and :interceptors
   add to the parent's, the rest is the child's when it gives it."
  [parent child]
  (reduce-kv (fn [m k v] (assoc m k (if (and (stacked k) (vector? (get m k)) (vector? v)) (into (get m k) v) v)))
             parent child))

(defn- slug
  "An unnamed route's id, scoped to where it is written, since fragments
   repeat relative paths: :app.users/routes.get.id in the def
   app.users/routes, :app.server/route.get.api.health in a router call."
  [method path own-ns in]
  (let [p (-> path (str/replace #"[:{}*]" "") (str/replace #"/+" ".") (str/replace #"^\.|\.$" ""))
        n (str (if method (name method) "any") (when (seq p) (str "." p)))]
    (if in
      (keyword (namespace in) (str (name in) "." n))
      (keyword own-ns (str "route." n)))))

(defn- route-entry
  "The entry for a route's merged `data` at `path`, or nil when it is only
   a parent: no name, no handler, no view."
  [path data own-ns aliases]
  (let [ms (filter #(contains? data %) http-methods)
        handler-forms (map var-ref (concat (when (contains? data :handler) [(:handler data)])
                                           (for [m ms :let [v (get data m)]] (if (map? v) (:handler v) v))))
        handlers (vec (distinct (keep #(qualify % own-ns aliases) (filter symbol? handler-forms))))
        ;; (app.users/get-user users): a fn that builds the handler, not code inline
        ;; (partial handle-x deps): handle-x
        builder #(if (and ('#{partial clojure.core/partial} (first %)) (symbol? (second %))) (second %) (first %))
        built? #(and (seq? %) (symbol? (first %)) (not ('#{fn fn* clojure.core/fn comp clojure.core/comp} (first %)))
                     (or (not ('#{partial clojure.core/partial} (first %))) (symbol? (second %))))
        built-by (vec (distinct (keep #(qualify (builder %) own-ns aliases) (filter built? handler-forms))))
        method-data (for [m ms :let [v (get data m)] :when (map? v)] v)
        params (concat [(:parameters data)] (map :parameters method-data))
        responses (concat [(:responses data)] (map :responses method-data))
        mw (vec (distinct (for [k stacked
                                x (concat (get data k) (mapcat k method-data))
                                :let [x (var-ref (if (vector? x) (first x) x))]
                                n [(cond (symbol? x) (qualify x own-ns aliases) (keyword? x) x)]
                                :when n]
                            n)))
        view (qualify (var-ref (:view data)) own-ns aliases)
        ;; a project's own route data: :require-privilege, :roles, :no-doc
        custom (into (sorted-map)
                     (for [m (cons data method-data) [k v] m :when (not (read-keys k))]
                       [k (literal v #(qualify % own-ns aliases))]))]
    (when (or (keyword? (:name data)) (seq handlers) view (seq ms) (contains? data :handler))
      (cond-> {:reitit/path path :reitit/ns own-ns}
        (seq ms) (assoc :reitit/methods (vec ms))
        (seq handlers) (assoc :reitit/handlers handlers)
        (seq built-by) (assoc :reitit/handler-built-by built-by)
        (some #(and (some? %) (not (symbol? %)) (not (built? %))) handler-forms) (assoc :reitit/handler-code true)
        (seq mw) (assoc :reitit/middleware mw)
        view (assoc :reitit/view view)
        (seq custom) (assoc :reitit/data custom)
        (seq (qualified-keywords params)) (assoc :reitit/parameter-keys (qualified-keywords params))
        (seq (qualified-keywords responses)) (assoc :reitit/response-keys (qualified-keywords responses))))))

(defn- walk
  "[[path merged-data own-data segment origin] ...] for every route in tree
   `x` under `prefix` and `inherited` data, and [:code] for a child written
   as code. `local` resolves a symbol to a route def's {:value :row} in the
   same file: its routes are read in place, their origin that def's row;
   other symbols are skipped."
  ([x prefix inherited local seen] (walk x prefix inherited local seen nil))
  ([x prefix inherited local seen origin]
  (cond
    (and (symbol? x) (not (seen x)) (local x))
    (walk (:value (local x)) prefix inherited local (conj seen x) (:row (local x)))
    ;; a project's route fn: its routes are read where it writes them
    (composed? x) []
    (seq? x) [[:code]]
    (route? x)
    (let [[path & more] x
          [data children] (cond
                            (map? (first more)) [(first more) (rest more)]
                            (keyword? (first more)) [{:name (first more)} (rest more)]
                            :else [{} more])
          here (str prefix path)
          merged (merge-data inherited data)]
      (cons [here merged data path origin]
            (mapcat #(walk % here (dissoc merged :name :handler :view) local seen origin) children)))
    (and (vector? x) (not (route? x))) (mapcat #(walk % prefix inherited local seen origin) x))))

(defn- tree-results
  "One result per route in `tree`, each at the line its path is written
   on: the routes are read in the order they are written, so each path is
   looked for after the one before."
  [tree {:keys [own-ns aliases find-row]} local in row]
  (let [walked (walk tree "" {} local #{})
        routes (remove #(= [:code] %) walked)
        ;; the last row found, per def the routes are written in
        rows (map second (rest (reductions (fn [[seen _] [_ _ _ segment origin]]
                                             (let [from (get seen origin (or origin row))
                                                   r (or (find-row (pr-str segment) from) from)]
                                               [(assoc seen origin r) r]))
                                           [{} row] routes)))]
    (concat
     (when (some #(= [:code] %) walked)
       [{:row row :kind kind :skipped (str (count (filter #(= [:code] %) walked)) " child route(s) written as code, not read")
         :pattern :pattern/reitit-literal-tree}])
     (for [[[path data own] at] (map vector routes rows)
          :let [e (route-entry path (assoc data :name (:name own)) own-ns aliases)]
          :when e
          :let [id (if (keyword? (:name own)) (:name own) (slug (first (:reitit/methods e)) path own-ns in))
                single (when (= 1 (count (:reitit/handlers e))) (first (:reitit/handlers e)))]]
      (cond-> {:row at :kind kind :dev-id id :entry (cond-> e
                                                       in (assoc :reitit/in in)
                                                       ;; keyed by where it is, for want of a :name
                                                       (not (keyword? (:name own))) (assoc :reitit/unnamed true))}
        single (assoc :var single))))))

(defn- def-value
  "The value a def holds, or the last form a defn returns."
  [form defined-by]
  (when (seq? form)
    (if (= "defn" (name defined-by))
      (last form)
      (when (>= (count form) 3) (last form)))))

(defn- named-in
  "The keywords `form` gives as a map's :name, each with the line it is
   written on: route names a form the pack can't read as a tree still
   spells out."
  [form find-row row]
  (distinct (for [x (tree-seq coll? seq form)
                  :when (and (map? x) (keyword? (:name x)))
                  :let [id (:name x)]]
              [id (or (find-row (str ":name " (pr-str id)) row) (find-row (pr-str id) row) row)])))

(defn- read-forms [sites {:keys [form-at] :as f} _opts]
  (let [defs (for [{:keys [site name-row name-col] :as s} sites :when (= :def site)
                   :let [form (form-at name-row name-col)
                         v (def-value form (:defined-by s))]
                   :when (routes? v)]
               (assoc s :value v))
        local (into {} (for [d defs] [(symbol (name (:var d))) {:value (:value d) :row (:row d)}]))
        router-args (for [{:keys [site name-row name-col]} sites :when (= :router site)
                          :let [form (form-at name-row name-col)]
                          :when (seq? form)]
                      (second form))
        ;; a def another tree in this file reads in place is not a tree of its own
        inlined (set (concat
                      (for [d defs x (tree-seq coll? seq (:value d))
                            :when (and (symbol? x) (local x) (not= x (symbol (name (:var d)))))]
                        x)
                      (for [a router-args x (tree-seq coll? seq a) :when (and (symbol? x) (local x))] x)))]
    (vec (concat
          (for [{:keys [site row name-row name-col]} sites
                :when (#{:router :link} site)
                :let [form (form-at name-row name-col)
                      [_ x] (when (seq? form) form)]
                r (cond
                    (= form :unreadable) [{:row row :kind kind :skipped "not readable as data"}]
                    (not (seq? form)) nil
                    (= :link site) (when (keyword? x) [{:row row :kind kind :dev-id x :refers true :entry {:reitit/linked true}}])
                    (symbol? x) (when-not (local x) [{:row row :kind kind :skipped (str "the routes are " x ", not written here")}])
                    (routes? x) (tree-results x f local nil row)
                    ;; composed of route fns, each read where it writes its routes
                    (composition? x) nil
                    :else [{:row row :kind kind :skipped "the routes are written as code" :pattern :pattern/reitit-literal-tree}])]
            r)
          (for [d defs
                :when (not (inlined (symbol (name (:var d)))))
                r (tree-results (:value d) f local (:var d) (:row d))]
            r)
          ;; routes a fn builds (reitit's :expand, a defn returning a tree)
          ;; still name them literally: {:view about :name :about-page}
          (for [{:keys [site name-row name-col] :as s} sites
                :let [form (form-at name-row name-col)]
                :when (and (seq? form)
                           (or (and (= :def site)
                                    (let [v (def-value form (:defined-by s))]
                                      ;; a router call is read at its own site
                                      (not (or (routes? v) (and (seq? v) (symbol? (first v)) (= "router" (name (first v))))))))
                               (and (= :router site) (not (symbol? (second form))) (not (routes? (second form))))))
                [id row] (named-in form (:find-row f) name-row)]
            {:row row :kind kind :dev-id id :refers true :entry {:reitit/named-in-code true}})))))

;; =============================================================================
;; Checks and the hover
;; =============================================================================

(defn- checks
  "- :route-not-defined (warning): a link to a route name no route read
     defines, at the place that links to it. A name a form the pack can't
     read as a tree gives literally ({:name :about-page} in an :expand fn)
     counts as defined. While some route trees couldn't be read, info, with
     :unread-trees: a name may still be built in one;
   - :handler-not-found (warning): a route's handler, written with a
     namespace (api/login), names a var in a namespace the analysis read,
     which defines no such var. A bare symbol may be a local, and isn't
     reported.

   With :pattern true in the pack's options, :off-pattern (info) where a
   route leaves the pattern (docs/pattern.md), and what is no longer exact
   for it: :pattern/reitit-named-route, a route with a handler and no :name,
   which nothing can link to and facts can't be given under a name of its
   own; :pattern/reitit-handler-var, a handler written as a fn there rather
   than a var, so what the route calls isn't followed.
   (:pattern/reitit-literal-tree, a tree or child written as code, comes
   from the forms not read.)"
  [{:keys [of-kinds loc var-defined? not-read opts]}]
  (let [routes (sort-by key (of-kinds #{kind}))
        unread (not-read kind)]
    (concat
     (for [[id e] routes
           :when (not (or (get-in e [:entry :reitit/path]) (get-in e [:entry :reitit/named-in-code])))
           d (:definitions e)
           :when (some #{:reitit/linked} (:keys d))]
       (cond-> {:severity (if (pos? unread) :info :warning) :problem :route-not-defined :dev-id id :definition (loc d)}
         (pos? unread) (assoc :unread-trees unread)))
     (for [[id e] routes
           h (get-in e [:entry :reitit/handlers])
           :when (and (not= (namespace h) (get-in e [:entry :reitit/ns])) (false? (var-defined? h)))]
       {:severity :warning :problem :handler-not-found :dev-id id :calls h :definition (loc e)})
     (when (get-in opts [:reitit :pattern])
       (for [[id e] routes
             :let [entry (:entry e)]
             :when (:reitit/path entry)
             [rule lost] [[:pattern/reitit-named-route
                           (when (and (:reitit/unnamed entry) (some entry [:reitit/handlers :reitit/handler-built-by :reitit/handler-code]))
                             "no :name: links can't name it, and facts about it are keyed by its path")]
                          [:pattern/reitit-handler-var
                           (when (:reitit/handler-code entry)
                             "a handler written as a fn here: what the route calls isn't followed")]]
             :when lost]
         {:severity :info :problem :off-pattern :pattern rule :dev-id id :lost lost :definition (loc e)})))))

(defn- describe [entry]
  (when-let [p (:reitit/path entry)]
    (str/join "  ·  " (remove nil? [(str "route " (str/join " " (map (comp str/upper-case name) (:reitit/methods entry))) (when (:reitit/methods entry) " ") p)
                                     (when-let [h (seq (:reitit/handlers entry))] (str "handler " (str/join " " h)))
                                     (when-let [v (:reitit/view entry)] (str "view " v))
                                     (when-let [m (seq (:reitit/middleware entry))] (str "middleware " (count m)))]))))

;; =============================================================================
;; The runtime half
;; =============================================================================

(defn runtime-entities
  "{route-name entry} for the named routes the running `router` has, as
   reitit compiled them: what code built (an :expand fn, a tree assembled
   at runtime) included. reitit.core/routes is resolved here: the process
   has reitit. Handlers and middleware given as #'vars, keywords, top-level
   fns (by their class) or maps with a :name are named; anonymous fns and
   closures are not."
  [router]
  (let [routes ((requiring-resolve 'reitit.core/routes) router)
        ;; a top-level fn is named by its class (user$wrap is user/wrap); a
        ;; closure or anonymous fn has no name to give
        fn-name (fn [f] (let [c (.getName (class f))]
                          (when-not (re-find #"\$fn__|\$reify__|__\d+$" c)
                            (let [[_ n v] (re-find #"^(.+?)\$([^$]+)$" c)]
                              (when (and n v) (symbol (clojure.lang.Compiler/demunge n) (clojure.lang.Compiler/demunge v)))))))
        named (fn [x] (cond (var? x) (symbol x) (keyword? x) x
                            (map? x) (let [n (:name x)] (when (or (keyword? n) (symbol? n)) n))
                            (fn? x) (fn-name x)
                            (and (vector? x) (seq x)) (recur (first x))))]
    (into (sorted-map)
          (for [[path data] routes
                :let [n (:name data)]
                :when (keyword? n)
                :let [ms (filterv #(contains? data %) http-methods)
                      hs (vec (distinct (keep named (cons (:handler data) (for [m ms :let [v (get data m)]] (if (map? v) (:handler v) v))))))
                      mw (vec (distinct (keep named (mapcat #(get data %) stacked))))]]
            [n (cond-> {:reitit/path path}
                 (seq ms) (assoc :reitit/methods ms)
                 (seq hs) (assoc :reitit/handlers hs)
                 (seq mw) (assoc :reitit/middleware mw))]))))

(defn snapshot!
  "Writes the named routes of the running `router` to `file` (default
   .meta-source/packs/reitit.edn), for meta-source.index to read beside the
   route trees in the code:

     (meta-source.pack.reitit/snapshot! router)"
  ([router] (snapshot! router {}))
  ([router {:keys [file] :or {file ".meta-source/packs/reitit.edn"}}]
   (let [f (java.io.File. ^String file)]
     (some-> (.getParentFile f) .mkdirs)
     (spit f (with-out-str (prn {:pack :reitit :written (System/currentTimeMillis)
                                 :entities (runtime-entities router)})))
     file)))

(defn- runtime-fill
  "`entry` with what the running router has and the code doesn't say."
  [entry runtime]
  (merge runtime entry))

(defn- runtime-diff
  "Where a route's path or methods in the code and in the running router
   differ; nil when they agree."
  [entry runtime]
  (not-empty
   (into (sorted-map)
         (for [k [:reitit/path :reitit/methods]
               :let [code (get entry k) live (get runtime k)]
               :when (and (some? live) (not= (if (vector? code) (set code) code) (if (vector? live) (set live) live)))]
           [k {:code code :runtime live}]))))

(def pack
  {:pack/id :reitit
   :pack/config-keys #{:reitit}
   :pack/kinds #{kind}
   :pack/vocabulary #{"reitit"}
   :pack/data-keys? false
   :pack/dataflow {:consumes [:reitit/parameter-keys] :produces [:reitit/response-keys]}
   :pack/find find-forms
   :pack/read read-forms
   :pack/checks checks
   :pack/describe describe
   ;; its runtime half: the router the process has (snapshot!)
   :pack/runtime-fill runtime-fill
   :pack/runtime-diff runtime-diff})
