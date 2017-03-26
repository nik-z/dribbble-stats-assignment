(ns clj-dribble.core
  (:gen-class)
  (:require [clj-http.client :as client])
  (:require [cheshire.core :refer :all])
  
  (:require [clj-dribble.queue :refer :all])
  (:require [clj-dribble.limiter :refer :all])
)

;; Dribbble API
;;;;;;;;;;;;;;;;;;;;

;; look up credentials
(def creds (load-string (slurp "resources/token.txt")))

(def client-id (:client-id creds))
(def client-token (:client-token creds))

;; Set constant parameters for accessing Dribbble API
(def auth-header {:Authorization (str "Bearer " client-token)})
(def api-prefix "https://api.dribbble.com/v1")
(def api-suffix "?per_page=100")

(defn log [& args] (println args))

;; Create limiter to throttle requests to API 
;; Dribble allows up to 60 requests per minute
(def api-limiter (semaphore-limiter 60))

;; Build complete path for HTTP request from short resource name
;; (e.g. "/users/username/followers")
(defn api-path [resource] (str api-prefix resource api-suffix))

;; Execute HTTTP Get request to the web server
(defn api-get-http 
    ([path reader-fn limiter]
        (acquire! limiter)
        
        (let [res  (reader-fn path {:headers auth-header}) ] 
            (log "X-RateLimit-Reset: " (get-in res [:headers "X-RateLimit-Reset"])) 
            (log "X-RateLimit-Remaining: " (get-in res [:headers "X-RateLimit-Remaining"])) 
            
            ;; Safety check - drop all limits left for this time slot if the server reports zero allowed requests remaining
            (if (= "0" (get-in res [:headers "X-RateLimit-Remaining"]))
                (acquire-all! limiter)
            )
            res
        )
    )
    ([path] (api-get-http path client/get api-limiter))
)

;; Parse JSON from the body of response
;; And some other important data
(defn api-parse-response [response] 
    {:data (parse-string (:body response))
     :http-status (:status response)
     
     ;; This link points to the next portion of requested resource
     ;; Empty if all data already red by this request
     :next-page-url (get-in response [:links :next :href]) 
    }
)

;; Get and parse data from server
(defn api-get 
    ([path mapper reader-fn limiter] 
        (let [result (api-parse-response (api-get-http path reader-fn limiter))] 
            (assoc result :data  (mapper (:data result)) )
        )
    )
    ;; Use custom mapper
    ([path mapper]  (api-get path mapper client/get api-limiter) )
    ([path] (api-get path #(identity %) ) )
)

;; Start refresh the API access limits counter 
(defn api-start []
   (start-refresh-limiter api-limiter 60 
        (Long/parseLong (get (:headers (api-get-http (api-path "/users/simplebits"))) "X-RateLimit-Reset"))
    )
)

;; Wrap API-specific data processing to be executed as StatefulProducer step
(defn api-producer-step [fn]
    #(let [results (fn %)]
        {:data (:data results)
        :next-state (:next-page-url results) }
    )
)

;; Map JSON representation of followers to the URL for follower's shots
(defn map-followers [json]
    (map #(str (get-in % ["follower" "shots_url"]) api-suffix) json )
)

;; Map JSON representation of shot to the URL for the likes for this shots
(defn map-shots [json]
    (map #(str (get % "likes_url") api-suffix) json )
)

;; Map JSON representation of like to the username of the user who added the like
(defn map-likes [json]
    (map #(get-in % ["user" "username"]) json )
)

;; Accumulate count of occurances of items to the map {item item-count}
(defn count-to-map [results u] 
    (log "collected: " u) 
    (if (nil? (get results u))
        (assoc results u 1)
        (update results u #(inc %))
    ) 
)    
    
(defn -main
  "Main function. Expects username as its first argument. Enumerates all likes for all shots for all followers of that user, then finds top 10 likers"
  [& args]
  (let [
        ;; Start API 
        limiter-refresh-control (api-start)
    ]
    
    ;; Get all followers of user
    (def followers-producer 
        (stateful-producer 
            (api-path (str "/users/" (first args) "/followers"))  
            (api-producer-step #(api-get % map-followers))
        )
    )
    ;; Followers queue
    (def q-followers (async-queue 10000) )
    ;; Shots queue
    (def q-shots (async-queue  10000) )
    ;; Likes queue
    (def q-likes (async-queue  10000) )

    ;; Pileline from followers producer to followers queue
    (future (pipeline #(get! followers-producer) #(put! q-followers %) #(close! q-followers) ))
    
    ;; Get all shots of a user
    ;; Reads URLs to shots from a queue
    (def shots-producer (stateful-multi-producer #(take! q-followers) 
        (fn [p] (stateful-producer p (api-producer-step #(api-get % map-shots)) ) ) 
        ))
    ;; Pileline from shots producer to followers queue
    (future (pipeline #(get! shots-producer) #(put! q-shots %) #(close! q-shots) ) )
    
    ;; Get all likes for shot
    ;; Reads URLs to likes from a queue
    (def likes-producer (stateful-multi-producer #(take! q-shots) 
        (fn [p] (stateful-producer p (api-producer-step #(api-get % map-likes)) ) )
        ))
    ;; Pileline from likes producer to likers
    (future (pipeline #(get! likes-producer) #(put! q-likes %) #(close! q-likes) ) )

    ;; Collect all likers, accumulate count of occurances to map
    (def results (collect #(take! q-likes) 
        (hash-map) 
        count-to-map
    ))
    
    ;; Sort by counts, print firts 10
    (println (take 10
        (into (sorted-map-by (fn [key1 key2]
                        (compare [(get results key2) key2]
                                [(get results key1) key1])))
        results)
    ))

    ;; Stop limiter refresh thread
    (stop-refresh-limiter limiter-refresh-control)
    ;; Shutdown executor threads pool
    (shutdown-agents)
  )
  
)
