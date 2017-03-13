(defproject clj-dribble "0.1.0-SNAPSHOT"
  :description "Test Dribbble app for job interview"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [cheshire "5.7.0"]
                 [clj-http "3.4.1"]
                 ;[factual/durable-queue "0.1.5"]
                  [org.clojure/core.async "0.3.441"]]
  :main ^:skip-aot clj-dribble.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
