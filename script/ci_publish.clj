(ns ci-publish
  "Publish work we invoke from GitHub Actions.
  Separated out here:
  - to make it clear what is happening on ci
  - rate of change here should be less/different than publish"
  (:require [babashka.tasks :as t]
            [clojure.edn :as edn]
            [lread.status-line :as status]
            [version]))

(def changelog-url "https://github.com/lread/muckabout/blob/main/changelog.adoc")

(defn- assert-on-ci []
  (when (not (System/getenv "CI"))
    (status/die 1 "to be run from continuous integration server only")))

(defn- ci-tag []
  (when (= "tag" (System/getenv "GITHUB_REF_TYPE"))
    (System/getenv "GITHUB_REF_NAME")))

(defn- analyze-ci-tag []
  (let [tag (ci-tag)]
    (if (not tag)
      (status/die 1 "CI tag not found")
      (if-let [version (version/tag->version tag)]
        {:tag tag
         :version version
         :ref-version (version/ref-version version)}
        (status/die 1 "Not recognized as version tag: %s" tag)))))

(defn clojars-deploy []
  (assert-on-ci)
  (analyze-ci-tag) ;; fail if non or no version tag
  (t/shell "clojure -T:build deploy"))

(defn github-create-release []
  (assert-on-ci)
  (let [{:keys [tag ref-version]} (analyze-ci-tag)]
    (t/shell "gh release create"
             tag
             "--title" ref-version
             "--notes" (format "[Changelog](%s#%s)" changelog-url ref-version))))

(defn- cljdoc-request-build []
  (assert-on-ci)
  (let [{:keys [version]} (analyze-ci-tag)
        project (-> "deps.edn" slurp edn/read-string :aliases :neil :project name)]
    (status/line :head "Informing cljdoc of %s version %s" project version)
    (assert-on-ci)
    (let [exit-code (->  (t/shell {:continue true}
                                  "curl" "-X" "POST"
                                  "-d" (str "project=" project)
                                  "-d" (str "version=" version)
                                  "https://cljdoc.org/api/request-build2")
                         :exit)]
      (when (not (zero? exit-code))
        (status/line :warn (str  "Informing cljdoc did not seem to work, exited with " exit-code))))))
