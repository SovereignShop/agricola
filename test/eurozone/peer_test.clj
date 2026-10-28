(ns eurozone.peer-test
  (:require [clojure.test :refer :all] [clojure.data.json :as json] [eurozone.peer :as p])
  (:import [java.nio.file Files]))
(def room (str (random-uuid)))
(def invite (apply str (repeat 64 "a")))
(def host (apply str (repeat 64 "b")))
(def guest (apply str (repeat 64 "c")))
(def settings {:name "Ada" :expected-players 2 :setup {:mode "draw" :extra 2}})
(defn harness [path]
  (let [service (p/service path) messages (atom [])
        send (fn [channel raw] (swap! messages conj [channel (json/read-str raw :key-fn keyword)]))]
    {:service service :messages messages :send send
     :route #(p/route! service send %1 (json/write-str %2))}))
(deftest routing-is-isolated-and-host-authenticated
  (let [{:keys [service messages route]} (harness nil)]
    (route :host {:op "create" :room room :invite invite :token host :options settings})
    (route :guest {:op "join" :room room :invite invite :token guest})
    (let [hid (p/peer-id room host) gid (p/peer-id room guest)]
      (reset! messages [])
      (route :guest {:op "relay" :to hid :from "forged" :payload "private game command"})
      (is (= [[:host {:op "relay" :from gid :kind nil :payload "private game command"}]] @messages))
      (is (not (.contains (pr-str @(:tables service)) "private game command")))
      (is (not (.contains (pr-str @(:tables service)) host)))
      (is (not (.contains (pr-str @(:tables service)) invite)))
      (route :guest {:op "initialized"})
      (is (false? (get-in @(:tables service) [room :initialized])))
      (route :host {:op "initialized"})
      (is (true? (get-in @(:tables service) [room :initialized])))
      (route :intruder {:op "join" :room room :token (p/secret) :invite (p/secret)})
      (is (= "error" (:op (second (last @messages)))))
      (route :duplicate {:op "join" :room room :token host :invite invite})
      (is (= "error" (:op (second (last @messages)))))
      (route :guest {:op "relay" :to "some-other-table" :payload "x"})
      (is (= "error" (:op (second (last @messages)))))
      (is (= #{:host :guest} (set (keys @(:connections service))))))))
(deftest invitations-survive-coordinator-restart-without-game-state
  (let [path (.toString (Files/createTempFile "eurozone-peer-test" ".edn" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (spit path "{}")
      (let [{:keys [service route]} (harness path)]
        (route :host {:op "create" :room room :invite invite :token host :options settings})
        (route :host {:op "initialized"})
        (let [restored (p/service path) table (get @(:tables restored) room)]
          (is (= @(:tables service) @(:tables restored)))
          (is (= (:host table) (p/peer-id room host)))
          (is (not-any? #(contains? table %) [:state :hand :recording :history]))))
      (finally (.delete (java.io.File. path))))))
(deftest private-rendezvous-fails-closed
  (let [{:keys [service send messages]} (harness nil)
        service (assoc service :authorize (fn [_ proof] (p/require! (= proof "approved") "Private app")))]
    (p/route! service send :host (json/write-str {:op "create" :room room :invite invite :token host :options settings}))
    (is (empty? @(:tables service)))
    (p/route! service send :host (json/write-str {:op "create" :room room :invite invite :token host :auth "approved" :options settings}))
    (is (= 1 (count @(:tables service))))))
