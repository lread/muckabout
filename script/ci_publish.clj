(ns ci-publish
  (:require [babashka.tasks :as t]
            [lread.status-line :as status]
            [version]))

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
      (if-let [version (version/tag->version ci-tag)]
        {:tag tag
         :ref-version (version/ref-version version)}
        (status/die 1 "Not recognized as version tag: %s" tag)))))

(defn clojars-deploy []
  (assert-on-ci)
  (analyze-ci-tag) ;; fail if non or no version tag
  (t/shell "lein deploy clojars"))

(defn github-create-release[]
  (assert-on-ci)
  (let [{:keys [tag ref-version]} (analyze-ci-tag)]
    (t/shell "gh release create"
             tag
             "--title" ref-version
             "--notes" (format "[change log](/changelog.adoc#%s)" ref-version))))
