; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.platform-vscode
  (:require [nyancad.mosaic.jsatom :as jsatom :refer [json-atom vscode send-request! injected-version]]
            [nyancad.mosaic.common :as cm]
            [nyancad.mosaic.jsatom.protocol :as ja]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [cljs.core.async :refer [go <!]]))

;; Re-export done? so editor can :refer it from this namespace
(def done? jsatom/done?)

;; --- State ---

;; @tags factory resolution
(defn model-cursor
  "Return a reactive cache cursor and register demand for its component."
  [source & keys]
  (ja/gfp-cursor source (vec keys)))

;; @tags factory resolution
(defn get-model-async
  "Resolve the component before reading a nested value; preserve resolution errors."
  [source & keys]
  (if (seq keys)
    (let [parameterized? (= :parameterizations (second keys))
          identity (if parameterized? (vec (take 3 keys)) (first keys))
          fields (drop (if parameterized? 3 1) keys)
          result (ja/get-async source identity)]
      (if (seq fields)
        (go
          (let [entry (<! result)]
            (if (instance? js/Error entry)
              entry
               (get-in entry fields))))
        result))
    (go @source)))

;; @tags factory resolution
(defn renamable-model-name
  "Render the catalogue-owned component name as read-only text in VS Code."
  [source model-key]
  [:span (or @(model-cursor source model-key :name) "Untitled")])

(def group (.-value (js/document.getElementById "group")))
(defonce schematic (json-atom "schematic"
                     (js/decodeURIComponent (.-value (js/document.getElementById "document")))
                     (r/atom {})
                     (injected-version "document-version")))
;; If the initial document is invalid, clear the cache before registering
;; the validator so set-validator!'s re-validation passes. The validator's
;; cm/alert side effect has already fired on the check. JS is single-threaded
;; so the check-then-clear-then-set sequence is race-free.
(let [v #(or (s/valid? :nyancad.mosaic.common/schematic %) (cm/alert (s/explain-str :nyancad.mosaic.common/schematic %)))]
  (when-not (v @schematic) (reset! (.-cache schematic) {}))
  (set-validator! schematic v))
;; Secondary modeldb atom — initialized from models.nyanlib injected by extension
(defonce modeldb (jsatom/gfpatom
                     (js/decodeURIComponent (.-value (js/document.getElementById "models")))
                     (r/atom {})))
(defonce snapshots (r/atom {}))
(defonce simulations (r/atom (sorted-map)))
(defonce local (r/atom {}))

(defonce syncactive (r/atom false))

;; React root for the editor canvas.
(defonce root (rdc/create-root (.querySelector js/document ".mosaic-app.mosaic-editor")))

;; --- Symbol URL resolution ---

(defonce ^:private symbol-url-cache (atom {}))

;; @tags factory resolution, on-demand symbol generation
(defn resolve-symbol-url
  "Read an existing image into a cached URL ratom, once per path/revision.
   A new revision gets its own ratom seeded with the previous URL while loading,
   so an older read cannot replace its URL and refresh does not blank the image.
   Uses data: URLs instead of blob: URLs so the URL resolves in isolated contexts
   (SVG <image> secure-static mode), enabling nested gsch subcircuit symbols to
   render recursively without document-scoping failures."
  [path & [revision]]
  (when (seq path)
    (let [cached (get @symbol-url-cache path)]
      (if (and cached (= revision (:revision cached)))
        (:url cached)
        (let [url (r/atom (when cached @(:url cached)))]
          (swap! symbol-url-cache assoc path {:revision revision :url url})
          (go
            (when-let [content (<! (send-request! path))]
              (let [type (if (str/ends-with? path ".svg") "image/svg+xml" "image/png")]
                (reset! url (str "data:" type ";base64," (cm/b64encode content))))))
          url)))))

;; --- Functions ---

;; @tags property editing, model identity
(defn editable-model?
  "True when a component identity can be opened as schematic or Python source."
  [model-id _model-def]
  (boolean (seq model-id)))

;; @tags property editing, model identity
(defn edit-model
  "Open a subcircuit schematic or a Python factory's indexed source."
  [model-id]
  (if (str/ends-with? model-id ".gsch")
    (.postMessage vscode
      #js{:type "open-file"
          :filename model-id})
    (.postMessage vscode
      #js{:type "open-factory-source"
          :factoryFqn model-id})))

(defn notebook-panel
  "No notebook panel in VSCode."
  [_notebook-state]
  nil)

(defn secondary-menu-items
  "VSCode secondary menu: simulate (toolbar-group dropdown), switch to Livewire."
  [_ui]
  [:<>
   [:a {:title "Simulate"
        :on-click #(.postMessage vscode
                                 #js{:type "open-sax-simulation"})}
    [cm/rocket-icon]]
   [:a {:title "Open in Livewire layout editor"
        :on-click #(.postMessage vscode
                                 #js{:type "switchEditor"
                                     :viewType "gdsfactoryplus.livewireNyancirEditor"})}
    [cm/layout-icon]]])

;; @tags schematic reference context
(defn send-to-agent
  "Send a schematic element to the AI agent as context."
  [document-key data label]
  (.postMessage vscode
    #js{:type "send-to-agent"
        :documentKey document-key
        :data (clj->js data)
        :label label}))

;; @tags gsch symbol sidecar
(defn serialize-styles
  "Serialize all reachable CSS rules from document.styleSheets into a string.
   Cross-origin sheets throw on cssRules access and are skipped. Prepends a
   :root color-scheme rule so light-dark() fallbacks resolve in the isolated
   SVG image context where VS Code theme variables are absent."
  []
  (str ":root { color-scheme: light dark; }\n"
       (->> (array-seq (.-styleSheets js/document))
            (keep (fn [^js sheet]
                    (try
                      (clojure.string/join
                        "\n"
                        (map #(.-cssText ^js %)
                             (array-seq (.. sheet -cssRules))))
                      (catch :default _e nil))))
            (clojure.string/join "\n"))))

(defn init-extra!
  "Set up message handlers for VS Code webview."
  []
  (.addEventListener js/window "message"
    (fn [^js event]
      (case (.. event -data -type)
        "get-state"
        (let [request-id (.. event -data -requestId)
              key (.. event -data -key)]
          (when (= key "preview")
            (let [svg-el (js/document.querySelector ".mosaic-canvas")
                  clone (when svg-el (.cloneNode svg-el true))]
              (when clone
                (.removeAttribute clone "class")
                (.setAttribute clone "class" "gsch-symbol")
                (let [style-el (.createElementNS
                                 js/document
                                 "http://www.w3.org/2000/svg"
                                 "style")]
                  (set! (.-textContent style-el) (serialize-styles))
                  (.insertBefore clone style-el (.-firstChild clone))))
              (.postMessage vscode
                #js{:type "state-response"
                    :requestId request-id
                    :value (when clone
                             (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                                  (.-outerHTML clone)))}))))
        "zoom-to-fit"
        (.dispatchEvent js/document (js/CustomEvent. "mosaic-zoom-to-fit"))
        nil))))
