(ns core
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.java.shell :only [sh]]))

(defn offers-get []
  (-> (http/get "https://farcaster.dev/api/offers")
      :body
      json/read-json
      (#(map :raw_offer %)))
  )

(def offers (atom (offers-get)))

(def offers-cached (atom @offers))

(into #{} @offers)
(count (clojure.set/difference (into #{} @offers) (into #{} offers-cached)))
(count (clojure.set/difference (into #{} offers-cached) (into #{} @offers)))


(def master-data-dir "./data_dirs/")

(def farcaster-stash-btc "***REMOVED***")
(def farcaster-stash-xmr "***REMOVED***")

(defn help []
  (println (:out (apply clojure.java.shell/sh ["swap-cli" "help"]))))

(help)

(defn offer-take [swap-index]
  (println (:out (apply clojure.java.shell/sh ["swap-cli"
                                          "-d" (str master-data-dir ".data_dir_" swap-index)
                                          "take" "-w"
                                          "--btc-addr" farcaster-stash-btc
                                          "--xmr-addr" farcaster-stash-xmr
                                          "--offer" (nth @offers swap-index)]))))

(count @offers)
(do
  (reset! offers (offers-get))
  (map offer-take (range 10)))
