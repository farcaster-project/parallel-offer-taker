(ns parallel-offer-taker.core
  (:require
   [clj-http.lite.client :as http]
   [clj-yaml.core :as yaml]
   clojure.core
   [clojure.data.json :as json]
   clojure.edn
   clojure.java.io
   [clojure.java.shell :as shell]
   clojure.pprint
   clojure.set
   [clojure.string :as string]
   clojure.tools.cli)
  (:gen-class)
  )

;; (println (shell/sh "pwd"))
;; (println (shell/sh "ls" "-lah"))

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

(defn farcasterd-data-dir [swap-index {:keys [data-dir-root]}]
  (let [data-dir (str (add-missing-trailing-slash data-dir-root) ".data_dir_" swap-index)]
    (try
      (if (.exists (clojure.java.io/as-file data-dir))
        data-dir
        (throw (Exception. (str data-dir " does not exist"))))
      (catch Exception e
        (println e)))))

(defn monero-wallet-rpc-data-dir [swap-index {:keys [data-dir-root]}]
  (let [data-dir (str (add-missing-trailing-slash data-dir-root) "syncer_wallets_" swap-index)]
    data-dir))

(defn farcaster-binary-dir [config]
  (or (add-missing-trailing-slash (:farcaster-binaries-path config)) "~/.cargo/bin/"))

(defn farcaster-binary-path [config binary-name]
  (str (farcaster-binary-dir config) binary-name))

(defn monero-wallet-rpc-binary [config]
  (or (:monero-wallet-rpc-binary config)
      "monero-wallet-rpc"))

(defn monero-wallet-rpc-port [swap-index config]
  (+ swap-index (:monero-wallet-rpc-start-rpc-port config)))

(defn monero-wallet-rpc-url [swap-index config]
  (let [rpc-port (monero-wallet-rpc-port swap-index config)]
    (str "http://127.0.0.1:" rpc-port "/json_rpc")))

(def get-version
  (json/write-str
   {"jsonrpc" "2.0",
    "id" "0",
    "method" "get_version",
    }))

(defn monero-wallet-rpc-running? [swap-index config]
  (let [rpc-url (monero-wallet-rpc-url swap-index config)]
    (try (some? (:body (http/get rpc-url {:body get-version})))
         (catch Exception e
           (println (:cause (Throwable->map e)))
           false))))

(defn farcaster-template-config-toml-file [config]
  (or (:farcaster-config-toml-file config) "~/.farcaster/farcaster.toml"))

(defn swap-specific-toml-path [swap-index config]
  (str (add-missing-trailing-slash (:data-dir-root config)) "farcasterd_" swap-index ".toml"))

