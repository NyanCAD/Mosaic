(ns nyancad.mosaic.build
  "Build commands for Mosaic project"
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [shadow.cljs.devtools.api :as shadow]))

(defn sh!
  "Execute a shell command, throwing an exception on failure."
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh args)]
    (when (not= 0 exit)
      (throw (ex-info (str "Command failed: " (pr-str args))
                      {:command args :exit-code exit :stdout out :stderr err})))
    out))

(defn- copy-tree!
  "Copy all files from src-dir to dest-dir, creating directories as needed."
  [src-dir dest-dir]
  (let [src (io/file src-dir)]
    (when (.isDirectory src)
      (doseq [f (file-seq src)
              :when (.isFile f)]
        (let [rel (.relativize (.toPath src) (.toPath f))
              dest (io/file dest-dir (.toString rel))]
          (io/make-parents dest)
          (io/copy f dest))))))

(defn- copy-vscode-assets!
  "Copy webview assets (CSS, icons, fonts) into vscode-ext/out/."
  []
  (let [out-dir "vscode-ext/out"]
    (io/copy (io/file "public/css/style.css")
             (io/file out-dir "style.css"))
    (copy-tree! "public/css/icons" (str out-dir "/icons"))
    (copy-tree! "public/css/fonts" (str out-dir "/fonts"))
    (println "VSCode webview assets copied to" out-dir)))

(defn release
  "Complete release build process"
  []
  (shadow/release :frontend)
  (sh! "bash" "src/bash/marimo-export.sh")
  (sh! "bash" "src/bash/jekyll-build.sh")
  (shadow/release :couchdb)
  (sh! "bash" "src/bash/couchdb-export.sh"))

(defn release-vscode
  "Build VSCode extension (webview frontend + extension host).
   Compiles both shadow-cljs targets and copies webview assets."
  []
  (shadow/release :vscode-webview)
  (shadow/release :vscode-ext)
  (copy-vscode-assets!))

(defn package-vscode
  "Build and package the VSCode extension into a .vsix file.
   Requires @vscode/vsce installed at the project root (npm install)."
  []
  (release-vscode)
  (sh! "npx" "vsce" "package" "--no-dependencies" :dir "vscode-ext"))
