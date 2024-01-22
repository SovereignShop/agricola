(ns agricola.torrent
   (:import [bt.torrent.maker TorrentBuilder]
            [bt.runtime BtRuntime BtClient Config]
            [bt.magnet MagnetUri$Builder]
            [bt Bt]
            [java.nio.file Paths]
            [java.io File]
            [java.net URI]))

(defn create-torrent [file-path announce-url torrent-file-path]
  (let [source-file (Paths/get file-path (into-array String []))
        builder (TorrentBuilder.)]
    (.addFile builder source-file)
    (.announce builder announce-url)
    (let [torrent (.build builder)]
      (File. torrent-file-path)
      (with-open [out (java.io.FileOutputStream. (File. torrent-file-path))]
        (.write out torrent)))))

(defn create-and-start-seeding-client [file-to-seed-path announce-url torrent-file-path]
  (let [source-path (Paths/get file-to-seed-path (into-array String []))
        torrent (-> (TorrentBuilder.)
                    (.addFile source-path)
                    (.announce (URI. announce-url))
                    (.build))
        config (Config.)
        runtime (BtRuntime/builder (.build config))
        client-builder (Bt/client runtime)]
    (spit torrent-file-path torrent)  ; Save the torrent file
    (.storage client-builder source-path)
    (.seed client-builder)  ; Set the client to seed mode
    (let [client (.build client-builder)]
      (.startAsync client true)
      client)))

(def builder (create-torrent "deps.edn" "http://tracker.openbittorrent.com:80/announce", "out.torrent"))
