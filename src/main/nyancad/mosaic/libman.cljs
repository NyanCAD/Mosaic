; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.libman
  (:require clojure.string
            [reagent.core :as r]
            [reagent.dom :as rd]
            [clojure.spec.alpha :as s]
            [nyancad.hipflask :refer [pouch-atom pouchdb sep watch-changes get-group alldocs]]
            [cljs.core.async :refer [go <! timeout]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [nyancad.mosaic.common :as cm]))

; initialise the model database
(defonce db (pouchdb "schematics"))
(defonce remotedb (pouchdb (str cm/couchdb-url "models")))

(defonce modeldb (pouch-atom db "models" (r/atom {})))
(set-validator! (.-cache modeldb)
                #(or (s/valid? :nyancad.mosaic.common/modeldb %) (.log js/console (pr-str %) (s/explain-str :nyancad.mosaic.common/modeldb %))))
(defonce watcher (watch-changes db modeldb))

; used for ephermeal UI state
(defonce selcat (r/atom []))
(defonce selmodel (r/atom nil))
(defonce syncactive (r/atom false))
(defonce filter-text (r/atom ""))
(defonce selection (r/atom {}))
(defonce preview-url (r/atom nil))

; remote model search state
(defonce remotemodeldb (r/atom {}))
(defonce remote-search-loading (r/atom false))

; computed seltype from last element of selcat only if it's a device type
(defonce seltype (r/track #(cm/device-types (last @selcat))))

(defn get-preview 
  "Watch function that loads preview for circuit models"
  [_ _ _ new-model]
  (if new-model
    (go 
      (let [schematic-id (cm/bare-id new-model)
            snapshot-group (str schematic-id "$snapshots")
            docs (<p! (alldocs db #js{:include_docs true
                                      :attachments true
                                      :binary true
                                      :endkey (str snapshot-group sep)
                                      :startkey (str snapshot-group sep "\ufff0")
                                      :descending true
                                      :limit 1}))
            rows (js->clj (.-rows docs) :keywordize-keys true)]
        (if-let [preview-attachment (get-in rows [0 :doc :_attachments :preview.svg :data])]
          (reset! preview-url (js/URL.createObjectURL preview-attachment))
          (reset! preview-url nil))))
    (reset! preview-url nil)))

; Watch selmodel and load preview asynchronously  
(add-watch selmodel ::preview-loader get-preview)

(defn search-remote-models
  "Search remote CouchDB for models matching filter and category"
  [_filter-text _selected-category]
  (go
    (reset! remote-search-loading true)
    (try
      ; TODO: Implement actual CouchDB Mango query
      ; For now, stub with dummy models for testing
      (<! (timeout 1000))
      (reset! remotemodeldb {"models:remote-resistor-1" {:name "High Precision Resistor"
                                                          :type "resistor" 
                                                          :category ["passive" "resistor"]
                                                          :templates {:spice [{:name "default" :code "R{name} {ports} {R}"}]
                                                                     :spectre []
                                                                     :verilog []
                                                                     :vhdl []}}
                             "models:remote-opamp-1" {:name "LM741 OpAmp"
                                                       :type "ckt"
                                                       :category ["analog" "amplifier"]
                                                       :ports {:left ["in-" "in+"]
                                                              :right ["out"]
                                                              :top ["vdd"]
                                                              :bottom ["vss"]}}
                             "models:remote-cap-1" {:name "Ceramic Capacitor"
                                                     :type "capacitor"
                                                     :category ["passive" "capacitor"] 
                                                     :templates {:spice [{:name "default" :code "C{name} {ports} {C}"}]
                                                                :spectre []
                                                                :verilog []
                                                                :vhdl []}}})
      (catch js/Error e
        (js/console.error "Remote search error:" e)
        (reset! remotemodeldb {}))
      (finally
        (reset! remote-search-loading false)))))

; Watch filter-text and selcat for remote searches
(add-watch filter-text ::remote-search 
           (fn [_ _ _ new-filter]
             (search-remote-models new-filter @selcat)))

(add-watch selcat ::remote-search-category
           (fn [_ _ _ new-category]
             (search-remote-models @filter-text new-category)))

; Initialize remote search on load
(search-remote-models @filter-text @selcat)

(defn edit-url [mod]
    (doto (js/URL. ".." js/window.location)
      (.. -searchParams (append "schem" (cm/bare-id mod)))))

(defn transform-direction 
  "Determine cardinal direction from transformation matrix with initial rotation offset.
  Returns one of: :left, :right, :top, :bottom based on the final orientation."
  [{:keys [transform]}]
  (let [; Rotate the transform by the initial rotation offset
        matrix (cm/transform transform)
        ; Transform unit vector (1,0) to see where it points
        point (.transformPoint matrix (cm/point 1 0))
        x (.-x point)
        y (.-y point)
        ; Determine closest cardinal direction
        abs-x (js/Math.abs x)
        abs-y (js/Math.abs y)]
    (if (> abs-x abs-y)
      (if (< x 0) :right :left)
      (if (< y 0) :bottom :top))))


(defn import-ports
  "Import port definitions from the selected model's schematic using get-group"
  [model-id mod]
  (go
    (let [schematic-docs (<! (get-group db (cm/bare-id model-id)))
          port-xf (comp
                   (filter #(= (:type %) "port"))
                   (remove #(= (:variant %) "text"))
                   (map (juxt transform-direction :name)))]
      (swap! mod update :ports 
             #(transduce port-xf
                         (fn [acc [side name]] (update acc side conj name))
                         {:top [] :bottom [] :left [] :right []}
                         (vals schematic-docs))))))

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
                          (when (= (:type model) "ckt")
                            [:li {:on-click #(js/window.open (edit-url model-id), model-id)} "edit"])
                          [:li {:on-click #(swap! db dissoc model-id)} "delete"]])))

(defn vec-startswith [v prefix]
  (= (subvec v 0 (min (count v) (count prefix))) prefix))

(defn build-category-type-index 
  "Build a hierarchical index of categories -> types from flattened model documents"
  [models]
  (reduce (fn [index [_id model]]
            (let [category (or (:category model) [])
                  type (:type model "ckt")]
              (assoc-in index (conj category type) #{})))
          {} models))

(defn categories [base trie]
  [:<>
   (doall (for [[cat subtrie] trie
                :let [path (conj base cat)]]
            [:details.tree {:key path
                            :class path
                            :open (vec-startswith @selcat path)
                            :on-toggle #(do
                                          (.stopPropagation %)
                                          (if (.. % -target -open)
                                            (reset! selcat path)
                                            (when (vec-startswith @selcat path)
                                              (reset! selcat (pop path)))))}
            [:summary cat]
             (when (seq subtrie)
               [:div.detailbody
                [categories path subtrie]])]))])

(defn database-selector []
  [:div.cellsel
   (if (seq @modeldb)
     [categories [] (build-category-type-index @modeldb)]
     [:div.empty "There aren't any models yet"])])


(defn model-list-selector 
  "Show list of individual models for the selected category/type path"
  [db]
  (let [filtered-models (filter (fn [[model-id model]]
                                  (let [model-path (conj (or (:category model) []) (:type model "ckt"))
                                        model-name (get model :name model-id)
                                        filter-match (clojure.string/includes?
                                                      (clojure.string/lower-case model-name)
                                                      (clojure.string/lower-case @filter-text))]
                                    (and (vec-startswith model-path @selcat) filter-match)))
                                @db)]
    [:div.schematics
     ; Installed models section
     [:div.installed-section
      [:h4.section-header "Installed"]
      (if (seq filtered-models)
        [cm/radiobuttons selmodel
         (doall (for [[model-id model] filtered-models
                      :let [schem? (= (:type model "ckt") "ckt")
                            icon (if schem? cm/schemmodel cm/codemodel)]]
                  ; label, key, title
                  [[:span [icon] " " (get model :name model-id)]
                   model-id (get model :name model-id)]))
         (fn [model-id]
           (when (= (get-in @db [model-id :type]) "ckt")
             #(js/window.open (edit-url model-id))))
         (fn [model-id]
           #(model-context-menu % db model-id))]
        [:div.empty "No installed models found"])]
     
     ; Separator and Available models section
     [:div.available-section
      [:h4.section-header "Available"]
      (cond
        @remote-search-loading 
        [:div.loading-spinner "Loading..."]
        
        (seq @remotemodeldb)
        [:div.remote-models
         (doall (for [[model-id model] @remotemodeldb
                      :let [schem? (= (:type model "ckt") "ckt")
                            icon (if schem? cm/schemmodel cm/codemodel)]]
                  [:label.remote-model {:key model-id}
                   [icon] " " (get model :name model-id)
                   [:button.download-btn {:on-click #(do
                                                        (js/console.log "Installing model:" model-id)
                                                        (swap! modeldb assoc model-id model))}
                    [cm/download]]]))]
        
        :else
        [:div.empty "No remote models found"])]]))



(defn port-editor [cell side]
  (let [path [:ports side]]
    [:<>
     [:label {:for (name side)} (name side)]
     [cm/dbfield :input {:id (name side), :type "text"} cell
      #(clojure.string/join " " (get-in % path))
      #(swap! %1 assoc-in path (clojure.string/split %2 #"[, ]+" -1))]]))

(defn model-properties 
  "Edit properties for the selected model"
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
       
       (if (= (:type @mod "ckt") "ckt")
         [:<>
          [:h4 "Port Configuration"]
          [:button {:on-click #(import-ports @selmodel mod)}
           "Import from schematic"]
          [port-editor mod :top]
          [port-editor mod :bottom]
          [port-editor mod :left]
          [port-editor mod :right]]
         [:<>
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
                      :on-change #(swap! template-cursor assoc :use-x (.. % -target -checked))}]])])]
      [:div.empty "Select a model to edit its properties."])))

(defn model-preview [db]
  (let [mod (r/cursor db [@selmodel])]
    [:div.preview
     (if @selmodel
       (if (= (:type @mod "ckt") "ckt")
         [:<>
          [:button.primary {:on-click #(js/window.open (edit-url @selmodel) @selmodel)
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
                        (vec (butlast @selcat))  ; remove type from path
                        @selcat)                 ; use full path if no type selected
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
        [:button.primary {:on-click add-schem }
         [cm/add-model] "Add schematic"]
        [:button {:on-click add-spice }
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
    [database-selector]]
   [cell-view]
   [cm/contextmenu]
   [cm/modal]])

(def shortcuts {})

(defn ^:dev/after-load render []
  (rd/render [library-manager]
             (.querySelector js/document ".mosaic-app.mosaic-libman")))

(defn ^:export init []
;;   (set! js/document.onkeyup (partial cm/keyboard-shortcuts shortcuts))
  (set! js/window.name "libman")
  (set! js/document.onclick #(cm/clear-context-menu))
  (render))
