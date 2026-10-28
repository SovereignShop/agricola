(ns eurozone.chat-test
  (:require [clojure.test :refer [deftest is]] [eurozone.chat :as chat]
            [eurozone.store :as store] [eurozone.server :as server]))

(deftest seat-authorized-table-scoped-signaling
  (let [s (store/make-store nil)
        app {:store s :peers (atom {})}
        messages (atom []) send! #(swap! messages conj [%1 %2])
        room-a (:id (store/transact! s "alice" {:event :create :mode :online :name "Alice"}))
        room-b (:id (store/transact! s "bob" {:event :create :mode :online :name "Bob"}))
        _ (store/transact! s "carol" {:event :join :id room-a :revision 0 :name "Carol"})
        saved @(:rooms s)]
    (chat/receive! app send! :outsider "unknown" {:event :chat/join :room room-a})
    (is (= :chat/error (:event (second (last @messages)))))
    (is (empty? @(:peers app)))
    (doseq [[channel token room] [[:a "alice" room-a] [:c "carol" room-a] [:b "bob" room-b]]]
      (chat/receive! app send! channel token {:event :chat/join :room room}))
    (let [a (get @(:peers app) :a) c (get @(:peers app) :c) b (get @(:peers app) :b)]
      (is (= #{:id :name :seat} (set (keys (first (:peers (second (last @messages))))))))
      (reset! messages [])
      (chat/receive! app send! :a "alice" {:event :chat/signal :room room-a :to (:id c) :kind :offer :payload {:type "offer" :sdp "test"}})
      (is (= [[:c {:event :chat/signal :room room-a :from (:id a) :kind :offer :payload {:type "offer" :sdp "test"}}]] @messages))
      (reset! messages [])
      (chat/receive! app send! :a "alice" {:event :chat/signal :room room-a :to (:id b) :kind :offer :payload {}})
      (is (= [:a] (mapv first @messages)))
      (is (= :chat/error (:event (second (first @messages)))))
      (reset! messages [])
      (chat/leave! app send! :c)
      (is (= [:a] (mapv first @messages)))
      (is (= [(:id a)] (mapv :id (:peers (second (first @messages))))))
      (is (nil? (get @(:peers app) :c)))
      (is (= saved @(:rooms s)) "Signaling never changes or persists the game"))))

(deftest original-artwork-is-served-as-images
  (doseq [[path content-type] [["/art/agricola/farm.jpeg" "image/jpeg"]
                             ["/art/agricola/board.png" "image/png"]
                             ["/art/agricola/major-1.png" "image/png"]]]
    (let [response (server/handler {} {:uri path :request-method :get})]
      (is (= 200 (:status response)))
      (is (= content-type (get-in response [:headers "Content-Type"])))
      (.close (:body response)))))
