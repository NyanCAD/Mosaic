; SPDX-FileCopyrightText: 2026 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.jsatom.util
  "Pure helpers for nyancad.mosaic.jsatom — the parts that don't touch
   js/window or js/acquireVsCodeApi and so are testable under
   shadow-cljs's :test build (Node).

   Mirrors the nyancad.hipflask.util split: keep the ratom/postMessage
   plumbing in jsatom.cljs and the JSON parsing here."
  (:require ["jsonc-parser" :as jsonc]
            [nyancad.hipflask.util :refer [json->clj]]))

(defn doc->state
  "Parse a JSONC document string into the schematic map shape JsAtom
   stores in its cache. Top-level keys remain strings (matching PouchDB
   format), inner values use keyword keys.

   Backfills :x/:y on entries that lack them, stacking unplaced devices
   at x=0 so Livewire-authored .nyancir files load cleanly. The if-gate
   skips the merge AND the counter increment for entries that already
   have coordinates, so backfilled :y values pack contiguously from 0
   with no gaps."
  [doc-str]
  (let [parsed (jsonc/parse doc-str)
        y (atom -1)]
    (if parsed
      (update-vals
        (into {}
              (map (fn [entry]
                     [(aget entry 0) (json->clj (aget entry 1))]))
              (js/Object.entries parsed))
        (fn [v]
          (if (and (number? (:x v)) (number? (:y v)))
            v
            (merge {:x 0 :y (swap! y inc)} v))))
      {})))
