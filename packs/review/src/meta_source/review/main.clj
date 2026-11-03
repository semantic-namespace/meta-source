(ns meta-source.review.main
  "EXPERIMENTAL. sdiff with meta-source's findings.

    text  <repo> <base> <head>      a local range, as `bb sdiff text` prints it
    pr    <owner/repo#N|url>        a pull request, through gh, with the
                                    keyword dependencies meta-source adds
    serve [port] [--host addr]      sdiff's review UI (default 127.0.0.1:7878)

  Both read the project at the merge base and at the head with the head's
  meta-source.edn, or, without one, the repository's entry in
  ~/.config/meta-source/review.edn (META_SOURCE_REVIEW_CONFIG overrides
  where)."
  (:require [meta-source.review :as review]
            [sdiff.decorate :as d]
            [sdiff.git :as git]
            [sdiff.github :as github]
            [sdiff.render.text :as text]
            [sdiff.serve :as serve]))

(defn -main [& [cmd & args]]
  (review/install!)
  (case cmd
    "text" (let [[repo base head] args
                 r (assoc (git/report repo base head :merge-base? true) ::review/repo repo)]
             (binding [d/*ctx* (@d/context-fn r)]
               (text/print-report r)))
    "pr" (let [r (github/report (first args))]
           (println (str "#" (:num r) " " (:title r) "\n" (get-in r [:pr :url])))
           (binding [d/*ctx* (@d/context-fn r)]
             (text/print-report r)))
    "serve" (let [port (some-> (first (remove #(re-find #"^--" %) (take 1 args))) parse-long)
                  host (second (drop-while #(not= "--host" %) args))
                  {:keys [url]} (serve/start! (or port 7878) (or host "127.0.0.1"))]
              (println (str "sdiff with meta-source on " url))
              @(promise))
    (println (:doc (meta (find-ns (quote meta-source.review.main)))))))
