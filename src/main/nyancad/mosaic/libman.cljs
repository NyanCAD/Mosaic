(ns nyancad.mosaic.libman
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            ["pouchdb" :as PouchDB]
            [nyancad.hipflask :refer [pouch-atom pouchdb put update-keys sep]]
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

(defn get-dbatom [dbid]
  (if-let [pa (get @dbcache dbid)]
    pa
    (let [dbmeta (get @databases dbid schdbmeta)
          db (pouchdb (:name dbmeta))
          cache (r/cursor modelcache [dbid])
          pa (pouch-atom db "models" cache)]
      (when (:url dbmeta)
      ; TODO filtered replication
        (.replicate PouchDB (.-href (js/URL. (:name dbmeta) (:url dbmeta))) db))
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
         (name key) key])
      (fn [key] #(js/window.open (edit-url (second (.split cellname ":")) key), '_blank'))]]))


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

(defn shape-selector [shape]
  (println @shape)
  (let [size (max 3 (inc (cm/pattern-size @shape)))]
    [:table
     [:tbody
      (doall
       (for [y (range size)]
         [:tr {:key y}
          (doall
           (for [x  (range size)
                 :let [handler (fn [^js e]
                                 (if (.. e -target -checked)
                                   (swap! shape cm/set-coord [x y "#"])
                                   (swap! shape cm/remove-coord [x y "#"])))]]
             [:td {:key x}
              [:input {:type "checkbox"
                       :checked (cm/has-coord @shape [x y])
                       :on-change handler}]]))]))]]))

(defn port-namer [shape]
  [:<>
   (for [[x y name] @shape
         :let [handler (cm/debounce #(swap! shape cm/set-coord [x y (.. % -target -value)]))]]
     [:<> {:key [x y]}
      [:label {:for (str "port" x ":" y) :title "Port name"} x "/" y]
      [:input {:id (str "port" x ":" y)
               :type "text"
               :default-value name
               :on-change handler}]])])

(defn cell-properties [db]
  (let [cell @selcell]
     (when cell
      [:<>
       [:div.properties
        [:label {:for "background" :title "ASCII pattern for the device background"} "bg"]
        [shape-selector (r/cursor db [cell :bg])]
        [:label {:for "ports" :title "ASCII pattern for the device ports"} "ports"]
        [shape-selector (r/cursor db [cell :conn])]
        [port-namer (r/cursor db [cell :conn])]
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
  (let [db (get-dbatom @seldb)
        add-cell #(when-let [name (and @seldb (js/prompt "Enter the name of the new cell"))]
                    (swap! db assoc (str "models" sep name) {:name name}))
        add-schem #(when-let [name (and @seldb @selcell (js/prompt "Enter the name of the new schematic"))]
                    (swap! db assoc-in [@selcell :models (keyword name)] {:name name, :type "schematic"}))
        add-spice #(when-let [name (and @seldb @selcell (js/prompt "Enter the name of the new SPICE model"))]
                    (swap! db assoc-in [@selcell :models (keyword name)] {:name name :type "spice"}))]
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
     [cell-properties db]]
     ]))

(defn library-manager []
  [:<>
   [:div.libraries
    [:div.libhead
     [:h1 "Library"]
     [:button.plus {:on-click
                    #(let [name (js/prompt "Enter the name of the new database")]
                        (swap! databases assoc (str "databases" sep name) {:name name}))}
      "+"]]
    [database-selector]
    [db-properties]]
   [cell-view]])

(def shortcuts {})

(defn ^:dev/after-load ^:export init []
;;   (set! js/document.onkeyup (partial cm/keyboard-shortcuts shortcuts))
  (rd/render [library-manager]
             (.getElementById js/document "mosaic_libman")))