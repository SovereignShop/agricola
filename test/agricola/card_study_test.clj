(ns agricola.card-study-test
  (:require [clojure.test :refer :all] [clojure.set :as set]
            [agricola.card-study :as study] [agricola.card-rules :as cards]))
(deftest controlled-deals-keep-opponents-and-background-fixed
  (doseq [pair [["revised-base/a002" "revised-base/a005"]
               ["revised-base/a002" "revised-base/a086"]]]
    (let [{:keys [focal states controls]} (study/factorial 123 2 pair)
          initial (get states "ab")]
      (doseq [[_ state] states]
        (is (= (dissoc initial :players) (dissoc state :players)))
        (is (= (get-in initial [:players (- 1 focal)]) (get-in state [:players (- 1 focal)])))
        (doseq [p (:players state)]
          (is (= 7 (count (get-in p [:hand :minor]))))
          (is (= 7 (count (get-in p [:hand :occupation])))))
        (let [all (mapcat study/hand (:players state))]
          (is (= 28 (count (set all))))))
      (is (= (set (map keyword pair)) (set/difference (set (study/hand (get-in states ["AB" :players focal])))
                                                     (set (study/hand (get-in states ["ab" :players focal]))))))
      (is (= (set controls) (set/difference (set (study/hand (get-in states ["ab" :players focal])))
                                           (set (study/hand (get-in states ["AB" :players focal])))))))))
