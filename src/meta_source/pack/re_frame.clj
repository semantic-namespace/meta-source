(ns meta-source.pack.re-frame
  "EXPERIMENTAL (see README.md) — the re-frame pack: events, subscriptions,
   effects and coeffects, read as data from the calls that register them
   and the calls that refer to them. Nothing is loaded or evaluated.

   `pack` is what meta-source.index runs (see the Packs section of
   meta-source.index), enabled by :re-frame under meta-source.edn's :packs:

     :re-frame {:provided [:http-xhrio]}   ; ids a library registers: effects,
                                           ; coeffects, events, subscriptions

   clj-kondo finds the re-frame.core calls; the pack reads each form. An id
   is one entity, however many places register it or refer to it. A
   registration's entry, in the pack's own vocabulary:

     :re-frame/registered  #{:event} #{:sub} #{:fx} #{:cofx}
     :re-frame/handler     an event's handler: :db, :fx or :ctx
     :re-frame/signals     a subscription's :<- inputs
     :re-frame/dispatches  the events an event's effects dispatch, an
                           effect's :on-* callbacks among them
     :re-frame/effects     the effects an event's handler returns, built-ins
                           (:db, :fx, :dispatch ...) left out
     :re-frame/cofx        the coeffects it injects
     :re-frame/reads       qualified keys it reads from the db or its input:
                           (:k db), (get db :k), (get-in db [:k ...])
     :re-frame/writes      qualified keys an event writes: assoc, assoc-in,
                           update, update-in (a dissoc removes, it produces nothing)
     :re-frame/not-read    [:handler] when the handler is a var, not a fn
                           written there; [:effects] when it returns what
                           another fn builds (whose dispatches are read when
                           that fn is a defn in a file using re-frame)

   Each place that refers to an id gives it one of :re-frame/dispatched,
   :re-frame/subscribed, :re-frame/injected or :re-frame/effect-used: a
   dispatch, dispatch-sync, :dispatch effect and :fx [:dispatch ...];
   a subscribe, sub, :<-; an inject-cofx; an effect a handler returns. The
   checks join the two: what is referred to and never registered, and what
   is registered and never referred to."
  (:require [clojure.string :as str]))

(def kind :re-frame)

(def ^:private registers
  '{reg-event-db :event reg-event-fx :event reg-event-ctx :event
    reg-sub :sub reg-sub-raw :sub reg-fx :fx reg-cofx :cofx})

