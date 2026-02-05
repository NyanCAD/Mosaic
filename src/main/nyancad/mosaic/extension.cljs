; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.extension
  "VSCode extension host: SchematicEditorProvider for .nyancir files.
   Bridges the VSCode text document model with the webview's JsAtom."
  (:require ["vscode" :as vscode]
            [cljs.core.async :refer [go go-loop <! >! chan]]
            [cljs.core.async.interop :refer-macros [<p!]]))

(defn get-nonce []
  (let [chars "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        buf (js/Uint8Array. 32)]
    (js/crypto.getRandomValues buf)
    (apply str (map #(aget chars (mod % (.-length chars))) buf))))

(defn get-html
  "Generate webview HTML that loads the schematic editor.
   Injects the document content via a hidden input element."
  [^js context ^js document ^js webview]
  (let [ext-uri (.-extensionUri context)
        js-uri (fn [file]
                 (.asWebviewUri webview
                   (.joinPath vscode/Uri ext-uri "out" file)))
        nonce (get-nonce)
        csp (str "default-src 'none';"
                 " style-src " (.-cspSource webview) " 'unsafe-inline';"
                 " script-src 'nonce-" nonce "';"
                 " img-src " (.-cspSource webview) " data:;")]
    (str "<!DOCTYPE html>
<html>
<head>
  <meta charset='UTF-8'>
  <meta http-equiv='Content-Security-Policy' content='" csp "'>
  <meta name='viewport' content='width=device-width, initial-scale=1.0'>
  <link rel='stylesheet' href='" (js-uri "style.css") "'>
</head>
<body>
  <input type='hidden' id='document' value='" (js/encodeURIComponent (.getText document)) "'>
  <div class='mosaic-app mosaic-editor'></div>
  <script nonce='" nonce "' src='" (js-uri "common.js") "'></script>
  <script nonce='" nonce "' src='" (js-uri "editor.js") "'></script>
</body>
</html>")))

(defn- apply-updates
  "Apply JSON edit operations to a VSCode WorkspaceEdit.
   Extracted from go-loop so ^js hints survive (go macro strips them)."
  [^js document ^js edit updates]
  (doseq [^js up updates]
    (let [offset (.-offset up)
          length (.-length up)
          content (.-content up)
          old-content (.-oldcontent up)
          start (.positionAt document offset)
          end (.positionAt document (+ offset (if old-content
                                                (.-length old-content)
                                                length)))]
      (.replace edit (.-uri document) (vscode/Range. start end) (or content "")))))

(deftype SchematicEditorProvider [^js context]
  Object
  (resolveCustomTextEditor [^js this ^js document ^js webviewpanel token]
    (let [^js ctx (.-context this)
          ^js webview (.-webview webviewpanel)
          edit-queue (chan 32)
          version (atom 1)]
      ;; Configure webview
      (set! (.-options webview)
            #js{:enableScripts true
                :localResourceRoots #js[(.joinPath vscode/Uri (.-extensionUri ctx) "out")]})
      (set! (.-html webview) (get-html ctx document webview))

      ;; Process edit queue sequentially to avoid conflicts
      (go-loop []
        (let [{:keys [update ver]} (<! edit-queue)]
          (when update
            (let [edit (vscode/WorkspaceEdit.)]
              (apply-updates document edit update)
              (<p! (.. vscode/workspace (applyEdit edit)))
              ;; Notify webview of the version
              (.postMessage webview #js{:type "applied" :version ver}))
            (recur))))

      ;; Listen for edits from the webview
      (.onDidReceiveMessage webview
        (fn [message]
          (when (= (.-type message) "update")
            (let [ver (swap! version inc)]
              (go (>! edit-queue {:update (.-update message) :ver ver}))))))

      ;; Listen for external changes to the text document
      (let [disposable
            (.. vscode/workspace
                (onDidChangeTextDocument
                  (fn [^js e]
                    (when (= (.-uri (.-document e)) (.-uri document))
                      (let [ver (swap! version inc)]
                        ;; Re-read the full document and send to webview
                        ;; This handles undo/redo, external file edits, etc.
                        (.postMessage webview
                          #js{:type "update"
                              :version ver
                              :fullDocument (.getText document)}))))))]
        ;; Clean up on panel dispose
        (.. webviewpanel -onDidDispose
            (fn []
              (.dispose disposable)))))))

(defn register-schematic-provider [^js context]
  (.registerCustomEditorProvider
    vscode/window
    "Mosaic.schematic"
    (SchematicEditorProvider. context)
    #js{:supportsMultipleEditorsPerDocument false
        :webviewOptions #js{:retainContextWhenHidden true}}))

(defn activate
  "Extension activation - register the custom editor provider."
  [^js context]
  (js/console.log "Mosaic extension activating")
  (.. context -subscriptions
      (push (register-schematic-provider context))))

(defn deactivate []
  (js/console.log "Mosaic extension deactivating"))

(def exports #js{:activate activate :deactivate deactivate})
