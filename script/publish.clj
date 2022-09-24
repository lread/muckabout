(ns publish
  (:require [babashka.tasks :as t]
            [lread.status-line :as status]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [version]))

(def github-coords "lread/muckabout")
(def changelog-fname "changelog.adoc")
(def readme-fname "README.adoc")

(defn main-branch? []
  (let [current-branch (->> (t/shell {:out :string} "git rev-parse --abbrev-ref HEAD")
                            :out
                            string/trim)]
    (= "main" current-branch)))

(defn uncommitted-code? []
  (-> (t/shell {:out :string}
               "git status --porcelain")
      :out
      string/trim
      seq))

(defn unpushed-commits? []
  (let [{:keys [exit :out]} (t/shell {:continue true :out :string}
                                      "git cherry -v")]
    (if (zero? exit)
      (-> out string/trim seq)
      (throw (ex-info "Failed to check for unpushed commits" {})))))

(defn- analyze-changelog
  "Certainly not fool proof, but should help for common mistakes"
  []
  (let [content (slurp changelog-fname)
        valid-attrs ["[minor breaking]" "[breaking]"]
        [_ attr desc :as match] (re-find #"(?ims)^== Unreleased ?(.*?)$(.*?)(== v\d|\z)" content)]
    (if (not match)
      [{:error :section-missing}]
      (cond-> []
        (and attr
             (not (string/blank? attr))
             (not (contains? (set valid-attrs) attr)))
        (conj {:error :suffix-invalid :valid-attrs valid-attrs :found attr})

        (string/blank? desc)
        (conj {:error :content-missing})))))

(defn release-checks []
  (let [changelog-findings (reduce (fn [acc n] (assoc acc (:error n) n))
                                   {}
                                   (analyze-changelog))]
    [{:check "on main branch"
      :result (if (main-branch?) :pass :fail)}
     {:check "no uncommitted code"
      :result (if (uncommitted-code?) :fail :pass)}
     {:check "no unpushed commits"
      :result (if (unpushed-commits?) :fail :pass)}
     {:check "changelog has unreleased section"
      :result (if (:section-missing changelog-findings) :fail :pass)}
     {:check "changelog unreleased section attributes valid"
      :result (cond
                (:section-missing changelog-findings) :skip
                (:suffix-invalid changelog-findings) :fail
                :else :pass)
      :msg (when-let [{:keys [valid-attrs found]} (:suffix-invalid changelog-findings)]
             (format "expected attributes to absent or one of %s, but found: %s" (string/join ", " valid-attrs) found))}
     {:check "changelog unreleased section has content"
      :result (cond
                (:section-missing changelog-findings) :skip
                (:content-missing changelog-findings) :fail
                :else :pass)}]))

(defn bump-version!
  "bump version stored in deps.edn"
  []
  (t/shell "bb neil version patch --no-tag"))

(defn version-string []
  (-> (edn/read-string (slurp "deps.edn"))
      :aliases :neil :project :version))

(defn- update-file! [fname desc match replacement]
  (let [old-content (slurp fname)
        new-content (string/replace-first old-content match replacement)]
    (if (= old-content new-content)
      (status/die 1 "Expected to %s in %s" desc fname)
      (spit fname new-content))))

(defn- update-readme! [version]
  (status/line :detail "Applying version %s to readme" version)
  (update-file! readme-fname
                "update :lib-version: adoc attribute"
                #"(?m)^(:lib-version: )(.*)$"
                (str "$1"version)))

(defn- yyyy-mm-dd-now-utc []
  (-> (java.time.Instant/now) str (subs 0 10)))

(defn- update-changelog! [version release-tag last-release-tag]
  (status/line :detail "Applying version %s to changelog" version)
  (update-file! changelog-fname
                "update unreleased header"
                #"(?ims)^== Unreleased(.*?)($.*?)(== v\d|\z)"
                (str
                  ;; add Unreleased section for next released
                  "== Unreleased\n\n"
                  ;; replace "Unreleased" with actual version
                  "== v" version
                 ;; followed by any attributes
                  "$1"
                  ;; followed by datestamp (local time is fine)
                  (str " - " (yyyy-mm-dd-now-utc))
                  ;; followed by an AsciiDoc anchor for easy referencing
                  (str " [[v" version  "]]")
                  ;; followed by section content
                  "$2"
                  ;; followed by link to commit log
                  (when last-release-tag
                    (str
                      "https://github.com/" github-coords "/compare/"
                      last-release-tag
                      "\\\\..."  ;; single backslash is escape for AsciiDoc
                      release-tag
                      "[commit log]\n\n"))
                  ;; followed by next section indicator
                  "$3")))

(defn- commit-changes! [version]
  (t/shell "git add deps.edn changelog.adoc README.adoc")
  (t/shell "git commit -m" (str "Release: apply version " version)))

(defn- tag! [tag version]
  (t/shell "git tag" tag "-m" (str "For release: " version)))

(defn- push! []
  (t/shell "git push"))

(defn- push-tag! [tag]
  (t/shell "git push origin" tag))

(defn -main [& _args]
  (status/line :head "Performing release checks")
  (let [check-results (release-checks)
        passed? (every? #(= :pass (:result %)) check-results)]
    (doseq [{:keys [check result msg]} check-results]
      (status/line :detail "%s %s"
                   (case result
                     :pass "✓"
                     :fail "x"
                     :skip "~")
                   check)
      (when msg
        (status/line :detail "  > %s" msg)))
   (if (not passed?)
      (status/die 1 "Release checks failed")
      (do
        (status/line :head "Calculating versions")
        (bump-version!)
        (let [last-release-tag (version/last-release-tag)
              version (version-string)
              release-tag (version/version->tag version)]
          (status/line :detail "Release version: %s" version)
          (status/line :detail "Release tag: %s" release-tag)
          (status/line :detail "Last release tag: %s" last-release-tag)
          (status/line :head "Updating docs")
          (update-readme! version)
          (update-changelog! version release-tag last-release-tag)
          (status/line :head "Committing changes")
          (commit-changes! version)
          (status/line :head "Tagging & pushing")
          (tag! release-tag version)
          (push!)
          (push-tag! release-tag)
          (status/line :detail "\nLocal work done.")
          (status/line :head "Remote work")
          (status/line :detail "The remainging work will be triggered by the release tag on CI:")
          (status/line :detail "- Publish a release jar to clojars")
          (status/line :detail "- Creating a GitHub release"))))))

;; default action when executing file directly
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

