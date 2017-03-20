(ns clj-dribble.core
  (:gen-class)
  (:require [clj-http.client :as client])
  (:require [cheshire.core :refer :all])
;  (:require [durable-queue :refer :all])
  (:require [clojure.core.async :as async])
)

(def client-id "7fc5bf1bfbef087d8f839d9fee7b10ad7d4dc6c92b10300b40feee980b08c642")
(def client-token "cb793ad08c4ff1de143c5b619b5bea9adc120462ece2cd1072624146294e5405")
(def auth-header {:Authorization (str "Bearer " client-token)})
(def api-prefix "https://api.dribbble.com/v1")


;; Queues and related 
;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Queues
(defprotocol Queue
  (put! [this, e])
  (take! [this])
  (close! [this])
)

(defn put-all! [queue elements] 
    (doseq [e elements] (put! queue e) ))

(deftype AsyncQueue [c]
    Queue
  (put! [this, e] (async/>!! c e) )
  (take! [this]  (async/<!! c) )
  (close! [this]  (async/close! c) )
)


;; Producers
(defprotocol Producer
  (get! [this])
  (closed? [this])
)

(deftype StatefulProducer [state-ref fn]
    Producer
    (get! [this] 
        (let [state (deref state-ref)]
            (if (nil? state)
                nil
                (let [result (fn state)]
                    (reset! state-ref (:next-state result))
                    (:data result)
                )
            )
        )
    )
    (closed? [this] (nil? (deref state-ref)) )
)

(defn stateful-producer [initial-state fn] (StatefulProducer. (atom initial-state) fn) )


;; Pipelines
(defn pipeline-exec [fn e dst]
    (let [results (fn e)]
        (doseq [result results]
            (put! dst result)) 
    )
)

(defn pipeline-close [destination]
    (close! destination) 
    destination
)

(defn pipeline-loop  [src-fn nonil-fn nil-fn]
    (loop [e (src-fn)]
        (if (nil? e)
            (nil-fn)
            (do
                (nonil-fn e) 
                (recur (src-fn))
            )
        )
    )
)

(defn pipeline [src-fn destination fn]
    (pipeline-loop src-fn
        #(pipeline-exec fn % destination) 
        #(pipeline-close destination)
    )
)

(defn seq-producer-pipeline [source destination producer-factory fn]
    (loop [p (take! source)]
        (if (nil? p)
            (pipeline-close destination)
            (let [producer (producer-factory p)]
                (pipeline-loop #(get! producer)
                    #(pipeline-exec fn % destination) 
                    #(print destination)
                )
                (recur (take! source))
            ) 
        )
    )
)

;; (defn sink [queue-from fn initial-value combine-fn] 
;;     (loop [e (take! queue-from) return initial-value]
;;         (let [results (fn e)]
;;             (doseq [result results]
;;                 (put! queue-to result) )
;;         )
;;         (recur (take! queue-from) (combine return)
;;     )
;; )

;; (defn create-queue [limiter]
;;     (ThrottledQueue. (AsyncQueue. (async/chan 10000)) limiter) )


;; Operations throughput throttling
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol ALimiter
  (acquire! 
    [this]
    [this, n] )
  (acquire-all! [this])
  (reset-limit! [this])
)
(defn acquire! [limiter] (acquire! limiter 1))

(deftype SemaphoreLimiter [max semaphore]
    ALimiter
  (acquire! [this, n] (.acquire semaphore n) )
  (acquire-all! [this] (.drainPermits semaphore))
  (reset-limit! [this] 
    (.drainPermits semaphore)
    (.release semaphore max)
  )
)

(defn semaphore-limiter [max]
    (let [semaphore (java.util.concurrent.Semaphore. max)]
        (SemaphoreLimiter. max semaphore)
    )
)

(defn reset-limiter-cycle [limiter reset-period next-reset-moment is-stopped]
    (loop [reset-at next-reset-moment]
        (println "next refresh:" reset-at)
        (if (deref is-stopped)
        (println "stop")
        (recur 
            (let [now (quot (.getTime (java.util.Date.)) 1000) ]
                (if (< now reset-at)
                    (do 
                        (println "now is" now)
                        (Thread/sleep 1000)
                        reset-at
                    )
                    (do 
                        (println "resetting limits")
                        (acquire-all! limiter)
                        (Thread/sleep 1000)
                        (reset-limit! limiter)
                        (+ reset-at reset-period)
                    )
                )
            )
        ) )
    )
)

(defn start-refresh-limiter [limiter reset-period next-reset-moment]
    (let [is-stopped (atom false)]
        (future (reset-limiter-cycle limiter reset-period  next-reset-moment is-stopped))
        is-stopped
    )
)

