(ns nyancad.mosaic.libman
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            ["pouchdb" :as PouchDB]
            [nyancad.hipflask :refer [pouch-atom pouchdb put update-keys sep]]
            [nyancad.mosaic.common :as cm]))

; used for ephermeal UI state
(defonce ui (r/atom {::selected [nil nil nil]}))
(defonce selected (r/cursor ui [::selected]))

; persistent settings
(defonce databases (pouch-atom (pouchdb "local") "databases" (r/atom {})))

; stores the names of the schematics in the current db
(defonce modelcache (r/atom {}))
(defonce modelref (atom nil))
(def models (r/track #(get @modelcache (first @selected))))
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

(defn get-schem-names [_ _ _ [dbid _ _]]
  (let [dbmeta (get @databases dbid schdbmeta)
        db (pouchdb (:name dbmeta))
        cache (r/cursor modelcache [dbid])]
    (reset! modelref (pouch-atom db "models" cache))
    (when (:url dbmeta)
       (.replicate PouchDB (.-href (js/URL. (:name dbmeta) (:url dbmeta))) db))))

(add-watch selected :schemnames get-schem-names)

(defn database-selector []
  [:select {:multiple true
            :on-change #(swap! selected assoc 0 (.. % -target -value))}
   [:option {:value "schematics"} "Public Schematics"]
   (for [[id db] @databases]
     [:option {:value id :key id} (:name db)])])

(defn database-adder []
  [:form {:on-submit (fn [^js e]
                       (.preventDefault e)
                       (try
                         (let [url (js/URL. (.. e -target -dburl -value))
                               name (subs (.-pathname url) 1)]
                           (set! (.-pathname url) "")
                           (swap! databases assoc (str "databases" sep name)
                                  {:name name
                                   :url (.-href url)}))
                         (catch js/TypeError _
                           (let [name (.. e -target -dburl -value)]
                             (swap! databases assoc (str "databases" sep name)
                                    {:name name})))))}
   [:input {:type "text"
            :placeholder "https://user:pw@host:port/database"
            :title "Add an online library by URL or a local one by name"
            :name "dburl"}]
   [:input {:type "submit" :value "Add library"}]])

(defn schematic-selector []
  [:div
   (doall (for [[id cell] @models
                :let [cname (second (.split id ":"))]]
            [:details {:key id
                       :open (= cname (get @selected 1))
                       :on-toggle #(when (.. % -target -open) (swap! selected assoc 1 cname))}
             [:summary cname]
             [:ul
              (doall (for [[key mod] (:models cell)
                           :let [mname (str cname "$" (name key))
                                 dbmeta (get @databases (first @selected) schdbmeta)
                                 url (doto (js/URL. "editor.html" js/window.location)
                                       (.. -searchParams (append "schem" mname))
                                       (.. -searchParams (append "db" (:name dbmeta "")))
                                       (.. -searchParams (append "sync" (:url dbmeta ""))))]]
                       [:li {:key key}
                        key
                        [:a {:href (.-href url)} "edit"]]))]]))])

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
                             cell (get @selected 1)
                             models @modelref]
                         (swap! models assoc-in [(str "models" sep cell) :models model] {})))}
   [:input {:type "text"
            :placeholder "schematic name"
            :title "Add a schematic to the selected cell"
            :name "model"}]
   [:input {:type "submit" :value "Add schematic"}]])

(defn library-manager []
  [:div
   [database-selector]
   [database-adder]
   [schematic-selector]
   [cell-adder]
   [model-adder]])

(def shortcuts {})

(defn ^:dev/after-load ^:export init []
  (set! js/document.onkeyup (partial cm/keyboard-shortcuts shortcuts))
  (rd/render [library-manager]
             (.getElementById js/document "mosaic_root")))