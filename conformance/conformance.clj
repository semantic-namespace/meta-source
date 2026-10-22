(ns conformance
  "The Clojure reader's answers about conformance/polyglot's registry, the
   ones every meta-source reader must give (js/test/conformance.test.js
   checks the JavaScript reader against the same file).

   bb conformance          checks the Clojure reader against expected.json
   bb conformance --write  writes expected.json from the Clojure reader

   Only what is about the registry is compared: where an id is defined, its
   sources and merged entry, what names it, what it names, a data key's
   producers and consumers, the registry's problems. What a reader finds in
   its own language's code (an implementing var, code calling it, coverage)
   is left out by `project`; each reader's own tests check that part."
  (:require [meta-source.index :as index]
            [meta-source.hover :as hover]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def root "conformance/polyglot")
(def expected-file "conformance/expected.json")

(def questions
  ["where :fn/price" "where :fn/render-receipt" "where :fn/apply-discount" "where :page/receipt"
   "uses :fn/price" "uses :fn/render-receipt"
   "deps :fn/render-receipt" "deps :page/receipt"
   "data :order/total" "data :order/lines"
   "check"
   "hover :fn/price" "hover :order/total" "hover :nope/x"])

(def registry-problems #{"conflicting-values" "unreadable-registry-file"})

(defn- deep-sort
  "Arrays of scalars sorted (a set's order is the reader's); everything else as is."
  [x]
  (cond
    (map? x) (into (sorted-map) (for [[k v] x] [k (deep-sort v)]))
    (sequential? x) (let [xs (mapv deep-sort x)]
                      (if (every? #(or (string? %) (number? %) (boolean? %) (nil? %)) xs) (vec (sort-by str xs)) xs))
    :else x))

(defn project
  "The part of an answer every reader must give, as JSON data."
  [question answer]
  (let [[cmd] (str/split question #" ")
        root-path (str (.getCanonicalPath (io/file root)) "/")
        rel (fn rel [x] (cond (string? x) (str/replace x root-path "")
                              (map? x) (into {} (for [[k v] x] [k (rel v)]))
                              (sequential? x) (mapv rel x)
                              :else x))
        a (rel answer)]
    (deep-sort
     (case cmd
       "where" (dissoc a "impl")
       "uses" (select-keys a ["dev-id" "referenced-by"])
       "check" (filterv #(registry-problems (get % "problem")) a)
       "hover" (case (get a "kind")
                 "entity" {"kind" "entity"
                           "definitions" (filterv #(str/starts-with? (get % "summary") "defined") (get a "definitions"))
                           "references" (filterv #(not (str/starts-with? (get % "summary") "called from")) (get a "references"))}
                 a)
       a))))

(defn- kw [s] (keyword (str/replace s #"^:" "")))

(defn answer [idx question]
  (let [[cmd arg] (str/split question #" ")
        out (case cmd
              "where" (index/where idx (kw arg))
              "uses" (index/uses idx (kw arg))
              "deps" (index/dependencies idx (kw arg))
              "data" (index/data-key idx (kw arg))
              "check" (index/check idx)
              "hover" (hover/lookup idx (kw arg)))]
    ;; as the command's --json prints it
    (json/parse-string (json/generate-string out))))

(defn -main [& args]
  (let [idx (index/build (assoc (edn/read-string (slurp (io/file root "meta-source.edn"))) :root root))
        answers (into (sorted-map) (for [q questions] [q (project q (answer idx q))]))]
    (if (some #{"--write"} args)
      (do (spit expected-file (str (json/generate-string answers {:pretty true}) "\n"))
          (println "wrote" expected-file (count answers) "answers"))
      (let [expected (json/parse-string (slurp expected-file))
            bad (for [[q e] expected :when (not= e (get answers q))] q)]
        (doseq [q (keys expected)] (println (if (some #{q} bad) "FAIL" "ok  ") q))
        (when (seq bad) (System/exit 1))))))