(def ^:private refers
  '{dispatch :dispatched dispatch-sync :dispatched subscribe :subscribed sub :subscribed inject-cofx :injected})

;; what re-frame registers itself; :reg-flow, :clear-flow and :flow are re-frame.alpha's flows
(def ^:private builtin-effects #{:db :fx :dispatch :dispatch-n :dispatch-later :deregister-event-handler :reg-flow :clear-flow})
(def ^:private builtin {:cofx #{:db} :sub #{:flow}})

;; =============================================================================
;; Finding the calls
;; =============================================================================

(defn- kw-of [{:keys [ns name]}]
  (if ns (keyword (str ns) (str name)) (keyword (str name))))

(defn- find-forms
  "Each re-frame.core (or re-frame.alpha) call that registers or refers to an id. A
   registration carries the keywords written inside it, with their rows,
   so what its effects dispatch is reported where it is written."
  [analysis _opts]
  (let [kws (group-by :filename (:keywords analysis))
        inside (fn [u] (vec (for [k (get kws (:filename u))
                                  :when (<= (:row u) (:row k) (or (:end-row u) (:row u)))]
                              [(kw-of k) (:row k)])))
        rf? #(#{'re-frame.core 're-frame.alpha} (:to %))
        rf-files (set (map :filename (filter rf? (:var-usages analysis))))]
    (concat
     (for [u (:var-usages analysis)
           :when (and (rf? u) (or (registers (:name u)) (refers (:name u))))]
       (cond-> (select-keys u [:filename :row :name-row :name-col])
         (registers (:name u)) (assoc :registers (registers (:name u)) :kws (inside u))
         (refers (:name u)) (assoc :refers (refers (:name u)))))
     ;; a fn a handler delegates to, which builds the effects: a defn in a
     ;; file using re-frame, with a dispatch key written in it
     (for [u (:var-usages analysis)
           :when (and (rf-files (:filename u)) ('#{clojure.core cljs.core} (:to u)) ('#{defn defn-} (:name u)))
           :let [ks (inside u)]
           :when (some (fn [[k]] (#{:dispatch :dispatch-n :first-dispatch} k)) ks)]
       (assoc (select-keys u [:filename :row :name-row :name-col]) :helper true :kws ks)))))

;; =============================================================================
;; Reading a handler
;; =============================================================================

(defn- head [form]
  (when (and (seq? form) (symbol? (first form))) (symbol (name (first form)))))

(defn- fn-body
  "The body of a (fn [args] body...) form, or nil. re-frame-debux's
   fn-traced is a fn."
  [form]
  (when (#{'fn 'fn* 'fn-traced} (head form))
    (let [more (if (symbol? (second form)) (drop 2 form) (rest form))]
      (when (vector? (first more)) (rest more)))))

(defn- returned
  "The forms `form` may return, through let, when, if, cond, case, try, do, and
   and -> and cond-> (over a map, with the effects its steps assoc)."
  [form]
  (case (head form)
    (let let* when when-not when-let when-some if-let if-some do binding letfn) (returned (last form))
    (if if-not) (mapcat returned (drop 2 form))
    try (let [[body handlers] (split-with #(not (#{'catch 'finally} (head %))) (rest form))]
          (concat (returned (last body))
                  (for [h handlers :when (= 'catch (head h)) r (returned (last h))] r)))
    cond (mapcat returned (take-nth 2 (drop 2 form)))
    ;; (case x a ra b rb default)
    case (let [clauses (drop 2 form)]
           (mapcat returned (concat (take-nth 2 (rest clauses)) (when (odd? (count clauses)) [(last clauses)]))))
    (-> cond->) (let [steps (if (= 'cond-> (head form)) (take-nth 2 (drop 3 form)) (drop 2 form))]
                  (for [m (returned (second form))]
                    (if (map? m)
                      (into m (for [s steps :when (= 'assoc (head s))
                                    [k v] (partition 2 (rest s))]
                                [k v]))
                      m)))
    [form]))

(defn- branches?
  "True for a form `returned` looks inside, rather than returns."
  [form]
  (contains? '#{let let* when when-not when-let when-some if-let if-some do binding letfn if if-not try cond case} (head form)))

(defn- event-of [v] (when (and (vector? v) (keyword? (first v))) (first v)))

(defn- bindings
  "{sym [value ...]} for every let-like binding in `body`."
  [body]
  (apply merge-with into {}
         (for [f (tree-seq coll? seq body)
               :when (and (#{'let 'let* 'when-let 'when-some 'if-let 'if-some 'loop 'binding} (head f)) (vector? (second f)))
               [sym v] (partition 2 (second f))
               :when (symbol? sym)]
           {sym [v]})))

(defn- dispatches-and-effects
  "[dispatched-ids effect-ids] in an event handler's `body`. A dispatch is
   looked for anywhere in it: under :dispatch, :dispatch-n, :first-dispatch
   or an :on-* key (http-xhrio's {:on-success [::paid]}, async-flow's rules),
   or as [:dispatch ...] in :fx; a local it names is followed to its value.
   Effects are the keys of the maps it returns and of maps holding :fx,
   and what :fx entries name."
  [body]
  (let [nodes (tree-seq coll? seq body)
        bound (bindings body)
        values (fn values [v] (if (and (symbol? v) (bound v)) (mapcat values (bound v)) (returned v)))
        one (fn [v] (keep event-of (values v)))
        ;; [[::a] [::b]], (cond-> [[::a]] x (conj [::b]) y (into [[::c]])), a local
        many (fn many [x]
               (case (head x)
                 conj (concat (many (second x)) (mapcat one (drop 2 x)))
                 into (mapcat many (rest x))
                 (cond-> ->) (concat (many (second x))
                                     (for [step (if (= 'cond-> (head x)) (take-nth 2 (drop 3 x)) (drop 2 x))
                                           e (case (head step)
                                               conj (mapcat one (rest step))
                                               into (mapcat many (rest step))
                                               nil)]
                                       e))
                 (cond
                   (vector? x) (mapcat one x)
                   (and (symbol? x) (bound x)) (mapcat many (bound x))
                   (branches? x) (mapcat many (returned x)))))
        maps (filter map? nodes)
        effect-maps (distinct (concat (filter map? (returned (last body))) (filter #(contains? % :fx) maps)))
        fx (for [m maps e (:fx m) r (returned e) :when (and (vector? r) (keyword? (first r)))] r)
        evs (concat (for [m maps [k v] m
                          :when (and (keyword? k) (or (#{:dispatch :first-dispatch} k) (str/starts-with? (name k) "on-")))
                          e (one v)]
                      e)
                    (for [m maps :when (contains? m :dispatch-n) e (many (:dispatch-n m))] e)
                    (for [r nodes :when (and (vector? r) (= 2 (count r)) (= :dispatch (first r)))  e (one (second r))] e)
                    (for [r nodes :when (and (vector? r) (= 2 (count r)) (= :dispatch-n (first r))) e (many (second r))] e))]
    [(vec (distinct evs))
     (vec (distinct (remove builtin-effects (filter keyword? (concat (mapcat keys effect-maps) (map first fx))))))]))

(defn- calls [form] (filter seq? (tree-seq coll? seq form)))

(defn- path-keys [p] (filter qualified-keyword? (if (vector? p) p [p])))

(defn- reads [body]
  (vec (distinct (concat (for [c (calls body) :when (qualified-keyword? (first c))] (first c))
                         (for [c (calls body) :when (#{'get 'get-in} (head c))
                               k (path-keys (nth c 2 nil))]
                           k)))))

(defn- writes [body]
  (vec (distinct (for [c (calls body)
                       :let [h (head c)]
                       :when (#{'assoc 'assoc-in 'update 'update-in} h)
                       ;; (assoc db k v) or, threaded, (assoc k v)
                       :let [args (if (keyword? (second c)) (rest c) (drop 2 c))]
                       k (if (= 'assoc h) (mapcat path-keys (take-nth 2 args)) (path-keys (first args)))]
                   k))))

(defn- registration
  "{:entry} for a registration form, with what its handler dispatches and
   the effects it returns."
  [as form]
  (let [[_ _id & args] form
        handler (last args)
        body (fn-body handler)
        interceptors (when (and (= :event as) (= 2 (count args))) (first args))
        cofx (vec (for [i (if (vector? interceptors) interceptors [])
                        :when (= 'inject-cofx (head i))
                        :let [c (second i)]
                        :when (keyword? c)]
                    c))
        signals (when (= :sub as) (vec (for [[k v] (partition 2 (butlast args)) :when (= :<- k) :let [s (event-of v)] :when s] s)))
        handler-kind ({'reg-event-db :db 'reg-event-fx :fx 'reg-event-ctx :ctx} (head form))
        [dispatched effects] (when (= :fx handler-kind) (dispatches-and-effects body))
        ;; a handler returning what a fn of its own builds: (enter db uid)
        delegates? (and (= :fx handler-kind) (some #(and (seq? %) (not (map? %))) (returned (last body))))]
    (cond-> {:re-frame/registered #{as}}
      handler-kind (assoc :re-frame/handler handler-kind)
      (seq signals) (assoc :re-frame/signals signals)
      (seq dispatched) (assoc :re-frame/dispatches dispatched)
      (seq effects) (assoc :re-frame/effects effects)
      (seq cofx) (assoc :re-frame/cofx cofx)
      (and body (#{:event :sub} as) (seq (reads body))) (assoc :re-frame/reads (reads body))
      (and body (= :event as) (seq (writes body))) (assoc :re-frame/writes (writes body))
      (and (#{:event :sub} as) (not body) (symbol? handler)) (assoc :re-frame/not-read [:handler])
      delegates? (assoc :re-frame/not-read [:effects]))))

(defn- read-forms [sites {:keys [form-at]} _opts]
  (vec (for [{:keys [row name-row name-col registers refers kws helper]} sites
             :let [form (form-at name-row name-col)
                   [_ x] (when (seq? form) form)
                   ids (cond (= :injected refers) [x]
                             refers (or (seq (keep event-of (returned x))) [x])
                             :else [x])
                   id (first ids)
                   ;; where a keyword is written in the form, from clj-kondo
                   row-of (fn [k] (or (some (fn [[kw r]] (when (and (= k kw) (> r row)) r)) kws) row))]
             r (cond
                 (= form :unreadable) [{:row row :kind kind :skipped "not readable as data"}]
                 helper (for [e (first (dispatches-and-effects (drop 2 form)))]
                          {:row (row-of e) :kind kind :refers true :dev-id e :entry {:re-frame/dispatched true}})
                 (not (keyword? id)) (when (seq? form)
                                       [{:row row :kind kind
                                         :skipped (str (or (some-> form first name) "the call") ": the id is written as code")}])
                 refers (for [id ids :when (keyword? id)]
                          {:row row :kind kind :dev-id id :refers true :entry {(keyword "re-frame" (name refers)) true}})
                 :else (let [entry (registration registers form)]
                         (concat
                          [{:row row :kind kind :dev-id id :entry entry}]
                          ;; what it refers to, at the line it is written on
                          (for [e (:re-frame/dispatches entry)] {:row (row-of e) :kind kind :refers true :dev-id e :entry {:re-frame/dispatched true}})
                          (for [e (:re-frame/effects entry)] {:row (row-of e) :kind kind :refers true :dev-id e :entry {:re-frame/effect-used true}})
                          (for [s (:re-frame/signals entry)] {:row (row-of s) :kind kind :refers true :dev-id s :entry {:re-frame/subscribed true}}))))]
         r)))

;; =============================================================================
;; Checks and the hover
;; =============================================================================

(def ^:private needs
  {:re-frame/dispatched [:event :event-not-registered :warning]
   :re-frame/subscribed [:sub :sub-not-registered :warning]
   :re-frame/effect-used [:fx :effect-not-registered :info]
   :re-frame/injected [:cofx :cofx-not-registered :info]})

(defn- checks
  "- :event-not-registered, :sub-not-registered (warning): a dispatch or a
     subscription to an id nothing registers and :provided doesn't list (an
     event re-pressed registers), at each place that refers to it. re-frame
     logs an error and does nothing;
   - :effect-not-registered, :cofx-not-registered (info): an effect a
     handler returns, or a coeffect it injects, that nothing read registers
     and :provided doesn't list (a library registers :http-xhrio);
   - :event-never-dispatched, :sub-never-subscribed (info): registered and
     referred to nowhere read. A dispatch written as code, or from a handler
     the pack couldn't read (:unread-handlers), may still reach it."
  [{:keys [of-kinds loc opts]}]
  (let [entities (sort-by key (of-kinds #{kind}))
        provided (set (get-in opts [:re-frame :provided]))
        unread (vec (for [[id e] entities :when (get-in e [:entry :re-frame/not-read])] id))
        registration #(or (first (filter (fn [d] (some #{:re-frame/registered} (:keys d))) (:definitions %))) %)]
    (concat
     (for [[id e] entities
           :let [registered (get-in e [:entry :re-frame/registered] #{})]
           d (:definitions e)
           k (:keys d)
           :let [[as problem severity] (needs k)]
           :when (and as (not (registered as))
                      (not (contains? (get builtin as) id))
                      (not (provided id)))]
       {:severity severity :problem problem :dev-id id :definition (loc d)})
     (for [[id e] entities
           :let [{:re-frame/keys [registered dispatched subscribed]} (:entry e)]
           [as used? problem] [[:event dispatched :event-never-dispatched] [:sub subscribed :sub-never-subscribed]]
           :when (and (contains? registered as) (not used?))]
       (cond-> {:severity :info :problem problem :dev-id id :definition (loc (registration e))}
         (and (= :event as) (seq unread)) (assoc :unread-handlers unread))))))

(defn- describe [entry]
  (when (some #(= "re-frame" (namespace %)) (keys entry))
    (let [{:re-frame/keys [registered handler signals dispatches effects cofx]} entry
          ids #(str/join " " (map pr-str %))
          refs (keep (fn [[k w]] (when (get entry k) w))
                     [[:re-frame/dispatched "dispatched"] [:re-frame/subscribed "subscribed"]
                      [:re-frame/injected "injected"] [:re-frame/effect-used "returned by a handler"]])]
      (str/join "  ·  " (remove nil? [(if (seq registered)
                                         (str (str/join "/" (map name (sort registered))) (when handler (str " (" (name handler) ")")))
                                         "not registered")
                                       (when (seq refs) (str/join ", " refs))
                                       (when (seq signals) (str "signals " (ids signals)))
                                       (when (seq dispatches) (str "dispatches " (ids dispatches)))
                                       (when (seq effects) (str "effects " (ids effects)))
                                       (when (seq cofx) (str "cofx " (ids cofx)))])))))

(def pack
  {:pack/id :re-frame
   :pack/config-keys #{:re-frame}
   :pack/kinds #{kind}
   :pack/vocabulary #{"re-frame"}
   :pack/data-keys? false
   :pack/merge {:re-frame/registered :union}
   :pack/dataflow {:consumes [:re-frame/reads] :produces [:re-frame/writes]}
   :pack/find find-forms
   :pack/read read-forms
   :pack/checks checks
   :pack/describe describe})
