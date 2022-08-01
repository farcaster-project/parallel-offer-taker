(ns parallel-offer-taker.core
  (:require
   [clj-http.lite.client :as http]
   [clojure.data.json :as json]
   [clojure.java.shell]
   clojure.core
   clojure.set)
  (:gen-class)
  )

(defn offers-get []
  (-> (http/get "https://farcaster.dev/api/offers")
      :body
      json/read-json
      (#(map :raw_offer %)))
  )

(def offers (atom nil))

(defn update-offers []
  (reset! offers (offers-get)))

(def offers-cached (atom @offers))
(count (clojure.set/difference (into #{} @offers) (into #{} @offers-cached)))
(count (clojure.set/difference (into #{} @offers-cached) (into #{} @offers)))

(def master-data-dir "***REMOVED***")

;; (def farcaster-stash-btc "***REMOVED***")
(def farcaster-stash-btc "***REMOVED***")
;; (def farcaster-stash-xmr "***REMOVED***")
(def farcaster-stash-xmr "***REMOVED***")

(defn help []
  (println (:out (apply clojure.java.shell/sh ["swap-cli" "help"]))))

(comment (help))

(defn offer-take [swap-index]
  (let [result (apply clojure.java.shell/sh ["~/.cargo/bin/swap-cli"
                                            "-d" (str master-data-dir ".data_dir_" swap-index)
                                            "take" "-w"
                                            "--btc-addr" farcaster-stash-btc
                                            "--xmr-addr" farcaster-stash-xmr
                                            "--offer" (nth @offers (mod swap-index (count @offers)))])]
    (println [(:out result) (:err result)])
    ))

(def simple (atom 0))
(comment (reset! simple 200))

(comment (let [count 20]
           (reset! offers (doall (offers-get)))
           (doall (map offer-take (range @simple (+ @simple count))))
           (reset! simple (+ count @simple))
           ;; (reset! offers-bag @offers)
           (println @simple)
           ))

(comment
  (update-offers)
  (pmap offer-take (range 20)))

(defn -main [& args]
  (if (= (count args) 2)
    (let [[min-swap-index max-swap-index] (map #(Integer/parseInt %) args)]
      (println "swap index range: " min-swap-index max-swap-index)
      (reset! offers (offers-get))
      ;; (println "offers: " @offers)
      (map offer-take (range min-swap-index (max (inc max-swap-index) (+ min-swap-index (count @offers)))))
      )
    (println "required args: min-swap-index max-swap-index"))
  )

(comment (-main "0" "20"))

(comment
  (reset! offers (offers-get))
  (count @offers)
  (def offers-cached @offers))
