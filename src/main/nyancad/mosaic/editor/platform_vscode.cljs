; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.platform-vscode
  (:require [nyancad.mosaic.jsatom :as jsatom :refer [json-atom]]
            [nyancad.mosaic.common :as cm]
            [reagent.core :as r]
            [clojure.spec.alpha :as s]))

;; Re-export done? so editor can :refer it from this namespace
(def done? jsatom/done?)

;; --- State ---

(def group "schematic")
(defonce schematic (json-atom
                    (js/decodeURIComponent (.-value (js/document.getElementById "document")))
                    (r/atom {})))
(set-validator! schematic
                #(or (s/valid? :nyancad.mosaic.common/schematic %) (.log js/console (pr-str %) (s/explain-str :nyancad.mosaic.common/schematic %))))
(defonce modeldb (r/atom {}))
(defonce snapshots (r/atom {}))
(defonce simulations (r/atom (sorted-map)))
(defonce local (r/atom {}))

(defonce syncactive (r/atom false))

;; --- Stubs ---

(defn notebook-panel
  "No notebook panel in VSCode."
  [_notebook-popped-out]
  nil)

(defn secondary-menu-items
  "No extra menu items in VSCode."
  [_notebook-popped-out]
  nil)

(defn init-extra!
  "No extra init in VSCode."
  []
  nil)
