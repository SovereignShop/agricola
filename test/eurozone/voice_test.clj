(ns eurozone.voice-test
  (:require [clojure.test :refer :all] [clojure.data.json :as json]
            [eurozone.voice :as voice] [eurozone.voice-relay :as relay]
            [eurozone.assistant-relay :as assistant]
            [eurozone.transcription :as transcription] [agricola.engine :as a]))
(defn game [] (a/new-game ["Voice tester" "Second player"] (vec (mapcat identity a/stages)) 0))
(deftest exact-commands-and-native-resolution
  (let [g (game) picked (voice/interpret g "Take wood!") next (a/command g 0 {:type :action :space (:space picked)})]
    (is (= {:kind :action :space :wood :name "Forest"} picked))
    (is (= :wood (:space (voice/interpret g "place my worker on the forest"))))
    (is (= 3 (get-in next [:players 0 :goods :wood])))
    (is (= :error (:kind (voice/interpret next "take wood"))))
    (is (= :error (:kind (voice/interpret g "don't take wood"))))
    (is (= :error (:kind (voice/interpret g "take wood and fishing"))))
    (is (= :confirm (:kind (voice/interpret g "Confirm."))))
    (is (= :cancel (:kind (voice/interpret g "cancel"))))
    (is (= :error (:kind (voice/interpret g "build rooms"))))))
(deftest ambiguity-and-control-gates
  (let [g (update (game) :active into [:stone-west :stone-east]) room {:state g}]
    (is (= :error (:kind (voice/interpret g "take stone"))))
    (is (= :stone-west (:space (voice/interpret g "western quarry"))))
    (is (voice/eligible? room [0] true false))
    (doseq [[r seats connected busy] [[room [] true false] [room [1] true false] [room [0] false false]
                                    [room [0] true true] [(assoc room :takeback {:id "vote"}) [0] true false]
                                    [(assoc-in room [:state :effect-queue] [{:player 0}]) [0] true false]]]
      (is (not (voice/eligible? r seats connected busy))))))
(deftest pairing-is-scoped-one-use-and-ephemeral
  (let [sent (atom []) send #(swap! sent conj [%1 (json/read-str %2 :key-fn keyword)])
        service (relay/service (fn [_ proof] (when-not (= proof "valid") (throw (ex-info "Sign in" {})))))
        pair (apply str (repeat 64 "a")) token (apply str (repeat 64 "b"))
        transmit #(relay/receive! service send %1 (json/write-str %2))
        listen {:op "listen" :pair pair :token token :auth "valid"}]
    (transmit :intruder (assoc listen :auth "invalid"))
    (is (empty? @(:clients service)))
    (transmit :game listen)
    (transmit :mic {:op "pair" :pair pair})
    (transmit :other {:op "pair" :pair pair})
    (is (= "error" (get-in (last @sent) [1 :op])))
    (reset! sent [])
    (transmit :mic {:op "heard" :id "one" :text "take wood" :version "rev1"})
    (transmit :mic {:op "heard" :id "one" :text "take wood" :version "rev1"})
    (is (= [[:game {:op "heard" :text "take wood" :version "rev1"}]] @sent))
    (transmit :game {:op "status" :version "rev1" :message "Confirm Forest?"})
    (is (= :mic (first (last @sent))))
    (transmit :other {:op "heard" :id "two" :text "confirm" :version "rev1"})
    (is (= :other (first (last @sent))))
    (is (= "error" (get-in (last @sent) [1 :op])))
    (relay/leave! service send :game)
    (is (empty? @(:clients service)))
    (transmit :mic {:op "pair" :pair pair})
    (is (= "error" (get-in (last @sent) [1 :op])))))

