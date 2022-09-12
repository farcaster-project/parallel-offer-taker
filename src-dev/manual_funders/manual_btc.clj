(ns manual-funders.manual-btc
  (:require
   [clojure.data.json :as json]
   [clojure.java.shell :only [sh]]
   clojure.string)
  (:gen-class)
  )

(def destination-file (atom "default-btc"))

(defn destination-array
  ([] (destination-array @destination-file))
  ([file]
   (map
    (fn [[amount address]] {:amount amount :address address})
    (map #(clojure.string/split % #" ")
         (clojure.string/split-lines (slurp (str "manual_funding" "/" file)))))))

(defn electrum [& args]
  (apply clojure.java.shell/sh (concat ["***REMOVED***" "--testnet"] args)))

(comment (map #(electrum "broadcast" (:out (electrum "payto" (:address %) (:amount %))))
              (take 10 (destination-array))))

(defn paytomany-output [destination-array] (electrum "paytomany" (json/write-str (map (fn [tuple] [(:address tuple) (:amount tuple)]) destination-array)) "--feerate" "2"))

(comment (electrum "broadcast" (:out (paytomany-output (destination-array)))))

(comment (electrum "broadcast" "***REMOVED***"))
