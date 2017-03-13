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

;Queues
(defprotocol AQueue
  (put! [this, e])
  (take! [this])
  (close! [this])
)

(defn put-all! [queue elements] 
    (doseq [e elements] (put! queue e) ))

(deftype AsyncQueue [c]
    AQueue
  (put! [this, e] (async/>!! c e) )
  (take! [this]  (async/<!! c) )
  (close! [this]  (async/close! c) )
)

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

(defn ->SemaphoreLimiter [max]
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


;; (deftype ThrottledQueue [q, limiter]
;;     AQueue
;;   (put! [this, e] (put! q e) )
;;   (take! [this]  
;;     (acquire! limiter)
;;     (take! q) )
;; )

(defn source [queue-to fn] (put! queue-to (fn)) )

(defn pipeline [queue-from queue-to fn]
    (loop [e (take! queue-from)]
        (let [results (fn e)]
;;            (println "passdown: " (:passdown results))
            (doseq [result (:passdown results)]
                (put! queue-to result) )
;;            (println "loopback: " (:loopback results))
            (doseq [e (:loopback results)]
                (put! queue-from e) )
            (if (:close results)
                (close! queue-from)
        )
        (recur (take! queue-from))
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

  
; Dribbble API
;;;;;;;;;;;;;;;;;;;;

(def api-limiter (->SemaphoreLimiter 60))

(defn api-path [resource] (str api-prefix resource))

(defn api-get-http [path]
    (acquire! api-limiter)
    
    (let [ res
    (client/get path {:headers auth-header})
    ] 
    (println "X-RateLimit-Reset: " (get-in res [:headers "X-RateLimit-Reset"])) 
    (println "X-RateLimit-Remaining: " (get-in res [:headers "X-RateLimit-Remaining"])) 
    (if (= "0" (get-in res [:headers "X-RateLimit-Remaining"]))
        (acquire-all! api-limiter)
    )
    res)
)

(defn api-parse-response [response] 
    {:data (parse-string (:body response))
     :http-status (:status response)
     :next-page-url (get-in response [:links :next :href]) 
    }
)

(defn api-get 
    ([path mapper] 
        (let [result (api-parse-response (api-get-http path))] 
            (assoc result :data  (mapper (:data result)) )
        )
    )
    ([path] (api-get path #(identity %) ) )
)

(defn api-get-list 
    ([path mapper] 
        (loop [p path result []] 
            (println "path: " p)
            (if (= p nil) 
                result
                (let [res (api-get p mapper)]
                    ;(println "result: \n" res)
                    (recur (:next-page-url res) (conj result (:data res)))
                )
            )
        )
    )
    ([path] (api-get-list path #(identity %) ) )
)

(defn api-start []
   (start-refresh-limiter api-limiter 60 
        (Long/parseLong (get (:headers (api-get-http (api-path "/users/simplebits"))) "X-RateLimit-Reset"))
    )
)


(defn api-pipeline-step [fn close?]
    #(let [results (fn %)]
        {:passdown (:data results)
        :loopback (if (:next-page-url results) [(:next-page-url results)] [])
        :close (close? results)}
    )
)

(defn map-followers [json]
    (println (map #(get-in % ["follower" "shots_url"]) json ))
    (map #(get-in % ["follower" "shots_url"]) json )
)

(defn map-shots [json]
    (println (map #(get % "likes_url") json ))
    (map #(get % "likes_url") json )
)

(defn map-likes [json]
    (println (map #(get % "likes_url") json ))
    (map #(get-in % ["user" "name"]) json )
)

(defn user-to-followers [queue-from queue-to] 
    (pipeline queue-from queue-to 
        (api-pipeline-step 
            #(api-get-list % map-followers)
            #(= (:next-page-url results) nil)
        )
    )
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
    (def q1 (AsyncQueue. (async/chan 10000)) )
    (def q2 (AsyncQueue. (async/chan 10000)) )
    (def q3 (AsyncQueue. (async/chan 10000)) )
    
    (put! q1 (api-path "/users/simplebits/followers"))
    
;;     (pipeline q1 q2
;;         #(api-get %)
;;     )
    (future (user-to-followers q1 q2))

    
    (future (pipeline q2 q3 
        (api-pipeline-step 
            #(api-get % map-shots)
            #(= (:next-page-url results) nil)
        )
    ) )
    
    (loop [a 1] (println (take! q3)) (recur 1)) 
    
;    (dotimes [i 180] (put! q1 (str "test" i)))
;    (put! q1 "simplebits")
;;    (future (pipeline q1 q2 #(user-followers %)))
;    (future (pipeline q1 q2 #(do (println "pipeline1: " %) [(str "after ppl1" %)])))
;    (println "----")
;    (dotimes [i 180] (println (take! q2)))
    (println (take! q2))
    
    ;(println (api-get-list (api-path "/users/simplebits/followers")))
    
    (stop-refresh-limiter limiter-refresh-control)
    (shutdown-agents)
  )
  
  

;  (println
;    (user-followers "simplebits")  )
)
   
   

;    Calculate Top10 "likers"


