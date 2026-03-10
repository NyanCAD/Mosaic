; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.libman
  (:require clojure.string
            [reagent.core :as r]
            [reagent.dom :as rd]
            #?@(:vscode [[nyancad.mosaic.libman.platform-vscode
                           :refer [modeldb syncactive
                                   preview-url get-preview search-remote-models
                                   remote-models-section
                                   edit-url import-ports workspace-selector init-extra!]]]
                :cljs    [[nyancad.mosaic.libman.platform-web
                           :refer [modeldb syncactive
                                   preview-url get-preview search-remote-models
                                   remote-models-section
                                   edit-url import-ports workspace-selector init-extra!]]])
            [nyancad.mosaic.common :as cm]))

;; --- Ephemeral UI state ---

(defonce selcat (r/atom []))
(defonce selmodel (r/atom nil))
(defonce filter-text (r/atom ""))
(defonce selection (r/atom {}))

(defonce seltype (r/track #(cm/device-types (peek @selcat))))

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
            [:select {:name "devicetype" :id "device-type" :default-value "diode"}
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
                          (when (not (:templates model))
                            [:li {:on-click #(edit-url model-id)} "edit"])
                          [:li {:on-click #(swap! db dissoc model-id)} "delete"]])))

(defn database-selector []
  [:div.cellsel
   (if (seq @modeldb)
     [cm/category-tree selcat [] (cm/build-category-type-index @modeldb)]
     [:div.empty "There aren't any models yet"])])

(defn model-list-selector
  "Show list of individual models for the selected category/type path."
  [db]
  (let [filtered-models (cm/filter-models @db @selcat @filter-text)]
    [:div.schematics
     ;; Installed models section
     [:div.installed-section
      [:h4.section-header "Installed"]
      [cm/model-list selmodel filtered-models
       (fn [model-id]
         (when (not (get-in @db [model-id :templates]))
           #(edit-url model-id)))
       (fn [model-id]
         #(model-context-menu % db model-id))]]

     ;; Available models section (platform-specific: web shows remote DB, VS Code is no-op)
     [remote-models-section selcat filter-text]]))

(defn port-editor [cell side]
  (let [path [:ports side]]
    [:<>
     [:label {:for (name side)} (name side)]
     [cm/dbfield :input {:id (name side), :type "text"} cell
      #(clojure.string/join " " (get-in % path))
      #(swap! %1 assoc-in path (clojure.string/split %2 #"[, ]+" -1))]]))

(defn parameters-editor [cell]
  [:<>
   [:label {:for "parameters"} "Names"]
   [cm/dbfield :input {:id "parameters", :type "text" :placeholder "resistance dtemp"} cell
    #(clojure.string/join " " (map :name (get % :props [])))
    (fn [atom-ref param-string]
      (let [param-names (clojure.string/split param-string #"[, ]+" -1)]
        (swap! atom-ref update :props
               #(mapv (fn [current new-name]
                        (assoc current :name new-name))
                      (concat % (repeat {}))
                      param-names))))]
   (doall
    (for [[idx param] (map-indexed vector (get @cell :props []))]
      [:<> {:key idx}
       [:label (str (:name param "") " tooltip")]
       [cm/dbfield :input {:type "text"} cell
        #(get-in % [:props idx :tooltip] "")
        #(swap! %1 assoc-in [:props idx :tooltip] %2)]
       [:label (str (:name param "") " default")]
       [cm/dbfield :input {:type "text" :placeholder "1k"} cell
        #(get-in % [:props idx :default] "")
        #(swap! %1 assoc-in [:props idx :default] %2)]]))])

(defn model-properties
  "Edit properties for the selected model."
  [db]
  (let [mod (r/cursor db [@selmodel])
        model-selection (r/cursor selection [@selmodel])
        language-cursor (r/cursor model-selection [:lang])
        implementation-cursor (r/cursor model-selection [:impl])
        lang-cursor (r/cursor mod [:templates @language-cursor])
        template-cursor (r/cursor lang-cursor [@implementation-cursor])]
    (when-not (seq @model-selection)
      (reset! model-selection {:lang :spice :impl 0}))
    (if @selmodel
      [:div.properties
       [:label {:for "model-name"} "Name"]
       [cm/dbfield :input {:id "model-name"} mod
        :name
        #(swap! %1 assoc :name %2)]

       [:label {:for "categories" :title "Comma-seperated device categories"} "Categories"]
       [cm/dbfield :input {:id "categories"} mod
        #(clojure.string/join " " (or (:category %) []))
        #(swap! %1 assoc :category (clojure.string/split %2 #"[, ]+" -1))]

       (when (#{"ckt" "amp"} (:type @mod "ckt"))
         [:<>
          [:h4 "Port Configuration"]
          (when (not (:templates @mod))
            [:button {:on-click #(import-ports @selmodel mod)}
             "Import from schematic"])
          [port-editor mod :top]
          [port-editor mod :bottom]
          [port-editor mod :left]
          [port-editor mod :right]])
       (when (:templates @mod)
         [:<>
          [:h4 "Template Configuration"]
          [:label {:for "device-type" :title "Device type"} "Device Type"]
          [:input {:id "device-type"
                   :type "text"
                   :disabled true
                   :value (:type @mod)}]

          [:label {:for "language"} "Language"]
          [:select {:id "language"
                    :type "text"
                    :value (if-let [lang @language-cursor] (name lang) "spice")
                    :on-change #(reset! language-cursor (keyword (.. % -target -value)))}
           [:option {:value "spice"} "Spice"]
           [:option {:value "spectre"} "Spectre"]
           [:option {:value "verilog"} "Verilog"]
           [:option {:value "vhdl"} "VHDL"]]

          [:label {:for "implementation"} "Implementation"]
          [cm/combobox-field {:id "implementation"} template-cursor implementation-cursor lang-cursor
           #(conj (if (seq %) % [{:name "default"}]) {:name "<new>"})
           #(:name % "default")
           #(swap! %1 assoc :name %2)]

          [:label {:for "template-code"} "Code"]
          [cm/dbfield :textarea {:id "template-code" :rows 8} template-cursor
           :code
           #(swap! %1 assoc :code %2)]

          (when (= @language-cursor :spice)
            [:<>
             [:label {:for "use-x" :title "Forces subcircuit instantiation even for other device types"} "Use X"]
             [:input {:type "checkbox"
                      :id "use-x"
                      :checked (get @template-cursor :use-x false)
                      :on-change #(swap! template-cursor assoc :use-x (.. % -target -checked))}]])])
       [:h4 "Parameters"]
       [parameters-editor mod]]
      [:div.empty "Select a model to edit its properties."])))

(defn model-preview [db]
  (let [mod (r/cursor db [@selmodel])]
    [:div.preview
     (if @selmodel
       (if (not (:templates @mod))
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

(defn cell-view []
  (let [category-path (if @seltype
                        (vec (butlast @selcat))
                        @selcat)
        add-schem #(cm/prompt "Enter the name of the new schematic"
                              (fn [name]
                                (let [model-id (str "models:" (cm/random-name))]
                                  (swap! modeldb assoc model-id
                                         {:name name
                                          :type "ckt"
                                          :category category-path}))))
        add-spice #(spice-model-modal
                    (fn [device-type model-name]
                      (let [model-id (str "models:" (cm/random-name))]
                        (swap! modeldb assoc model-id
                               {:name model-name
                                :type device-type
                                :category category-path
                                :templates {:spice [] :spectre [] :verilog [] :vhdl []}}))))]
    [:<>
     [:div.schsel
      [:div.addbuttons
       [:button.primary {:on-click add-schem}
        [cm/add-model] "Add schematic"]
       [:button {:on-click add-spice}
        [cm/add-model] "Add SPICE model"]]
      [:div.models-header
       [:h2 "Models: " (if (seq @selcat)
                         (clojure.string/join "/" @selcat)
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

(defn ^:dev/after-load render []
  (rd/render [library-manager]
             (.querySelector js/document ".mosaic-app.mosaic-libman")))

(defn ^:export init []
  (set! js/window.name "libman")
  (set! js/document.onclick #(cm/clear-context-menu))
  (init-extra! selmodel selcat filter-text)
  (render))
