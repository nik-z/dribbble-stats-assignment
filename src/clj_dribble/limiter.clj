(ns clj-dribble.limiter
)


;; Operations throughput limiting
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Abstract protocol for managing limited resources
(defprotocol Limiter
  ;; Acquire n permits. Locks if quantity of available limits is less then n.
  (acquire! [this, n] )
  ;; Acquire all available permits."
  (acquire-all! [this])
  ;; Reset permits counter."
  (reset-limit! [this])
)
(defn acquire! [limiter] (acquire! limiter 1))

;; Limiter implementation based on java.util.concurrent.Semaphore
;; semaphore is initialized with max number of permits at creation
(deftype SemaphoreLimiter [max semaphore]
    Limiter
  
  ;;acquire permits if available or locks and waits if no
  (acquire! [this, n] (.acquire semaphore n) )
  (acquire-all! [this] (.drainPermits semaphore))
  
  ;; reset number of available permits back to max
  (reset-limit! [this] 
    (.drainPermits semaphore)
    (.release semaphore max)
  )
)

;; Create a SemaphoreLimiter with given max permits
(defn semaphore-limiter [max]
    (let [semaphore (java.util.concurrent.Semaphore. max)]
        (SemaphoreLimiter. max semaphore)
    )
)

;; Loop for resetting Limiter permits on timely basis
;; limiter - a limiter to operate on
;; reset-period - a period in seconds
;; next-reset-moment - limit resetting needs to be synchronized with external service.
;; This parameter sets a moment of first reset which then will be used to calculate next moments 
;; by adding next-reset-moment
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

;; Start limiter refresh loop 
(defn start-refresh-limiter [limiter reset-period next-reset-moment]
    (let [is-stopped (atom false)]
        (future (reset-limiter-cycle limiter reset-period  next-reset-moment is-stopped))
        is-stopped
    )
)

;; stop limiter refresh loop
(defn stop-refresh-limiter [limiter-refresh-control]
    (reset! limiter-refresh-control true) )
