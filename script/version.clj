(ns version
  "Some tag/version related fns"
  (:require [babashka.tasks :as t]
            [clojure.string :as string]))

(def version-tag-prefix "Release-")

(defn last-release-tag []
  (let [pattern (re-pattern (str "refs/tags/(" version-tag-prefix "\\d+\\..*)"))]
    (->>  (t/shell {:out :string}
                   "git ls-remote --tags --refs --sort='-version:refname'")
          :out
          string/split-lines
          (keep #(last (re-find pattern %)))
          first)))

(defn tag->version [ci-tag]
  (and (string/starts-with? ci-tag version-tag-prefix)
       (string/replace-first ci-tag version-tag-prefix "")))

(defn version->tag [version]
  (str version-tag-prefix version))

(defn ref-version [version]
  (str "v" version))
