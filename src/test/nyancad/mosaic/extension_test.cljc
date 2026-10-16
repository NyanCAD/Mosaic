; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.extension-test
  "Message-router contract for `read-file`: an absolute packaged symbol path is
   read verbatim, while relative names still resolve against the workspace
   root. See specs/on_demand_symbol_generation.md (Mosaic Bridge)."
  (:require [cljs.test :refer [deftest is testing async]]
            ["vscode" :as vscode]
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

;; @tags gsch symbol sidecar, mosaic symbol maker
(defn- preview-webview
  "Webview stand-in that answers the sidecar's preview request."
  [preview]
  #js{:postMessage
      (fn [^js m]
        (js/setTimeout
         #(#'ext/deliver-response! #js{:requestId (.-requestId m)
                                       :value preview})
         0))})

;; @tags gsch symbol sidecar, mosaic symbol maker
(defn- run-sidecar-save
  "Run save-svg-sidecar! with `read-file` as the existing sidecar reader and
   call `check` with every [path content] the save wrote."
  [read-file check done]
  (let [fs (.. vscode -workspace -fs)
        orig-read (.-readFile fs)
        orig-write (.-writeFile fs)
        writes (atom [])]
    (set! (.-readFile fs) read-file)
    (set! (.-writeFile fs)
          (fn [^js uri bytes]
            (swap! writes conj [(.-path uri)
                                (.decode (js/TextDecoder. "utf-8") bytes)])
            (js/Promise.resolve nil)))
    (#'ext/save-svg-sidecar! (preview-webview "<svg>preview</svg>")
                             (vscode/Uri.file "/workspace/schematics")
                             "amp.gsch")
    (js/setTimeout
     (fn []
       (set! (.-readFile fs) orig-read)
       (set! (.-writeFile fs) orig-write)
       (check @writes)
       (done))
     50)))

;; @tags gsch symbol sidecar, mosaic symbol maker
(deftest sidecar-save-keeps-symbol-maker-drawing
  (testing "an existing sidecar carrying symbol maker metadata is never overwritten"
    (async done
      (run-sidecar-save
       (fn [_]
         (js/Promise.resolve
          (.encode (js/TextEncoder.)
                   "<svg><metadata id=\"gfp-symbol-maker\">{}</metadata></svg>")))
       (fn [writes] (is (empty? writes)))
       done))))

;; @tags gsch symbol sidecar, mosaic symbol maker
(deftest sidecar-save-replaces-auto-preview
  (testing "an auto-written preview sidecar is refreshed on save"
    (async done
      (run-sidecar-save
       (fn [_] (js/Promise.resolve (.encode (js/TextEncoder.) "<svg>old</svg>")))
       (fn [writes]
         (is (= [["/workspace/schematics/amp.gsch.svg" "<svg>preview</svg>"]]
                writes)))
       done))))

;; @tags gsch symbol sidecar, mosaic symbol maker
(deftest sidecar-save-writes-missing-sidecar
  (testing "a missing sidecar is written"
    (async done
      (run-sidecar-save
       (fn [_] (js/Promise.reject (js/Error. "FileNotFound")))
       (fn [writes]
         (is (= [["/workspace/schematics/amp.gsch.svg" "<svg>preview</svg>"]]
                writes)))
       done))))
