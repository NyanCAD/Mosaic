; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.filestore
  (:require [reagent.core :as r]
            [cljs.core.async :refer [take! go <!]]
            [nyancad.hipflask :refer [pouch-atom pouchdb watch-changes done? sep]]))

; Extract parameters similar to editor.cljs
(def params (js/URLSearchParams. js/window.location.search))
(def group (str (or (.get params "schem") "myschem") "$notebook"))
(def dbname "schematics")

; Create the database and pouch-atom for notebook storage
(defonce db (pouchdb dbname))
(defonce notebook-atom (pouch-atom db group (r/atom {})))
(defonce notebook-watcher (watch-changes db notebook-atom))

(defn save-file
  "Save file contents to the notebook atom"
  [contents]
  (println "Saving notebook to database")
  (swap! notebook-atom assoc (str group sep "notebook.py")
         {:content contents
          :lastModified (.toISOString (js/Date.))}))

(defn read-file
  "Read file contents from the notebook atom"
  []
  (println "Reading notebook from database")
  (get-in @notebook-atom [(str group sep "notebook.py") :content]))

(defn ^:export init
  "Initialize and register PouchDBFileStore globally"
  []
  ; Create the filestore instance
  (let [pouchdb-store (js-obj
                       "saveFile" save-file
                       "readFile" read-file)]
    ; Set the fileStores array with our PouchDB store
    (set! (.. js/window -__MARIMO_MOUNT_CONFIG__ -config -save)
          (clj->js {:autosave "after_delay", :autosave_delay 2000, :format_on_save true}))
    (set! (.. js/window -__MARIMO_MOUNT_CONFIG__ -fileStores) 
          (clj->js [pouchdb-store]))))
