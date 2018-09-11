(ns rmqueueingservice.lk-test
  (:require [midje.sweet :refer :all]
            [lambdakube.testing :as lkt]
            [lambdakube.core :as lk]
            [lambdakube.util :as lku]
            [rmqueueingservice.lk :as rmqlk])
  (:import injectthedriver.interfaces.QueueService))

(defn test-module [$]
  (-> $
      ;; An acceptance test for RabbitMQ.
      (lkt/test :with-single-rabbitmq
                {:use-single-rabbitmq true}
                [:event-broker]
                (fn [event-broker]
                  (-> (lk/pod :test {})
                      (lku/add-midje-container
                       :test
                       '[[org.clojure/clojure "1.9.0"]
                         [com.novemberain/langohr "5.0.0"]
                         [brosenan/injectthedriver "0.0.4-SNAPSHOT"]]
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
                                     [langohr.consumers :as lc])
                           (:import (injectthedriver DriverFactory)
                                    (injectthedriver.interfaces QueueService
                                                                QueueService$Callback)))
                         (fact
                          "RabbitMQ sanity using Langohr"
                          ;; Roughly based on Langohr's "Hello World" example
                          (def message (atom ""))
                          (defn message-handler [ch meta payload]
                            (println "Message received: " (String. payload))
                            (reset! message (String. payload)))
                          (let [conn (rmq/connect rabbitmq)
                                ch (lch/open conn)
                                qname "langohr.examples.hello-world"]
                            (lq/declare ch qname {:exclusive false :auto-delete true})
                            (lc/subscribe ch qname message-handler {:auto-ack true})
                            (lb/publish ch "" qname "Hello!" {:content-type "text/plain" :type "greetings.hi"})
                            (Thread/sleep 1000)
                            ;; Check that the message has been delivered
                            @message => "Hello!"
                            (rmq/close ch)
                            (rmq/close conn)))
                         (fact
                          "Driver publishes, Langohr receives"
                          (def message (atom ""))
                          (defn message-handler [ch meta payload]
                            (println "Message received: " (String. payload))
                            (reset! message (String. payload)))
                          (let [conn (rmq/connect rabbitmq)
                                ch (lch/open conn)
                                qname "foo"
                                qs (DriverFactory/createDriverFor QueueService)
                                q (.defineQueue qs qname)]
                            ;; We trust that the queue is being
                            ;; declared by the driver, so we do not
                            ;; declare it here.
                            (lc/subscribe ch qname message-handler {:auto-ack true})
                            (.enqueue q (.getBytes "Hello, World"))
                            (Thread/sleep 1000)
                            ;; Check that the message has been delivered
                            @message => "Hello, World"
                            (rmq/close ch)
                            (rmq/close conn)))
                         (fact
                          "Langohr publishes, Driver receives"
                          (def message (atom ""))
                          (let [conn (rmq/connect rabbitmq)
                                ch (lch/open conn)
                                qname "foo"
                                qs (DriverFactory/createDriverFor QueueService)
                                q (.defineQueue qs qname)
                                subs (.register q (reify QueueService$Callback
                                                    (handleTask [this data]
                                                      (reset! message (String. data)))))]
                            (lb/publish ch "" qname "Hola!" {:content-type "text/plain" :type "greetings.hi"})
                            (Thread/sleep 1000)
                            ;; Check that the message has been delivered
                            @message => "Hola!"
                            (.stop subs)
                            (rmq/close ch)
                            (rmq/close conn)))])
                      (lku/wait-for-service-port event-broker :amqp)
                      (lk/update-container :test lku/inject-driver QueueService event-broker))))))

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

