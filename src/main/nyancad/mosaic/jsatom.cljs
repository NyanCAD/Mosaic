; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.jsatom
  "Reactive atom backed by a VSCode text document buffer.
   Uses jsonc-parser to apply edits and sync state with the extension host.
   Top-level keys are strings (matching PouchDB format), inner values use keyword keys.
   Each JsAtom has a group field for message routing in multi-atom webviews.

   The edit protocol itself (JsAtom, edit generation, echo application) lives in
   nyancad.mosaic.jsatom.protocol so it can be unit-tested under the :test build
   without js/window or js/acquireVsCodeApi. This namespace keeps only the
   VSCode-specific wiring: the acquireVsCodeApi transport, the window message
   listener, and the read-file request/response channel."
  (:require [nyancad.mosaic.jsatom.protocol :as protocol]
            [cljs.core.async :refer [go <! put! promise-chan]]))

(defonce vscode (js/acquireVsCodeApi))

(defn injected-version
  "Read a host document version from a hidden input the extension injects,
   defaulting to 1 when the input is absent or unparseable so the self-echo
   guard degrades to its old behaviour rather than breaking."
  [id]
  (let [v (some-> (js/document.getElementById id) .-value (js/parseInt 10))]
    (if (and (number? v) (not (js/isNaN v))) v 1)))

(defn json-atom
  "Create a JsAtom from a JSON document string, tagged with a group name.
   Listens for update messages from the VSCode extension host,
   filtering by group to support multiple atoms in one webview.
   `init-version` is the host document's VSCode version at load time, so the
   self-echo guard stays in lockstep with the host (defaults to 1)."
  ([group doc] (json-atom group doc (atom {})))
  ([group doc cache] (json-atom group doc cache 1))
  ([group doc cache init-version]
   (let [{:keys [atom receive!]}
         (protocol/make-json-atom group doc cache #(.postMessage vscode %) init-version)]
     (.addEventListener js/window "message"
       (fn [^js event] (receive! (.-data event))))
     atom)))

;; --- Shared request/response transport ---

(defonce ^:private pending-requests (atom {}))

(.addEventListener js/window "message"
  (fn [^js event]
    (when-let [request-id (.. event -data -requestId)]
      (when-let [ch (get @pending-requests request-id)]
        (swap! pending-requests dissoc request-id)
        (put! ch (.-data event))))))

;; @tags factory resolution
(defn request!
  "Post a message with a fresh request ID; return a promise channel yielding raw
   event.data from the matching reply. The webview-lifetime listener removes the
   pending entry on reply. Unanswered requests have no timeout or disposal API;
   their state is released when the webview is destroyed."
  [message]
  (let [request-id (str (random-uuid))
        ch (promise-chan)]
    (swap! pending-requests assoc request-id ch)
    (.postMessage vscode (clj->js (assoc message :requestId request-id)))
    ch))

;; @tags factory resolution
(defn send-request!
  "Read a document-relative file. Return a go channel yielding its content or
   false when the host reports no content, including read/access failures."
  [filename]
  (go (or (.-content ^js (<! (request! {:type "read-file" :filename filename}))) false)))

;; @tags factory resolution
(defn- entries-request!
  "Return a go channel yielding decoded FactoryEntries or ex-info as a value.
   Host errors and malformed replies are reported through the channel, not thrown."
  [message]
  (go
    (try
      (let [^js response (<! (request! message))]
        (if-let [error (.-error response)]
          (ex-info error {})
          (protocol/decode-entries (.-value response))))
      (catch :default e
        (ex-info "Factory request failed" {:type (:type message)} e)))))

;; @tags factory resolution
(defn gfpatom
  "Create a read-only factory cache, subscribe to pushes, then fetch catch-up data.
   The window message listener belongs to the webview lifetime; there is no
   per-atom removal API. Catalogue errors are logged and preserve cached data."
  [doc cache]
  (let [gfp (protocol/make-gfp-atom doc cache
              (fn [lookup]
                (let [[model-key & path] (if (vector? lookup) lookup [lookup])]
                  (entries-request! {:type "resolve-factory"
                                     :fqn (subs model-key (count "models:"))
                                     :path (vec path)}))))]
    (.addEventListener js/window "message"
      (fn [^js event]
        (when (= "factory-entries" (.. event -data -type))
          (let [update (protocol/decode-entries (.. event -data -value))]
            (if (instance? js/Error update)
              (js/console.error "Factory catalogue unavailable:" update)
              (protocol/receive-entries! gfp update true))))))
    ;; Subscribe before fetching the catch-up snapshot. Entry revisions prevent
    ;; a slower snapshot from replacing a newer direct reply or watcher push.
    (go
      (let [snapshot (<! (entries-request! {:type "factory-entries"}))]
        (if (instance? js/Error snapshot)
          (js/console.error "Factory catalogue unavailable:" snapshot)
          (protocol/receive-entries! gfp snapshot))))
    gfp))

(defn done?
  "Returns the channel for the latest mutation on this JsAtom. Carries the
    new cache value on success or the ex-info on validator rejection. Matches
   the hipflask/done? API so editor code can chain writes uniformly."
  [^js ja]
  (.-done? ja))
