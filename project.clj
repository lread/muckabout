;; PROJECT_VERSION is set by CI scripting to actual release version
(defproject com.github.lread/muckabout (or (System/getenv "PROJECT_VERSION") "0.0.1")
  :description "Muckabout"
  :url "https://github.com/lread/muckabout"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "Same as Clojure"}
  ;; Emit warnings on all reflection calls.
  :deploy-repositories [["clojars" {:url "https://repo.clojars.org"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]

  :global-vars {*warn-on-reflection* true}
  :source-paths ["src"]
  :java-source-paths ["src"]
  :dependencies
  [[org.yaml/snakeyaml "1.32"]
   [org.flatland/ordered "1.5.9"]]
  :profiles {:provided {:dependencies [[org.clojure/clojure "1.10.1"]]}})
