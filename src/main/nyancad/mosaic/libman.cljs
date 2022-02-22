(ns nyancad.mosaic.libman
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            ["pouchdb" :as PouchDB]
            [nyancad.hipflask :refer [pouch-atom pouchdb put update-keys sep]]
            [nyancad.mosaic.common :as cm]))

; used for ephermeal UI state
(defonce ui (r/atom {::database nil
                     ::cell nil
                     ::model nil}))
(defonce seldb (r/cursor ui [::database]))
(defonce selcell (r/cursor ui [::cell]))
(defonce selmod (r/cursor ui [::model]))

; persistent settings
(defonce databases (pouch-atom (pouchdb "local") "databases" (r/atom {})))

; stores the names of the schematics in the current db
(defonce modelcache (r/atom {}))
(defonce modelref (atom nil))
(def models (r/track #(get @modelcache @seldb)))
;; (def ddoc {
;;   :_id "_design/schematicviews",
;;   :views {
;;     :schematics {
;;       :reduce "_count",
;;       :map "function (doc) {\n  ns = doc._id.split(\":\")[0].split(\"$\");\n  if (ns.length == 2) emit(ns);\n}"
;;     }
;;   },
;;   :language "javascript",
;;   :options {:partitioned false}
;; })

;; (defn schematics-view [db]
;;   (-> db
;;       (.query "schematicviews/schematics" #js{:group true})
;;       (.then (fn [res] (reset! schemnames (reduce (fn [sch [c m]] (update sch c conj m)) {} (map #(js->clj (.-key %)) (.-rows res))))))
;;       (.catch (fn [err] (when (= (.-status err) 404)
;;                           (.then (put db ddoc) #(schematics-view db)))))))

(def schdbmeta {:name "schematics" :url cm/default-sync})

(defn get-schem-names [_ _ _ dbid]
  (let [dbmeta (get @databases dbid schdbmeta)
        db (pouchdb (:name dbmeta))
        cache (r/cursor modelcache [dbid])]
    (reset! modelref (pouch-atom db "models" cache))
    (when (:url dbmeta)
       (.replicate PouchDB (.-href (js/URL. (:name dbmeta) (:url dbmeta))) db))))

(add-watch seldb :schemnames get-schem-names)

(defn database-selector []
  [:div.dbsel
    [cm/radiobuttons seldb
    (concat
        [["Public schematics" "schematics" "Default database"]]
        (for [[id db] @databases]
          [(:name db) id (:url db)]))]])

(defn database-adder []
  [:form {:on-submit (fn [^js e]
                       (.preventDefault e)
                         (let [name (.. e -target -db -value)]
                            (swap! databases assoc (str "databases" sep name)
                                {:name name})))}
   [:input {:type "text"
            :placeholder "database"
            :title "Add a library database"
            :name "db"}]
   [:input {:type "submit" :value "Add library"}]])

(defn edit-url []
  (let [mname (str @selcell "$" @selmod)
        dbmeta (get @databases @seldb schdbmeta)]
    (doto (js/URL. "editor" js/window.location)
      (.. -searchParams (append "schem" mname))
      (.. -searchParams (append "db" (:name dbmeta "")))
      (.. -searchParams (append "sync" (:url dbmeta ""))))))

(defn schematic-selector []
  [:div.schsel
   (doall (for [[id cell] @models
                :let [cname (second (.split id ":"))]]
            [:details {:key id
                       :open (= cname @selcell)
                       :on-toggle #(when (.. % -target -open) (reset! selcell cname))}
             [:summary cname]
             [cm/radiobuttons selmod (for [[key mod] (:models cell)]
                                       [key (name key) key])]]))])

(defn cell-adder []
  [:form {:on-submit (fn [^js e]
                       (.preventDefault e)
                       (let [cell (.. e -target -cell -value)
                             models @modelref]
                         (swap! models assoc (str "models" sep cell)  {})))}
   [:input {:type "text"
            :placeholder "cell name"
            :title "Add a cell to the selected library"
            :name "cell"}]
   [:input {:type "submit" :value "Add cell"}]])

(defn model-adder []
  [:form {:on-submit (fn [^js e]
                       (.preventDefault e)
                       (let [model (.. e -target -model -value)
                             cell @selcell
                             models @modelref]
                         (swap! models assoc-in [(str "models" sep cell) :models model] {})))}
   [:input {:type "text"
            :placeholder "schematic name"
            :title "Add a schematic to the selected cell"
            :name "model"}]
   [:input {:type "submit" :value "Add schematic"}]])

(defn db-properties []
  (let [id @seldb
        db (get @databases id schdbmeta)]
    [:details
     [:summary "Database properties"]
     (when id
       [:div.properties
        [:label {:for "dbname"} "Name"]
        [:input {:id "dbname"
                 :value (:name db)
                 :on-change #(swap! databases assoc-in [id :name] (.. % -target -value))}]
        [:label {:for "dburl"} "URL"]
        [:input {:id "dburl"
                 :value (:url db)
                 :on-change #(swap! databases assoc-in [id :url] (.. % -target -value))}]])]))

(defn shape-selector [key layer]
  (let [path [(str "models" sep key) layer]
        shape (r/cursor models path)]
    (fn []
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
                                       (swap! @modelref update-in path cm/set-coord [x y "#"])
                                       (swap! @modelref update-in path cm/remove-coord [x y "#"])))]]
                 [:td {:key x}
                  [:input {:type "checkbox"
                           :checked (cm/has-coord @shape [x y])
                           :on-change handler}]]))]))]]))))

(defn port-namer [key]
  (let [path [(str "models" sep key) :conn]
        shape (r/cursor models path)]
    (fn []
      [:<>
       (for [[x y name] @shape
             :let [handler (cm/debounce #(swap! @modelref update-in path cm/set-coord [x y (.. % -target -value)]))]]
         [:<> {:key [x y]}
          [:label {:for (str "port" x ":" y) :title "Port name"} x "/" y]
          [:input {:id (str "port" x ":" y)
                   :type "text"
                   :default-value name
                   :on-change handler}]])])))

(defn cell-properties []
  (let [cell @selcell]
    [:details
     [:summary "Cell properties"]
     (when cell
      [:<>
       [:div.properties
        [:label {:for "background" :title "ASCII pattern for the device background"} "bg"]
        [shape-selector cell :bg]
        [:label {:for "ports" :title "ASCII pattern for the device ports"} "ports"]
        [shape-selector cell :conn]
        [port-namer cell]
        [:label {:for "symurl" :title "image url for this component"} "url"]
        [:input {:id "symurl" :type "text"
                 :default-value (get-in @models [(str "models" sep cell) :sym])
                 :on-blur #(swap! @modelref assoc-in [(str "models" sep cell) :sym] (.. % -target -value))}]]])]))


(defn model-properties []
  (let [sel (keyword @selmod)
        mod (get-in @models [(str "models" sep @selcell) :models sel])]
    [:details
     [:summary "Model properties"]
     (when mod
       [:div.properties
    ;;    (prn-str mod)
        [:a {:href (edit-url)} "edit"]
        [:label {:for "modtype"} "Model type"]
        [:select {:id "modtype"
                  :value (:type mod)
                  :on-change #(swap! @modelref assoc-in [(str "models" sep @selcell) :models sel :type] (.. % -target -value))}
         [:option {:value "schematic"} "schematic"]
         [:option {:value "spice"} "spice"]]
        (when (= (:type mod) "spice")
          [:<>
           [:label {:for "reftempl"} "Reference template"]
           [:input {:id "reftempl"
                    :value (:reftempl mod)
                    :on-change #(swap! @modelref assoc-in [(str "models" sep @selcell) :models sel :reftempl] (.. % -target -value))}]
           [:label {:for "decltempl"} "Declaration template"]
           [:input {:id "decltempl"
                    :value (:decltempl mod)
                    :on-change #(swap! @modelref assoc-in [(str "models" sep @selcell) :models sel :decltempl] (.. % -target -value))}]])])]))

(defn library-manager []
  [:div#mosaic_libman
   [:div.menu "menu"]
   [:div.libraries
    [database-selector]
    [database-adder]]
   [:div.schematics
    [schematic-selector]
    [cell-adder]
    [model-adder]]
   [:div.allproperties
    [db-properties]
    [cell-properties]
    [model-properties]]])

(def shortcuts {})

(defn ^:dev/after-load ^:export init []
  (set! js/document.onkeyup (partial cm/keyboard-shortcuts shortcuts))
  (rd/render [library-manager]
             (.getElementById js/document "mosaic_root")))