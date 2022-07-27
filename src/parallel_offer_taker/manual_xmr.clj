(ns parallel-offer-taker.manual-xmr
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string])
  (:gen-class))

(def destination-file (atom "monerokon-xmr"))

(defn destination-array
  ([] (destination-array destination-file))
  ([destination-file]
   (map
    (fn [[amount address]] {:amount (bigint (+ (* (Float/parseFloat amount) 1E12) 1E10)) :address address})
    (map #(clojure.string/split % #" ")
         ;; (clojure.string/split-lines (slurp "manual_funding/needs-funding-monero-2"))
         ;; (clojure.string/split-lines (slurp "manual_funding/needs-funding-monero"))
         ;; (clojure.string/split-lines (slurp "manual_funding/monerokon-xmr"))
         (clojure.string/split-lines (slurp (str "manual_funding" "/" @destination-file)))
         ))))

(def get-balance
  (json/write-str
   {"jsonrpc" "2.0",
    "id" "0",
    "method" "get_balance",
    "params" {"account_index" 0,"address_indices" [0,1]}})
  )

(def monero-wallet-rpc-url "http://127.0.0.1:38085/json_rpc")

(comment
  (/ (get-in (json/read-str (:body (http/post monero-wallet-rpc-url {:body get-balance}))) ["result" "balance"]) 1E12)
  (/ (get-in (json/read-str (:body (http/post monero-wallet-rpc-url {:body get-balance}))) ["result" "unlocked_balance"]) 1E12))

(comment
  (get-in (json/read-str (:body (http/post monero-wallet-rpc-url {:body get-balance}))) ["result" "per_subaddress" 0 "address"]))

(def output-list (atom (destination-array)))

(filter #(= (:address %) "***REMOVED***") (destination-array))

(comment
  ;; curl http://localhost:18082/json_rpc -d '{"jsonrpc":"2.0","id":"0","method":"sweep_single","params":{"address":"74Jsocx8xbpTBEjm3ncKE5LBQbiJouyCDaGhgSiebpvNDXZnTAbW2CmUR5SsBeae2pNk9WMVuz6jegkC4krUyqRjA6VjoLD","ring_size":7,"unlock_time":0,"key_image":"a7834459ef795d2efb6f665d2fd758c8d9288989d8d4c712a68f8023f7804a5e","get_tx_keys":true}}' -H 'Content-Type: application/json'
  )

(comment
  ;; '{"jsonrpc":"2.0","id":"0","method":"incoming_transfers","params":{"transfer_type":"available","account_index":0,"subaddr_indices":[0]}}' -H 'Content-Type: application/json'
  )

(def incoming-transfers
  {"jsonrpc" "2.0",
   "id" "0",
   "method" "incoming_transfers",
   "params" {"transfer_type" "available",
             "account_index" 0,
             "subaddr_indices" [0],
             }})

(defn monero-rpc [body]
  (get-in (json/read-str (:body (http/post monero-wallet-rpc-url {:body (json/write-str body)}))) ["result"]))

(comment (int (/ (get (first (get (monero-rpc incoming-transfers) "transfers")) "amount") 5E11)))

(comment (get (first (get (monero-rpc incoming-transfers) "transfers")) "key_image"))

(comment (count (filter #(and (get % "unlocked") (> (get % "amount") 1E12)) (get (monero-rpc incoming-transfers) "transfers"))))
(comment (count (get (monero-rpc incoming-transfers) "transfers")))

(comment (def cached-transfer-in (first (get (monero-rpc incoming-transfers) "transfers"))))
;; (/ (get-in (monero-rpc incoming-transfers) ["transfers" 0 "amount"]) 1E12)

(defn splittable-key-images [incoming-transfer]
  (let [
        amount (get incoming-transfer "amount")
        target-size 5E11
        target-count (int (/ amount target-size))
        ]
   (if (< 1 target-count)
     [(get incoming-transfer "key_image") target-count]
     []
     )
   ))

(comment (map splittable-key-images (get (monero-rpc incoming-transfers) "transfers")))

(defn sweep [key-image outputs target-address]
  {"jsonrpc" "2.0",
   "id" "0",
   "method" "sweep_single",
   "params" {"address" target-address,
             "mixin" 0
             "ring_size" 11,
             "unlock_time" 0,
             "key_image" key-image,
             "get_tx_keys" true}})


(defn transfer [transfers]
  {"jsonrpc" "2.0",
  "id" "0",
  "method" "transfer",
   "params" {"destinations" transfers,
            "account_index" 0,
            "subaddr_indices" [0],
            "priority" 0,
            "ring_size" 11,
            "get_tx_key" true}})

(http/post "http://127.0.0.1:38085/json_rpc" {:body "{\"jsonrpc\":\"2.0\",\"id\":\"0\",\"method\":\"transfer\",\"params\":{\"destinations\":[{\"amount\":100000000000,\"address\":\"***REMOVED***\"},{\"amount\":200000000000,\"address\":\"***REMOVED***\"}],\"account_index\":0,\"subaddr_indices\":[0],\"priority\":0,\"ring_size\":7,\"get_tx_key\": true}}"})

(reset! output-list (destination-array))

(def response (atom nil))
(comment (do
           (reset! response (http/post "http://127.0.0.1:38085/json_rpc" {:body (json/write-str (transfer (take 15 @output-list)))}))
           (swap! output-list #(drop 15 %))
           [(count @output-list) (json/read-str (:body @response))]
           ))

(comment (:tx_hash (:result (json/read-str (:body (identity @response)) :key-fn keyword))))
(count @output-list)

(comment (monero-rpc (transfer [{:amount (biginteger (* 1E12 0.434427100000)) :address "***REMOVED***"}])))
(comment (json/read-str (:body @response)))

(comment (biginteger (* 1E12 0.434427100000)))

(comment (swap! output-list #(drop 15 %)))

(count @output-list)

(comment (json/write-str {:a 2}))

;; curl http://127.0.0.1:18082/json_rpc -d '{"jsonrpc":"2.0","id":"0","method":"transfer","params":{"destinations":[{"amount":100000000000,"address":"***REMOVED***"},{"amount":200000000000,"address":"***REMOVED***"}],"account_index":0,"subaddr_indices":[0],"priority":0,"ring_size":7,"get_tx_key": true}}' -H 'Content-Type: application/json'
