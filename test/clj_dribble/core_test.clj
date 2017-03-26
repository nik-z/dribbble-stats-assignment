(ns clj-dribble.core-test
  (:require [clojure.test :refer :all]
            [clj-dribble.core :refer :all]
            [clj-dribble.queue :refer :all]
            [clj-dribble.limiter :refer :all])
)

            

(deftest test-pipeline
    (testing "Pipeline works as supposed."
        (let [q1 (async-queue 10)
            q2 (async-queue 10) ]
            (put! q1 '(1 2 3)) 
            (put! q1 '(3 4))
            (close! q1)
            
            (pipeline #(take! q1) #(put! q2 %) #(close! q2) )
            
            (is (= 1 (take! q2)) )
            (is (= 2 (take! q2)) )
            (is (= 3 (take! q2)) )
            (is (= 3 (take! q2)) )
            (is (= 4 (take! q2)) )
            (is (= nil (take! q2)) )
        )
    )
)

(deftest test-collect
    (testing "Collect to sum"
        (let [q1 (async-queue 10)]
            (put! q1 1) 
            (put! q1 2) 
            (put! q1 3) 
            (put! q1 4) 
            (close! q1)
            
            (is (= 10 (collect #(take! q1) 0 #(+ %1 %2) )) )
        )
    )

    (testing "Collect to vector"
        (let [q1 (async-queue 10)]
            (put! q1 1) 
            (put! q1 2) 
            (put! q1 3) 
            (put! q1 4) 
            (close! q1)
            
            (is (= [1 2 3 4] (collect #(take! q1) [] conj )) )
        )
    )


    (testing "Collect to map"
        (let [q1 (async-queue 10)]
            (put! q1 "A") 
            (put! q1 "B") 
            (put! q1 "A") 
            (put! q1 "C") 
            (put! q1 "C") 
            (put! q1 "C") 
            (close! q1)
            
            (is (= {"A" 2 "B" 1 "C" 3} (collect #(take! q1) (hash-map) count-to-map )) )
        )
    )
)


(def request-to-response (load-string (slurp "test/clj_dribble/test-request-to-response.txt")))

(defn http-mock [request, headers] (get request-to-response request))

(deftest test-followers-producer
    (with-redefs [clj-http.client/get http-mock]
        (testing "Producer works for user-to-followers step."
            (let [producer 
                (stateful-producer 
                    (api-path "/users/test0/followers")  
                    (api-producer-step #(api-get % map-followers))
                )]
                
                (is (= 
                    '("https://api.dribbble.com/v1/users/123451/shots?per_page=100" "https://api.dribbble.com/v1/users/123452/shots?per_page=100" "https://api.dribbble.com/v1/users/123453/shots?per_page=100") 
                    (get! producer)
                ))
                
                (is (= 
                    '("https://api.dribbble.com/v1/users/123454/shots?per_page=100" "https://api.dribbble.com/v1/users/123455/shots?per_page=100") 
                    (get! producer)
                ))
                
                (is (= 
                    nil 
                    (get! producer)
                ))
            )
        )
    )
)

(deftest test-shots-multiproducer
    (with-redefs [clj-http.client/get http-mock]
        (testing "Multi-producer works for follower-to-shots step."
            (let [q1 (async-queue 10)]
                (put! q1 "https://api.dribbble.com/v1/users/123451/shots?per_page=100")
                (put! q1 "https://api.dribbble.com/v1/users/123452/shots?per_page=100")
                (close! q1)
                
                (let [producer
                  (stateful-multi-producer #(take! q1) 
                    (fn [p] (stateful-producer p (api-producer-step #(api-get % map-shots)) ) ) 
                  )
                ]
                
                    (is (= 
                        '("https://api.dribbble.com/v1/shots/100001/likes?per_page=100" "https://api.dribbble.com/v1/shots/100002/likes?per_page=100") 
                        (get! producer)
                    ))
                    
                    (is (= 
                        '("https://api.dribbble.com/v1/shots/100003/likes?per_page=100" "https://api.dribbble.com/v1/shots/100004/likes?per_page=100") 
                        (get! producer)
                    ))
                    
                    (is (= 
                        '("https://api.dribbble.com/v1/shots/100005/likes?per_page=100") 
                        (get! producer)
                    ))
                    
                    (is (= 
                        nil 
                        (get! producer)
                    ))
                )
            )
        )
    )
)

