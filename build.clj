(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.github.lread/muckabout)
(def version (str "1.0." (b/git-count-revs {})))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s.jar" (name lib)))

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
  [_]
  (println "install")
  (b/install {:class-dir class-dir
              :lib lib
              :version version
              :basis basis
              :jar-file jar-file}))

(defn tag
  [_]
  (println "tag" version)
  (b/git-process {:git-args (format "tag -a v%s -m muckety-muck" version)})
  (b/git-process {:git-args (format "push origin tag v%s" version)}))

(defn deploy
  [_]
  (println "deploy")
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
