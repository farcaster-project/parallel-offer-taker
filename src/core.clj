(ns core
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]))

(def cached-call (http/get "https://farcaster.dev/api/offers"))

(keys cached-call)

(json/read-json (:body cached-call))
