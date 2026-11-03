; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.extension
  "VSCode extension host: custom editor providers for .gsch, .nyanlib and
   symbol maker .svg files.
   Bridges the VSCode text document model with the webview's JsAtom."
  (:require ["vscode" :as vscode]
            ["path" :as path]
            ["fs" :as fs]
            ["child_process" :as cp]
            [clojure.string :as str]
            [goog.string :as gstring]
            [nyancad.mosaic.jsatom.bridge :as bridge]
            [nyancad.mosaic.symbol-file :as sf]
            [cljs.core.async :refer [go go-loop <! chan put! promise-chan timeout]]
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
   Webview assets (style.css, shared.js, editor.js, libman.js,
   symbol-maker.js) are siblings of
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


(deftype VscodeDoc [^js document]
  bridge/IHostDocument
  (-position-at    [_ offset]    (.positionAt document offset))
  (-make-range     [_ start end] (vscode/Range. start end))
  (-get-text-range [_ range]     (.getText document range)))

(defn- apply-updates
  "Validate oldcontent and apply JSON edit operations to a VSCode WorkspaceEdit.
   Returns true if all edits were valid and added, false if rejected.
   Extracted from go-loop so ^js hints survive (go macro strips them).
   The oldcontent validation + range computation is shared with the jsatom
   test harness via nyancad.mosaic.jsatom.bridge/build-edit-ops."
  [^js document ^js edit updates]
  (let [ops (bridge/build-edit-ops (VscodeDoc. document) updates)]
    (if (= bridge/rejected ops)
      (do (js/console.log "Rejected edit batch: content mismatch")
          false)
      (do (doseq [{:keys [range content]} ops]
            (.replace edit (.-uri document) range content))
          true))))

;; ---------------------------------------------------------------------------
;; Atom channel — bidirectional sync between a TextDocument and a JsAtom
;; ---------------------------------------------------------------------------

(defn- create-atom-channel
  "Set up bidirectional sync between a TextDocument and a JsAtom in the webview.
   Webview→Host edits are validated (oldcontent check) and applied via WorkspaceEdit.
   Host→Webview changes are forwarded as incremental contentChanges with document.version.
   Returns {:enqueue-edit! fn, :disposable disposable}."
  [^js webview ^js document group]
  (let [edit-queue (chan 32)]
    ;; Process edit queue with oldcontent validation. On rejection the webview
    ;; has already advanced its optimistic state, so send it an authoritative
    ;; resync snapshot instead of silently dropping the edit — otherwise the
    ;; webview stays diverged from the document (see rejected-batch-resyncs).
    (go-loop []
      (when-let [update (<! edit-queue)]
        (let [edit (vscode/WorkspaceEdit.)]
          (if (apply-updates document edit update)
            (<p! (.. vscode/workspace (applyEdit edit)))
            (.postMessage webview
              #js{:type "resync"
                  :group group
                  :version (.-version document)
                  :text (.getText document)})))
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
      {:enqueue-edit! #(put! edit-queue %)
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

;; @tags on-demand symbol generation
(defn- safe-relative-uri
  "Resolve filename within doc-uri. Reject absolute paths and traversal."
  [^js doc-uri filename]
  (when (and (string? filename)
             (not (path/isAbsolute filename))
             (not (re-find #"\.\." filename)))
    (let [resolved (uri-join doc-uri filename)]
      (when (str/starts-with? (.-path resolved) (str (.-path doc-uri) "/"))
        resolved))))

;; @tags on-demand symbol generation
(defn- readable-uri
  "Resolve a contained relative path or an exact host-authorized absolute path."
  [^js doc-uri filename authorized-paths]
  (if (and (string? filename) (path/isAbsolute filename))
    (when (contains? authorized-paths filename)
      (vscode/Uri.file filename))
    (safe-relative-uri doc-uri filename)))

;; @tags on-demand symbol generation
(defn- entry-symbol-paths
  "Return default and parameterized symbol paths from one factory entry."
  [entry]
  (when (map? entry)
    (cons (get entry "symbol")
          (keep #(when (map? %) (get % "symbol"))
                (vals (get entry "parameterizations" {}))))))

;; @tags on-demand symbol generation
(defn- absolute-symbol-paths
  "Extract absolute default and parameterized paths from factory entries."
  [entries]
  (->> (vals entries)
       (mapcat entry-symbol-paths)
       (filter #(and (string? %) (path/isAbsolute %)))
       set))

;; @tags on-demand symbol generation
(defn symbol-paths-from-json
  "Extract authorized packaged-symbol paths from serialized nyanlib entries."
  [content]
  (try
    (absolute-symbol-paths (js->clj (js/JSON.parse content)))
    (catch :default _ #{})))

;; @tags on-demand symbol generation
(def ^:private ^js host-api nil)

;; @tags on-demand symbol generation, mosaic livewire interop, schematic reference context, mosaic symbol maker
(defn setup-message-router!
  "Install a single message handler that dispatches by type.
   atom-channels: atom of map group -> channel map (read-only channels omit :enqueue-edit!)
   doc-uri: directory URI for resolving relative filenames
   doc-uri may be any scheme (file://, vsls://, etc.).
   doc-file-uri and document are present for document-backed editors."
  [^js webview atom-channels ^js doc-uri & [^js doc-file-uri ^js document authorized-read-paths]]
  (.onDidReceiveMessage webview
    (fn [^js message]
      (case (.-type message)
        ;; Atom edits — route to the right atom channel by group.
        ;; Read-only channels expose no :enqueue-edit!, so their updates are dropped.
        "update"
        (when-let [enqueue-edit! (:enqueue-edit! (get @atom-channels (.-group message)))]
          (enqueue-edit! (.-update message)))

        ;; Read a file relative to document directory.
        "read-file"
        (let [request-id (.-requestId message)
              filename (.-filename message)]
          (if-let [file-uri (readable-uri doc-uri filename (or (some-> authorized-read-paths deref) #{}))]
            (go
              (let [content (try (<p! (ws-read file-uri))
                                 (catch :default _e nil))]
                (.postMessage webview
                  #js{:requestId request-id
                      :content content})))
            (.postMessage webview
              #js{:requestId request-id
                  :content nil})))

        ("factory-entries" "resolve-factory")
        (go
          (try
            (when-not host-api (throw (js/Error. "Factory resolution is unavailable")))
            (let [value (<p! (if (= "resolve-factory" (.-type message))
                               (.resolveFactory host-api (.-fqn message) (.-path message))
                               (.getFactoryEntries host-api)))]
              (when authorized-read-paths
                (swap! authorized-read-paths into
                       (absolute-symbol-paths (js->clj (.-entries value)))))
              (<p! (.postMessage webview #js{:requestId (.-requestId message) :value value})))
            (catch :default e
              (<p! (.postMessage webview
                     #js{:requestId (.-requestId message)
                         :error (or (.-message e) (str e))})))))

        ;; Static artwork is selected by the host, then embedded by the webview.
        "import-symbol-svg"
        (go
          (try
            (let [selection (<p! (.showOpenDialog vscode/window
                                   #js{:canSelectMany false :canSelectFolders false
                                       :openLabel "Import SVG" :filters #js{:SVG #js["svg"]}}))
                  uri (when selection (aget selection 0))]
              (if uri
                (let [stat (<p! (.. vscode/workspace -fs (stat uri)))
                      _ (when (> (.-size stat) 1048576) (throw (js/Error. "SVG files must be at most 1 MiB")))
                      bytes (<p! (.. vscode/workspace -fs (readFile uri)))
                      _ (when (> (.-length bytes) 1048576) (throw (js/Error. "SVG files must be at most 1 MiB")))
                      content (.decode (js/TextDecoder. "utf-8" #js{:fatal true}) bytes)]
                  (<p! (.postMessage webview #js{:requestId (.-requestId message) :content content})))
                (<p! (.postMessage webview #js{:requestId (.-requestId message) :cancelled true}))))
            (catch :default error
              (<p! (.postMessage webview #js{:requestId (.-requestId message)
                                           :error (or (.-message error) "Could not read SVG file")})))))

        ;; Open a file in VS Code (create if missing, use custom editor)
        "open-file"
        (when-let [file-uri (safe-relative-uri doc-uri (.-filename message))]
          (let [filename (.-filename message)]
            (go
              (when-not (<! (ws-exists? file-uri))
                (<p! (ws-write file-uri "{}")))
              (let [editor-id (cond
                                (str/ends-with? filename ".gsch") "Mosaic.schematic"
                                :else "default")]
                (<p! (.. vscode/commands (executeCommand "vscode.openWith" file-uri editor-id)))))))

        ;; Open an indexed Python factory definition in VS Code.
        "open-factory-source"
        (when host-api
          (go
            (try
              (<p! (.openFactorySource host-api (.-factoryFqn message)))
              (catch :default e
                (js/console.error "Failed to open factory source:" e)))))

        ;; Switch to another custom editor for the same document
        "switchEditor"
        (when (and doc-file-uri document)
          (let [view-type (or (.-viewType message) "gdsfactoryplus.livewireNyancirEditor")]
            (go
              (try
                (when (<p! (.save document))
                  (<p! (.. vscode/commands
                           (executeCommand "vscode.openWith" doc-file-uri view-type))))
                (catch :default e
                  (js/console.error "Editor switch failed:" e))))))

        ;; State response from webview (for get-state requests)
        "state-response"
        (deliver-response! message)

        ;; Start simulation — spawn marimo sidecar (local file:// only)
        "start-simulation"
        (when (and doc-file-uri (= "file" (.-scheme doc-file-uri)))
          (start-marimo! (.-fsPath doc-file-uri) "spice"))

        ;; Start SAX simulation — spawn marimo sidecar (local file:// only)
        ;; DEPRECATED: use "open-sax-simulation" instead (opens the React panel)
        "start-sax-simulation"
        (when (and doc-file-uri (= "file" (.-scheme doc-file-uri)))
          (start-marimo! (.-fsPath doc-file-uri) "sax"))

        ;; Open the SAX simulation panel (React webview)
        "open-sax-simulation"
        (when (and doc-file-uri (= "file" (.-scheme doc-file-uri)))
          (.. vscode/commands (executeCommand "gdsfactoryplus.openSimulation" (.-fsPath doc-file-uri))))

        ;; Send schematic element to AI agent
        "send-to-agent"
        (when doc-file-uri
          (let [data (.-data message)
                document-key (.-documentKey message)
                label (or (.-label message) "Schematic element")
                kind (cond
                       (js/Array.isArray data) "schematic-selection"
                       (= "wire" (.-type data)) "schematic-wire"
                       :else "schematic-component")]
            (go
              (try
                (<p! (.. vscode/commands
                         (executeCommand "gdsfactoryplus.contributeAgentContext"
                           #js{:kind kind
                               :source #js{:editor "mosaic"
                                           :filePath (.-fsPath doc-file-uri)}
                               :documentKey document-key
                               :data data
                               :label label}
                           #js{:newTab true})))
                (catch :default e
                  (js/console.error "Failed to send schematic context to agent:" e))))))

        ;; Unknown — ignore
        nil))))

;; ---------------------------------------------------------------------------
;; HTML generators
;; ---------------------------------------------------------------------------

;; @tags mosaic startup readiness
(defn- get-loading-html
  "Generate the inert loading document shown before the server is ready."
  []
  (let [nonce (get-nonce)
        csp (str "default-src 'none';"
                 " style-src 'unsafe-inline';"
                 " script-src 'nonce-" nonce "';")]
    (str "<!DOCTYPE html>
<html>
<head>
  <meta charset=\"UTF-8\">
  <meta http-equiv=\"Content-Security-Policy\" content=\"" csp "\">
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
  <style>
    html, body { width: 100%; height: 100%; margin: 0; }
    body { display: grid; place-items: center; background: var(--vscode-editor-background, #1e1e1e); color: var(--vscode-editor-foreground, #cccccc); font: 13px var(--vscode-font-family, sans-serif); }
    main { display: flex; max-width: 32rem; flex-direction: column; align-items: center; gap: 12px; padding: 24px; text-align: center; }
    .spinner { width: 24px; height: 24px; border: 3px solid var(--vscode-progressBar-background, #0e70c0); border-right-color: transparent; border-radius: 50%; animation: spin 0.8s linear infinite; }
    .stalled { color: var(--vscode-descriptionForeground, #a0a0a0); }
    @keyframes spin { to { transform: rotate(360deg); } }
  </style>
</head>
<body>
  <main aria-live=\"polite\" aria-busy=\"true\">
    <div class=\"spinner\" aria-hidden=\"true\"></div>
    <div>Indexing PDK…</div>
    <div id=\"stalled\" class=\"stalled\" hidden>This is taking longer than usual. Waiting for PDK indexing and model generation to finish.</div>
  </main>
  <script nonce=\"" nonce "\">setTimeout(() => { document.getElementById('stalled').hidden = false; }, 120000);</script>
</body>
</html>")))

;; @tags mosaic startup readiness
(defn- get-index-error-html
  "Generate the terminal startup-error document with escaped server text."
  [error]
  (let [escaped-error (gstring/htmlEscape (str error))]
    (str "<!DOCTYPE html>
<html>
<head>
  <meta charset=\"UTF-8\">
  <meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline';\">
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
  <style>
    html, body { width: 100%; height: 100%; margin: 0; }
    body { display: grid; place-items: center; background: var(--vscode-editor-background, #1e1e1e); color: var(--vscode-editor-foreground, #cccccc); font: 13px var(--vscode-font-family, sans-serif); }
    main { max-width: 42rem; padding: 24px; }
    h1 { font-size: 16px; }
    pre { overflow-wrap: anywhere; white-space: pre-wrap; color: var(--vscode-errorForeground, #f48771); }
  </style>
</head>
<body>
  <main>
    <h1>Mosaic could not start because project indexing failed.</h1>
    <p>Fix the project error and reopen this editor.</p>
    <pre>" escaped-error "</pre>
  </main>
</body>
</html>")))

;; @tags mosaic startup readiness
(defn- server-readiness
  "Classify one getInfo result outside a go block so JS type hints survive."
  [^js info]
  (cond
    (nil? info)
    :retry

    (some? (.-index_error info))
    [:index-error (.-index_error info)]

    (or (.-indexing info) (.-nyanlib_generating info))
    :retry

    :else
    :ready))

;; @tags mosaic startup readiness
(defn- editor-resolution-cancelled?
  "Report cancellation after provider resolution as well as while it is pending."
  [^js token disposed?]
  (or @disposed? (.-isCancellationRequested token)))

;; @tags mosaic startup readiness
(defn- wait-for-server-ready!
  "Poll the GF+ host until indexing and initial nyanlib generation finish.
   Returns :ready, :cancelled, or [:index-error message]."
  [^js token disposed?]
  (go-loop []
    (if (editor-resolution-cancelled? token disposed?)
      :cancelled
      (let [info (try
                   (<p! (.getInfo host-api))
                   (catch :default _e nil))
            readiness (server-readiness info)]
        (cond
          (editor-resolution-cancelled? token disposed?)
          :cancelled

          (= readiness :retry)
          (do (<! (timeout 1000))
              (recur))

          :else
          readiness)))))

;; @tags factory resolution
(defn- get-html
  "Generate webview HTML for the schematic editor.
   models-content is the initial JSON string from models.nyanlib.
   Only the writable schematic needs a document version for JsAtom's echo guard."
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
  <input type=\"hidden\" id=\"document-version\" value=\"" (.-version document) "\">
  <input type=\"hidden\" id=\"group\" value=\"" (uri-basename (.-uri document) ".gsch") "\">
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
  <input type=\"hidden\" id=\"document-version\" value=\"" (.-version document) "\">
  <div class=\"mosaic-app mosaic-libman\"></div>
  <script nonce=\"" nonce "\" src=\"" (out-uri "shared.js") "\"></script>
  <script nonce=\"" nonce "\" src=\"" (out-uri "libman.js") "\"></script>
</body>
</html>")))

;; ---------------------------------------------------------------------------
;; SVG auto-save on schematic save
;; ---------------------------------------------------------------------------

;; @tags gsch symbol sidecar, mosaic symbol maker
(defn- save-svg-sidecar!
  "Request preview SVG from webview and write it as a sidecar file. A sidecar
   drawn in the symbol maker is user-owned and is never overwritten."
  [^js webview ^js doc-uri basename]
  (go
    (try
      (let [svg-uri (uri-join doc-uri (str basename ".svg"))
            existing (try (<p! (ws-read svg-uri))
                          (catch :default _e nil))]
        (when-not (sf/owned-svg? existing)
          (let [svg (<! (send-request! webview "preview"))]
            (when (and svg (seq svg))
              (<p! (ws-write svg-uri svg))))))
      (catch :default e
        (js/console.error "Failed to save SVG sidecar:" (.-message e))))))

;; ---------------------------------------------------------------------------
;; Marimo sidecar process management
;; ---------------------------------------------------------------------------

(defonce ^:private marimo-processes
  ;; {[kind doc-fsPath] -> {:process ChildProcess, :url string}}
  (atom {}))

;; @tags cross-platform file and process operations
(defn- start-marimo!
  "Spawn marimo run as a sidecar process for the given .gsch file.
   If already running, re-opens the Simple Browser."
  [doc-path kind]
  (let [doc-dir (path/dirname doc-path)
        basename (path/basename doc-path ".gsch")
        process-key [kind doc-path]]
    (if-let [{:keys [url]} (get @marimo-processes process-key)]
      ;; Already running — just re-show the browser
      (.. vscode/commands (executeCommand "simpleBrowser.show" url))
      ;; Spawn a new marimo process
      (let [sax? (= kind "sax")
            python-cmd (.. vscode/workspace
                           (getConfiguration "mosaic")
                           (get (if sax? "saxPythonCommand" "pythonCommand")
                                (if sax?
                                  "uv run --with nyancad-server[sax]"
                                  "uv run --with nyancad-server")))
            module-name (if sax? "nyancad_server.run_sax" "nyancad_server.run")
            output-ch (.. vscode/window (createOutputChannel (str "Mosaic " kind ": " basename)))]
        (.show output-ch true)
        (let [cmd-str (str python-cmd " python -m " module-name
                           " --host 127.0.0.1 --headless"
                           " -- --schem " basename
                           " --project " (js/JSON.stringify doc-dir))
              _ (.appendLine output-ch (str "Running: " cmd-str))
              proc (if (and host-api (re-find #"^uv(?:\s|$)" python-cmd))
                     (.spawnUvShell host-api cmd-str #js{:cwd doc-dir})
                     (.spawn cp cmd-str #js{:cwd doc-dir :shell true}))]
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
                            (swap! marimo-processes assoc process-key {:process proc :url url})
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
                            (swap! marimo-processes assoc process-key {:process proc :url url})
                            (.appendLine output-ch (str "Opening " url))
                            (.. vscode/commands (executeCommand "simpleBrowser.show" url))))))))))
          (.on proc "exit"
            (fn [code]
              (.appendLine output-ch (str "marimo exited with code " code))
              (swap! marimo-processes dissoc process-key))))))))

(defn- kill-marimo!
  "Kill a running marimo process for the given document path."
  [doc-path]
  (doseq [kind ["spice" "sax"]
          :let [process-key [kind doc-path]]
          :when (get @marimo-processes process-key)]
    (when-let [{:keys [^js process]} (get @marimo-processes process-key)]
      (.kill process)
      (swap! marimo-processes dissoc process-key))))

;; ---------------------------------------------------------------------------
;; SchematicEditorProvider
;; ---------------------------------------------------------------------------

(defonce ^:private schematic-webviews
  ;; {uri-string -> webview} — tracks active schematic webviews for commands
  (atom {}))

(deftype SchematicEditorProvider [^js context]
  Object
  ;; @tags mosaic startup readiness
  (resolveCustomTextEditor [^js _this ^js document ^js webviewpanel ^js token]
    (let [^js webview (.-webview webviewpanel)
          doc-uri (uri-parent (.-uri document))
          ws-root (some-> (first (.-workspaceFolders vscode/workspace)) .-uri)
          basename (uri-basename (.-uri document) ".nyancir")
          uri-key (.. document -uri (toString))
          disposed? (atom false)
          disposables #js[]
          nyanlib-uri (if ws-root
                        (uri-join ws-root "build" "models.nyanlib")
                        (uri-join doc-uri "models.nyanlib"))]
      (js/Promise.
        (fn [resolve _reject]
          ;; Resolve after the initial cache read. Cold-start polling must
          ;; continue in the background because VS Code does not present a
          ;; custom editor while this promise is pending.
          (set! (.-iconPath webviewpanel)
                #js{:light (uri-join (.-extensionUri context) "assets" "img" "icon" "schematic-red.svg")
                    :dark  (uri-join (.-extensionUri context) "assets" "img" "icon" "schematic-red.svg")})
          (set! (.-options webview)
                #js{:enableScripts true
                    :localResourceRoots #js[bundle-uri]})
          ;; This listener is installed before resolution so panel disposal is
          ;; the durable cancellation signal for background initialization.
          (.onDidDispose webviewpanel
            (fn []
              (reset! disposed? true)
              (swap! schematic-webviews dissoc uri-key)
              (when (= "file" (.. document -uri -scheme))
                (kill-marimo! (.. document -uri -fsPath)))
              (doseq [d disposables]
                (.dispose d))))
          (go
            (try
              (let [cached-content (try
                                     (<p! (ws-read nyanlib-uri))
                                     (catch :default _e nil))
                    cached? (some? cached-content)
                    _ (when (and (not cached?) (not @disposed?))
                        (set! (.-html webview) (get-loading-html)))
                    _ (resolve js/undefined)
                    readiness (cond
                                (editor-resolution-cancelled? token disposed?)
                                :cancelled

                                cached?
                                :ready

                                :else
                                (<! (wait-for-server-ready! token disposed?)))]
                (cond
                  (= readiness :cancelled)
                  nil

                  (and (vector? readiness)
                       (= (first readiness) :index-error))
                  (when-not @disposed?
                    (set! (.-html webview) (get-index-error-html (second readiness)))
                    nil)

                  :else
                  (when-not @disposed?
                     ;; The persisted snapshot seeds the read-only runtime cache.
                     ;; Subsequent updates arrive as entries, not file reloads.
                    (let [models-content (if cached?
                                           cached-content
                                           (<p! (ws-read nyanlib-uri)))]
                      (when-not @disposed?
                         (set! (.-html webview) (get-html document webview models-content))

                         (let [schem-ch (create-atom-channel webview document "schematic")
                               atom-channels (atom {"schematic" schem-ch})
                               authorized-read-paths (atom (symbol-paths-from-json models-content))]
                           (.push disposables (:disposable schem-ch))
                           (.push disposables
                                  (.onFactoryEntries host-api
                                    (fn [update]
                                      (swap! authorized-read-paths into
                                             (absolute-symbol-paths
                                               (js->clj (.-entries update))))
                                      (.postMessage webview #js{:type "factory-entries" :value update}))))

                          ;; Track this webview for the zoomToFit command
                          (swap! schematic-webviews assoc uri-key webview)

                          ;; Message router — pass document URI for marimo (local file:// only)
                           (.push disposables
                             (setup-message-router! webview atom-channels (or ws-root doc-uri)
                                                    (.-uri document) document authorized-read-paths))

                          ;; Save SVG sidecar on document save
                          (let [save-disposable
                                (.. vscode/workspace
                                    (onDidSaveTextDocument
                                      (fn [^js saved-doc]
                                        (when (= (.. saved-doc -uri (toString))
                                                 (.. document -uri (toString)))
                                          (save-svg-sidecar! webview doc-uri basename)))))]
                            (.push disposables save-disposable))))))))
              (catch :default e
                (js/console.error "Mosaic background initialization failed:" e)))))))))

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
;; SymbolEditorProvider
;; ---------------------------------------------------------------------------

;; @tags mosaic symbol maker
(defn- get-symbol-html
  "Generate webview HTML for the symbol maker. `rel-path` is the document's
   workspace-relative path, used to infer the target of a file without
   symbol maker metadata. Every injected hidden value is percent-encoded for
   the webview's one `decodeURIComponent`, including the catalog URL: its
   `.asWebviewUri` result is already percent-encoded, so encoding it again
   keeps an installation path containing `#` or `?` out of the URL fragment."
  [^js document ^js webview rel-path]
  (let [out-uri (fn [file]
                  (.asWebviewUri webview (uri-join bundle-uri file)))
        nonce (get-nonce)
        csp (str "default-src 'none';"
                 " style-src " (.-cspSource webview) " 'unsafe-inline';"
                 " script-src 'nonce-" nonce "' 'unsafe-eval';"
                 " img-src " (.-cspSource webview) " data: blob:;"
                 " connect-src " (.-cspSource webview) ";")]
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
  <input type=\"hidden\" id=\"relative-path\" value=\"" (js/encodeURIComponent rel-path) "\">
  <input type=\"hidden\" id=\"library-catalog-url\" value=\"" (js/encodeURIComponent (str (out-uri "symbols/catalog.json"))) "\">
  <div class=\"mosaic-app mosaic-symbol-maker\"></div>
  <script nonce=\"" nonce "\" src=\"" (out-uri "shared.js") "\"></script>
  <script nonce=\"" nonce "\" src=\"" (out-uri "symbol-maker.js") "\"></script>
</body>
</html>")))

;; @tags mosaic symbol maker
(defn- replace-document!
  "Replace the whole text of `document` with `text` as one undoable edit.
   Returns the applyEdit promise, or nil when the text is unchanged."
  [^js document text]
  (let [current (.getText document)]
    (when (not= text current)
      (let [edit (vscode/WorkspaceEdit.)
            whole (vscode/Range. (.positionAt document 0)
                                 (.positionAt document (.-length current)))]
        (.replace edit (.-uri document) whole text)
        (.. vscode/workspace (applyEdit edit))))))

;; @tags mosaic symbol maker
(defn- step-edit-queue!
  "Advance the edit queue atom with `f` (a pure symbol-file queue step) and
   return the resulting action."
  [queue f]
  (let [action (volatile! nil)]
    (swap! queue (fn [q]
                   (let [[next-q next-action] (f q)]
                     (vreset! action next-action)
                     next-q)))
    @action))

;; @tags mosaic symbol maker
(defn- document-editor
  "Serialize webview edits into `document`: at most one applyEdit is in flight
   and only the newest requested text waits behind it, so rapid edits are never
   built from stale text. Returns {:request! (fn [text]) :idle? (fn [])}.
   `post-document!` re-syncs the webview whenever the queue goes idle,
   including after a rejected edit."
  [^js document post-document!]
  (let [queue (atom sf/idle-edit-queue)]
    (letfn [(perform! [[op text]]
              (case op
                :apply (if-let [pending (replace-document! document text)]
                         (-> (js/Promise.resolve pending)
                             (.then (fn [applied?]
                                      (when-not applied?
                                        (js/console.error "Symbol maker edit was not applied"))
                                      (settle! (boolean applied?)))
                                    (fn [e]
                                      (js/console.error "Symbol maker edit failed:" e)
                                      (settle! false)))
                             (.catch (fn [e]
                                       (js/console.error "Symbol maker could not re-sync its webview:" e))))
                         (settle! true))
                :post (post-document!)
                nil))
            (settle! [applied?]
              (perform! (step-edit-queue! queue #(sf/edit-queue-settled % applied?))))]
      {:request! (fn [text] (perform! (step-edit-queue! queue #(sf/edit-queue-request % text))))
       :idle? (fn [] (not (:in-flight? @queue)))})))

(deftype SymbolEditorProvider [^js context]
  Object
  ;; @tags mosaic symbol maker
  (resolveCustomTextEditor [^js _this ^js document ^js webviewpanel _token]
    (let [^js webview (.-webview webviewpanel)
          ws-root (or (some-> (first (.-workspaceFolders vscode/workspace)) .-uri)
                      (uri-parent (.-uri document)))
          rel-path (.. vscode/workspace (asRelativePath (.-uri document) false))
          uri-key (.. document -uri (toString))
          disposables #js[]]
      (set! (.-options webview)
            #js{:enableScripts true
                :localResourceRoots #js[bundle-uri]})
      (set! (.-html webview) (get-symbol-html document webview rel-path))
      ;; resolve-factory / factory-entries requests supply the target's ports.
      (.push disposables (setup-message-router! webview (atom {}) ws-root))
      ;; The document owns history: each committed drawing edit replaces it.
      ;; Echoes are held back while edits are queued; the queue re-syncs the
      ;; webview once it goes idle.
      (let [post-document! #(.postMessage webview #js{:type "document"
                                                      :text (.getText document)})
            {:keys [request! idle?]} (document-editor document post-document!)]
        (.push disposables
               (.onDidReceiveMessage webview
                 (fn [^js message]
                   (when (= "edit" (.-type message))
                     (request! (.-text message))))))
        (.push disposables
               (.. vscode/workspace
                   (onDidChangeTextDocument
                     (fn [^js e]
                       (when (and (= uri-key (.. e -document -uri (toString)))
                                  (idle?))
                         (post-document!)))))))
      (when host-api
        (.push disposables
               (.onFactoryEntries host-api
                 (fn [update]
                   (.postMessage webview #js{:type "factory-entries" :value update})))))
      (.onDidDispose webviewpanel
        (fn []
          (doseq [d disposables]
            (.dispose d)))))))

;; @tags mosaic symbol maker, gsch symbol sidecar
(defn- real-path
  "Resolved real path of `file`, or nil when it cannot be resolved (for
   example after deletion)."
  [file]
  (try (.native (.-realpathSync fs) file)
       (catch :default _e nil)))

;; @tags mosaic symbol maker, gsch symbol sidecar
(defn- symbol-change-paths
  "Every spelling a schematic may cache a symbol under: absolute, real, and
   workspace-relative, with Windows drive letters in both cases."
  [^js uri]
  (let [absolute (.-fsPath uri)]
    (clj->js (sf/symbol-path-forms absolute
                                   (real-path absolute)
                                   (.. vscode/workspace (asRelativePath uri false))))))

;; @tags mosaic symbol maker, gsch symbol sidecar
(defn- watch-symbol-files
  "Tell every open schematic when a workspace SVG is created, changed or
   deleted so it re-reads or drops that symbol. Returns the watcher to
   dispose."
  []
  (let [^js watcher (.. vscode/workspace (createFileSystemWatcher "**/*.svg"))
        notify (fn [deleted? ^js uri]
                 (let [paths (symbol-change-paths uri)]
                   (doseq [[_ ^js webview] @schematic-webviews]
                     (.postMessage webview #js{:type "symbol-changed"
                                               :paths paths
                                               :deleted deleted?}))))]
    (.onDidCreate watcher (partial notify false))
    (.onDidChange watcher (partial notify false))
    (.onDidDelete watcher (partial notify true))
    watcher))

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

;; @tags mosaic symbol maker
(defn- register-symbol-provider [^js context]
  (.registerCustomEditorProvider
    vscode/window
    "Mosaic.symbol"
    (SymbolEditorProvider. context)
    #js{:supportsMultipleEditorsPerDocument false
        :webviewOptions #js{:retainContextWhenHidden true}}))

;; @tags on-demand symbol generation, mosaic symbol maker
(defn activate
  "Extension activation - register custom editor providers."
  [^js context & [^js api]]
  (js/console.log "Mosaic extension activating")
  (set! host-api api)
  (.. context -subscriptions (push (register-schematic-provider context)))
  (.. context -subscriptions (push (register-library-provider context)))
  (.. context -subscriptions (push (register-symbol-provider context)))
  (.. context -subscriptions (push (watch-symbol-files)))
  (.. context -subscriptions
      (push (.. vscode/commands
                (registerCommand "Mosaic.schematic.zoomToFit"
                  (fn [^js uri]
                    (let [uri-key (when uri (.toString uri))]
                      (when-let [^js wv (or (when uri-key (get @schematic-webviews uri-key))
                                            (first (vals @schematic-webviews)))]
                        (.postMessage wv #js{:type "zoom-to-fit"})))))))))

(defn deactivate []
  (js/console.log "Mosaic extension deactivating")
  (doseq [[doc-path _] @marimo-processes]
    (kill-marimo! doc-path)))

(def exports #js{:activate activate :deactivate deactivate})
