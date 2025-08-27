; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.anywidget
  (:require [reagent.core :as r]
            [cljs.core.async :refer [go-loop <!]]
            [nyancad.hipflask :refer [pouch-atom pouchdb watch-changes add-watch-group done?]]))

;; Extract parameters like editor.cljs (but for schematic, not notebook)
(def params (js/URLSearchParams. js/window.location.search))
(def group (or (.get params "schem") "myschem"))
(def dbname (or (.get params "db") "schematics"))

;; Create database and top-level cache for all schematic data
(defonce db (pouchdb dbname))
(defonce schematic-cache (r/atom {group {}, "models" {}})) ; Top-level cache containing all schematics

;; Individual pouch-atoms using cursors into the main cache
(defonce schematic-atom (pouch-atom db group (r/cursor schematic-cache [group])))
(defonce model-atom (pouch-atom db "models" (r/cursor schematic-cache ["models"])))
(defonce simulations (pouch-atom db (str group "$result") (r/atom (sorted-map))))

;; Watch changes for the initial groups - returns atom with group->cache mapping
(defonce schematic-groups (watch-changes db schematic-atom model-atom simulations))

(defn watch-subcircuits []
  (go-loop [devs (seq (vals @schematic-atom))]
    (when (seq devs)
      (let [dev (first devs)
            device-type (:type dev)
            model (:model dev)
            has-templates (get-in @model-atom [model :templates])]
        (if (and model (not (contains? @schematic-groups model)) (not has-templates))
          (let [_ (swap! schematic-cache assoc model {})
                subschem (pouch-atom db model (r/cursor schematic-cache [model]))]
            (add-watch-group schematic-groups subschem)
            (<! (done? subschem))
            (recur (concat (rest devs) (seq (vals @subschem)))))
          (recur (rest devs)))))))

(defn render
  "Render placeholder icon and set up schematic data sync"
  [^js obj]
  (let [model (.-model obj)
        el (.-el obj)]

    ;; Show placeholder icon
    (set! (.-innerHTML el) "Connected to editor.")

    ;; Set initial schematic data and name on model
    (.set model "schematic_data" (clj->js @schematic-cache))
    (.set model "name" group)
    (.save_changes model)

    ;; Watch for changes to schematic atom and update model
    (add-watch schematic-cache ::anywidget-sync
               (fn [key atom old-state new-state]
                 (println "Schematic updated, syncing to anywidget model")
                 (watch-subcircuits)
                 (.set model "schematic_data" (clj->js new-state))
                 (.save_changes model)))

    ;; Watch for simulation data changes from Python side
    (.on model "change:simulation_data"
         (fn []
           (let [simulation-data (js->clj (.get model "simulation_data") :keywordize-keys true)
                 timestamp (.toISOString (js/Date.))
                 key (str group "$result:" timestamp)]
             (println "Received simulation data from Python, storing with key:" key)
             (swap! simulations assoc key simulation-data))))
    nil))
