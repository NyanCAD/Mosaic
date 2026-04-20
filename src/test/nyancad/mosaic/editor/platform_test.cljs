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
            [cljs.core.async :refer [go]]))

(def group "test$top")

(defonce schematic (r/atom {}))
(defonce modeldb (r/atom {}))
(defonce snapshots (r/atom {}))
(defonce simulations (r/atom (sorted-map)))
(defonce local (r/atom {}))
(defonce syncactive (r/atom false))

(defn done?
  "Match the JsAtom/pouch-atom contract: returns a channel. Nothing to
   synchronize in tests, so close immediately."
  [_atom]
  (go nil))

;; UI hooks — no-ops in tests.
(defn notebook-panel [_state] nil)
(defn secondary-menu-items [_state] nil)
(defn open-schematic [_model-id] nil)
(defn resolve-symbol-url [_path] nil)
(defn init-extra! [] nil)
