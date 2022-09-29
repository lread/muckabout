(ns clj-http.lite.test-util.server-state
  (:require [clojure.java.io :as io]))

(def server-state-file (io/file "target/http-server.edn"))
