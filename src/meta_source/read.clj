(ns meta-source.read
  "EXPERIMENTAL (see README.md) — reading EDN as data with the line of
   each top-level key, for meta-source.index and the packs (bb and JVM)."
  (:require [clojure.string :as str])
  (:import (java.io StringReader)
           (clojure.lang LineNumberingPushbackReader)))

(defn- blank-tag-meta
  "`text` with metadata written on a tagged literal blanked out, its lines
   and columns kept: duct's merge hints (^:replace #ig/ref :k). A tagged
   literal kept as data carries no metadata, so the reader would fail."
  [text]
  (str/replace text #"\^:[A-Za-z][\w.*+!?<>=/-]*(?=\s*#[A-Za-z])"
               (fn [m] (apply str (repeat (count m) " ")))))

(defn keyed-lines
  "[[k v line] ...] for the top-level map in `text`, `line` being where k is
   written, or nil when the text doesn't start with a map. The reader keeps
   no lines on maps, so the map is read one key and value at a time, noting
   the line before each key. Anything but whitespace and comments after the
   map is an error: it would be read by nobody. With `:tags :keep`, a tag no reader knows
   (#ig/ref, #env) is kept as a tagged literal instead of failing the read,
   and metadata on such a tag is dropped. With `:inner true`, each entry
   whose value is written as a map literal is [k v line inner], inner being
   that map's [[k v line] ...]."
  ([text] (keyed-lines text {}))
  ([text {:keys [tags inner]}]
   (let [text (if (= tags :keep) (blank-tag-meta text) text)
         r (LineNumberingPushbackReader. (StringReader. text))
         skip (fn [] (loop []
                       (let [c (.read r)]
                         (cond
                           (= c -1) c
                           (or (Character/isWhitespace c) (= c (int \,))) (recur)
                           (= c (int \;)) (do (while (not (#{-1 (int \newline)} (.read r)))) (recur))
                           :else c))))
         ;; the entries of a map whose { was just read, up to its }
         entries (fn entries [nested?]
                   (loop [acc []]
                     (let [c (skip)]
                       (cond
                         (= c -1) (if nested? (throw (ex-info "a map is not closed" {})) acc)
                         (= c (int \})) acc
                         :else
                         (do (.unread r c)
                             (let [line (.getLineNumber r)
                                   k (read r)
                                   c (skip)]
                               (if (and inner (not nested?) (= c (int \{)))
                                 (let [in (entries true)]
                                   (recur (conj acc [k (into {} (map (fn [[ik iv]] [ik iv])) in) line in])))
                                 (do (when (not= c -1) (.unread r c))
                                     (recur (conj acc [k (read r) line]))))))))))]
     (binding [*read-eval* false
               *default-data-reader-fn* (if (= tags :keep) tagged-literal *default-data-reader-fn*)]
       (when (= (int \{) (skip))
         (let [acc (entries false)]
           ;; a form after the map would be read by nobody
           (when (not= -1 (skip))
             (throw (ex-info (str "something after the map, at line " (.getLineNumber r)) {:line (.getLineNumber r)})))
           acc))))))
