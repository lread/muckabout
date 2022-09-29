(ns test-jvm
  (:require [babashka.cli :as cli]
            [babashka.tasks :as t]
            [lread.status-line :as status]))

(defn -main [& args]
  (let [valid-clojure-versions ["1.8" "1.9" "1.10" "1.11"]
        spec {:clj-version
              {:ref "<version>"
               :desc "The Clojure version to test against."
               :coerce :string
               :default-desc "1.8"
               ;; don't specify :default, we want to know if the user passed this option in
               :validate
               {:pred (set valid-clojure-versions)
                :ex-msg (fn [_m]
                          (str "--clj-version must be one of: " valid-clojure-versions))}}}
        opts (cli/parse-opts args {:spec spec})
        clj-version (:clj-version opts)
        runner-args (if-not clj-version
                      args
                      (loop [args args
                             out-args []]
                        (if-let [a (first args)]
                          (if (re-matches #"(--|:)clj-version" a)
                            (recur (drop 2 args) out-args)
                            (recur (rest args) (conj out-args a)))
                          out-args)))
        clj-version (or clj-version "1.8")]

    (if (:help opts)
      (do
        (status/line :head "bb task option help")
        (println (cli/format-opts {:spec spec}))
        (status/line :head "test-runner option help")
        (t/clojure "-M:test --test-help"))
      (do
        (println "Testing against Clojure" clj-version)
        (apply t/clojure (format "-M:%s:test" clj-version) runner-args)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
