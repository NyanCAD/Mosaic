; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.libman.platform-vscode
  "JsAtom-backed platform module for the library manager (VS Code deployment)."
  (:require [reagent.core :as r]
            [nyancad.mosaic.jsatom :as jsatom :refer [json-atom vscode send-request!]]
            [nyancad.mosaic.common :as cm]
            [nyancad.hipflask.util :refer [json->clj]]
            [cljs.core.async :refer [go <!]]))

;; --- State ---

(defonce modeldb (json-atom "models"
                   (js/decodeURIComponent (.-value (js/document.getElementById "document")))
                   (r/atom {})))

(defonce syncactive (r/atom false))
(defonce remotemodeldb (r/atom {}))
(defonce remote-search-loading (r/atom false))
(defonce preview-url (r/atom nil))

;; --- Preview ---

(defn get-preview
  "Watch function that loads preview from SVG sidecar file via extension host."
  [_ _ _ new-model]
  (if new-model
    (go
      (let [bare-id (cm/bare-id new-model)
            svg-content (<! (send-request! (str bare-id ".svg")))]
        (when @preview-url (js/URL.revokeObjectURL @preview-url))
        (if svg-content
          (let [blob (js/Blob. #js[svg-content] #js{:type "image/svg+xml"})
                url (js/URL.createObjectURL blob)]
            (reset! preview-url url))
          (reset! preview-url nil))))
    (do (when @preview-url (js/URL.revokeObjectURL @preview-url))
        (reset! preview-url nil))))

;; --- Remote models section (stub — no remote DB in VS Code) ---

(defn remote-models-section [_selcat _filter-text] nil)

;; --- Remote search (stubs — no remote DB in VS Code) ---

(defn search-remote-models [_filter-text _selected-category] nil)
(defn replicate-model [_model-id] nil)
(defn replicate-filtered-models [_selected-category _filter-text] nil)

;; --- Edit & Import ---

(defn edit-url
  "Open the model's schematic in a VS Code editor tab."
  [model-id]
  (.postMessage vscode
    #js{:type "open-file"
        :filename (str (cm/bare-id model-id) ".nyancir")}))

(defn import-ports
  "Import port definitions from the model's schematic file via extension host."
  [model-id mod]
  (go
    (let [bare-id (cm/bare-id model-id)
          json-str (<! (send-request! (str bare-id ".nyancir")))]
      (when json-str
        (let [parsed (js/JSON.parse json-str)
              schematic-docs (into {}
                                   (map (fn [entry]
                                          [(aget entry 0) (json->clj (aget entry 1))]))
                                   (js/Object.entries parsed))
              port-xf (comp
                       (filter #(= (:type %) "port"))
                       (remove #(= (:variant %) "text"))
                       (map (juxt cm/transform-direction :name)))]
          (swap! mod update :ports
                 #(transduce port-xf
                             (fn [acc [side name]] (update acc side conj name))
                             {:top [] :bottom [] :left [] :right []}
                             (vals schematic-docs))))))))

;; --- Workspace selector (stub — no workspaces in VS Code) ---

(defn workspace-selector [] nil)

;; --- Init ---

(defn init-extra!
  "Set up preview watch for VS Code deployment."
  [selmodel _selcat _filter-text]
  (add-watch selmodel ::preview-loader get-preview))
