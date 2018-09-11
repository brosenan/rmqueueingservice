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
                                       Stopable
                                       RecoverableError)))


;; # Initialization

;; The `-init` function (mapped to the constructor), takes a Java
;; `Map` object containing properties for the queue service, and
;; initializes a connection and a channel. The two are returned as the
;; state of the object. The preceding empty vector in the return value
;; is the list of parameters for the base-class constructor.
(fact
 (let [props (HashMap.)
       ports (HashMap.)]
   (.put props "hostname" "foo")
   (.put props "ports" ports)
   (.put ports "amqp" 1234)
   (.put ports "other" 4321)
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
    ;; Publish on the default exchange, with the queue name as a routing key
    (lb/publish ..chan.. "" ..name.. msg {:content-type "application/octet-stream"}) => irrelevant)))


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

;; Calling the `.stop` method of the returned `Stoppable` causes the
;; consumer to be canceled.
(fact
 (let [driver (MockObj. {:chan ..chan..})
       queue (qs/-defineQueue driver ..name..)
       cb (reify QueueService$Callback
            (handleTask [this data]))
       stoppable (.register queue cb)]
   (.stop stoppable)) => nil
 (provided
  (lq/declare ..chan.. ..name.. irrelevant) => irrelevant
  (lc/subscribe ..chan.. ..name.. irrelevant) => ..constag..
  (lb/cancel ..chan.. ..constag..) => irrelevant))

;; ## Callback Wrapper

;; The `callback-wrapper` function takes a `QueueService$Callback`
;; object, an ack function (intended to be `lb/ack`), a nack function
;; (intended to be `lb/nack`) and a log function to log potential
;; errors. It returns a callback function applicable to langohr. When
;; this function is called, the callback's `.handleTask` method is
;; called with the same data.
(fact
 (let [calls (atom [])
       acks (atom [])
       nacks (atom [])
       logs (atom [])
       cb (reify QueueService$Callback
            (handleTask [this task]
              (swap! calls conj task)))
       myack #(swap! acks conj [%1 %2])
       mynack #(swap! nacks conj [%1 %2])
       mylog #(swap! logs conj %)
       wrapped (qs/callback-wrapper cb myack mynack mylog)
       bytes (.getBytes "foobar")]
   (wrapped ..chan.. {:delivery-tag ..deltag..} bytes) => nil
   @calls => [bytes]
   ;; After completion, the message is acknowledged.
   @acks => [[..chan.. ..deltag..]]
   @nacks => []
   @logs => []))

;; At the event that the callback throws a `RecoverableError`, the
;; message is `nack`ed instead of beind acknowledged.
(fact
 (let [acks (atom [])
       nacks (atom [])
       logs (atom [])
       cb (reify QueueService$Callback
            (handleTask [this task]
              (throw (RecoverableError. "boo"))))
       myack #(swap! acks conj [%1 %2])
       mynack #(swap! nacks conj [%1 %2])
       mylog #(swap! logs conj %)
       wrapped (qs/callback-wrapper cb myack mynack mylog)
       bytes (.getBytes "foobar")]
   (wrapped ..chan.. {:delivery-tag ..deltag..} bytes) => nil
   @acks => []
   @nacks => [[..chan.. ..deltag..]]
   (first @logs) => (partial instance? RecoverableError)))

;; Any other exception is considered unrecoverable, and the message is
;; acknowledged, to make sure the crash does not repeat itself.
(fact
 (let [acks (atom [])
       nacks (atom [])
       logs (atom [])
       cb (reify QueueService$Callback
            (handleTask [this task]
              (throw (Exception. "boo"))))
       myack #(swap! acks conj [%1 %2])
       mynack #(swap! nacks conj [%1 %2])
       mylog #(swap! logs conj %)
       wrapped (qs/callback-wrapper cb myack mynack mylog)
       bytes (.getBytes "foobar")]
   (wrapped ..chan.. {:delivery-tag ..deltag..} bytes) => nil
   @acks => [[..chan.. ..deltag..]]
   @nacks => []
   (first @logs) => (partial instance? Exception)))
