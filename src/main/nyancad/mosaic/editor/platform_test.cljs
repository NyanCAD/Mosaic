; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.platform-test
  "Test platform stub for editor.cljc.

   Provides bare reagent atoms and no-op functions for the symbols
   editor.cljc :refers from the real platform modules — without pulling in
   PouchDB, the browser DOM, or the VSCode API. Selected by the `:test`
   reader feature via shadow-cljs build configuration.

   Tests that need specific state just `(reset! schematic …)` or pass the
   value directly to the pure function under test."
  (:require [reagent.core :as r]
            [nyancad.mosaic.common :as cm]
            [nyancad.mosaic.invocation :as invocation]
            [cljs.core.async :refer [go]]))

(def group "test$top")

;; @tags factory resolution
(defn model-cursor
  "Return an ordinary reactive cursor at the supplied keys."
  [source & keys]
  (if (= :parameterizations (second keys))
    (r/cursor (fn [path] (invocation/lookup @source path)) (vec keys))
    (r/cursor source (vec keys))))

;; @tags factory resolution
(defn renamable-model-name
  "Match the web platform's editable local component name."
  [source model-key]
  [cm/renamable (model-cursor source model-key :name) "Untitled"])

;; @tags factory resolution
(defn get-model-async
  "Read local component data without runtime resolution."
  [source & keys]
  (let [data @source]
    (go
      (if (and (seq keys) (nil? (get data (first keys))))
        (ex-info "Model is unavailable" {:key (first keys)})
        (if (seq keys) (invocation/lookup data keys) data)))))

(defonce schematic (r/atom {}))
(defonce modeldb (r/atom {}))
(defonce snapshots (r/atom {}))
(defonce simulations (r/atom (sorted-map)))
(defonce local (r/atom {}))
(defonce syncactive (r/atom false))

;; No DOM in Node — render is never called from tests.
(def root nil)

(defonce settled-write (go nil))
;; @tags mosaic port exposure
(defn done?
  "Match the JsAtom/pouch-atom contract: returns a channel. Nothing to
   synchronize in tests, so close immediately."
  [_atom]
  settled-write)

;; UI hooks — no-ops in tests.
(defn notebook-panel [_state] nil)
(defn secondary-menu-items [_ui] nil)
;; @tags property editing, model identity
(defn editable-model? [model-id _model-def]
  (boolean (seq model-id)))
;; @tags property editing, model identity
(defn edit-model [_model-id] nil)
;; @tags schematic reference context
(defn send-to-agent [_document-key _data _label] nil)
;; @tags factory resolution
(defn resolve-symbol-url [_path & [_revision]] nil)
(defn init-extra! [] nil)
