(ns publish
  (:require [babashka.tasks :as t]
            [lread.status-line :as status]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [rewrite-clj.zip :as z]))

(def github-coords "lread/muckabout")
(def version-tag-prefix "Release-")
(def changelog-fname "changelog.adoc")
(def readme-fname "README.adoc")

(defn- last-release-tag []
  (->  (t/shell {:out :string}
                (format "git describe --abbrev=0 --match %s[0-9]*" version-tag-prefix))
       :out
       string/trim))

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
  "Bump :release in version.edn file while preserving any formatting and comments"
  []
  (spit "version.edn"
        (-> "version.edn"
            z/of-file
            (z/find-value z/next :release)
            z/right
            (z/edit inc)
            z/root-string)))

(defn version-string []
  (let [{:keys [major minor release qualifier]} (-> "version.edn"
                                                    slurp
                                                    edn/read-string)]
    (format "%s.%s.%s%s"
            major minor release (if qualifier
                                  (str "-" qualifier)
                                  ""))))
(defn release-tag [version]
  (str version-tag-prefix version))

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
                  (str " - " (java.time.LocalDate/now))
                  ;; followed by an AsciiDoc anchor for easy referencing
                  (str " [[v" version  "]]")
                  ;; followed by section content
                  "$2"
                  ;; followed by link to commit log
                  (when last-release-tag
                    (str
                      "\nhttps://github.com/" github-coords "/compare/"
                      last-release-tag
                      "\\\\..."  ;; single backslash is escape for AsciiDoc
                      release-tag
                      "[commit log]\n\n"))
                  ;; followed by next section indicator
                  "$3")))

(defn commit-changes! [version]
  (status/line :detail "Committing changes to git repo")
  (t/shell "git add version.edn changelog.adoc README.adoc")
  (t/shell "git commit -m" (str "Release: updates for version " version) ))

;; TODO: Do we want/need an annotated tag?
(defn tag! [tag]
  (status/line :detail "Tagging git repo with: %s" tag)
  (t/shell "git tag" tag))

(defn push! []
  (status/line :detail "Pushing git changes")
  (t/shell "git push"))

(defn push-tag! [tag]
  (status/line :detail "Pushing git tag: %s" tag)
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
        (status/line :head "Releasing")
        (bump-version!)
        (let [last-release-tag (last-release-tag)
              version (version-string)
              release-tag (release-tag version)]
          (status/line :detail "Release version: %s" version)
          (status/line :detail "Release tag: %s" release-tag)
          (status/line :detail "Last release tag: %s" last-release-tag)
          (update-readme! version)
          (update-changelog! version release-tag last-release-tag)
          (commit-changes! version)
          (tag! release-tag)
          (push!)
          (push-tag! release-tag)
          (status/line :detail "\nDone. The pushed tag will trigger remaining work up on CI."))))))

;; default action when executing file directly
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))