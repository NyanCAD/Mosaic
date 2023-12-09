; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.libman
  (:require clojure.string
            [reagent.core :as r]
            [reagent.dom :as rd]
            ;; [nyancad.mosaic.jsatom :refer [json-atom]]
            [nyancad.mosaic.common :as cm]))

(defonce modeldb (r/atom {}))
(defonce snapshots (r/atom {}))

; used for ephermeal UI state
(defonce selcat (r/atom []))
(defonce selcell (r/atom nil))
(defonce selmod (r/atom nil))
(defonce syncactive (r/atom false))

(defonce modal-content (r/atom nil))

(defn modal []
  [:div.modal.window
   {:class (if @modal-content "visible" "hidden")}
   @modal-content])

(defonce context-content (r/atom {:x 0 :y 0 :body nil}))

(defn contextmenu []
  (let [{:keys [:x :y :body]} @context-content]
    [:div.contextmenu.window
     {:style {:top y, :left, x}
      :class (if body "visible" "hidden")}
     body]))

(defn prompt [text cb]
  (reset! modal-content
          [:form {:on-submit (fn [^js e]
                               (js/console.log e)
                               (.preventDefault e)
                               (let [name (.. e -target -elements -valuefield -value)]
                                 (when (seq name)
                                   (cb name)))
                               (reset! modal-content nil))}
           [:div text]
           [:input {:name "valuefield" :type "text" :auto-focus true}]
           [:button {:on-click #(reset! modal-content nil)} "Cancel"]
           [:input {:type "submit" :value "Ok"}]]))

(defn alert [text]
  (reset! modal-content
          [:div [:p text]
           [:button {:on-click #(reset! modal-content nil)} "Ok"]]))


(defn cell-context-menu [e db cellname]
  (.preventDefault e)
  (reset! context-content
          {:x (.-clientX e), :y (.-clientY e)
           :body [:ul
                  [:li {:on-click #(swap! db dissoc cellname)} "delete"]]}))

(defn vec-startswith [v prefix]
  (= (subvec v 0 (min (count v) (count prefix))) prefix))

(defn cell-categories [cell]
  (map #(conj (clojure.string/split % "/") (:_id cell))
       (conj (mapcat :categories (vals (:models cell))) "Everything")))

(defn category-trie [models]
  (->> (vals models)
       (mapcat cell-categories)
       (reduce #(assoc-in %1 %2 {}) {})))

(defn categories [base trie]
  [:<>
   (doall (for [[cat subtrie] trie
                :when (and (seq cat) (seq subtrie)) ; branch
                :let [path (conj base cat)]]
            [:details.tree {:key path
                            :class path
                            :open (vec-startswith @selcat path)
                            :on-toggle #(when (.. % -target -open)
                                          (.stopPropagation %)
                                          (reset! selcell nil)
                                          (reset! selmod nil)
                                          (reset! selcat path))}
             [:summary cat]
             [:div.detailbody
              [categories path subtrie]]]))
   [cm/radiobuttons selcell
    (doall (for [[cat subtrie] trie
                 :when (empty? subtrie) ; leaf
                 :let [cell (get @modeldb cat)
                       cname (second (.split cat ":"))]]
                                         ; inactive, active, key, title
             [(get cell :name cname) [cm/renamable (r/cursor modeldb [cat :name])] cat cname]))
    nil
    (fn [key] #(cell-context-menu % modeldb key))]])

(defn database-selector []
  [:div.cellsel
   (if (seq @modeldb)
     [categories [] (category-trie @modeldb)]
     [:div.empty "There aren't any interfaces yet. Open a different workspace or click \"Add interface\" to get started."])])

(defn edit-url [cell mod]
  (let [mname (str cell "$" mod)]
    (doto (js/URL. "editor" js/window.location)
      ;(.. -searchParams (append "schem" mname))
      ;(.. -searchParams (append "db" dbname))
      ;(.. -searchParams (append "sync" sync))
      )))

(defn schem-context-menu [e db cellname key]
  (.preventDefault e)
  (reset! context-content
          {:x (.-clientX e), :y (.-clientY e)
           :body [:ul
                  [:li {:on-click #(js/window.open (edit-url (second (.split cellname ":")) (name key)), cellname)} "edit"]
                  [:li {:on-click #(swap! db update-in [cellname :models] dissoc key)} "delete"]]}))

(defn schematic-selector [db]
  (let [cellname @selcell
        cell (get @db cellname)]
    [:div.schematics
     (if @selcell
       [cm/radiobuttons selmod
        (doall (for [[key mod] (:models cell)
                     :when (or (= @selcat ["Everything"])
                               (some (partial = (apply str (interpose "/" @selcat))) (:categories mod)))
                     :let [schem? (= (get-in cell [:models key :type]) "schematic")
                           icon (if schem? cm/schemmodel cm/codemodel)]]
                 ; inactive, active, key, title
                 [[:span [icon] " " (get mod :name key)]
                  [:span [icon] " " [cm/renamable (r/cursor db [cellname :models key :name])]]
                  key key]))
        (fn [key]
          (when (= (get-in cell [:models key :type]) "schematic")
            #(js/window.open (edit-url (second (.split cellname ":")) (name key)), cellname)))
        (fn [key]
          #(schem-context-menu % db cellname key))]
       [:div.empty "There are no implementations to show. Select an interface to edit its schematics and SPICE models."])]))


(defn shape-selector [cell]
  (let [[width height] (:bg @cell)
        width (+ 2 width)
        height (+ 2 height)]
    [:table
     [:tbody
      (doall
       (for [y (range height)]
         [:tr {:key y}
          (doall
           (for [x  (range width)
                 :let [handler (fn [^js e]
                                 (if (.. e -target -checked)
                                   (swap! cell update :conn cm/set-coord [x y "#"])
                                   (swap! cell update :conn cm/remove-coord [x y "#"])))]]
             [:td {:key x}
              [:input {:type "checkbox"
                       :checked (cm/has-coord (:conn @cell) [x y])
                       :on-change handler}]]))]))]]))

(defn background-selector [cell]
  [:<>
   [:label {:for "bgwidth" :title "Width of the background tile"} "Background width"]
   [:input {:id "bgwidth"
            :type "number"
            :default-value (get (:bg @cell) 0 1)
            :on-change (cm/debounce #(swap! cell update :bg (fnil assoc [0 0]) 0 (js/parseInt (.. % -target -value))))}]
   [:label {:for "bgheight" :title "Width of the background tile"} "Background height"]
   [:input {:id "bgheigt"
            :type "number"
            :default-value (get (:bg @cell) 1 1)
            :on-change (cm/debounce #(swap! cell update :bg (fnil assoc [0 0]) 1 (js/parseInt (.. % -target -value))))}]])

(defn port-namer [cell]
  [:<>
   (for [[x y name] (:conn @cell)
         :let [handler (cm/debounce #(swap! cell update :conn cm/set-coord [x y (.. % -target -value)]))]]
     [:<> {:key [x y]}
      [:label {:for (str "port" x ":" y) :title "Port name"} x "/" y]
      [:input {:id (str "port" x ":" y)
               :type "text"
               :default-value name
               :on-change handler}]])])

(defn cell-properties [db]
  (let [cell @selcell
        sc (r/cursor db [cell])]
    (if cell
      [:<>
       [:div.properties
        [background-selector sc]
        [:label {:for "ports" :title "pattern for the device ports"} "ports"]
        [shape-selector sc]
        [port-namer sc]
        [:label {:for "symurl" :title "image url for this component"} "url"]
        [:input {:id "symurl" :type "text"
                 :default-value (get-in @db [cell :sym])
                 :on-blur #(swap! db assoc-in [cell :sym] (.. % -target -value))}]]]
      [:div.empty "Select an interface to edit its properties."])))

(def dialect (r/atom "NgSpice"))

(defn model-preview [db]
  (let [mod (r/cursor db [@selcell :models (keyword @selmod) (keyword @dialect)])]
    (prn @mod)
    [:div.properties
     (if (= (get-in @db [@selcell :models (keyword @selmod) :type]) "spice")
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
         #(apply str (interpose ", " (:vectors %)))
         #(swap! %1 assoc :vectors (clojure.string/split %2 #", " -1))]
        (when (clojure.string/starts-with? (clojure.string/lower-case (:reftempl @mod "")) "x")
          [:<>
           [:label {:for "vectorcomp" :title "For subcircuits, the name of the thing inside of which to save vectors"} "Main component"]
           [cm/dbfield :input {:id "vectorcomp"} mod
            :component
            #(swap! %1 assoc :component %2)]])]
       (when (and @selcell @selmod)
         [:<>
          [:a {:href (edit-url (second (.split @selcell ":")) (name @selmod))
               :target @selcell} "Edit"]]))
     (if (and @selcell @selmod)
       [:<>
        [:label {:for "categories" :title "Comma-seperated device categories"} "Categories"]
        [cm/dbfield :input {:id "categories"} mod
         #(apply str (interpose ", " (:categories %)))
         #(swap! %1 assoc :categories (clojure.string/split %2 #", " -1))]]
       [:div.empty "Select a schematic or SPICE model to edit its properties."])]))

(defn cell-view []
  (let [add-cell #(prompt "Enter the name of the new interface"
                          (fn [name] (swap! modeldb assoc (str "models" cm/sep name) {:name name})))
        add-schem #(prompt "Enter the name of the new schematic"
                          (fn [name] (swap! modeldb assoc-in [@selcell :models (keyword name)] {:name name, :type "schematic"})))
        add-spice #(prompt "Enter the name of the new SPICE model"
                           (fn [name] (swap! modeldb assoc-in [@selcell :models (keyword name)] {:name name :type "spice"})))]
    [:<>
     [:div.schsel
      [:div.addbuttons
       
       [:button {:on-click add-cell
                 :disabled (nil? @selcat)}
        [cm/add-cell] "Add interface"]
       [:div.buttongroup.primary
        [:button {:on-click add-schem
                  :disabled (or (nil? @selcat) (nil? @selcell))}
         [cm/add-model] "Add schematic"]
        [:details
         [:summary.button]
         [:button {:on-click add-spice
                   :disabled (or (nil? @selcat) (nil? @selcell))}
          [cm/add-model] "Add SPICE model"]]]]
      [:h2 "Interface " (when-let [cell @selcell] (second (.split cell ":")))]
      [schematic-selector modeldb]]
     [:div.proppane
      [:div.preview [model-preview modeldb]]
      [:h3 "Interface properties"]
      [cell-properties modeldb]]]))

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
   [contextmenu]
   [modal]])

(def shortcuts {})

(defn ^:dev/after-load render []
  (rd/render [library-manager]
             (.getElementById js/document "mosaic_libman")))

(defn ^:export init []
;;   (set! js/document.onkeyup (partial cm/keyboard-shortcuts shortcuts))
  (set! js/window.name "libman")
  (set! js/document.onclick #(swap! context-content assoc :body nil))
  (render))