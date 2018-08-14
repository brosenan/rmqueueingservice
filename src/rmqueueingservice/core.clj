(ns rmqueueingservice.core
  (:require [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.exchange :as le]
            [langohr.queue :as lq]
            [langohr.basic :as lb]
            [langohr.consumers :as lc])
  (:import injectthedriver.interfaces.QueueService$Queue))

(defn -init [props]
  (let [props (into {} (for [[k v] props]
                         [(keyword k) v]))
        conn (rmq/connect props)
        chan (lch/open conn)]
    [[] {:conn conn
         :chan chan}]))

(defn -defineQueue [this name]
  (lq/declare (-> this .state :chan) name {:exclusive false :auto-delete false})
  (reify QueueService$Queue
    (enqueue [this' task]
      (lb/publish (-> this .state :chan) name task {:content-type "application/octet-stream"}))))
