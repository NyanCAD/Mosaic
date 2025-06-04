(ns nyancad.mosaic.build-hooks
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]))

(defn marimo-export-hook
  "Build hook that exports marimo notebook after build completion"
  {:shadow.build/stage :flush}
  [build-state & args]
  (let [build-id (:shadow.build/build-id build-state)
        mode (:shadow.build/mode build-state)]
    (println (str "[" build-id "] Running marimo export..."))

    (let [result (shell/sh "marimo" "export" "html-wasm" "src/notebook.py"
                           "-o" "public/notebook/index.html"
                           "--mode" "edit"
                           "--show-save"
                           "--extra-script" "../js/common.js"
                           "--extra-script" "../js/filestore.js")]
      (if (= 0 (:exit result))
        (println (str "[" build-id "] Marimo export completed successfully"))
        (do
          (println (str "[" build-id "] Marimo export failed with exit code: " (:exit result)))
          (println "STDOUT:" (:out result))
          (println "STDERR:" (:err result))))))

  ;; Always return the build-state unchanged
  build-state)
