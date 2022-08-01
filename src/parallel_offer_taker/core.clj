(ns parallel-offer-taker.core
  (:require
   [clj-http.lite.client :as http]
   clojure.core
   [clojure.data.json :as json]
   clojure.edn
   [clojure.java.shell]
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
(comment
  (count (clojure.set/difference (into #{} @offers) (into #{} @offers-cached)))
  (count (clojure.set/difference (into #{} @offers-cached) (into #{} @offers))))

(defn read-config [path]
  (clojure.edn/read-string (slurp path)))

(defn help []
  (println (:out (apply clojure.java.shell/sh ["swap-cli" "help"]))))

(comment (help))

(defn offer-take [swap-index {:keys [address-btc address-xmr data-dir-root]}]
  (let [result (apply clojure.java.shell/sh ["~/.cargo/bin/swap-cli"
                                            "-d" (str data-dir-root ".data_dir_" swap-index)
                                            "take" "-w"
                                            "--btc-addr" address-btc
                                            "--xmr-addr" address-xmr
                                            "--offer" (nth @offers (mod swap-index (count @offers)))])]
    (println [(:out result) (:err result)])
    ))

(def simple (atom 0))
(comment (reset! simple 200))

(comment (let [count 20
               config (read-config "config.edn")]
           (reset! offers (doall (offers-get)))
           (doall (map #(offer-take % config) (range @simple (+ @simple count))))
           (reset! simple (+ count @simple))
           ;; (reset! offers-bag @offers)
           (println @simple)
           ))

(comment
  (update-offers)
  (pmap offer-take (range 20)))

(defn -main [& args]
  (if (= (count args) 2)
    (let [[min-swap-index max-swap-index] (map #(Integer/parseInt %) args)
          config (read-config "config.edn")]
      (println "swap index range: " min-swap-index max-swap-index)
      (println "config: " config)
      (reset! offers (offers-get))
      ;; (println "offers: " @offers)
      (map #(offer-take % config) (range min-swap-index (max (inc max-swap-index) (+ min-swap-index (count @offers)))))
      )
    (println "required args: min-swap-index max-swap-index"))
  )

(comment (-main "0" "20"))

(comment
  (reset! offers (offers-get))
  (count @offers)
  (def offers-cached @offers))
