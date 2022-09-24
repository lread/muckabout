(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.edn :as edn]
            [deps-deploy.deps-deploy :as dd]))

;; babashka/neil-isms
(def project (-> (edn/read-string (slurp "deps.edn"))
                 :aliases :neil :project))
(def lib (:name project))
(def version (:version project))

;; build constants
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn jar
  "Build library jar file.
  Also writes built version to target/built-jar-version.txt for easy peasy pickup by any interested downstream operation.
  We use the optional :version-suffix to distinguish local installs from production releases.
  For example, when testing 3rd party libs against rewrite-clj master we use the suffix: canary. "
  [_]
  (println "jar")
  (b/delete {:path class-dir})
  (b/delete {:path jar-file})
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :scm {:tag (format "v%s" version)}
                :basis basis
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install
  "mimics: lein install"
  [opts]
  (jar opts)
  (println "install")
  (b/install {:class-dir class-dir
              :lib lib
              :version version
              :basis basis
              :jar-file jar-file}))

(defn deploy
  "mimics: lein deploy clojars
  called from CI workflow."
  [opts]
  (jar opts)
  (println "deploy")
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