(defn stop-refresh-limiter [limiter-refresh-control]
    (reset! limiter-refresh-control true) )



;; Dribbble API
;;;;;;;;;;;;;;;;;;;;

(def api-limiter (semaphore-limiter 60))

(defn api-path [resource] (str api-prefix resource "?per_page=100"))

(defn api-get-http 
    ([path reader-fn limiter]
        (acquire! limiter)
        
        (let [res  (reader-fn path {:headers auth-header}) ] 
            (println "X-RateLimit-Reset: " (get-in res [:headers "X-RateLimit-Reset"])) 
            (println "X-RateLimit-Remaining: " (get-in res [:headers "X-RateLimit-Remaining"])) 
            (if (= "0" (get-in res [:headers "X-RateLimit-Remaining"]))
                (acquire-all! limiter)
            )
            res
        )
    )
    ([path] (api-get-http path client/get api-limiter))
)

(defn api-parse-response [response] 
    {:data (parse-string (:body response))
     :http-status (:status response)
     :next-page-url (get-in response [:links :next :href]) 
    }
)

(defn api-get 
    ([path mapper reader-fn limiter] 
        (let [result (api-parse-response (api-get-http path reader-fn limiter))] 
            (assoc result :data  (mapper (:data result)) )
        )
    )
    ([path mapper]  (api-get path mapper client/get api-limiter) )
    ([path] (api-get path #(identity %) ) )
)

(defn api-start []
   (start-refresh-limiter api-limiter 60 
        (Long/parseLong (get (:headers (api-get-http (api-path "/users/simplebits"))) "X-RateLimit-Reset"))
    )
)


(defn api-pipeline-step [fn]
    #(let [results (fn %)]
        {:data (:data results)
        :next-state (:next-page-url results) }
    )
)

(defn map-followers [json]
;;    (println (map #(get-in % ["follower" "shots_url"]) json ))
    (map #(get-in % ["follower" "shots_url"]) json )
)

(defn map-shots [json]
;;    (println (map #(get % "likes_url") json ))
    (map #(get % "likes_url") json )
)

(defn map-likes [json]
    (println (map #(get-in % ["user" "username"]) json ))
    (map #(get-in % ["user" "username"]) json )
)

;; (defn api-get [resource] 
;;     (parse-string (get-api-json resource)) 
;; )
;; 
;; ;    For a given Dribbble user find all followers
;; (defn api-user-followers [user]
;;     (get-api (str "/users/" user "/followers") )
;; )
;; 
;; (defn user-followers [user]
;;     (map #(get-in % ["follower" "username"]) (api-user-followers user ) )
;; )
;; 
;; ;    For each follower find all shots
;; (defn get-user-shots [user]
;;     (get-api (str "/users/" user "/shots") ) 
;; )
;; 
;; ;    For each shot find all "likers"
;; (defn get-shot-likes [shot-id]
;;     (get-api (str "/shots/" shot-id "/likes") )
;; )


    
    
(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (let [
        limiter-refresh-control (api-start)
    ]
    
    (def followers-producer 
        (stateful-producer 
            (api-path "/users/an-nguyen/followers")  
            (api-pipeline-step #(api-get % map-followers))
        )
    )
    (def q-followers (AsyncQueue. (async/chan 10000)) )
    (def q-shots (AsyncQueue. (async/chan 10000)) )
    (def q-likes (AsyncQueue. (async/chan 10000)) )

    

    
     (future (pipeline #(get! followers-producer) q-followers 
         #(identity %)
     ) )
    
    (future (seq-producer-pipeline q-followers  q-shots
        (fn [p] 
            (stateful-producer p (api-pipeline-step #(api-get % map-shots)) )
        )
        #(identity %)
    ) )

    (future (seq-producer-pipeline q-shots q-likes
        (fn [p] 
            (stateful-producer p (api-pipeline-step #(api-get % map-likes)) )
        )
        #(identity %)
    ) )

            
    (def results (loop [u (take! q-likes) results (hash-map)] 
        (println "collect: " u) 
        (if (nil? u)
            results
            (recur 
                (take! q-likes)
                (if (nil? (get results u))
                    (assoc results u 1)
                    (update results u #(inc %))
                )
            )
        )
    ) )
    
    (println 
        (into (sorted-map-by (fn [key1 key2]
                        (compare [(get results key2) key2]
                                [(get results key1) key1])))
        results)
    )

    (stop-refresh-limiter limiter-refresh-control)
    (shutdown-agents)
  )
  
  

;  (println
;    (user-followers "simplebits")  )
)
   
   

;    Calculate Top10 "likers"


