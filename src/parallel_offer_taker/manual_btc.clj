(ns parallel-offer-taker.manual-btc
  (:require
   [clojure.data.json :as json]
   [clojure.java.shell :only [sh]]
   clojure.string)
  )

(def destination-file (atom "default-btc"))

(println (clojure.java.shell/sh "pwd"))

(defn destination-array
  ([] (destination-array @destination-file))
  ([file]
   (map
    (fn [[amount address]] {:amount amount :address address})
    (map #(clojure.string/split % #" ")
         (clojure.string/split-lines (slurp (str "manual_funding" "/" file)))))))

;; (def outputs (map #(clojure.java.shell/sh "***REMOVED***" "--testnet" "payto" (:address %) (:amount %)) (rest (destination-array))))

;; (comment
;;   (first outputs))

(defn electrum [& args]
  (apply clojure.java.shell/sh (concat ["***REMOVED***" "--testnet"] args)))

(def final-outputs (map #(electrum "broadcast" (:out (electrum "payto" (:address %) (:amount %))))
                         (rest (rest (destination-array)))))

(def final-outputs (do (map #(electrum "broadcast" (:out (electrum "payto" (:address %) (:amount %))))
                            (take 10 (destination-array)))))

(comment (map #(electrum "broadcast" (:out (electrum "payto" (:address %) (:amount %))))
              (take 10 (destination-array))))

(def paytomany-output (electrum "paytomany" (json/write-str (map (fn [tuple] [(:address tuple) (:amount tuple)]) (destination-array))) "--feerate" "2"))

(comment (electrum "broadcast" (:out paytomany-output)))

(comment (println (map #(clojure.java.shell/sh "***REMOVED***" "--testnet" "broadcast" %) (map :out outputs))))

(comment (electrum "broadcast" "***REMOVED***"))
