; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.anywidget
  (:require [reagent.core :as r]
            [nyancad.hipflask :refer [pouch-atom pouchdb watch-changes sep]]))

;; Extract parameters like editor.cljs (but for schematic, not notebook)
(def params (js/URLSearchParams. js/window.location.search))
(def group (or (.get params "schem") "myschem"))
(def dbname (or (.get params "db") "schematics"))

;; Create database and pouch-atom for schematic access
(defonce db (pouchdb dbname))
(defonce schematic-atom (pouch-atom db group (r/atom {})))
(defonce schematic-watcher (watch-changes db schematic-atom))

(defn render
  "Render placeholder icon and set up schematic data sync"
  [^js obj]
  (let [model (.-model obj)
        el (.-el obj)]

    ;; Show placeholder icon
    (set! (.-innerHTML el) "Connected to editor.")

    ;; Set initial schematic data on model
    (.set model "schematic_data" (clj->js @schematic-atom))
    (.save_changes model)

    ;; Watch for changes to schematic atom and update model
    (add-watch schematic-atom ::anywidget-sync
               (fn [key atom old-state new-state]
                 (println "Schematic updated, syncing to anywidget model")
                 (.set model "schematic_data" (clj->js new-state))
                 (.save_changes model)))
    nil))
