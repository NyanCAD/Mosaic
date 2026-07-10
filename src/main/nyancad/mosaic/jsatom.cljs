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
            [cljs.core.async :refer [go put! promise-chan]]))

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

;; --- Request/response for read-file messages ---

(defonce ^:private pending-requests (atom {}))

(.addEventListener js/window "message"
  (fn [^js event]
    (when-let [request-id (.. event -data -requestId)]
      (when-let [ch (get @pending-requests request-id)]
        (swap! pending-requests dissoc request-id)
        (put! ch (or (.. event -data -content) false))))))

(defn send-request!
  "Post a read-file message to the extension host, return a promise-chan with the response."
  [filename]
  (let [request-id (str (random-uuid))
        ch (promise-chan)]
    (swap! pending-requests assoc request-id ch)
    (.postMessage vscode
      #js{:type "read-file"
          :filename filename
          :requestId request-id})
    ch))

(defn done?
  "Returns a channel that completes immediately.
   JsAtom edits are synchronous, so there is never anything to wait for.
   This matches the hipflask/done? API so editor code can use done? uniformly."
  [_ja]
  (go nil))
