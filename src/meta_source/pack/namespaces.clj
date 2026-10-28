(ns meta-source.pack.namespaces
  "EXPERIMENTAL (see README.md) — the namespaces pack: a project's own
   namespaces as entities, by a naming convention the project states. Read
   from clj-kondo's analysis; nothing is loaded.

   `pack` is what meta-source.index runs (see the Packs section of
   meta-source.index), enabled by :namespaces under meta-source.edn's
   :packs, with the roots the convention maps:

     {:packs {:namespaces {:roots {\"org.acme.shop\" \"shop\"}}}}

   A namespace under a root is an entity whose id is the rest of its name,
   its last segment the id's name and the ones before it the id's
   namespace, under the id root: org.acme.shop.orders.api is
   :shop.orders/api, org.acme.shop.db is :shop/db. The longest root a
   namespace is under maps it. Its entry:

     :meta-source/ns        the namespace's name
     :meta-source/requires  the ids of the namespaces under a root it
                            requires; namespaces outside every root (a
                            library's) aren't the system's, and aren't named

   So `deps` on a namespace lists what it requires of the project, `uses`
   what requires it, and a rule can say which may require which. The same
   ids written elsewhere (an architecture model's component, an atlas
   entity) are the same entities: what they say of a namespace is compared
   with what its requires do.

   Nothing is guessed: a namespace is under a root or it isn't. The root
   namespace itself has no name under the root to give its id: it is not
   read, and with :pattern true in the pack's options it is :off-pattern
   (:pattern/namespace-under-root)."
  (:require [clojure.string :as str]))

(def kind :namespace)

(defn- roots
  "[[ns-root id-root] ...], longest first, as strings."
  [opts]
  (sort-by (comp - count first)
           (for [[n i] (get-in opts [:namespaces :roots])] [(name n) (name i)])))

(defn- root-of [rs ns]
  (first (filter (fn [[r]] (or (= ns r) (str/starts-with? ns (str r ".")))) rs)))

(defn id-of
  "The id `ns` (a string) has under the roots `rs`, :root for a root
   itself, or nil when it is under none."
  [rs ns]
  (when-let [[r i] (root-of rs ns)]
    (if (= ns r)
      :root
      (let [segs (str/split (subs ns (inc (count r))) #"\.")]
        (keyword (str/join "." (cons i (butlast segs))) (last segs))))))

(defn- find-namespaces
  "Each ns form of a namespace under a root, with the ids of what it
   requires under a root. A .cljc file's ns form is one, whatever languages
   kondo reads it as."
  [analysis opts]
  (let [rs (roots opts)
        requires (group-by (juxt :filename :from) (:namespace-usages analysis))]
    (for [{:keys [filename name row name-row name-col]} (:namespace-definitions analysis)
          :let [id (id-of rs (str name))]
          :when id]
      {:filename filename :row row :name-row name-row :name-col name-col :ns (str name) :id id
       :requires (vec (distinct (for [u (sort-by (juxt :row :col) (get requires [filename name]))
                                      :let [r (id-of rs (str (:to u)))]
                                      :when (and (keyword? r) (not= r id))]
                                  r)))})))

(defn- read-namespaces [sites _file _opts]
  (for [{:keys [row ns id requires]} sites]
    (if (= :root id)
      {:row row :kind kind :pattern :pattern/namespace-under-root
       :skipped (str ns " is a root of the convention: no name under it to give its id")}
      {:row row :kind kind :dev-id id
       :entry (cond-> {:meta-source/ns ns}
                (seq requires) (assoc :meta-source/requires requires))})))

(defn- describe [entry]
  (when-let [ns (:meta-source/ns entry)]
    (let [n (count (:meta-source/requires entry))]
      (str "namespace " ns (when (pos? n) (str "  ·  requires " n " of the project's"))))))

(def pack
  {:pack/id :namespaces
   :pack/config-keys #{:namespaces}
   :pack/kinds #{kind}
   :pack/vocabulary #{"meta-source"}
   :pack/data-keys? false
   ;; a .clj and a .cljs file of one namespace each say what they require
   :pack/merge {:meta-source/requires :union}
   :pack/find find-namespaces
   :pack/read read-namespaces
   :pack/describe describe})
