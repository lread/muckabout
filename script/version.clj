(ns version
  "Some tag/version related fns"
  (:require [babashka.tasks :as t]
            [clojure.string :as string]
            [version-clj.core :as v]))

(def version-tag-prefix "v")
(def legacy-version-tag-prefix "Release-")

(defn- raw-tags[]
  (->>  (t/shell {:out :string}
                   "git ls-remote --tags --refs")
          :out
          string/split-lines))

(defn- parse-raw-tag [raw-tag-line]
  (let [pattern (re-pattern (str "refs/tags/((?:" legacy-version-tag-prefix "|" version-tag-prefix ")(\\d+\\..*))"))]
    (some->> (re-find pattern raw-tag-line)
             rest
             (zipmap [:tag :version]))))

(defn- most-recent-tag [parsed-tags]
  (->>  parsed-tags
        (sort-by :version v/version-compare)
        reverse
        first
        :tag))

(defn last-release-tag []
  (->>  (raw-tags)
        (keep parse-raw-tag)
        (most-recent-tag)))

(defn tag->version [ci-tag]
  (and (string/starts-with? ci-tag version-tag-prefix)
       (string/replace-first ci-tag version-tag-prefix "")))

(defn version->tag [version]
  (str version-tag-prefix version))

(defn ref-version [version]
  (str version-tag-prefix version))

(comment

  (parse-raw-tag "boo refs/tags/Release-1.8")
  ;; => {:tag "Release-1.8", :version "1.8"}

  (parse-raw-tag "boo refs/tags/v1.8")
  ;; => {:tag "v1.8", :version "1.8"}

  (parse-raw-tag "boo refs/tags/1.8")
  ;; => nil

  (parse-raw-tag "boo refs/tags/nope")
  ;; => nil

  (most-recent-tag [{:tag "a" :version "0.0.2"}
                    {:tag "b" :version "7.8.9"}
                    {:tag "c" :version "0.0.4"}
                    {:tag "d" :version "1.2.3"}])
  ;; => "b"
)