(deftest transcription-authorization-and-bounds
  (let [pair (apply str (repeat 64 "c"))
        request {:headers {"authorization" (str "Bearer " pair)}}
        service {:clients (atom {})}
        app {:discord-config {} :voice-service service}
        paired {:headers {"x-eurozone-voice-pair" pair}}
        expires (+ (System/currentTimeMillis) 60000)]
    (is (= 401 (:status (transcription/transcribe app {:headers {}}))))
    (is (= 503 (:status (transcription/transcribe app request))))
    (is (= 403 (:status (transcription/transcribe (assoc app :discord-config {:private? true}) request))))
    (is (thrown? clojure.lang.ExceptionInfo (transcription/authorize! app paired)))
    (swap! (:clients service) assoc :game {:pair pair :role :game :expires expires})
    (is (thrown? clojure.lang.ExceptionInfo (transcription/authorize! app paired)))
    (swap! (:clients service) assoc :mic {:pair pair :role :mic :expires expires})
    (is (nil? (transcription/authorize! app paired)))
    (swap! (:clients service) assoc-in [:mic :expires] 0)
    (is (thrown? clojure.lang.ExceptionInfo (transcription/authorize! app paired)))
    ;; Size validation runs before forwarding; failures release the concurrency slot.
    (doseq [size [0 2000001]]
      (is (= 413 (:status (transcription/transcribe
                           (assoc app :transcription-url "http://127.0.0.1:1/transcribe")
                           (assoc request :body (java.io.ByteArrayInputStream. (byte-array size))))))))
    (is (= 1 (.availablePermits transcription/gate)))))

(deftest assistant-context-is-public-and-actions-are-scoped
  (let [g (-> (game) (assoc :secret "must not send")
              (assoc-in [:players 0 :hand] ["hidden-card"]) (assoc-in [:players 1 :token] "secret-token"))
        context (voice/assistant-context {:state g} [0] [] nil)
        encoded (json/write-str context)]
    (is (not-any? #(clojure.string/includes? encoded %) ["hidden-card" "secret-token" "round-order" "must not send"]))
    (is (= :place (:mode (first (filter #(= :wood (:space %)) (:actions context))))))
    (is (= :open (:mode (first (filter #(= :plow (:space %)) (:actions context))))))
    (is (not-any? #(= :wood (:space %)) (voice/assistant-actions (a/command g 0 {:type :action :space :wood}))))
    (is (empty? (voice/assistant-actions (assoc g :effect-queue [{:player 0}]))))))

(deftest assistant-pairing-isolates-accounts-and-discards-cancelled-results
  (let [sent (atom []) send #(swap! sent conj [%1 (json/read-str %2 :key-fn keyword)])
        service (assistant/service (fn [_ proof] (when-not (= proof "valid") (throw (ex-info "Denied" {})))))
        pair (apply str (repeat 64 "d")) other-pair (apply str (repeat 64 "e"))
        transmit #(assistant/receive! service send %1 (json/write-str %2))
        listen {:op "listen" :pair pair :token pair :auth "valid"}
        request {:op "interpret" :id "request-1" :version "rev-1" :text "get building materials" :context {:actions []}}]
    (transmit :intruder (assoc listen :auth "wrong"))
    (is (empty? @(:clients service)))
    (transmit :game listen)
    (transmit :other-game (assoc listen :pair other-pair :token other-pair))
    (transmit :companion {:op "pair" :pair pair :model "Test model"})
    (transmit :other-companion {:op "pair" :pair other-pair :model "Other model"})
    (transmit :replay {:op "pair" :pair pair :model "Test model"})
    (is (= "error" (get-in (last @sent) [1 :op])))
    (reset! sent [])
    (transmit :game request)
    (is (= [[:companion request]] @sent))
    (transmit :game request)
    (is (= "error" (get-in (last @sent) [1 :op])))
    (reset! sent [])
    (transmit :other-companion {:op "result" :id "request-1" :version "rev-1" :result {:kind "answer" :message "crossed"}})
    (is (empty? @sent))
    (transmit :game {:op "cancel"})
    (reset! sent [])
    (transmit :companion {:op "result" :id "request-1" :version "rev-1" :result {:kind "action" :space "wood"}})
    (is (empty? @sent))
    (transmit :game (assoc request :id "request-2"))
    (transmit :companion {:op "result" :id "request-2" :version "rev-1" :result {:kind "answer" :message "Done"}})
    (is (= :game (first (last @sent))))
    (assistant/leave! service send :companion)
    (is (= #{:other-game :other-companion} (set (keys @(:clients service)))))))
