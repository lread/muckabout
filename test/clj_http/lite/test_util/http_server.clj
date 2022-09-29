(ns clj-http.lite.test-util.http-server
  (:require [babashka.fs :as fs]
            [clj-http.lite.util :as util]
            [clj-http.lite.test-util.server-state :refer [server-state-file]]
            [clojure.string :as str]
            [ring.adapter.jetty :as ring])
  (:import (org.eclipse.jetty.server Server ServerConnector)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(defn b64-decode [^String s]
  (when s
    (-> (Base64/getDecoder)
        (.decode s)
        util/utf8-string)))

(defn handler [req]
  (condp = [(:request-method req) (:uri req)]
    [:get "/get"]
    {:status 200 :body "get"}
    [:head "/head"]
    {:status 200}
    [:get "/content-type"]
    {:status 200 :body (:content-type req)}
    [:get "/header"]
    {:status 200 :body (get-in req [:headers "x-my-header"])}
    [:post "/post"]
    {:status 200 :body (slurp (:body req))}
    [:get "/redirect"] {:status 302 :headers {"Location" "/get"}}
    [:get "/error"]
    {:status 500 :body "o noes"}
    [:get "/timeout"]
    (do
      (Thread/sleep 100)
      {:status 200 :body "timeout"})
    [:delete "/delete-with-body"]
    {:status 200 :body "delete-with-body"}
    ;; minimal to support testing
    [:get "/basic-auth"]
    (let [cred (some->> (get (:headers req) "authorization")
                        (re-find #"^Basic (.*)$")
                        last
                        b64-decode)
      [user pass] (and cred (str/split cred #":"))]
      (if (and (= "username" user) (= "password" pass))
        {:status 200 :body "welcome"}
        {:status 401 :body "denied"}))
    [:get "/stop"]
    (do
      (future (Thread/sleep 1000)
              (println "http-server exiting")
              (System/exit 0))
      {:status 200 :body "bye"})))

(defn- port-for-protocol [^Server s p]
  (some (fn [^ServerConnector c]
          (when (str/starts-with? (str/lower-case (.getDefaultProtocol c)) p)
            (.getLocalPort c)))
        (.getConnectors s)))

(defn run
  "ex. clojure -X:http-server"
  [_opts]
  (let [^Server s (ring/run-jetty handler {:port         0 ;; Use a free port
                                           :join?        false
                                           :ssl-port     0 ;; Use a free port
                                           :ssl?         true
                                           :keystore     "test-resources/keystore"
                                           :key-password "keykey"})]
    (println "server started")
    (fs/create-dirs "target")
    (let [ports {:http-port (port-for-protocol s "http/")
                 :https-port (port-for-protocol s "ssl")}
          ;; write to temp then move to avoid chance of watcher reading partially written file
          tmp-file (fs/create-temp-file {:path "target"
                                         :prefix "http-server"
                                         :suffix ".edn"})]
      (spit (fs/file tmp-file) ports)
      (fs/move tmp-file server-state-file {:atomic-move true
                                           :replace-existing true}))))
