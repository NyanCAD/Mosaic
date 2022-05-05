; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.libman
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            ["pouchdb" :as PouchDB]
            [nyancad.hipflask :refer [pouch-atom pouchdb sep watch-changes]]
            [nyancad.mosaic.common :as cm]))

; used for ephermeal UI state
(defonce seldb (r/atom nil))
(defonce selcell (r/atom nil))
(defonce selmod (r/atom nil))

; persistent settings
(defonce databases (pouch-atom (pouchdb "local") "databases" (r/atom {})))

; stores the names of the schematics in the current db
(defonce modelcache (r/atom {}))
(defonce dbcache (atom {}))

(def schdbmeta {:name "schematics" :url cm/default-sync})

(defonce modal-content (r/atom nil))

(defn modal []
  [:div.modal
   {:class (if @modal-content "visible" "hidden")}
   @modal-content])

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
           [:input {:name "valuefield" :type "text"}]
           [:button {:on-click #(reset! modal-content nil)} "Cancel"]
           [:input {:type "submit" :value "Ok"}]]))

(defn get-dbatom [dbid]
  (if-let [pa (get @dbcache dbid)]
    pa
    (let [dbmeta (get @databases dbid schdbmeta)
          dbname (:name dbmeta)
          dburl (if js/window.dburl (.-href (js/URL. dbname js/window.dburl)) dbname)
          db (pouchdb dburl)
          cache (r/cursor modelcache [dbid])
          pa (pouch-atom db "models" cache)]
      (watch-changes db pa)
      (when (:url dbmeta)
      ; TODO filtered replication
        (.sync PouchDB (:url dbmeta) db))
      (swap! dbcache assoc dbid pa)
      pa)))

(defn database-selector []
  [:div.cellsel
   (doall (for [[id db] (concat [[:schematics schdbmeta]] @databases)
                :let [pa (get-dbatom id)]]
            [:details.tree {:key id
                            :open (= id @seldb)
                            :on-toggle #(when (.. % -target -open) (reset! seldb id))}
             [:summary (:name db)]
             [:div.detailbody
              [cm/radiobuttons selcell (for [[key cell] @pa
                                             :let [cname (second (.split key ":"))]]
                                         ; inactive, active, key, title
                                         [(get cell :name cname) [cm/renamable (r/cursor pa [key :name])] key cname])]]]))])

(defn edit-url [cell mod]
  (let [mname (str cell "$" mod)
        dbmeta (get @databases @seldb schdbmeta)]
    (doto (js/URL. "editor" js/window.location)
      (.. -searchParams (append "schem" mname))
      (.. -searchParams (append "db" (:name dbmeta "")))
      (.. -searchParams (append "sync" (:url dbmeta ""))))))

(defn schematic-selector [db]
  (let [cellname @selcell
        cell (get @db cellname)]
    [:div.schematics
     [cm/radiobuttons selmod
      (for [[key mod] (:models cell)]
        ; inactive, active, key, title
        [(get mod :name key)
         [cm/renamable (r/cursor db [cellname :models key :name])]
         key key])
      (fn [key]
        (println cell key)
        (when (= (get-in cell [:models key :type]) "schematic")
          #(js/window.open (edit-url (second (.split cellname ":")) (name key)), cellname)))]]))


(defn db-properties []
  (let [id @seldb
        db (get @databases id schdbmeta)]
    (when id
      [:div.dbprops
       [:h3 "Library properties"]
       [:div.properties
        [:label {:for "dbname"} "Name"]
        [:input {:id "dbname"
                 :value (:name db)
                 :on-change #(swap! databases assoc-in [id :name] (.. % -target -value))}]
        [:label {:for "dburl"} "URL"]
        [:input {:id "dburl"
                 :value (:url db)
                 :on-change #(swap! databases assoc-in [id :url] (.. % -target -value))}]]])))

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
    (println (keys (get-in @db [@selcell :models])))
    (if (= (:type @mod) "spice")
      [:div.properties
       [:label {:for "reftempl"} "Reference template"]
       [:textarea {:id "reftempl"
                   :value (:reftempl @mod "X{name} {ports} {properties}")
                   :on-change #(swap! mod assoc :reftempl (.. % -target -value))}]
       [:label {:for "decltempl"} "Declaration template"]
       [:textarea {:id "decltempl"
                   :value (:decltempl @mod)
                   :on-change #(swap! mod assoc :decltempl (.. % -target -value))}]]
      "TODO: preview")))

(defn cell-view []
  (let [db (get-dbatom (or @seldb :schematics))
        add-cell #(prompt "Enter the name of the new cell"
                          (fn [name] (swap! db assoc (str "models" sep name) {:name name})))
        add-schem #(prompt "Enter the name of the new schematic"
                          (fn [name] (swap! db assoc-in [@selcell :models (keyword name)] {:name name, :type "schematic"})))
        add-spice #(prompt "Enter the name of the new SPICE model"
                           (fn [name] (swap! db assoc-in [@selcell :models (keyword name)] {:name name :type "spice"})))]
    [:<>
     [:div.schsel
      [:div.addbuttons
       [:button {:on-click add-cell
                 :disabled (nil? @seldb)}
        "+ Add cell"]
       [:div.buttongroup.primary
        [:button {:on-click add-schem
                  :disabled (or (nil? @seldb) (nil? @selcell))}
         "+ Add schematic"]
        [:details
         [:summary.button]
         [:button {:on-click add-spice
                   :disabled (or (nil? @seldb) (nil? @selcell))}
          "+ Add SPICE model"]]]]
      [:h2 "Cell " (when-let [cell @selcell] (second (.split cell ":")))]
      [schematic-selector db]]
     [:div.proppane
      [:div.preview [model-preview db]]
      [cell-properties db]]]))

(defn library-manager []
  [:<>
   [:div.libraries
    [:div.libhead
     [:h1 "Library"]
     [:button.plus {:on-click
                    #(prompt "Enter the name of the new database"
                             (fn [name] (swap! databases assoc (str "databases" sep name) {:name name})))}
      "+"]]
    [database-selector]
    [db-properties]]
   [cell-view]
   [modal]])

(def shortcuts {})

(defn ^:dev/after-load ^:export init []
;;   (set! js/document.onkeyup (partial cm/keyboard-shortcuts shortcuts))
  (set! js/window.name "libman")
  (rd/render [library-manager]
             (.getElementById js/document "mosaic_libman")))