(defn spit-swap-specific-toml [swap-index config]
  (as-> (slurp (farcaster-template-config-toml-file config)) data
    (clojure.string/split data #"\[")
    (map #(if (clojure.core/re-find #"^farcasterd.auto_funding*" %)
            %
            (clojure.string/replace %
                                    #"monero_rpc_wallet = (\D+)(\d+)" (str "monero_rpc_wallet = $1" (monero-wallet-rpc-port swap-index config)))
            ) data)
    (clojure.string/join "[" data)
    (spit (swap-specific-toml-path swap-index config) data)
    )
  (println "overwrote" (swap-specific-toml-path swap-index config)))

(comment (farcaster-template-config-toml-file (read-config "config.edn"))
         (farcasterd-data-dir 0 (read-config "config.edn")))

(defn help []
  (println (:out (apply shell/sh ["swap-cli" "help"]))))

(comment (help))

(defn offer-take [swap-index config]
  (let [result (apply shell/sh [(farcaster-binary-path config "swap-cli")
                                "-d" (farcasterd-data-dir swap-index config)
                                "take" "-w"
                                "--btc-addr" (:address-btc config)
                                "--xmr-addr" (:address-xmr config)
                                "--offer" (nth @offers (mod swap-index (count @offers)))])]
    (println "took offer for" swap-index)
    (println [(:out result) (:err result)])
    ))

(defn list-running-swaps [swap-index config]
  (let [result (apply shell/sh [(farcaster-binary-path config "swap-cli")
                                "-d" (farcasterd-data-dir swap-index config)
                                "ls"])]
    (-> (:out result)
        (yaml/parse-string))
    ))

(defn list-checkpoints [swap-index config]
  (let [result (apply shell/sh [(farcaster-binary-path config "swap-cli")
                                "-d" (farcasterd-data-dir swap-index config)
                                "list-checkpoints"])]
    (-> (:out result)
        (yaml/parse-string))
    ))

(defn list-swaps [swap-index config]
  (let [result (apply shell/sh [(farcaster-binary-path config "swap-cli")
                                "-d" (farcasterd-data-dir swap-index config)
                                "list-swaps"])]
    (-> (:out result)
        (yaml/parse-string))
    ))

(defn restore-all-checkpoints [swap-index config]
  (let [checkpoints (map :swap_id (list-checkpoints swap-index config))
        results (map (fn [checkpoint]
                       (apply shell/sh [(farcaster-binary-path config "swap-cli")
                                        "-d" (farcasterd-data-dir swap-index config)
                                        "restore-checkpoint" checkpoint]))
                     checkpoints)]
    (clojure.pprint/pprint (map #(or (-> (:out %) (yaml/parse-string)) (:err %)) results))
    ))

(defn restore-or-offer-take [swap-index config]
  (let [checkpoints (list-checkpoints swap-index config)]
    (if (empty? checkpoints)
      (do
        (println "swap" swap-index ": taking offer")
        (offer-take swap-index config)
        )
      (do
        (println "swap" (str swap-index ": restoring:") (map :swap_id checkpoints))
        (restore-all-checkpoints swap-index config)
        )
      ))
  )

(comment (->> "config.edn"
              read-config
              (list-checkpoints 0)
              (map :swap_id)))

(comment  (def simple (atom 0)))
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
  (let [data-dir (farcasterd-data-dir swap-index config)]
    (if data-dir (try (-> (apply shell/sh
                                 [
                                  (str (farcaster-binary-dir config) "swap-cli")
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
  (let [data-dir (farcasterd-data-dir swap-index config)]
    [
     (str (farcaster-binary-dir config) "farcasterd")
     "-c" (swap-specific-toml-path swap-index config)
     "-d" data-dir
     ]))

(defn monero-wallet-rpc-launch-vec [swap-index config]
  (concat [
             (monero-wallet-rpc-binary config)
             "--rpc-bind-port" (+
                                (:monero-wallet-rpc-start-rpc-port config)
                                swap-index)
             "--wallet-dir" (monero-wallet-rpc-data-dir swap-index config)
             ]
            (clojure.string/split (:monero-wallet-rpc-options config) #" ")))

(defn append-logging [swap-index config binary-launch-vec]
  (let [binary-name (-> binary-launch-vec
                        first
                        (clojure.string/split #"/")
                        last)]
    (concat
     binary-launch-vec
     ["1>>"
      (str (add-missing-trailing-slash (:data-dir-root config)) binary-name "_" swap-index ".log")
      "2>&1"
      "&"
      "\n"
      "echo"
      "$!"
      ])))

(defn process-launch-vec [process]
  (case process
    :farcasterd farcasterd-launch-vec
    :monero-wallet-rpc monero-wallet-rpc-launch-vec))

(defn process-id-exact [process swap-index config]
  (try (->> ["bash" "-c" (str "ps -ef | grep \"" (string/join " "
                                                              ((process-launch-vec process) swap-index config)) "$\" | grep -v grep | awk '{print $2}'"
                              )]
            (apply shell/sh)
            :out
            string/trim-newline
            Integer/parseInt)
       ;; TODO: handle properly
       (catch Exception _
         (println "process" process "for" swap-index "does not exist"))))

(defn farcasterd-process-id [swap-index]
  (try
    (->> ["bash" "-c" (str "ps -ef | grep farcasterd | grep \".data_dir_" swap-index "$\" | grep -v grep | awk '{print $2}'"
                           )]
         (apply shell/sh)
         :out
         string/trim-newline
         Integer/parseInt)
    ;; TODO: handle properly
    (catch Exception _
      (println "process for" swap-index "does not exist"))))

(comment
  (process-id-exact :farcasterd 0 (read-config "config.edn"))
  (farcasterd-process-id 0))

(def process-ids (atom {}))

(defn launch-process [process swap-index config]
  (if (not  (or (contains? @process-ids (keyword (str process "-" swap-index)))
                (process-id-exact process swap-index config)))
    (->> config
         ((process-launch-vec process) swap-index)
         (append-logging swap-index config)
         (string/join " ")
         (shell/sh "bash" "-c")
         ((comp #(Integer/parseInt %) string/trim-newline :out))
         ((fn [pid] (do (swap! process-ids
                              (fn [m] (assoc m (keyword (str process "-" swap-index)) pid)))
                       (println "launched process" pid "for swap-index" swap-index)))))
    (println process swap-index "already running with pid" (get @process-ids swap-index))))

(defn launch-monero-wallet-rpc [swap-index config]
  (if (not (process-id-exact :farcasterd swap-index config))
    (->> config
         (monero-wallet-rpc-launch-vec swap-index)
         (append-logging swap-index config)
         (string/join " ")
         (shell/sh "bash" "-c")
         ((comp #(Integer/parseInt %) string/trim-newline :out))
         ((fn [pid] (do (swap! process-ids
                                      (fn [m] (assoc m swap-index pid)))
                               (println "launched process" pid "for swap-index" swap-index)))))
    (println "swap" swap-index "already running with pid" (get @process-ids swap-index))))

(comment
  (launch-process :farcasterd 0 (read-config "config.edn"))
  (farcasterd-launch-vec 0 (read-config "config.edn")))

(defn kill-farcasterd [swap-index config]
  (let [process-id (or (process-id-exact :farcasterd swap-index config)
                       (farcasterd-process-id swap-index))]
    (if process-id
      (do (shell/sh "kill" "-s" "SIGTERM" (str process-id))
          (swap! process-ids #(dissoc % swap-index))
          (println "killed swap instance" swap-index))
      (throw (Exception. (str "no process for swap-index " swap-index))))))

(comment
  (launch-process :farcasterd 0 (read-config "config.edn"))
  (kill-farcasterd 0 (read-config "config.edn")))

(defn progress [swap-index config]
  (let [swaps (list-swaps swap-index config)
        results (map (fn [swap]
                       (apply shell/sh [(farcaster-binary-path config "swap-cli")
                                        "-d" (farcasterd-data-dir swap-index config)
                                        "progress" swap]))
                     swaps)]
    (if (seq results)
      (clojure.pprint/pprint (map #(-> (or (:out %) (:err %))
                                       (yaml/parse-string)) results)))
    ))

(defn runner [min-swap-index max-swap-index options]
  (let [config (:config options)
        unresponsive-daemons (filter #(not (farcasterd-running? % config)) (range min-swap-index max-swap-index))
        unresponsive-xmr-wallet-rpcs (filter #(not (monero-wallet-rpc-running? % config)) (range min-swap-index max-swap-index))]
    (println "swap index range:" min-swap-index max-swap-index)
    (println "config:" config)

    (println "creating config per swap")
    (doall (map #(spit-swap-specific-toml % config) (range min-swap-index max-swap-index)))

    ;; ensure all daemons running
    (if (empty? unresponsive-daemons)
      (do
        (println "all farcasterd instances running -> can continue!")
        )
      (do
        (println "following daemons aren't responding, launching them first:" unresponsive-daemons)
        (doall (map #(launch-process :farcasterd % config) unresponsive-daemons))))

    ;; ensure all monero-wallet-rpcs are listening
    (if (empty? unresponsive-xmr-wallet-rpcs)
      (do
        (println "all monero-wallet-rpc instances running -> can continue!")
        )
      (do
        (println "following rpc daemons aren't responding, launching them first:" unresponsive-xmr-wallet-rpcs)

        (doall
         (map #(launch-process :monero-wallet-rpc % config) unresponsive-xmr-wallet-rpcs))))

    ;; get offers
    (reset! offers (offers-get))
    (println "offers: " @offers)

    ;; unless can restore past swap(s), take offer(s)
    ;; (doall (map #(restore-or-offer-take % config) (range min-swap-index (min max-swap-index (+ min-swap-index (dec (count @offers)))))))

    ;; keep alive
    (while true (do
                  (Thread/sleep 60000)
                  (let [running-swaps (map
                                       (fn [idx] {:farcaster-id idx :swap-ids (list-running-swaps idx config)})
                                       (range min-swap-index max-swap-index))
                        idle-farcasterds (filter #(and (empty? (:swap-ids %)) ;; (farcasterd-running? (:farcaster-id %) config)
                                                       ) running-swaps)]
                    (if (:sustain options)
                      ;; if user wants to sustain swap quantity, take another offer
                       (do
                        (println "idle-farcasterds:" idle-farcasterds)
                        (println "retrieving new offers")
                        (reset! offers (offers-get))
                        (doall (map #(offer-take (:farcaster-id %) config) idle-farcasterds)))
                      ;; else kill the daemon
                      (do
                        (println "killing daemon")
                        (doall (map #(kill-farcasterd (:farcaster-id %) config) idle-farcasterds))))

                    (doall (map clojure.pprint/pprint
                                [(java.util.Date.)
                                 "running swaps:"
                                 {
                                  :details (filter #(seq (:swap-ids %)) running-swaps)
                                  :farcasterd-running (map #(farcasterd-running? (:farcaster-id %) config) running-swaps)
                                  :count
                                  (->> running-swaps
                                       (map :swap-ids)
                                       (apply concat)
                                       count)}
                                 ]
                                )))
                  ))
    )
  )

(def cli-options
  [["-c" "--config CONFIG" "Config file"
    :default (read-config "config-sample.edn")
    :parse-fn #(read-config %)]
   ["-s" "--sustain"]
   ;; ["-v" nil "Verbosity level"
   ;;  :id :verbosity
   ;;  :default 0
   ;;  :update-fn inc]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["Farcaster Parallel Offer Taker Launcher"
        ""
        "Usage: parallel-offer-taker [options] start-index end-index"
        "Positional args:"
        "   start-index Lowest index of swaps to be launched"
        "   end-index   Highest index of swaps to be launched (exclusive)"
        "Options:"
        options-summary
        ""]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with an error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary]} (clojure.tools.cli/parse-opts args cli-options)]
    (cond
      (:help options)                   ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}
      errors                         ; errors => exit with description of errors
      {:exit-message (error-msg errors)}
      ;; custom validation on arguments
      (and (= 2 (count arguments))
           (every? (fn [arg] (try (Integer/parseInt arg)
                                 (catch Exception e
                                   ;; TODO: cleaner return - cause alone here not sufficient, and rest too verbose
                                   (println "failure parsing" arg "as integer:" e))))
                   arguments))
      {:start-range (Integer/parseInt (first arguments))
       :end-range (Integer/parseInt (second arguments))
       :options options}
      :else                ; failed custom validation => exit with usage summary
      {:exit-message (usage summary)})))

(defn valid-shell-call? [args]
  (let [ret (try (apply shell/sh args)
                 (catch Exception e
                   {:exit 1
                    :err (:cause (Throwable->map e))}))]
    (if (= 0 (:exit ret))
      true
      (do (println "call" args "failed with:" (:err ret))
          false))))

(defn validate-config [options]
  (let [config (:config options)]
    (keys (:config options))
    (every? identity
            [(valid-shell-call? [(farcaster-binary-path config "swap-cli") "help"])
             (valid-shell-call? [(farcaster-binary-path config "farcasterd") "--help"])
             (valid-shell-call? [(or (:monero-wallet-rpc-binary config) "monero-wallet-rpc") "--help"])])
    ))

(defn exit [status msg]
  (println msg)
  ;; (println "exiting with status" status)
  (System/exit status)
  )

(defn -main [& args]
  (let [{:keys [start-range end-range options exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      #_{:clj-kondo/ignore [:missing-else-branch]}
      (if (validate-config options)
        (runner start-range end-range options))
      )))
