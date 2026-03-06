; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.extension
  "VSCode extension host: custom editor providers for .nyancir and .nyanlib files.
   Bridges the VSCode text document model with the webview's JsAtom."
  (:require ["vscode" :as vscode]
            ["path" :as path]
            ["fs" :as fs]
            [cljs.core.async :refer [go go-loop <! >! chan put! promise-chan]]
            [cljs.core.async.interop :refer-macros [<p!]]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- get-nonce []
  (let [chars "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        buf (js/Uint8Array. 32)]
    (js/crypto.getRandomValues buf)
    (apply str (map #(aget chars (mod % (.-length chars))) buf))))

(defn- apply-updates
  "Validate oldcontent and apply JSON edit operations to a VSCode WorkspaceEdit.
   Returns true if all edits were valid and added, false if rejected.
   Extracted from go-loop so ^js hints survive (go macro strips them)."
  [^js document ^js edit updates]
  (let [ops (mapv (fn [^js up]
                    (let [offset (.-offset up)
                          length (.-length up)
                          content (.-content up)
                          old-content (.-oldcontent up)
                          start (.positionAt document offset)
                          end (.positionAt document (+ offset (if old-content
                                                                (.-length old-content)
                                                                length)))
                          range (vscode/Range. start end)]
                      (when (or (nil? old-content)
                                (= old-content (.getText document range)))
                        {:range range :content (or content "")})))
                  updates)]
    (if (every? some? ops)
      (do (doseq [{:keys [range content]} ops]
            (.replace edit (.-uri document) range content))
          true)
      (do (js/console.log "Rejected edit batch: content mismatch")
          false))))

;; ---------------------------------------------------------------------------
;; Atom channel — bidirectional sync between a TextDocument and a JsAtom
;; ---------------------------------------------------------------------------

(defn- create-atom-channel
  "Set up bidirectional sync between a TextDocument and a JsAtom in the webview.
   Webview→Host edits are validated (oldcontent check) and applied via WorkspaceEdit.
   Host→Webview changes are forwarded as incremental contentChanges with document.version.
   Returns {:edit-queue chan, :disposable disposable}."
  [^js webview ^js document group]
  (let [edit-queue (chan 32)]
    ;; Process edit queue with oldcontent validation
    (go-loop []
      (when-let [update (<! edit-queue)]
        (let [edit (vscode/WorkspaceEdit.)]
          (when (apply-updates document edit update)
            (<p! (.. vscode/workspace (applyEdit edit)))))
        (recur)))
    ;; Forward TextDocument changes as incremental edits
    (let [disposable
          (.. vscode/workspace
              (onDidChangeTextDocument
                (fn [^js e]
                  (when (= (.. e -document -uri (toString))
                           (.. document -uri (toString)))
                    (let [^js changes (.-contentChanges e)]
                      (when (pos? (.-length changes))
                        (.postMessage webview
                          #js{:type "update"
                              :group group
                              :version (.. e -document -version)
                              :update (.map changes
                                        (fn [^js c]
                                          #js{:offset (.-rangeOffset c)
                                              :length (.-rangeLength c)
                                              :content (.-text c)}))})))))))]
      {:edit-queue edit-queue
       :disposable disposable})))

;; ---------------------------------------------------------------------------
;; Request/response — send get-state to webview, wait for state-response
;; ---------------------------------------------------------------------------

(defonce ^:private pending-requests (atom {}))

(defn- send-request!
  "Post a get-state message to the webview, return a promise-chan with the response."
  [^js webview key]
  (let [request-id (str (random-uuid))
        ch (promise-chan)]
    (swap! pending-requests assoc request-id ch)
    (.postMessage webview
      #js{:type "get-state"
          :requestId request-id
          :key key})
    ch))

(defn- deliver-response!
  "Deliver a state-response to the pending request channel."
  [^js message]
  (let [request-id (.-requestId message)]
    (when-let [ch (get @pending-requests request-id)]
      (swap! pending-requests dissoc request-id)
      (put! ch (.-value message)))))

;; ---------------------------------------------------------------------------
;; Message router — single onDidReceiveMessage handler per webview
;; ---------------------------------------------------------------------------

(defn- safe-resolve
  "Resolve filename within doc-dir. Rejects paths containing slashes."
  [doc-dir filename]
  (when-not (re-find #"[/\\]" filename)
    (path/join doc-dir filename)))

(defn- setup-message-router!
  "Install a single message handler that dispatches by type.
   atom-channels: atom of map group -> {:edit-queue chan}
   doc-dir: directory path for resolving relative filenames"
  [^js webview atom-channels doc-dir]
  (.onDidReceiveMessage webview
    (fn [^js message]
      (case (.-type message)
        ;; Atom edits — route to the right atom channel by group
        "update"
        (when-let [{:keys [edit-queue]} (get @atom-channels (.-group message))]
          (go (>! edit-queue (.-update message))))

        ;; Read a file relative to document directory
        "read-file"
        (let [request-id (.-requestId message)]
          (if-let [file-path (safe-resolve doc-dir (.-filename message))]
            (go
              (let [content (try (<p! (.. fs/promises (readFile file-path "utf8")))
                                 (catch :default _e nil))]
                (.postMessage webview
                  #js{:requestId request-id
                      :content content})))
            (.postMessage webview
              #js{:requestId request-id
                  :content nil})))

        ;; Open a file in VS Code (create if missing, use custom editor)
        "open-file"
        (when-let [file-path (safe-resolve doc-dir (.-filename message))]
          (let [filename (.-filename message)
                file-uri (vscode/Uri.file file-path)]
            (go
              (when-not (fs/existsSync file-path)
                (<p! (.. fs/promises (writeFile file-path "{}" "utf8"))))
              (let [editor-id (cond
                                (.endsWith filename ".nyancir") "Mosaic.schematic"
                                (.endsWith filename ".nyanlib") "Mosaic.library"
                                :else "default")]
                (<p! (.. vscode/commands (executeCommand "vscode.openWith" file-uri editor-id)))))))

        ;; State response from webview (for get-state requests)
        "state-response"
        (deliver-response! message)

        ;; Unknown — ignore
        nil))))

;; ---------------------------------------------------------------------------
;; HTML generators
;; ---------------------------------------------------------------------------

(defn- get-html
  "Generate webview HTML for the schematic editor.
   models-content is the initial JSON string from models.nyanlib."
  [^js context ^js document ^js webview models-content]
  (let [ext-uri (.-extensionUri context)
        out-uri (fn [file]
                  (.asWebviewUri webview
                    (.joinPath vscode/Uri ext-uri "out" file)))
        nonce (get-nonce)
        csp (str "default-src 'none';"
                 " style-src " (.-cspSource webview) " 'unsafe-inline';"
                 " script-src 'nonce-" nonce "' 'unsafe-eval';"
                 " img-src " (.-cspSource webview) " data:;")]
    (str "<!DOCTYPE html>
<html>
<head>
  <meta charset=\"UTF-8\">
  <meta http-equiv=\"Content-Security-Policy\" content=\"" csp "\">
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
  <link rel=\"stylesheet\" href=\"" (out-uri "style.css") "\">
  <style>html, body { width: 100%; height: 100%; overflow: hidden; margin: 0; padding: 0; }</style>
</head>
<body>
  <input type=\"hidden\" id=\"document\" value=\"" (js/encodeURIComponent (.getText document)) "\">
  <input type=\"hidden\" id=\"group\" value=\"" (path/basename (.. document -uri -fsPath) ".nyancir") "\">
  <input type=\"hidden\" id=\"models\" value=\"" (js/encodeURIComponent models-content) "\">
  <div class=\"mosaic-app mosaic-editor\"></div>
  <script nonce=\"" nonce "\" src=\"" (out-uri "shared.js") "\"></script>
  <script nonce=\"" nonce "\" src=\"" (out-uri "editor.js") "\"></script>
</body>
</html>")))

(defn- get-libman-html
  "Generate webview HTML for the library manager."
  [^js context ^js document ^js webview]
  (let [ext-uri (.-extensionUri context)
        out-uri (fn [file]
                  (.asWebviewUri webview
                    (.joinPath vscode/Uri ext-uri "out" file)))
        nonce (get-nonce)
        csp (str "default-src 'none';"
                 " style-src " (.-cspSource webview) " 'unsafe-inline';"
                 " script-src 'nonce-" nonce "' 'unsafe-eval';"
                 " img-src " (.-cspSource webview) " data: blob:;")]
    (str "<!DOCTYPE html>
<html>
<head>
  <meta charset=\"UTF-8\">
  <meta http-equiv=\"Content-Security-Policy\" content=\"" csp "\">
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
  <link rel=\"stylesheet\" href=\"" (out-uri "style.css") "\">
  <style>html, body { width: 100%; height: 100%; margin: 0; padding: 0; }</style>
</head>
<body>
  <input type=\"hidden\" id=\"document\" value=\"" (js/encodeURIComponent (.getText document)) "\">
  <div class=\"mosaic-app mosaic-libman\"></div>
  <script nonce=\"" nonce "\" src=\"" (out-uri "shared.js") "\"></script>
  <script nonce=\"" nonce "\" src=\"" (out-uri "libman.js") "\"></script>
</body>
</html>")))

;; ---------------------------------------------------------------------------
;; SVG auto-save on schematic save
;; ---------------------------------------------------------------------------

(defn- save-svg-sidecar!
  "Request preview SVG from webview and write it as a sidecar file."
  [^js webview doc-dir basename]
  (go
    (try
      (let [svg (<! (send-request! webview "preview"))
            svg-path (path/join doc-dir (str basename ".svg"))]
        (when (and svg (seq svg))
          (<p! (.. fs/promises (writeFile svg-path svg "utf8")))))
      (catch :default e
        (js/console.error "Failed to save SVG sidecar:" (.-message e))))))

;; ---------------------------------------------------------------------------
;; SchematicEditorProvider
;; ---------------------------------------------------------------------------

(deftype SchematicEditorProvider [^js context]
  Object
  (resolveCustomTextEditor [^js this ^js document ^js webviewpanel _token]
    (let [^js ctx (.-context this)
          ^js webview (.-webview webviewpanel)
          doc-dir (path/dirname (.. document -uri -fsPath))
          basename (path/basename (.. document -uri -fsPath) ".nyancir")
          disposables #js[]]
      ;; Ensure models.nyanlib exists and read it synchronously for initial injection
      (let [nyanlib-path (path/join doc-dir "models.nyanlib")]
        (when-not (fs/existsSync nyanlib-path)
          (fs/writeFileSync nyanlib-path "{}" "utf8"))
        (let [models-content (fs/readFileSync nyanlib-path "utf8")]

          ;; Configure webview
          (set! (.-options webview)
                #js{:enableScripts true
                    :localResourceRoots #js[(.joinPath vscode/Uri (.-extensionUri ctx) "out")]})
          (set! (.-html webview) (get-html ctx document webview models-content))

          ;; Create atom channel for the primary schematic document
          (let [schem-ch (create-atom-channel webview document "schematic")
                atom-channels (atom {"schematic" schem-ch})]
            (.push disposables (.-disposable schem-ch))

            ;; Set up atom channel for models.nyanlib (already exists from above)
            (go
              (try
                (let [nyanlib-uri (vscode/Uri.file nyanlib-path)
                      nyanlib-doc (<p! (.. vscode/workspace (openTextDocument nyanlib-uri)))
                      models-ch (create-atom-channel webview nyanlib-doc "models")]
                  (swap! atom-channels assoc "models" models-ch)
                  (.push disposables (.-disposable models-ch)))
                (catch :default e
                  (js/console.error "Failed to set up models.nyanlib:" (.-message e)))))

            ;; Message router (pass atom — models channel added async)
            (setup-message-router! webview atom-channels doc-dir)

            ;; Save SVG sidecar on document save
            (let [save-disposable
                  (.. vscode/workspace
                      (onDidSaveTextDocument
                        (fn [^js saved-doc]
                          (when (= (.. saved-doc -uri (toString))
                                   (.. document -uri (toString)))
                            (save-svg-sidecar! webview doc-dir basename)))))]
              (.push disposables save-disposable))

            ;; Clean up on panel dispose
            (.onDidDispose webviewpanel
              (fn []
                (doseq [d disposables]
                  (.dispose d))))))))))

;; ---------------------------------------------------------------------------
;; LibraryEditorProvider
;; ---------------------------------------------------------------------------

(deftype LibraryEditorProvider [^js context]
  Object
  (resolveCustomTextEditor [^js this ^js document ^js webviewpanel _token]
    (let [^js ctx (.-context this)
          ^js webview (.-webview webviewpanel)
          doc-dir (path/dirname (.. document -uri -fsPath))
          disposables #js[]]
      ;; Configure webview
      (set! (.-options webview)
            #js{:enableScripts true
                :localResourceRoots #js[(.joinPath vscode/Uri (.-extensionUri ctx) "out")]})
      (set! (.-html webview) (get-libman-html ctx document webview))

      ;; Create atom channel for the models document (primary)
      (let [models-ch (create-atom-channel webview document "models")
            atom-channels (atom {"models" models-ch})]
        (.push disposables (.-disposable models-ch))

        ;; Message router
        (setup-message-router! webview atom-channels doc-dir)

        ;; Clean up on panel dispose
        (.onDidDispose webviewpanel
          (fn []
            (doseq [d disposables]
              (.dispose d))))))))

;; ---------------------------------------------------------------------------
;; Registration & activation
;; ---------------------------------------------------------------------------

(defn- register-schematic-provider [^js context]
  (.registerCustomEditorProvider
    vscode/window
    "Mosaic.schematic"
    (SchematicEditorProvider. context)
    #js{:supportsMultipleEditorsPerDocument false
        :webviewOptions #js{:retainContextWhenHidden true}}))

(defn- register-library-provider [^js context]
  (.registerCustomEditorProvider
    vscode/window
    "Mosaic.library"
    (LibraryEditorProvider. context)
    #js{:supportsMultipleEditorsPerDocument false
        :webviewOptions #js{:retainContextWhenHidden true}}))

(defn activate
  "Extension activation - register custom editor providers."
  [^js context]
  (js/console.log "Mosaic extension activating")
  (.. context -subscriptions (push (register-schematic-provider context)))
  (.. context -subscriptions (push (register-library-provider context))))

(defn deactivate []
  (js/console.log "Mosaic extension deactivating"))

(def exports #js{:activate activate :deactivate deactivate})
