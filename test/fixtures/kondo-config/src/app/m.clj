(ns app.m)
(defmacro defhandler [name args & body] `(defn ~name ~args ~@body))
