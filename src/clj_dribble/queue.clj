(ns clj-dribble.queue
;  (:gen-class)
;  (:require [durable-queue :refer :all])
  (:require [clojure.core.async :as async])
)

;; Queues
;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Abstract Queue definition
(defprotocol Queue
  (put! [this, e])
  (take! [this])
  (close! [this])
)

;; Put all elements from sequence to queue
(defn put-all! [queue elements] 
    (doseq [e elements] (put! queue e) ))

;; Queue implementation based on core/async
(deftype AsyncQueue [c]
    Queue
  (put! [this, e] (async/>!! c e) )
  (take! [this]  (async/<!! c) )
  (close! [this]  (async/close! c) )
)

(defn async-queue [buffer-size] (AsyncQueue. (async/chan buffer-size)) )


;; Producers
;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Abstract Producer definition
(defprotocol Producer
  (get! [this])
  (closed? [this])
)

;; StatefulProducer implementation of the Producer protocol
;; Takes a reference containing initial state
;; fn function should return map {:data result-of-function :next-state next-state-for-producer
;; next-state = nil is the terminal state
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

;; Create StatefulProducer for given initial state
(defn stateful-producer [initial-state fn] (StatefulProducer. (atom initial-state) fn) )


;; StatefulMultiProducer implementation of the Producer protocol
;; Obtains a producer from the given producer-factory with the initial state obtained from src-fn
;; Obtains values from the producer until it returns nil then obtains next producer etc
;; Stops after current producer returned nil and src-fn returned nil
(deftype StatefulMultiProducer [src-fn producer-factory producer-ref]
    Producer
    (get! [this] 
        (loop    [producer (deref producer-ref) result (get! producer)]
            (if (not (nil? result))
                result
                (let [next-initial (src-fn)]
                    (if (nil? next-initial)
                        nil
                        (let [producer (producer-factory next-initial)]
                            (reset! producer-ref producer)
                            (recur 
                                producer
                                (get! producer)
                            )
                        )
                    )
                )
            )
        )
    )
    (closed? [this] (nil? (deref producer-ref)) )
)

;; Create StatefulProducer for given initial state
(defn stateful-multi-producer [src-fn producer-factory]
    (StatefulMultiProducer. src-fn producer-factory (atom (producer-factory (src-fn))) )
)


;; Pipelines
;;;;;;;;;;;;;;


;; A pipeline to connect some source of values (e.g. Queue or Producer) with some destination
;; Applies some 
(defn pipeline 
    ([src-take dst-put dst-close transducer]
        (loop [values (src-take)]
            (if (nil? values)
                (dst-close)
                (do 
                    (doseq [v values]
                        (dst-put (transducer v))) 
                    (recur (src-take))
                )
            )
        )
    )
    ([src-take dst-put dst-close]
        (pipeline src-take dst-put dst-close identity)
    )
)

;; Collect data from a source (e.g. Queue or Producer)
;; with given collector function starting from some initial collection
(defn collect [src-take collection collector]
    (loop [u (src-take) results collection] 
        (if (nil? u)
            results
            (recur (src-take) (collector results u) )
        )
    ) 
)    
    

