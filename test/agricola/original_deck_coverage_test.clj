(ns agricola.original-deck-coverage-test
  (:require [clojure.test :refer :all] [clojure.java.io :as io]
            [agricola.card-rules :as c] [agricola.card-expressions :as expr]))

(defn art-map []
  (with-open [reader (java.io.PushbackReader. (io/reader "src/cljs/eurozone/browser/card_art.cljs"))]
    (loop []
      (let [form (read reader false nil)]
        (cond (nil? form) (throw (ex-info "Original card artwork map is missing" {}))
              (and (seq? form) (= '(def original-sprites) (take 2 form))) (nth form 2)
              :else (recur))))))

(deftest completed-original-decks-have-native-rules-and-distinct-english-faces
  (let [art (art-map)]
    (doseq [[deck expected] [["E" 126] ["I" 91]]]
      (let [catalogue (into {} (filter (fn [[_ card]] (and (= "Original" (:edition card)) (= deck (:deck card))
                                                          (#{:minor :occupation} (:kind card)))) c/metadata))
            faces (map art (keys catalogue))]
        (is (= expected (count catalogue)))
        (is (= expected (count (distinct faces))))
        (doseq [[id _] catalogue]
          (is (contains? c/original-definitions id) (str id " has native rules"))
          (is (contains? c/cards id) (str id " is available to deal"))
          (let [card (c/card id)]
            (is (some? (if (contains? card :requires) (:requires card) (expr/requirement (:prerequisites card))))
                (str id " has executable prerequisites")))
          (is (some? (art id)) (str id " has English art"))
          (when-let [[sheet index] (art id)]
            (is (.isFile (io/file "resources/public/art/agricola" sheet)))
            (is (<= 0 index 69))))))))
