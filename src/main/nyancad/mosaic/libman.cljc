; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.libman
  (:require clojure.string
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [#?(:web    nyancad.mosaic.libman.platform-web
                :vscode nyancad.mosaic.libman.platform-vscode
                :test   nyancad.mosaic.libman.platform-test)
             :refer [modeldb syncactive
                     preview-url get-preview search-remote-models
                     remote-models-section
                     edit-url import-ports workspace-selector init-extra!]]
            [nyancad.mosaic.common :as cm]))

;; --- Ephemeral UI state ---

(defonce selcat (r/atom []))
(defonce selmodel (r/atom nil))
(defonce filter-text (r/atom ""))
(defonce selection (r/atom {}))

(defonce seltype
  (r/track #(some (fn [t] (let [[k v] (cm/parse-prop-tag t)]
                            (when (= k "type") v)))
                  @selcat)))

;; --- UI components ---

(defn spice-model-modal [cb]
  (reset! cm/modal-content
          [:form {:on-submit (fn [^js e]
                               (.preventDefault e)
                               (let [device-type (.. e -target -elements -devicetype -value)
                                     model-name (.. e -target -elements -modelname -value)]
                                 (when (and (seq model-name) (contains? cm/device-types device-type))
                                   (cb device-type model-name)))
                               (reset! cm/modal-content nil))}
           [:div "Enter the type and name of the new SPICE model"]
           [:div
            [:select {:name "devicetype" :id "device-type" :default-value (or @seltype "diode")}
             (for [dtype (sort cm/device-types)]
               [:option {:key dtype :value dtype} dtype])]
            [:input {:name "modelname" :id "model-name" :type "text" :auto-focus true :placeholder "Model name"}]]
           [:div
            [:button {:type "button" :on-click #(reset! cm/modal-content nil)} "Cancel"]
            [:input {:type "submit" :value "Create"}]]]))

(defn model-context-menu [e db model-id]
  (.preventDefault e)
  (let [model (get @db model-id)]
    (cm/set-context-menu (.-clientX e) (.-clientY e)
                         [:ul
                          (when (not (cm/has-code-models? model))
                            [:li {:on-click #(edit-url model-id)} "edit"])
                          [:li {:on-click #(swap! db dissoc model-id)} "delete"]])))

(defn database-selector []
  [:div.cellsel
   (if (seq @modeldb)
     [cm/tag-tree selcat (cm/build-tag-index @modeldb)]
     [:div.empty "There aren't any models yet"])])

(defn model-list-selector
  "Show list of individual models for the selected tag filters."
  [db]
  (let [filtered-models (cm/filter-models @db @selcat @filter-text)]
    [:div.schematics
     ;; Installed models section
     [:div.installed-section
      [:h4.section-header "Installed"]
      [cm/model-list selmodel filtered-models
       (fn [model-id]
         (when (not (cm/has-code-models? (get @db model-id)))
           #(edit-url model-id)))
       (fn [model-id]
         #(model-context-menu % db model-id))
       #(when-not (some #{%} @selcat)
          (swap! selcat conj %))]]

     ;; Available models section (platform-specific: web shows remote DB, VS Code is no-op)
     [remote-models-section selcat filter-text]]))

(defn parameters-editor [cell]
  [cm/recursive-editor
   [{:name "props" :tooltip "Parameters" :type :h4
     :children [{:name "name" :tooltip "Parameter name"}
                {:name "tooltip" :tooltip "Description"}
                {:name "default" :tooltip "Default value"}
                {:name "important" :tooltip "Show on schematic" :type :checkbox}]}]
   cell])

(defn model-properties
  "Edit properties for the selected model."
  [db]
  (let [mod (r/cursor db [@selmodel])]
    (if @selmodel
      [:div.properties
       [:label {:for "model-name"} "Name"]
       [cm/dbfield :input {:id "model-name"} mod
        :name
        #(swap! %1 assoc :name %2)]

       [:label {:for "tags" :title "Space-separated tags (first 2 form tree, rest are badges)"} "Tags"]
       [cm/dbfield :input {:id "tags"} mod
        #(clojure.string/join " " (or (:tags %) []))
        #(swap! %1 assoc :tags (vec (remove empty? (clojure.string/split %2 #"[, ]+"))))]

       (when (#{"ckt" "amp"} (:type @mod "ckt"))
         [:<>
          [:label {:for "symbol-url" :title "Image URL to render inside the schematic symbol"} "Symbol URL"]
          [cm/dbfield :input {:id "symbol-url" :type "url" :placeholder "https://..."} mod
           :symbol
           #(swap! %1 assoc :symbol %2)]
          (when (not (cm/has-code-models? @mod))
            [:button {:on-click #(import-ports @selmodel mod)}
             "Import from schematic"])
          [cm/recursive-editor
           [{:name "ports" :tooltip "Port Configuration" :type :h4
             :children
             [{:name "name" :tooltip "Port name"}
              {:name "side" :tooltip "Side" :type :select :default "left"
               :options [{:value "left" :label "Left"}
                         {:value "right" :label "Right"}
                         {:value "top" :label "Top"}
                         {:value "bottom" :label "Bottom"}]}
              {:name "type" :tooltip "Port type" :type :select :default "electric"
               :options [{:value "electric" :label "Electric"}
                         {:value "photonic" :label "Photonic"}]}]}]
           mod]])
       (when (cm/has-code-models? @mod)
         [cm/recursive-editor
           [{:name "models" :tooltip "Model Configuration" :type :h4
             :children
             [{:name "language" :tooltip "Language" :type :select :default "spice"
               :options [{:value "spice" :label "SPICE"}
                         {:value "sax" :label "SAX"}
                         {:value "verilog-a" :label "Verilog-A"}
                         {:value "vhdl" :label "VHDL"}
                         {:value "spectre" :label "Spectre"}]}
              {:name "implementation" :tooltip "Variant (e.g. ngspice, behavioral)"}
              {:name "name" :tooltip "Model name in netlist"}
              {:name "spice-type" :tooltip "SPICE prefix type" :type :select
               :options [{:value "" :label "None"}
                         {:value "SUBCKT" :label "SUBCKT"}
                         {:value "R" :label "R"} {:value "C" :label "C"}
                         {:value "L" :label "L"} {:value "D" :label "D"}
                         {:value "M" :label "M"} {:value "Q" :label "Q"}
                         {:value "V" :label "V"} {:value "I" :label "I"}]}
              {:name "library" :tooltip "Library file path"}
              {:name "sections" :tooltip "PVT corners" :type :csv}
              {:name "code" :tooltip "Inline model code" :type :textarea}
              {:name "port-order" :tooltip "Port connection order" :type :csv}]}]
           mod])
       [parameters-editor mod]]
      [:div.empty "Select a model to edit its properties."])))

(defn model-preview [db]
  (let [mod (r/cursor db [@selmodel])]
    [:div.preview
     (if @selmodel
       (if (not (cm/has-code-models? @mod))
         [:<>
          [:button.primary {:on-click #(edit-url @selmodel)
                            :title "Edit"} [cm/edit] " Edit"]
          (if @preview-url
            [:object {:data @preview-url
                      :type "image/svg+xml"}
             "Schematic preview"]
            [:div.empty
             "No preview available. Use the snapshot button in the schematic editor to create one."])]
         [:div.empty "N/A"])
       [:div.empty "Select a model to preview."])]))

(defn plain-tags
  "Return only plain (non-property) tags from a tag vector."
  [tags]
  (vec (remove cm/parse-prop-tag tags)))

(defn- prop-tag-matches?
  "True if tag is plain, or if it's a prop-tag whose k:v matches doc."
  [tag doc]
  (if-let [[k v] (cm/parse-prop-tag tag)]
    (= v (str (get doc (keyword k))))
    true))

(defn cell-view []
  (letfn [(create-model! [model-id doc]
            (swap! modeldb assoc model-id doc)
            (swap! selcat (fn [v] (vec (filter #(prop-tag-matches? % doc) v))))
            (reset! filter-text "")
            (reset! selmodel model-id)
            (js/requestAnimationFrame
             (fn []
               (when-let [label (some-> (js/document.getElementById model-id)
                                        .-labels
                                        (.item 0))]
                 (.scrollIntoView label #js {:block "nearest" :behavior "smooth"})))))
          (add-schem []
            (cm/prompt "Enter the name of the new schematic"
                       (fn [name]
                         (create-model! (str "models:" (cm/random-name))
                                        {:name name
                                         :type "ckt"
                                         :tags (plain-tags @selcat)}))))
          (add-spice []
            (spice-model-modal
             (fn [device-type model-name]
               (create-model! (str "models:" (cm/random-name))
                              {:name model-name
                               :type device-type
                               :tags (plain-tags @selcat)
                               :models [{:language "spice"}]}))))]
    [:<>
     [:div.schsel
      [:div.addbuttons
       [:button.primary {:on-click add-schem}
        [cm/add-model] "Add schematic"]
       [:button {:on-click add-spice}
        [cm/add-model] "Add SPICE model"]]
      [:div.models-header
       [:h2 "Models: "
        (if (seq @selcat)
          (for [tag @selcat]
            [:span.tag-badge.active
             {:key tag
              :class (when (cm/parse-prop-tag tag) "prop-tag")
              :on-click #(swap! selcat (fn [v] (vec (remove #{tag} v))))}
             tag " \u00d7"])
          "All models")]
       [cm/dbfield :input {:type "text" :placeholder "Filter models..." :class "filter-input"}
        filter-text identity reset!]]
      [model-list-selector modeldb]]
     [:div.proppane
      [model-preview modeldb]
      [:h3 "Model Properties"]
      [model-properties modeldb]]]))

(defn library-manager []
  [:<>
   [:div.libraries
    [:div.libhead
     [:h1 "Libraries"]
     (if @syncactive
       [:span.syncstatus.active {:title "saving changes"} [cm/sync-active]]
       [:span.syncstatus.done   {:title "changes saved"} [cm/sync-done]])]
    [database-selector]
    [workspace-selector]]
   [cell-view]
   [cm/contextmenu]
   [cm/modal]])

(def shortcuts {})

;; The react root binds to a DOM element at load time, which can't
;; happen under :test (Node has no document). Only create it for real
;; deployments; tests exercise helpers directly and never render.
#?(:web    (defonce root (rdc/create-root (.querySelector js/document ".mosaic-app.mosaic-libman")))
   :vscode (defonce root (rdc/create-root (.querySelector js/document ".mosaic-app.mosaic-libman"))))

(defn ^:dev/after-load render []
  (rdc/render root [library-manager]))

(defn ^:export init []
  (set! js/window.name "libman")
  (set! js/document.onclick #(cm/clear-context-menu))
  (init-extra! selmodel selcat filter-text)
  (render))
