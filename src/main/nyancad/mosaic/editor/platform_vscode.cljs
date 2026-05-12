; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.platform-vscode
  (:require [nyancad.mosaic.jsatom :as jsatom :refer [json-atom vscode send-request!]]
            [nyancad.mosaic.common :as cm]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
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

;; React root for the editor canvas.
(defonce root (rdc/create-root (.querySelector js/document ".mosaic-app.mosaic-editor")))

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
  "VSCode secondary menu: simulate and open library manager."
  [_notebook-state]
  [:<>
   [:a {:title "Simulate"
        :on-click #(.postMessage vscode
                     #js{:type "start-simulation"})}
    [cm/simulate]]
   [:a {:title "Open library manager"
        :on-click #(.postMessage vscode
                     #js{:type "open-file"
                         :filename "models.nyanlib"})}
    [cm/library]]])

(defn menu-toolbar
  "VS Code: flat toolbar, no groups."
  [{:keys [tool cancel add-device ui transform-selected delete-selected
           copy cut paste button-zoom undo-schematic redo-schematic]}]
  [:<>
   [cm/radiobuttons tool
    [[[cm/cursor] :nyancad.mosaic.editor/cursor "Cursor [esc]"]
     [[cm/wire] :nyancad.mosaic.editor/wire "Wire [w]"]
     [[cm/move] :nyancad.mosaic.editor/pan "Pan [space]"]
     [[cm/pin-angle] :nyancad.mosaic.editor/port "Port marker [p]"]]
    nil nil
    (fn [new-tool]
      (if (= new-tool :nyancad.mosaic.editor/port)
        (add-device "port" (:nyancad.mosaic.editor/mouse @ui))
        (cancel new-tool)))]
   [:a.disabled {:title "Eraser" :style {:opacity 0.3 :pointer-events "none"}} [cm/eraser]]
   [:a.disabled {:title "Probe"  :style {:opacity 0.3 :pointer-events "none"}} [cm/probe]]
   [:a {:title "Rotate selected clockwise [s]"
        :on-click (fn [_] (transform-selected #(.rotate % 90)))}
    [cm/rotatecw]]
   [:a {:title "Rotate selected counter-clockwise [shift+s]"
        :on-click (fn [_] (transform-selected #(.rotate % -90)))}
    [cm/rotateccw]]
   [:a {:title "Mirror selected horizontal [shift+f]"
        :on-click (fn [_] (transform-selected #(.flipY %)))}
    [cm/mirror-horizontal]]
   [:a {:title "Mirror selected vertical [f]"
        :on-click (fn [_] (transform-selected #(.flipX %)))}
    [cm/mirror-vertical]]
   [:a {:title "Delete selected [del]"
        :on-click (fn [_] (delete-selected))}
    [cm/delete]]
   [:a {:title "Copy selected [ctrl+c]"
        :on-click (fn [_] (copy))}
    [cm/copyi]]
   [:a {:title "Cut selected [ctrl+x]"
        :on-click (fn [_] (cut))}
    [cm/cuti]]
   [:a {:title "Paste [ctrl+v]"
        :on-click (fn [_] (paste))}
    [cm/pastei]]
   [:a {:title "zoom in [scroll wheel/pinch]"
        :on-click #(button-zoom -1)}
    [cm/zoom-in]]
   [:a {:title "zoom out [scroll wheel/pinch]"
        :on-click #(button-zoom 1)}
    [cm/zoom-out]]
   [:a {:title "undo [ctrl+z]"
        :on-click undo-schematic}
    [cm/undoi]]
   [:a {:title "redo [ctrl+shift+z]"
        :on-click redo-schematic}
    [cm/redoi]]])

(defn menu-extras
  "VS Code: switch-editor button, right-aligned."
  [_ctx]
  [:a {:title "Open in Livewire layout editor"
       :on-click #(.postMessage vscode
                    #js{:type "switchEditor"
                        :viewType "gdsfactoryplus.livewireNyancirEditor"})}
   [cm/sync-active]])

(defn device-tray-items
  "VS Code: flat top-level buttons, no expandable groups."
  [{:keys [add-device add-gnd add-supply add-label device-active]}]
  [:<>
   [:button {:title "Add port [p]"
             :class (device-active "port")
             :on-pointer-up #(add-device "port" (cm/viewbox-coord %))}
    [cm/device-icon "port"]]
   [:button {:title "Add wire label [t]"
             :class (device-active "port")
             :on-pointer-up #(add-label (cm/viewbox-coord %))}
    [cm/namei]]
   [:button {:title "Add ground [g]"
             :class (device-active "port")
             :on-pointer-up #(add-gnd (cm/viewbox-coord %))}
    [cm/device-icon "ground"]]
   [:button {:title "Add power supply [shift+p]"
             :class (device-active "port")
             :on-pointer-up #(add-supply (cm/viewbox-coord %))}
    [cm/device-icon "supply"]]
   [:button {:title "Add text area [shift+t]"
             :class (device-active "port")
             :on-pointer-up #(add-device "text" (cm/viewbox-coord %))}
    [cm/text]]
   [:button {:title "Add voltage source [v]"
             :class (device-active "vsource")
             :on-pointer-up #(add-device "vsource" (cm/viewbox-coord %))}
    [cm/device-icon "vsource"]]
   [:button {:title "Add current source [i]"
             :class (device-active "isource")
             :on-pointer-up #(add-device "isource" (cm/viewbox-coord %))}
    [cm/device-icon "isource"]]])

(defn- make-name [device-type]
  (let [prefix (cm/initial device-type)]
    (first (remove #(contains? @schematic (str group ":" %))
                   (map #(str prefix %) (next (range)))))))

(defn- on-drop [e]
  (.preventDefault e)
  (.stopPropagation e)
  (let [dt (.-dataTransfer e)
        fqn (some-> (.getData dt "text/x-gfp-factory") js/JSON.parse js->clj (get "factory"))]
    (js/console.log "Dropped FQN:" fqn)
    (when (seq fqn)
      (let [model-key (cm/model-key fqn)
            model-def (get @modeldb model-key)]
        (if-not model-def
          (js/console.warn "Dropped unknown model (no schematic?):" fqn)
          (let [[x y] (cm/viewbox-coord e)
                raw-type (or (:type model-def) "ckt")
                device-type (if (contains? cm/device-types raw-type) raw-type "ckt")
                name (make-name device-type)
                dev {:type device-type
                     :model fqn
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
