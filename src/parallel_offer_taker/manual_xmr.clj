(ns parallel-offer-taker.manual-xmr
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.java.shell :only [sh]])
  (:gen-class))

(defn destination-array []
  (map
   (fn [[amount address]] {:amount (bigint (+ (* (Float/parseFloat amount) 1E12) 1E10)) :address address})
   (map #(clojure.string/split % #" ")
        ;; (clojure.string/split-lines (slurp "manual_funding/needs-funding-monero-2"))
        (clojure.string/split-lines (slurp "manual_funding/needs-funding-monero"))
        )))

(clojure.java.shell/sh "pwd")

(last (destination-array))

(def get-balance
  (json/write-str
   {"jsonrpc" "2.0",
    "id" "0",
    "method" "get_balance",
    "params" {"account_index" 0,"address_indices" [0,1]}})
  )

(def monero-wallet-rpc-url "http://127.0.0.1:38085/json_rpc")

(/ (get-in (json/read-str (:body (http/post monero-wallet-rpc-url {:body get-balance}))) ["result" "balance"]) 1E12)
(/ (get-in (json/read-str (:body (http/post monero-wallet-rpc-url {:body get-balance}))) ["result" "unlocked_balance"]) 1E12)

(get-in (json/read-str (:body (http/post monero-wallet-rpc-url {:body get-balance}))) ["result" "per_subaddress" 0 "address"])

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

(int (/ (get (first (get (monero-rpc incoming-transfers) "transfers")) "amount") 5E11))

(get (first (get (monero-rpc incoming-transfers) "transfers")) "key_image")

(count (filter #(and (get % "unlocked") (> (get % "amount") 1E12)) (get (monero-rpc incoming-transfers) "transfers")))
(count (get (monero-rpc incoming-transfers) "transfers"))

(def cached-transfer-in (first (get (monero-rpc incoming-transfers) "transfers")))
;; (/ (get-in (monero-rpc incoming-transfers) ["transfers" 0 "amount"]) 1E12)

(defn splittable-key-images [incoming-transfer]
  (let [
        amount (get incoming-transfer "amount")
        target-size 5E11
        target-count (int (/ amount target-size))
        ]
   (if (< 1 target-count)
     [(get incoming-transfer "key_image") target-count]
     )
   ))

(map splittable-key-images (get (monero-rpc incoming-transfers) "transfers"))

(comment
  (["5c4c90d64eb913b04e95b7d488b7712a970511508b2aaeb5a5405ea2a0726b97" 18] ["f2e32d1fdda90e5065457fbcb572cca00fb9268852d98718e6b581c34d8ecc2b" 16] ["a7de5619cf0dbf43b8eeb51dfe9bd30b5c8510f00c130dc1a5788934ad0add4b" 19] nil ["9a89d89e838ecfa90b247727d6dec9fa728fca18a6e67757970f09e83a517464" 19] ["35174d95831440b8ce6a649c21353d9e19f173d986c98ebcbb4e4754d97b3ebb" 19] ["b15a94feba1acb6af099919526e40879a45e1b3c94510df28e43db8c95404c21" 19] nil nil nil nil nil nil ["8f61a9deb8bc925a775163305e06667abb517cc47e8ac4c16fb1c324514299fc" 19] ["eace627b73d365c6d37a7bee61b70cb3e3ce3f31a9b7c69549313749f495a510" 19] nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil ["9cb8bfbb76dd101ccc891682feee984fd46f60eaaa45f629c2d8e2ebc0156887" 20] nil nil nil nil nil nil nil)
  (["299a7fd9fe589469217c40d5cfaad5dd22195601c586208693552cf80cdbe393" 10] ["bcb7b064acc49bec389043fb54e4df18b5a1a16551685e01bd0b320e100ba3a4" 8] ["67d72931e1454bca72ddd49d03d75bc601d0cc2e8f9e6138497c4926c8b62811" 9] ["67eddadec477890eec9f4e3e97180941f088398d1b091760100b878d6dd8d21c" 9] ["4e28ca5745d33675b74e8b2a27ee5c03d26f4ad6abdbf6aabd3cfb17c2f82baf" 20]))

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

(json/write-str (request))
(http/post "http://127.0.0.1:38085/json_rpc" {:body "{\"jsonrpc\":\"2.0\",\"id\":\"0\",\"method\":\"transfer\",\"params\":{\"destinations\":[{\"amount\":100000000000,\"address\":\"***REMOVED***\"},{\"amount\":200000000000,\"address\":\"***REMOVED***\"}],\"account_index\":0,\"subaddr_indices\":[0],\"priority\":0,\"ring_size\":7,\"get_tx_key\": true}}"})

(reset! output-list (destination-array))

(def response (atom nil))
(do
  (reset! response (http/post "http://127.0.0.1:38085/json_rpc" {:body (json/write-str (transfer (take 15 @output-list)))}))
  (swap! output-list #(drop 15 %))
  [(count @output-list) (json/read-str (:body @response))]
  )

(:tx_hash (:result (json/read-str (:body (identity @response)) :key-fn keyword)))
(count @output-list)

(monero-rpc (transfer [{:amount (biginteger (* 1E12 0.434427100000)) :address "***REMOVED***"}]))
(json/read-str (:body @response))

(biginteger (* 1E12 0.434427100000))

(swap! output-list #(drop 15 %))

(count @output-list)

(json/write-str {:a 2})

(comment
  curl http://127.0.0.1:18082/json_rpc -d '{"jsonrpc":"2.0","id":"0","method":"transfer","params":{"destinations":[{"amount":100000000000,"address":"***REMOVED***"},{"amount":200000000000,"address":"***REMOVED***"}],"account_index":0,"subaddr_indices":[0],"priority":0,"ring_size":7,"get_tx_key": true}}' -H 'Content-Type: application/json'
  )
