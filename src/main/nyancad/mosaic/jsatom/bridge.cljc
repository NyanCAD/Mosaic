; SPDX-FileCopyrightText: 2026 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.jsatom.bridge
  "Host-side validation core shared between the VSCode extension host
   (nyancad.mosaic.extension) and the jsatom test harness.

   Extracts the oldcontent check + range computation from the extension's
   apply-updates, parameterized over IHostDocument so the same logic runs
   against a real vscode.TextDocument in production and against Microsoft's
   vscode-languageserver-textdocument model under the :test build. The async
   apply (WorkspaceEdit) and event-driven change forwarding stay with each
   caller — only the pure validation lives here.")

(defprotocol IHostDocument
  "The three primitives apply-updates needs from a text document model."
  (-position-at    [this offset] "Offset -> position.")
  (-make-range     [this start end] "Two positions -> a range value get-text understands.")
  (-get-text-range [this range] "getText(range), for the oldcontent comparison."))

(def rejected
  "Sentinel returned by build-edit-ops when an edit batch fails validation."
  ::rejected)

(defn build-edit-ops
  "Validate each jsonc update's oldcontent against host-doc and compute the
   {:range :content} replacements to apply. `updates` is the js array posted by
   the webview (each op has :offset :length :content and optionally :oldcontent).

   Returns the ops vector when every op is valid (an empty batch yields []),
   or `rejected` if any op's oldcontent no longer matches the document — the
   whole batch is dropped in that case, matching the original apply-updates."
  [host-doc updates]
  (let [ops (mapv (fn [^js up]
                    (let [offset      (.-offset up)
                          length      (.-length up)
                          content     (.-content up)
                          old-content (.-oldcontent up)
                          start       (-position-at host-doc offset)
                          end         (-position-at host-doc
                                                    (+ offset (if old-content
                                                                (.-length old-content)
                                                                length)))
                          range       (-make-range host-doc start end)]
                      (when (or (nil? old-content)
                                (= old-content (-get-text-range host-doc range)))
                        {:range range :content (or content "")})))
                  updates)]
    (if (every? some? ops)
      ops
      rejected)))
