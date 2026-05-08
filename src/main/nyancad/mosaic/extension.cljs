; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.extension
  "VSCode extension host: custom editor providers for .nyancir and .nyanlib files.
   Bridges the VSCode text document model with the webview's JsAtom."
  (:require ["vscode" :as vscode]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.string :as str]
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

(defn- uri-basename
  "Extract filename from a URI, optionally stripping a suffix."
  [^js uri & [suffix]]
  (cond-> (last (str/split (.-path uri) #"/"))
    suffix (str/replace suffix "")))

(defn- uri-join
  "Join path segments onto a base URI, preserving its scheme."
  [^js base & segments]
  (.apply (.-joinPath vscode/Uri) nil (to-array (cons base segments))))

(defn- uri-parent
  "Get the parent directory URI."
  [^js uri]
  (uri-join uri ".."))

(def ^:private bundle-uri
  "Filesystem URI of the directory containing this compiled extension bundle.
   Webview assets (style.css, shared.js, editor.js, libman.js) are siblings of
   main.js in the same out/ directory, so resolving them against __dirname
   keeps mosaic independent of how the host extension lays out its files."
  (vscode/Uri.file js/__dirname))

;; ---------------------------------------------------------------------------
;; Workspace filesystem helpers — thin wrappers over vscode.workspace.fs
;; ---------------------------------------------------------------------------

(defn- ws-read
  "Read a file as UTF-8 string. Returns a promise."
  [^js uri]
  (-> (.. vscode/workspace -fs (readFile uri))
      (.then #(.decode (js/TextDecoder. "utf-8") %))))

(defn- ws-write
  "Write a UTF-8 string to a file. Returns a promise."
  [^js uri content]
  (.. vscode/workspace -fs (writeFile uri (.encode (js/TextEncoder.) content))))

(defn- ws-exists?
  "Check if a URI exists. Returns a channel yielding true/false."
  [^js uri]
  (go (try (<p! (.. vscode/workspace -fs (stat uri))) true
           (catch :default _ false))))

(defn- get-text
  "Extract text from a TextDocument. Separate fn so ^js hint survives go macro."
  [^js document]
  (.getText document))

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

(declare start-marimo!)

(defn- safe-resolve-uri
  "Resolve filename within doc-uri. Rejects path traversal."
  [^js doc-uri filename]
  (when-not (re-find #"\.\." filename)
    (let [resolved (uri-join doc-uri filename)]
      (when (str/starts-with? (.-path resolved) (str (.-path doc-uri) "/"))
        resolved))))

(defn- setup-message-router!
  "Install a single message handler that dispatches by type.
   atom-channels: atom of map group -> {:edit-queue chan}
   doc-uri: directory URI for resolving relative filenames
   doc-uri may be any scheme (file://, vsls://, etc.)."
  [^js webview atom-channels ^js doc-uri & [^js doc-file-uri]]
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
          (if-let [file-uri (safe-resolve-uri doc-uri (.-filename message))]
            (go
              (let [content (try (<p! (ws-read file-uri))
                                 (catch :default _e nil))]
                (.postMessage webview
                  #js{:requestId request-id
                      :content content})))
            (.postMessage webview
              #js{:requestId request-id
                  :content nil})))

        ;; Open a file in VS Code (create if missing, use custom editor)
        "open-file"
        (when-let [file-uri (safe-resolve-uri doc-uri (.-filename message))]
          (let [filename (.-filename message)]
            (go
              (when-not (<! (ws-exists? file-uri))
                (<p! (ws-write file-uri "{}")))
              (let [editor-id (cond
                                (str/ends-with? filename ".nyancir") "Mosaic.schematic"
                                (str/ends-with? filename ".nyanlib") "Mosaic.library"
                                :else "default")]
                (<p! (.. vscode/commands (executeCommand "vscode.openWith" file-uri editor-id)))))))

        ;; State response from webview (for get-state requests)
        "state-response"
        (deliver-response! message)

        ;; Start simulation — spawn marimo sidecar (local file:// only)
        "start-simulation"
        (when (and doc-file-uri (= "file" (.-scheme doc-file-uri)))
          (start-marimo! (.-fsPath doc-file-uri)))

        ;; Unknown — ignore
        nil))))

;; ---------------------------------------------------------------------------
;; HTML generators
;; ---------------------------------------------------------------------------

(defn- get-html
  "Generate webview HTML for the schematic editor.
   models-content is the initial JSON string from models.nyanlib."
  [^js document ^js webview models-content]
  (let [out-uri (fn [file]
                  (.asWebviewUri webview (uri-join bundle-uri file)))
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
  <style>html, body { width: 100%; height: 100%; overflow: hidden; margin: 0; padding: 0; }</style>
</head>
<body>
  <input type=\"hidden\" id=\"document\" value=\"" (js/encodeURIComponent (.getText document)) "\">
  <input type=\"hidden\" id=\"group\" value=\"" (uri-basename (.-uri document) ".nyancir") "\">
  <input type=\"hidden\" id=\"models\" value=\"" (js/encodeURIComponent models-content) "\">
  <div class=\"mosaic-app mosaic-editor\"></div>
  <script nonce=\"" nonce "\" src=\"" (out-uri "shared.js") "\"></script>
  <script nonce=\"" nonce "\" src=\"" (out-uri "editor.js") "\"></script>
</body>
</html>")))

(defn- get-libman-html
  "Generate webview HTML for the library manager."
  [^js document ^js webview]
  (let [out-uri (fn [file]
                  (.asWebviewUri webview (uri-join bundle-uri file)))
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
  [^js webview ^js doc-uri basename]
  (go
    (try
      (let [svg (<! (send-request! webview "preview"))
            svg-uri (uri-join doc-uri (str basename ".svg"))]
        (when (and svg (seq svg))
          (<p! (ws-write svg-uri svg))))
      (catch :default e
        (js/console.error "Failed to save SVG sidecar:" (.-message e))))))

;; ---------------------------------------------------------------------------
;; Marimo sidecar process management
;; ---------------------------------------------------------------------------

(defonce ^:private marimo-processes
  ;; {doc-fsPath -> {:process ChildProcess, :url string}}
  (atom {}))

(defn- start-marimo!
  "Spawn marimo run as a sidecar process for the given .nyancir file.
   If already running, re-opens the Simple Browser."
  [doc-path]
  (let [doc-dir (path/dirname doc-path)
        basename (path/basename doc-path ".nyancir")]
    (if-let [{:keys [url]} (get @marimo-processes doc-path)]
      ;; Already running — just re-show the browser
      (.. vscode/commands (executeCommand "simpleBrowser.show" url))
      ;; Spawn a new marimo process
      (let [python-cmd (.. vscode/workspace
                           (getConfiguration "mosaic")
                           (get "pythonCommand" "uv run --with nyancad-server"))
            output-ch (.. vscode/window (createOutputChannel (str "Mosaic: " basename)))]
        (.show output-ch true)
        (.appendLine output-ch (str "Discovering notebook path..."))
        ;; Step 1: discover the notebook path via shell command
        (let [discover-cmd (str python-cmd " python -c \"from nyancad_server import get_notebook_path; print(get_notebook_path())\"")]
          (let [discover-proc (.spawn cp discover-cmd
                                #js{:cwd doc-dir
                                    :shell true})]
            (let [stdout-buf (atom "")]
              (.. discover-proc -stdout (on "data" #(swap! stdout-buf str %)))
              (.. discover-proc -stderr (on "data" #(.appendLine output-ch (str "discover: " %))))
              (.on discover-proc "exit"
                (fn [code]
                  (if (not= code 0)
                    (do
                      (.appendLine output-ch (str "Failed to discover notebook path (exit code " code ")"))
                      (.. vscode/window (showErrorMessage "Failed to find Mosaic notebook. Is nyancad-server installed?")))
                    ;; Step 2: spawn marimo run
                    (let [notebook-path (.trim @stdout-buf)
                          cmd-str (str python-cmd " marimo run " (js/JSON.stringify notebook-path)
                                       " --host 127.0.0.1 --headless"
                                       " -- --schem " basename
                                       " --project " (js/JSON.stringify doc-dir))
                          _ (.appendLine output-ch (str "Running: " cmd-str))
                          proc (.spawn cp cmd-str
                                 #js{:cwd doc-dir
                                     :shell true})]
                      ;; Watch stdout for the URL marimo prints on startup
                      (let [url-found (atom false)]
                        (.. proc -stdout
                            (on "data"
                              (fn [data]
                                (let [line (str data)]
                                  (.appendLine output-ch line)
                                  (when-not @url-found
                                    (when-let [match (.match line #"https?://[\w\.\-]+:\d+")]
                                      (let [url (aget match 0)]
                                        (reset! url-found true)
                                        (swap! marimo-processes assoc doc-path {:process proc :url url})
                                        (.appendLine output-ch (str "Opening " url))
                                        (.. vscode/commands (executeCommand "simpleBrowser.show" url)))))))))
                        (.. proc -stderr
                            (on "data"
                              (fn [data]
                                (let [line (str data)]
                                  (.appendLine output-ch line)
                                  (when-not @url-found
                                    (when-let [match (.match line #"https?://[\w\.\-]+:\d+")]
                                      (let [url (aget match 0)]
                                        (reset! url-found true)
                                        (swap! marimo-processes assoc doc-path {:process proc :url url})
                                        (.appendLine output-ch (str "Opening " url))
                                        (.. vscode/commands (executeCommand "simpleBrowser.show" url))))))))))
                      (.on proc "exit"
                        (fn [code]
                          (.appendLine output-ch (str "marimo exited with code " code))
                          (swap! marimo-processes dissoc doc-path))))))))))))))

(defn- kill-marimo!
  "Kill a running marimo process for the given document path."
  [doc-path]
  (when-let [{:keys [^js process]} (get @marimo-processes doc-path)]
    (.kill process)
    (swap! marimo-processes dissoc doc-path)))

;; ---------------------------------------------------------------------------
;; SchematicEditorProvider
;; ---------------------------------------------------------------------------

(deftype SchematicEditorProvider [^js context]
  Object
  (resolveCustomTextEditor [^js _this ^js document ^js webviewpanel _token]
    (let [^js webview (.-webview webviewpanel)
          doc-uri (uri-parent (.-uri document))
          basename (uri-basename (.-uri document) ".nyancir")
          disposables #js[]
          nyanlib-uri (uri-join doc-uri "models.nyanlib")]
      (js/Promise.
        (fn [resolve reject]
          (go
            (try
              ;; Ensure models.nyanlib exists
              (when-not (<! (ws-exists? nyanlib-uri))
                (<p! (ws-write nyanlib-uri "{}")))

              ;; Open nyanlib as TextDocument — use .getText() for initial content
              (let [nyanlib-doc (<p! (.. vscode/workspace (openTextDocument nyanlib-uri)))
                    models-content (get-text nyanlib-doc)]

                ;; Configure webview
                (set! (.-options webview)
                      #js{:enableScripts true
                          :localResourceRoots #js[bundle-uri]})
                (set! (.-html webview) (get-html document webview models-content))

                ;; Create atom channels for schematic and models documents
                (let [schem-ch (create-atom-channel webview document "schematic")
                      models-ch (create-atom-channel webview nyanlib-doc "models")
                      atom-channels (atom {"schematic" schem-ch "models" models-ch})]
                  (.push disposables (:disposable schem-ch))
                  (.push disposables (:disposable models-ch))

                  ;; Message router — pass document URI for marimo (local file:// only)
                  (setup-message-router! webview atom-channels doc-uri (.-uri document))

                  ;; Save SVG sidecar on document save
                  (let [save-disposable
                        (.. vscode/workspace
                            (onDidSaveTextDocument
                              (fn [^js saved-doc]
                                (when (= (.. saved-doc -uri (toString))
                                         (.. document -uri (toString)))
                                  (save-svg-sidecar! webview doc-uri basename)))))]
                    (.push disposables save-disposable))

                  ;; Clean up on panel dispose
                  (.onDidDispose webviewpanel
                    (fn []
                      (when (= "file" (.. document -uri -scheme))
                        (kill-marimo! (.. document -uri -fsPath)))
                      (doseq [d disposables]
                        (.dispose d))))))
              (resolve js/undefined)
              (catch :default e
                (js/console.error "resolveCustomTextEditor failed:" e)
                (reject e)))))))))

;; ---------------------------------------------------------------------------
;; LibraryEditorProvider
;; ---------------------------------------------------------------------------

(deftype LibraryEditorProvider [^js context]
  Object
  (resolveCustomTextEditor [^js _this ^js document ^js webviewpanel _token]
    (let [^js webview (.-webview webviewpanel)
          doc-uri (uri-parent (.-uri document))
          disposables #js[]]
      ;; Configure webview
      (set! (.-options webview)
            #js{:enableScripts true
                :localResourceRoots #js[bundle-uri]})
      (set! (.-html webview) (get-libman-html document webview))

      ;; Create atom channel for the models document (primary)
      (let [models-ch (create-atom-channel webview document "models")
            atom-channels (atom {"models" models-ch})]
        (.push disposables (:disposable models-ch))

        ;; Message router
        (setup-message-router! webview atom-channels doc-uri)

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
  (js/console.log "Mosaic extension deactivating")
  (doseq [[doc-path _] @marimo-processes]
    (kill-marimo! doc-path)))

(def exports #js{:activate activate :deactivate deactivate})
