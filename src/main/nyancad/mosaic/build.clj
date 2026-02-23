(ns nyancad.mosaic.build
  "Build commands for Mosaic project"
  (:require [clojure.java.shell :as shell]
            [shadow.cljs.devtools.api :as shadow]))

(defn sh!
  "Execute a shell command, throwing an exception on failure."
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh args)]
    (when (not= 0 exit)
      (throw (ex-info (str "Command failed: " (pr-str args))
                      {:command args :exit-code exit :stdout out :stderr err})))
    out))

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
  (sh! "bash" "src/bash/copy-vscode-assets.sh"))

(defn package-vscode
  "Build and package the VSCode extension into a .vsix file.
   Requires @vscode/vsce: npm install in vscode-ext/ first."
  []
  (release-vscode)
  (sh! "npx" "vsce" "package" "--no-dependencies" :dir "vscode-ext"))
