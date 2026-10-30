(ns agricola.card-random
  "A private persisted random stream, identical in JVM and browser replays."
  (:require [clojure.string :as str]))
(def modulus 2147483647)
(defn seed [g]
  ;; Real tables supply an independent setup seed. This deterministic fallback
  ;; supports fixtures and old saves without using runtime-dependent hashes.
  (let [text (str (:starting-player g) "|" (str/join "," (map str (:round-order g))) "|"
                  (str/join "," (sort (map str (mapcat #(mapcat val (:hand %)) (:players g))))))]
    (inc (reduce (fn [n ch] (mod (+ (* 31 n) #?(:clj (int ch) :cljs (.charCodeAt ch 0))) (dec modulus))) 0 text))))
(defn choose [g choices]
  (let [next (mod (* 48271 (or (:card-rng g) (seed g))) modulus)]
    [(assoc g :card-rng next) (nth choices (mod (dec next) (count choices)))]))
