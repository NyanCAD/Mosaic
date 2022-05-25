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
(def params (js/URLSearchParams. js/window.location.search))
(def dbname (or (.get params "db") (js/localStorage.getItem "db") "schematics"))
(def dburl (if js/window.dburl (.-href (js/URL. dbname js/window.dburl)) dbname))
(def sync (or (.get params "sync") (js/localStorage.getItem "sync") cm/default-sync))
(defonce db (pouchdb dburl))

(defonce modeldb (pouch-atom db "models" (r/atom {})))
(defonce snapshots (pouch-atom db "snapshots" (r/atom {})))
(defonce watcher (watch-changes db modeldb snapshots))

; used for ephermeal UI state
(defonce selcat (r/atom []))
(defonce selcell (r/atom nil))
(defonce selmod (r/atom nil))

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
   [cm/radiobuttons selcell (for [[cat subtrie] trie
                                  :when (empty? subtrie) ; leaf
                                  :let [cell (get @modeldb cat)
                                        cname (second (.split cat ":"))]]
                                         ; inactive, active, key, title
                              [(get cell :name cname) [cm/renamable (r/cursor modeldb [cat :name])] cat cname])
    nil
    (fn [key] #(cell-context-menu % modeldb key))]])

(defn database-selector []
  [:div.cellsel
   [categories [] (category-trie @modeldb)]])

(defn edit-url [cell mod]
  (let [mname (str cell "$" mod)]
    (doto (js/URL. "editor" js/window.location)
      (.. -searchParams (append "schem" mname))
      (.. -searchParams (append "db" dbname))
      (.. -searchParams (append "sync" sync)))))

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
     [cm/radiobuttons selmod
      (for [[key mod] (:models cell)
            :when (or (= @selcat ["Everything"])
                      (some (partial = (apply str (interpose "/" @selcat))) (:categories mod)))
            :let [schem? (= (get-in cell [:models key :type]) "schematic")
                  icon (if schem? cm/schemmodel cm/codemodel)]]
        ; inactive, active, key, title
        [[:span [icon] " " (get mod :name key)]
         [:span [icon] " " [cm/renamable (r/cursor db [cellname :models key :name])]]
         key key])
      (fn [key]
        (println cell key)
        (when (= (get-in cell [:models key :type]) "schematic")
          #(js/window.open (edit-url (second (.split cellname ":")) (name key)), cellname)))
      (fn [key]
        (println "add ctxclk")
        #(schem-context-menu % db cellname key))]]))


(defn db-properties []
  [:div.dbprops
   [:details [:summary "Workspace properties"]
    [:form.properties
     [:label {:for "dbname"} "Name"]
     [:input {:id "dbname" :name "db"
              :default-value dbname}]
     [:label {:for "dbsync"} "URL"]
     [:input {:id "dbsync" :name "sync"
              :default-value sync}]
     [:button.primary {:type "submit"}
        [cm/connect] "Open"]]]])

(defn shape-selector [cell]
  (println @cell)
  (let [[width height] (:bg @cell)
        width (+ 2 width)
        height (+ 2 height)]
    (println width height)
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
  (println @cell)
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
    (when cell
      [:<>
       [:div.properties
        [background-selector sc]
        [:label {:for "ports" :title "pattern for the device ports"} "ports"]
        [shape-selector sc]
        [port-namer sc]
        [:label {:for "symurl" :title "image url for this component"} "url"]
        [:input {:id "symurl" :type "text"
                 :default-value (get-in @db [cell :sym])
                 :on-blur #(swap! db assoc-in [cell :sym] (.. % -target -value))}]]])))


(defn model-preview [db]
  (let [mod (r/cursor db [@selcell :models (keyword @selmod)])]
    (prn @mod)
    [:div.properties
     (if (= (:type @mod) "spice")
       [:<>
        [:label {:for "reftempl"} "Reference template"]
        [:textarea {:id "reftempl"
                    :value (:reftempl @mod "X{name} {ports} {properties}")
                    :on-change #(swap! mod assoc :reftempl (.. % -target -value))}]
        [:label {:for "decltempl"} "Declaration template"]
        [:textarea {:id "decltempl"
                    :value (:decltempl @mod)
                    :on-change #(swap! mod assoc :decltempl (.. % -target -value))}]]
       (when (and @selcell @selmod)
         [:<>
          [:a {:href (edit-url (second (.split @selcell ":")) (name @selmod))
               :target @selcell} "Edit"]]))
     (when (and @selcell @selmod)
       [:<>
        [:label {:for "categories"} "Categories"]
        [:input {:id "categories"
                 :value (apply str (interpose ", " (:categories @mod)))
                 :on-change #(swap! mod assoc :categories (clojure.string/split (.. % -target -value) #", " -1))}]])]))

(defn cell-view []
  (let [add-cell #(prompt "Enter the name of the new interface"
                          (fn [name] (swap! modeldb assoc (str "models" sep name) {:name name})))
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
      [cell-properties modeldb]]]))

(defn library-manager []
  [:<>
   [:div.libraries
    [:div.libhead
     [:h1 "Libraries"]]
    [database-selector]
    [db-properties]]
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
  (js/localStorage.setItem "db" dbname)
  (js/localStorage.setItem "sync" sync)
  (when (seq sync) ; pass nil to disable synchronization
    (.sync db sync #js{:live true, :retry true}))
  (render))