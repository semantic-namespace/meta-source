(ns meta-source.libs
  "A project's libraries whose own registrations count: meta-source.edn's
   :libs names deps the project uses (io.github.semantic-namespace/atlas),
   whose source registers entities the project refers to (a prompt naming
   :atlas.llm-ide/entity-detail). Each lib's coordinate is the project's
   own, from its deps.edn, resolved as the clojure CLI would (a jar from
   Maven, a git dep, a :local/root), and its sources are copied under
   .meta-source/libs/<lib>@<version>/, so the packs read them as they read
   the project's. What the checks find in a lib's code is the lib's own
   and is never reported (index/check)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.util.zip ZipFile)))

(def ^:private source-exts [".clj" ".cljc" ".cljs"])

(defn- source? [path] (some #(str/ends-with? path %) source-exts))

(defn- coord-of
  "The project's coordinate for `lib`: its deps.edn's :deps, else an alias's
   :extra-deps."
  [root lib]
  (let [f (io/file root "deps.edn")
        deps (when (.isFile f) (edn/read-string (slurp f)))]
    (or (get-in deps [:deps lib])
        (some #(get-in % [:extra-deps lib]) (vals (:aliases deps))))))

(defn- classpath
  "The classpath entries `lib` at `coord` resolves to, with its own deps:
   clojure -Spath (babashka's deps.clj) from an empty directory, so the
   project's deps.edn doesn't join in."
  [lib coord]
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "meta-source-libs-" (System/nanoTime))) .mkdirs)
        ;; deps.clj runs in process and prints the classpath
        out (try (with-out-str ((requiring-resolve 'babashka.deps/clojure)
                                ["-Spath" "-Sdeps" (pr-str {:paths [] :deps {lib coord}})]
                                {:dir (str dir)}))
                 (catch Exception ex
                   (throw (ex-info (str "meta-source :libs: " lib " doesn't resolve: " (ex-message ex)) {:lib lib})))
                 (finally (.delete dir)))]
    (str/split (str/trim out) (re-pattern java.io.File/pathSeparator))))

(defn- own-entries
  "The entries of `cp` that are `lib` itself, not its deps."
  [lib coord cp]
  (let [group (namespace lib) artifact (name lib)]
    (filter (fn [e]
              (let [e (str/replace e "\\" "/")]
                (cond
                  (:mvn/version coord) (str/includes? e (str "/" (str/replace group "." "/") "/" artifact "/" (:mvn/version coord) "/"))
                  (:git/sha coord) (str/includes? e (str "/libs/" lib "/" (:git/sha coord)))
                  (:local/root coord) (str/starts-with? (.getCanonicalPath (io/file e))
                                                        (.getCanonicalPath (io/file (:local/root coord))))
                  :else false)))
            cp)))

(defn- copy-sources!
  "The sources of a classpath entry (a jar or a directory) into `dir`."
  [entry ^java.io.File dir]
  (let [f (io/file entry)]
    (cond
      (and (.isFile f) (str/ends-with? entry ".jar"))
      (with-open [z (ZipFile. f)]
        (doseq [e (enumeration-seq (.entries z))
                :let [n (.getName e)]
                :when (and (not (.isDirectory e)) (source? n) (not (str/starts-with? n "META-INF")))]
          (let [out (io/file dir n)]
            (io/make-parents out)
            (with-open [in (.getInputStream z e)] (io/copy in out)))))
      (.isDirectory f)
      (doseq [^java.io.File s (file-seq f)
              :when (and (.isFile s) (source? (.getName s)))]
        (let [out (io/file dir (str (.relativize (.toPath f) (.toPath s))))]
          (io/make-parents out)
          (io/copy s out))))))

(defn materialize
  "The source directories, relative to `root`, holding `libs`' sources: one
   per lib, under .meta-source/libs/, copied once per version. A lib the
   project's deps.edn doesn't name is an error."
  [root libs]
  (vec (for [lib libs
             :let [lib (symbol lib)
                   coord (or (coord-of root lib)
                             (throw (ex-info (str "meta-source :libs: " lib " is not in deps.edn") {:lib lib})))
                   version (or (:mvn/version coord) (:git/sha coord) "local")
                   rel (str ".meta-source/libs/" lib "@" version)
                   dir (io/file root rel)]]
         (do (when (or (:local/root coord) (not (.isDirectory dir)))
               (let [coord (cond-> coord
                             (:local/root coord) (update :local/root #(.getCanonicalPath (io/file root %))))
                     cp (classpath lib coord)]
                 (.mkdirs dir)
                 (doseq [e (own-entries lib coord cp)] (copy-sources! e dir))))
             rel))))
