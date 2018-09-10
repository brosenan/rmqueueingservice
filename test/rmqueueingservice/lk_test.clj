(ns rmqueueingservice.lk-test
  (:require [midje.sweet :refer :all]
            [lambdakube.testing :as lkt]
            [lambdakube.core :as lk]
            [lambdakube.util :as lku]
            [rmqueueingservice.lk :as rmqlk]))

(defn test-module [$]
  (-> $
      ;; An acceptance test for RabbitMQ.
      (lkt/test :acceptance
                {:use-single-rabbitmq true}
                [:event-broker]
                (fn [event-broker]
                  (-> (lk/pod :test {})
                      (lku/add-midje-container
                       :test
                       '[[org.clojure/clojure "1.9.0"]
                         [com.novemberain/langohr "5.0.0"]]
                       {:rabbitmq {:host (:hostname event-broker)
                                   :port (-> event-broker :ports :amqp)
                                   :username "guest"
                                   :vhost "/"
                                   :password "guest"}}
                       '[(ns main-test
                           (:require [midje.sweet :refer :all]
                                     [langohr.core :as rmq]
                                     [langohr.channel :as lch]
                                     [langohr.exchange :as le]
                                     [langohr.queue :as lq]
                                     [langohr.basic :as lb]
                                     [langohr.consumers :as lc]))
                         ;; Roughly based on Langohr's "Hello World" example
                         (def message (atom ""))
                         (defn message-handler [ch meta payload]
                           (println "Message received: " (String. payload))
                           (reset! message (String. payload)))
                         (fact
                          (println rabbitmq)
                          (let [conn (rmq/connect rabbitmq)
                                ch (lch/open conn)
                                qname "langohr.examples.hello-world"]
                            (lq/declare ch qname {:exclusive false :auto-delete true})
                            (lc/subscribe ch qname message-handler {:auto-ack true})
                            (println "Subscribed...")
                            (Thread/sleep 2000)
                            (lb/publish ch "" qname "Hello!" {:content-type "text/plain" :type "greetings.hi"})
                            (println "Message published!")
                            (Thread/sleep 10000)
                            ;; Check that the message has been delivered
                            @message => "Hello!"
                            (rmq/close ch)
                            (rmq/close conn)))])
                      (lku/wait-for-service-port event-broker :amqp))))))

(fact :kube
 (-> (lk/injector)
     (test-module)
     (rmqlk/module)
     (lk/standard-descs)
     (lkt/kube-tests "rmq")) => "")

(fact
 (-> (lk/injector)
     (test-module)
     (rmqlk/module)
     (lk/standard-descs)) => map?)

