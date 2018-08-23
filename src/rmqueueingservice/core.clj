(ns rmqueueingservice.core
  (:require [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.exchange :as le]
            [langohr.queue :as lq]
            [langohr.basic :as lb]
            [langohr.consumers :as lc])
  (:import (injectthedriver.interfaces QueueService$Queue
                                       Stopable
                                       RecoverableError)))

(defn -init [props]
  (let [props (into {} (for [[k v] props]
                         [(keyword k) v]))
        conn (rmq/connect props)
        chan (lch/open conn)]
    [[] {:conn conn
         :chan chan}]))

(defn callback-wrapper [cb ack nack log]
  (fn [chan {:keys [delivery-tag]} task]
    (try
      (.handleTask cb task)
      (ack chan delivery-tag)
      (catch RecoverableError e
        (log e)
        (nack chan delivery-tag))
      (catch Exception e
        (log e)
        (ack chan delivery-tag)))
    nil))

(defn -defineQueue [this name]
  (let [chan (-> this .state :chan)]
    (lq/declare chan name {:exclusive false :auto-delete false})
    (reify QueueService$Queue
      (enqueue [this' task]
        (lb/publish chan name task {:content-type "application/octet-stream"}))
      (register [this' cb]
        (let [constag (lc/subscribe chan name (callback-wrapper cb lb/ack lb/nack prn))]
          (reify Stopable
            (stop [this']
              (lb/cancel chan constag))))))))


