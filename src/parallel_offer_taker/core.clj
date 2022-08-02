(ns parallel-offer-taker.core
  (:require
   [clj-http.lite.client :as http]
   clojure.core
   [clojure.data.json :as json]
   clojure.edn
   clojure.java.io
   [clojure.java.shell :as shell]
   clojure.set
   clojure.string)
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

(defn add-missing-trailing-slash [path]
  (if (and path (not= \/ (last path)))
    (str path "/")
    path))

(defn data-dir [swap-index {:keys [data-dir-root]}]
  (let [data-dir (str (add-missing-trailing-slash data-dir-root) ".data_dir_" swap-index)]
    (try
      (if (.exists (clojure.java.io/as-file data-dir))
        data-dir
        (throw (Exception. (str data-dir " does not exist"))))
      (catch Exception e
        (println e)))))

(defn binary-dir [config]
  (or (add-missing-trailing-slash (:farcaster-binaries-path config)) "~/.cargo/bin/"))

(defn binary-path [config binary-name]
  (str (binary-dir config) binary-name))

(defn farcaster-config-toml-file [config]
  (or (:farcaster-config-toml-file config) "~/.farcaster/farcaster.toml"))

(comment (farcaster-config-toml-file (read-config "config.edn"))
         (data-dir 0 (read-config "config.edn")))

(defn help []
  (println (:out (apply shell/sh ["swap-cli" "help"]))))

(comment (help))

(defn offer-take [swap-index config]
  (let [result (apply shell/sh [(binary-path config "swap-cli")
                                "-d" (data-dir config swap-index)
                                "take" "-w"
                                "--btc-addr" (:address-btc config)
                                "--xmr-addr" (:address-xmr config)
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

(defn farcasterd-running? [swap-index config]
  (let [data-dir (data-dir swap-index config)]
    (if data-dir (try (-> (apply shell/sh
                                 [
                                  (str (binary-dir config) "swap-cli")
                                  "-d" data-dir
                                  "info"
                                  ])
                          :err
                          (= ""))
                      (catch Exception e
                        (println (:cause (Throwable->map e)))
                        false))
        false)))

(comment (farcasterd-running? 0 (read-config "config.edn")))

(defn farcasterd-launch-vec [swap-index config]
  (let [data-dir (data-dir swap-index config)]
    [
     (str (binary-dir config) "farcasterd")
     "-c" (farcaster-config-toml-file config)
     "-d" data-dir
     ]))

(def process-futures (atom {}))

(defn launch-farcasterd [swap-index config]
  (->> config
       (farcasterd-launch-vec swap-index)
       (apply shell/sh)
       future
       ;; NOTE: this still requires check that a future does not exist under given key yet
       ((fn [future] (swap! process-futures (fn [m] (assoc m swap-index future)))))))

(comment
  (launch-farcasterd 0 (read-config "config.edn"))

  (future-cancel (get @process-futures 0))
  (future-cancelled? (get @process-futures 0))
  (future-done? (get @process-futures 0))

  (farcasterd-launch-vec 0 (read-config "config.edn")))

(defn farcasterd-process-id-exact [swap-index config]
  (->> ["bash" "-c" (str "ps -ef | grep \"" (clojure.string/join " " (farcasterd-launch-vec swap-index config)) "$\" | grep -v grep | awk '{print $2}'"
                         )]
       (apply shell/sh)
       :out
       clojure.string/trim-newline
       Integer/parseInt))

(defn farcasterd-process-id [swap-index]
  (->> ["bash" "-c" (str "ps -ef | grep farcasterd | grep .data_dir_" swap-index "$ | grep -v grep | awk '{print $2}'"
                         )]
       (apply shell/sh)
       :out
       clojure.string/trim-newline
       Integer/parseInt))

(comment
  (farcasterd-process-id-exact 0 (read-config "config.edn"))
  (farcasterd-process-id 0))

(defn kill-farcasterd [swap-index config]
  (let [process-id (or (farcasterd-process-id-exact swap-index config)
                       (farcasterd-process-id swap-index))]
    (if process-id
      (shell/sh "kill" "-s" "SIGTERM" (str process-id))
      (throw (Exception. (str "no process for swap-index " swap-index))))))

(comment
  (launch-farcasterd 0 (read-config "config.edn"))
  (kill-farcasterd 0 (read-config "config.edn")))

(defn -main [& args]
  (if (= (count args) 2)
    (let [[min-swap-index max-swap-index] (map #(Integer/parseInt %) args)
          config (read-config "config.edn")
          unresponsive-daemons (filter #(not (farcasterd-running? % config)) (range min-swap-index max-swap-index))]
      (println "swap index range: " min-swap-index max-swap-index)
      (println "config: " config)
      (if (empty? unresponsive-daemons)
        (do
          (println "all running -> can continue!")
          (reset! offers (offers-get))
          (println "offers: " @offers)
          ;; (map #(offer-take % config) (range min-swap-index (max (inc max-swap-index) (+ min-swap-index (count @offers)))))
          )
        (do
          (println "following daemons aren't responding, please get them running first:" unresponsive-daemons)
          (map #(launch-farcasterd % config) unresponsive-daemons)))
      )
    (println "required args: min-swap-index max-swap-index"))
  )

(comment (-main "0" "20"))

(comment
  (reset! offers (offers-get))
  (count @offers)
  (def offers-cached @offers))
