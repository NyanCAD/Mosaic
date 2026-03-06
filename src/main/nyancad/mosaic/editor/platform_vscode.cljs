; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.platform-vscode
  (:require [nyancad.mosaic.jsatom :as jsatom :refer [json-atom vscode]]
            [nyancad.mosaic.common :as cm]
            [reagent.core :as r]
            [clojure.spec.alpha :as s]))

;; Re-export done? so editor can :refer it from this namespace
(def done? jsatom/done?)

;; --- State ---

(def group (.-value (js/document.getElementById "group")))
(defonce schematic (json-atom "schematic"
                    (js/decodeURIComponent (.-value (js/document.getElementById "document")))
                    (r/atom {})))
(set-validator! schematic
                #(or (s/valid? :nyancad.mosaic.common/schematic %) (.log js/console (pr-str %) (s/explain-str :nyancad.mosaic.common/schematic %))))
;; Secondary modeldb atom — initialized from models.nyanlib injected by extension
(defonce modeldb (json-atom "models"
                   (js/decodeURIComponent (.-value (js/document.getElementById "models")))
                   (r/atom {})))
(defonce snapshots (r/atom {}))
(defonce simulations (r/atom (sorted-map)))
(defonce local (r/atom {}))

(defonce syncactive (r/atom false))

;; --- Functions ---

(defn open-schematic
  "Open a subcircuit's .nyancir file in VS Code via the extension host."
  [model-id]
  (.postMessage vscode
    #js{:type "open-file"
        :filename (str model-id ".nyancir")}))

(defn notebook-panel
  "No notebook panel in VSCode."
  [_notebook-popped-out]
  nil)

(defn secondary-menu-items
  "VSCode secondary menu: open library manager."
  [_notebook-popped-out]
  [:a {:title "Open library manager"
       :on-click #(.postMessage vscode
                    #js{:type "open-file"
                        :filename "models.nyanlib"})}
   [cm/library]])

(defn init-extra!
  "Set up get-state handler for SVG preview requests from extension host."
  []
  (.addEventListener js/window "message"
    (fn [^js event]
      (when (= (.. event -data -type) "get-state")
        (let [request-id (.. event -data -requestId)
              key (.. event -data -key)]
          (when (= key "preview")
            (let [svg-el (js/document.querySelector ".mosaic-canvas")]
              (.postMessage vscode
                #js{:type "state-response"
                    :requestId request-id
                    :value (when svg-el
                             (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                                  (.-outerHTML svg-el)))}))))))))
