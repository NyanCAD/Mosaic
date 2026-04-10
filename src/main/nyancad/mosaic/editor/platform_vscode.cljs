; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.platform-vscode
  (:require [nyancad.mosaic.jsatom :as jsatom :refer [json-atom vscode send-request!]]
            [nyancad.mosaic.common :as cm]
            [reagent.core :as r]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [cljs.core.async :refer [go <!]]))

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

;; --- Symbol URL resolution ---

(defonce ^:private symbol-url-cache (r/atom {}))

(defn resolve-symbol-url
  "Resolve a symbol path to a blob URL. Returns a reactive cursor (deref for the URL).
   Triggers async load on first call for each path; cursor yields nil until loaded."
  [path]
  (when (seq path)
    (when-not (contains? @symbol-url-cache path)
      (go
        (let [content (<! (send-request! path))]
          (when content
            (let [type (if (str/ends-with? path ".svg") "image/svg+xml" "image/png")
                  blob (js/Blob. #js[content] #js{:type type})
                  url (js/URL.createObjectURL blob)]
              (swap! symbol-url-cache assoc path url))))))
    (r/cursor symbol-url-cache [path])))

;; --- Functions ---

(defn open-schematic
  "Open a subcircuit's .nyancir file in VS Code via the extension host."
  [model-id]
  (.postMessage vscode
    #js{:type "open-file"
        :filename (str model-id ".nyancir")}))

(defn notebook-panel
  "No notebook panel in VSCode."
  [_notebook-state]
  nil)

(defn secondary-menu-items
  "VSCode secondary menu: open library manager."
  [_notebook-state]
  [:a {:title "Open library manager"
       :on-click #(.postMessage vscode
                    #js{:type "open-file"
                        :filename "models.nyanlib"})}
   [cm/library]])

(defn- extract-gds-name
  "Extract bare model ID from a dropped text/plain or URI path.
   Matches (.*).gds on plain text first, else takes basename of a path."
  [plain uri-path]
  (or (when-let [[_ name] (re-matches #"(.*)\.gds$" (str plain))]
        name)
      (when-let [filename (last (str/split (str uri-path) #"[/\\]"))]
        (when-let [[_ name] (re-matches #"(.*)\.gds$" filename)]
          name))))

(defn- make-name [device-type]
  (let [prefix (cm/initial device-type)]
    (first (remove #(contains? @schematic (str group ":" %))
                   (map #(str prefix %) (next (range)))))))

(defn- on-drop [e]
  (.preventDefault e)
  (.stopPropagation e)
  (let [dt (.-dataTransfer e)
        uri-list (.getData dt "text/uri-list")
        plain (.getData dt "text/plain")
        uri-path (when (seq uri-list)
                   (let [uri (str/trim (first (str/split-lines uri-list)))]
                     (try
                       (js/decodeURIComponent (.-pathname (js/URL. uri)))
                       (catch :default _ nil))))
        model-id (extract-gds-name plain uri-path)]
    (when model-id
      (let [model-key (cm/model-key model-id)
            model-def (get @modeldb model-key)]
        (if-not model-def
          (js/console.warn "Dropped unknown model (no schematic?):" model-id)
          (let [[x y] (cm/viewbox-coord e)
                raw-type (or (:type model-def) "ckt")
                device-type (if (contains? cm/device-types raw-type) raw-type "ckt")
                name (make-name device-type)
                dev {:type device-type
                     :model model-id
                     :name name
                     :transform cm/IV
                     :x (Math/round x)
                     :y (Math/round y)}]
            (if (s/valid? :nyancad.mosaic.common/device dev)
              (swap! schematic assoc (str group ":" name) dev)
              (js/console.warn "Invalid device from drop:" (s/explain-str :nyancad.mosaic.common/device dev)))))))))

(defn init-extra!
  "Set up get-state handler and drop target for VS Code webview."
  []
  (.addEventListener js/document "dragover" #(.preventDefault %))
  (.addEventListener js/document "drop" on-drop)
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
