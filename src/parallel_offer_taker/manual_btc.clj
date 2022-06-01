(ns parallel-offer-taker.manual-btc
  (:require [clojure.java.shell :only [sh]]
            [clojure.data.json :as json]
            )
  (:gen-class))

(defn destination-array []
  (map
   (fn [[amount address]] {:amount amount :address address})
   (map #(clojure.string/split % #" ")
        ;; (clojure.string/split-lines (slurp "manual_funding/needs-funding-monero-2"))
        ;; (clojure.string/split-lines (slurp "manual_funding/btc.payouts"))
        ;; (clojure.string/split-lines (slurp "manual_funding/needs-funding-bitcoin"))
        (clojure.string/split-lines (slurp "manual_funding/needs-funding-bitcoin"))
        )))

(count (destination-array))

(map (clojure.java.shell/sh "***REMOVED***"))

(def outputs (map #(clojure.java.shell/sh "***REMOVED***" "--testnet" "payto" (:address %) (:amount %)) (rest (destination-array))))
(first outputs)

(def outputs (map #(clojure.java.shell/sh "***REMOVED***" "--testnet" "payto" (:address %) (:amount %)) (rest (destination-array))))

(defn electrum [& args]
  (apply clojure.java.shell/sh (concat ["***REMOVED***" "--testnet"] args)))

(def final-outputs (do (map #(electrum "broadcast" (:out (electrum "payto" (:address %) (:amount %))))
                         (rest (rest (destination-array))))))

(def final-outputs (do (map #(electrum "broadcast" (:out (electrum "payto" (:address %) (:amount %))))
                            (take 10 (destination-array)))))

(map #(electrum "broadcast" (:out (electrum "payto" (:address %) (:amount %))))
     (take 10 (destination-array)))

(def paytomany-output (electrum "paytomany" (json/write-str (map (fn [tuple] [(:address tuple) (:amount tuple)]) (destination-array))) "--feerate" "2"))

(electrum "broadcast" (:out paytomany-output))

(identity final-outputs)

(map :err final-outputs)

(println (map #(clojure.java.shell/sh "***REMOVED***" "--testnet" "broadcast" %) (map :out outputs)))

(electrum "broadcast" "***REMOVED***")
