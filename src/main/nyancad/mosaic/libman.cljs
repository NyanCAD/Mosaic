; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.libman
  (:require clojure.string
            [reagent.core :as r]
            [reagent.dom :as rd]
            [nyancad.hipflask :refer [pouch-atom pouchdb sep watch-changes]]
            [nyancad.mosaic.common :as cm]))

; initialise the model database
(def sync (cm/get-sync-url))
(defonce db (pouchdb "schematics"))

(defonce modeldb (pouch-atom db "models" (r/atom {})))
(defonce snapshots (pouch-atom db "snapshots" (r/atom {})))
(defonce watcher (watch-changes db modeldb snapshots))

; Device types for SPICE models - corresponds to editor device types
(def device-types #{"pmos" "nmos" "npn" "pnp" "resistor" "capacitor" 
                    "inductor" "vsource" "isource" "diode"})

; used for ephermeal UI state
(defonce selcat (r/atom []))
(defonce selmodel (r/atom nil))
(defonce syncactive (r/atom false))

; computed seltype from last element of selcat only if it's a device type
(defonce seltype (r/track #(device-types (last @selcat))))

(defn edit-url [mod]
    (doto (js/URL. ".." js/window.location)
      (.. -searchParams (append "schem" mod))))

(defn spice-model-modal [cb]
  (reset! cm/modal-content
          [:form {:on-submit (fn [^js e]
                               (.preventDefault e)
                               (let [device-type (.. e -target -elements -devicetype -value)
                                     model-name (.. e -target -elements -modelname -value)]
                                 (when (and (seq model-name) (contains? device-types device-type))
                                   (cb device-type model-name)))
                               (reset! cm/modal-content nil))}
           [:div "Enter the type and name of the new SPICE model"]
           [:div
            [:select {:name "devicetype" :id "device-type" :default-value "diode"}
             (for [dtype (sort device-types)]
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
                  type (:type model "uncategorized")]
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
  (let [filtered-models (filter (fn [[_id model]]
                                  (let [model-path (conj (or (:category model) []) (:type model))]
                                    (vec-startswith model-path @selcat)))
                                @db)]
    [:div.schematics
     [cm/radiobuttons selmodel
      (doall (for [[model-id model] filtered-models
                   :let [schem? (= (:type model) "ckt")
                         icon (if schem? cm/schemmodel cm/codemodel)]]
               ; inactive, active, key, title
               [[:span [icon] " " (get model :name model-id)]
                [:span [icon] " " [cm/renamable (r/cursor db [model-id :name])]]
                model-id (get model :name model-id)]))
      (fn [model-id]
        (when (= (get-in @db [model-id :type]) "ckt")
          #(js/window.open (edit-url model-id))))
      (fn [model-id]
        #(model-context-menu % db model-id))]]))



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
  (let [mod (r/cursor db [@selmodel])]
    (if @selmodel
      [:div.properties
       [:label {:for "categories" :title "Comma-seperated device categories"} "Categories"]
       [cm/dbfield :input {:id "categories"} mod
        #(clojure.string/join " " (or (:category %) []))
        #(swap! %1 assoc :category (clojure.string/split %2 #"[, ]+" -1))]
       [:h4 "Port Configuration"]
       [port-editor mod :top]
       [port-editor mod :bottom]
       [port-editor mod :left]
       [port-editor mod :right]]
      [:div.empty "Select a model to edit its properties."])))

(def dialect (r/atom "NgSpice"))

(defn model-preview [db]
  (let [mod (r/cursor db [@selmodel])]
    (prn @mod)
    [:div.properties
     (if (and @selmodel (contains? device-types (:type @mod)))
       [:<>
        [:label {:for "dialect"} "Simulator"]
        [:select {:id "dialect"
                  :type "text"
                  :value @dialect
                  :on-change #(reset! dialect (.. % -target -value))}
         [:option "NgSpice"]
         [:option "Xyce"]]
        [:label {:for "reftempl"} "Reference template"]
        [cm/dbfield :textarea {:id "reftempl", :placeholder "X{name} {ports} {properties}"} mod
         :reftempl
         #(swap! %1 assoc :reftempl %2)]
        [:label {:for "decltempl"} "Declaration template"]
        [cm/dbfield :textarea {:id "decltempl"} mod
         :decltempl
         #(swap! %1 assoc :decltempl %2)]
        [:label {:for "vectors" :title "Comma-seperated device outputs to save"} "Vectors"]
        [cm/dbfield :input {:id "vectors", :placeholder "id, gm"} mod
         #(clojure.string/join " " (:vectors %))
         #(swap! %1 assoc :vectors (clojure.string/split %2 #"[, ]+" -1))]
        (when (clojure.string/starts-with? (clojure.string/lower-case (:reftempl @mod "")) "x")
          [:<>
           [:label {:for "vectorcomp" :title "For subcircuits, the name of the thing inside of which to save vectors"} "Main component"]
           [cm/dbfield :input {:id "vectorcomp"} mod
            :component
            #(swap! %1 assoc :component %2)]])]
       (when @selmodel
         [:<>
          [:a {:href (edit-url @selmodel)
               :target @selmodel} "Edit"]]))]))

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
                                :category category-path}))))]
    [:<>
     [:div.schsel
      [:div.addbuttons
        [:button.primary {:on-click add-schem }
         [cm/add-model] "Add schematic"]
        [:button {:on-click add-spice }
         [cm/add-model] "Add SPICE model"]]
      [:h2 "Models: " (if (seq @selcat)
                        (clojure.string/join "/" @selcat)
                        "All models")]
      [model-list-selector modeldb]]
     [:div.proppane
      [:div.preview [model-preview modeldb]]
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
