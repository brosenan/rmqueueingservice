(ns rmqueueingservice.core-test
  (:require [midje.sweet :refer :all]
            [rmqueueingservice.core :as qs]
            [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.exchange :as le]
            [langohr.queue :as lq]
            [langohr.basic :as lb]
            [langohr.consumers :as lc])
  (:import java.util.HashMap
           (injectthedriver.interfaces QueueService$Queue
                                       QueueService$Callback
                                       Stopable)))


;; # Initialization

;; The `-init` function (mapped to the constructor), takes a Java
;; `Map` object containing properties for the queue service, and
;; initializes a connection and a channel. The two are returned as the
;; state of the object. The preceding empty vector in the return value
;; is the list of parameters for the base-class constructor.
(fact
 (let [props (HashMap.)]
   (.put props "host" "foo")
   (.put props "port" 1234)
   (qs/-init props) => [[] {:conn ..conn..
                           :chan ..chan..}]
   (provided
    (rmq/connect {:host "foo"
                  :port 1234}) => ..conn..
    (lch/open ..conn..) => ..chan..)))

;; Since `gen-class` creates Java objects for which the state
;; represented as Clojure values is given in a `.state` field, we
;; create the following struct to mock an object with such a field.
(defrecord MockObj [state])

;; # Defining a Queue

;; The `-defineQueue` method takes a name and returns a queue
;; object. Internally, it defines a queue with the same name. It is
;; idempotent because the underlying AMQP operation is.

;; `-defineQueue` returns an instance of
;; `injectthedriver.interfaces.QueueService.Queue`.
(fact
 (let [driver (MockObj. {:chan ..chan..})]
   (qs/-defineQueue driver ..name..) => (partial instance? QueueService$Queue)
   (provided
    (lq/declare ..chan.. ..name.. {:exclusive false :auto-delete false}) => nil)))

;; # Enqueuing

;; Given a queue, its `.enqueue` method publishes a task on
;; it. Internally, publishing is done on the default exchange, with a
;; routing key equal to the queue name (the default exchange is a
;; direct exchange, which routes messages to the queue it receives as
;; routing key).
(fact
 (let [msg (.getBytes "foobar" "utf8")]
   (let [driver (MockObj. {:chan ..chan..})
         queue (qs/-defineQueue driver ..name..)]
     (.enqueue queue msg)) => nil
   (provided
    (lq/declare ..chan.. ..name.. irrelevant) => irrelevant
    (lb/publish ..chan.. ..name.. msg {:content-type "application/octet-stream"}) => irrelevant)))


;; # Registerring to Tasks

;; The `.register` method of a queue takes a callback object
;; (implementation of
;; `injectthedriver.interfaces.QueueService.Callback`), and starts a
;; thread that would call it for tasks coming from the
;; queue. Internally, it starts a consumer.
(fact
 (let [driver (MockObj. {:chan ..chan..})
       queue (qs/-defineQueue driver ..name..)
       cb (reify QueueService$Callback
            (handleTask [this data]))]
   (.register queue cb)) => (partial instance? Stopable)
 (provided
  (lq/declare ..chan.. ..name.. irrelevant) => irrelevant
  (lc/subscribe ..chan.. ..name.. irrelevant) => ..constag..))
