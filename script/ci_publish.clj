(ns ci-publish
  "Publish work we invoke from GitHub Actions"
  (:require [babashka.tasks :as t]
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
         :ref-version (version/ref-version version)}
        (status/die 1 "Not recognized as version tag: %s" tag)))))

(defn clojars-deploy []
  (assert-on-ci)
  (analyze-ci-tag) ;; fail if non or no version tag
  (t/shell "clojure -T:build deploy"))

(defn github-create-release[]
  (assert-on-ci)
  (let [{:keys [tag ref-version]} (analyze-ci-tag)]
    (t/shell "gh release create"
             tag
             "--title" ref-version
             "--notes" (format "[change log](%s#%s)" changelog-url ref-version))))
