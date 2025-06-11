(ns nyancad.mosaic.build-hooks
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]))

(defn sh!
  "Execute a shell command, throwing an exception on failure."
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh args)]
    (when (not= 0 exit)
      (throw (ex-info (str "Command failed: " (pr-str args))
                      {:command args :exit-code exit :stdout out :stderr err})))))

(defn marimo-export-hook
  "Build hook that exports marimo notebook after build completion"
  {:shadow.build/stage :flush}
  [build-state & args]
  (let [build-id (:shadow.build/build-id build-state)]
    (println (str "[" build-id "] Running marimo export..."))
    
    (sh! "rm" "-rf" "public/notebook")
    (sh! "marimo" "export" "html-wasm" "src/marimo/notebook.py"
         "-o" "public/notebook/index.html"
         "--mode" "edit")
    (sh! "sed" "-i" "s|</title>|</title>\\n<script type=\"module\" src=\"../js/filestore.js\"></script>|" 
         "public/notebook/index.html")
    (sh! "sed" "-i" "/display: none !important;/d" 
         "public/notebook/index.html")
    
    (println (str "[" build-id "] Marimo export and head injection completed successfully"))
    
    ;; Always return the build-state unchanged
    build-state))
