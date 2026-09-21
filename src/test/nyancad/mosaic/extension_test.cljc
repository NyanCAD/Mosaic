; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.extension-test
  "Message-router contract for `read-file`: an absolute packaged symbol path is
   read verbatim, while relative names still resolve against the workspace
   root. See specs/on_demand_symbol_generation.md (Mosaic Bridge)."
  (:require [cljs.test :refer [deftest is testing async]]
            [nyancad.mosaic.extension :as ext]))

;; @tags on-demand symbol generation
(defn- make-webview []
  (let [handler (atom nil)
        messages (atom [])]
    {:webview #js{:onDidReceiveMessage (fn [h] (reset! handler h))
                  :postMessage (fn [m] (swap! messages conj m))}
     :handler handler
     :messages messages}))

;; @tags on-demand symbol generation
(defn- last-content [messages]
  (.-content (last @messages)))

;; @tags on-demand symbol generation
(deftest read-file-resolves-authorized-absolute-and-contained-relative-paths
  (testing "parameterized packaged paths and contained relative paths are readable"
    (let [{:keys [webview handler messages]} (make-webview)
          models-json (js/JSON.stringify
                       (clj->js {"models:ring" {"parameterizations"
                                                {"{\"radius\":10}"
                                                 {"symbol" "/opt/pkg/symbols/ring.svg"}}}}))
          authorized (ext/symbol-paths-from-json models-json)]
      (is (= #{"/opt/pkg/symbols/ring.svg"} authorized))
      (ext/setup-message-router!
       webview (atom {}) #js{:path "/workspace/schematics"}
       nil nil (atom authorized))
      (async done
        (@handler (clj->js {:type "read-file" :requestId 1
                            :filename "/opt/pkg/symbols/ring.svg"}))
        (js/setTimeout
         (fn []
           (is (= "content:/opt/pkg/symbols/ring.svg" (last-content messages))
               "an absolute path is read verbatim")
           (@handler (clj->js {:type "read-file" :requestId 2
                               :filename "ring.svg"}))
           (js/setTimeout
            (fn []
              (is (= "content:/workspace/schematics/ring.svg"
                     (last-content messages))
                  "a relative name resolves against the workspace root")
              (done))
            20))
         20)))))

;; @tags on-demand symbol generation
(deftest read-file-rejects-traversal-and-unauthorized-absolute-paths
  (testing "a webview cannot use read-file to escape the workspace or symbol allowlist"
    (let [{:keys [webview handler messages]} (make-webview)]
      (ext/setup-message-router!
       webview (atom {}) #js{:path "/workspace/schematics"})
      (@handler (clj->js {:type "read-file" :requestId 1
                          :filename "../secret"}))
      (is (nil? (last-content messages)))
      (@handler (clj->js {:type "read-file" :requestId 2
                          :filename "/etc/passwd"}))
      (is (nil? (last-content messages))))))

;; @tags on-demand symbol generation
(deftest open-file-rejects-traversal-and-absolute-paths
  (testing "open-file never writes outside the workspace"
    (let [{:keys [webview handler]} (make-webview)]
      (ext/setup-message-router!
       webview (atom {}) #js{:path "/workspace/schematics"})
      (is (nil? (@handler (clj->js {:type "open-file"
                                    :filename "../secret.gsch"}))))
      (is (nil? (@handler (clj->js {:type "open-file"
                                    :filename "/tmp/secret.gsch"})))))))
