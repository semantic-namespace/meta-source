(ns emacs-smoke
  "Runs test/emacs/meta-source-test.el: meta-source.el against a real
   `meta-source serve' on the shop fixture. Its hover tests need
   clojure-mode: $CLOJURE_MODE_DIR, or where Debian's elpa-clojure-mode,
   straight or package.el put it; without it they are skipped. Needs Emacs
   27 or later: $EMACS, or the first of emacs, /snap/bin/emacs that is."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn- version [emacs]
  (try (some->> (:out (p/shell {:out :string :err :string :continue true} emacs "--version"))
                (re-find #"GNU Emacs (\d+)") second parse-long)
       (catch Exception _ nil)))

(defn- clojure-mode-dir []
  (let [home (System/getProperty "user.home")]
    (->> (concat [(System/getenv "CLOJURE_MODE_DIR")]
                 (map str (fs/glob "/usr/share/emacs/site-lisp" "elpa*/clojure-mode*"))
                 [(str home "/.emacs.d/straight/build/clojure-mode")]
                 (map str (fs/glob (str home "/.emacs.d/elpa") "clojure-mode-*")))
         (remove nil?)
         (filter #(fs/exists? (fs/path % "clojure-mode.el")))
         first)))

(defn -main [& _]
  (let [emacs (first (filter #(some-> (version %) (>= 27))
                             (remove nil? [(System/getenv "EMACS") "emacs" "/snap/bin/emacs"])))
        dir (clojure-mode-dir)]
    (when-not emacs
      (println "emacs-smoke: needs Emacs 27 or later (set EMACS)")
      (System/exit 1))
    (println "emacs-smoke:" (str/trim (first (str/split-lines (:out (p/shell {:out :string} emacs "--version")))))
             (if dir (str "with clojure-mode from " dir) "without clojure-mode: hover tests skipped"))
    (System/exit (:exit (apply p/shell {:continue true} emacs "--batch" "-Q"
                               (concat (when dir ["-L" dir])
                                       ["-l" "test/emacs/meta-source-test.el" "-f" "ert-run-tests-batch-and-exit"]))))))
