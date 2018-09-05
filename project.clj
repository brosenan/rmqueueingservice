(defproject brosenan/rmqueueingservice "0.1.4"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [brosenan/injectthedriver "0.0.4-SNAPSHOT"]
                 [com.novemberain/langohr "5.0.0"]]
  :profiles {:dev {:dependencies [[midje "1.9.2"]]
                   :plugins [[lein-midje "3.2.1"]
                             [brightnorth/uberjar-deploy "1.0.1"]]}}
  :aliases {"jar"    "uberjar"
            "deploy" ["do" ["uberjar"] ["uberjar-deploy"]]}
  :aot :all
  :deploy-repositories [["releases" :clojars]])
