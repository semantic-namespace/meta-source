(ns jvm-check
  "meta-source as a library on the JVM: every library namespace loads there,
   and the shop fixture's index is the one babashka builds. babashka ignores
   type hints, so a hint naming an unimported class (a ^File) only fails on
   the JVM. Needs the clojure CLI."
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [meta-source.cli :as cli]
            [meta-source.index :as index]))

;; meta-source.corpus is babashka tooling (babashka.fs, babashka.process)
(def namespaces
  '[meta-source.read meta-source.merge meta-source.rules meta-source.libs
    meta-source.pack.aero meta-source.pack.datascript meta-source.pack.fn-schema
    meta-source.pack.integrant meta-source.pack.pathom meta-source.pack.re-frame
    meta-source.pack.reitit meta-source.pack.schema meta-source.pack.tilakone
    meta-source.index meta-source.hover meta-source.cli])

(def fixture "test/fixtures/shop")

(defn- summary [idx]
  {:entities (count (:entities idx)) :findings (count (index/check idx))})

(defn -main []
  (let [deps (pr-str {:deps {'clj-kondo/clj-kondo {:mvn/version "2026.01.19"}
                             'cheshire/cheshire {:mvn/version "5.13.0"}
                             'io.github.semantic-namespace/meta-source {:local/root "."}}})
        expr (str "(doseq [n '" (pr-str namespaces) "] (require n))"
                  "(let [idx ((requiring-resolve 'meta-source.index/build) ((requiring-resolve 'meta-source.cli/config) {:root \"" fixture "\"}))]"
                  "  (prn {:entities (count (:entities idx)) :findings (count ((requiring-resolve 'meta-source.index/check) idx))}))")
        {:keys [exit out err]} @(p/process ["clojure" "-Sdeps" deps "-M" "-e" expr] {:out :string :err :string})
        jvm (when (zero? exit) (edn/read-string (last (str/split-lines (str/trim out)))))
        bb (summary (index/build (cli/config {:root fixture})))]
    (cond
      (not (zero? exit)) (do (println "FAIL loading on the JVM:") (println err) (System/exit 1))
      (not= jvm bb) (do (println "FAIL the JVM index differs:" jvm "on the JVM," bb "on babashka") (System/exit 1))
      :else (println "jvm-check: every namespace loads on the JVM; the fixture's index matches babashka's" bb))))
