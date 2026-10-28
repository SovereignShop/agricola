(ns agricola.data
  "Embed the same authoritative EDN into JVM and browser builds."
  (:require [clojure.edn :as edn] [clojure.java.io :as io]))
(defmacro read-edn [path]
  (let [resource (io/resource path)]
    (when-not resource (throw (ex-info "Missing game data resource" {:path path})))
    (list 'quote (edn/read-string (slurp resource)))))
