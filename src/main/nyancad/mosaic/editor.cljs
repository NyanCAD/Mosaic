(ns nyancad.mosaic.editor
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [nyancad.hipflask :refer [pouch-atom pouchdb update-keys sep]]
            [clojure.spec.alpha :as s]
            [cljs.core.async :refer [go <!]]
            clojure.edn
            clojure.set
            clojure.string
            goog.functions
            [nyancad.mosaic.common :as cm
             :refer [grid-size debounce sconj
                     point transform transform-vec
                     mosfet-shape twoport-shape bjt-conn]]))


; these are set on init
; just so they can be "reloaded" without a full page reload
; maybe it's more "correct" to use an atom, but then to use schematic you'd have to @@schematic, yuk
; and either way they are pretty static
(declare group dbname sync schematic modeldb snapshots)

(defonce local (pouch-atom (pouchdb "local") "local"))

(defn make-name
  ([base] (make-name group base))
  ([group base]
   (letfn [(hex [] (.toString (rand-int 16) 16))]
     (str group sep base "-" (hex) (hex) (hex) (hex) (hex) (hex) (hex) (hex)))))

(defonce ui (r/atom {::zoom [0 0 500 500]
                     ::theme "tetris"
                     ::tool ::cursor
                     ::selected #{}
                     ::delta {:x 0 :y 0 :rx 0 :ry 0}
                     ::mouse [0 0]}))

(s/def ::zoom (s/coll-of number? :count 4))
(s/def ::theme #{"tetris" "eyesore"})
(s/def ::tool #{::cursor ::eraser ::wire})
(s/def ::selected (s/and set? (s/coll-of string?)))
(s/def ::dragging (s/nilable #{::wire ::device ::view}))
(s/def ::ui (s/keys :req [::zoom ::theme ::tool ::selected]
                    :opt [::dragging]))

(set-validator! ui #(or (s/valid? ::ui %) (.log js/console (pr-str %) (s/explain-str ::ui %))))

(defonce zoom (r/cursor ui [::zoom]))
(defonce theme (r/cursor ui [::theme]))
(defonce tool (r/cursor ui [::tool]))
(defonce selected (r/cursor ui [::selected]))
(defonce delta (r/cursor ui [::delta]))

(defonce undotree (cm/newundotree))

(defn restore [state]
  (go
    (remove-watch schematic ::undo)
    (<! (swap! schematic into state)) ; TODO delete documents
    (add-watch schematic ::undo #(cm/newdo undotree %4))))

(defn undo-schematic []
  (when-let [st (cm/undo undotree)]
    (restore st)))

(defn redo-schematic []
  (when-let [st (cm/redo undotree)]
    (restore st)))

(declare mosfet-sym wire-sym wire-bg label-sym
         resistor-sym capacitor-sym inductor-sym
         vsource-sym isource-sym diode-sym
         circuit-shape circuit-conn circuit-sym
         bjt-sym)
; should probably be in state eventually
(def models {"pmos" {::bg mosfet-shape
                     ::conn mosfet-shape
                     ::sym #'mosfet-sym
                     ::props {:m {:tooltip "multiplier"}
                              :nf {:tooltip "number of fingers"}
                              :w {:tooltip "width" :unit "meter"}
                              :l {:tooltip "lenght" :unit "meter"}}}
             "nmos" {::bg mosfet-shape
                     ::conn mosfet-shape
                     ::sym #'mosfet-sym
                     ::props {:m {:tooltip "multiplier"}
                              :nf {:tooltip "number of fingers"}
                              :w {:tooltip "width" :unit "meter"}
                              :l {:tooltip "lenght" :unit "meter"}}}
             "npn" {::bg mosfet-shape
                     ::conn bjt-conn
                     ::sym #'bjt-sym
                     ::props {:m {:tooltip "multiplier"}
                              :nf {:tooltip "number of fingers"}
                              :w {:tooltip "width" :unit "meter"}
                              :l {:tooltip "lenght" :unit "meter"}}}
             "pnp" {::bg mosfet-shape
                     ::conn bjt-conn
                     ::sym #'bjt-sym
                     ::props {:m {:tooltip "multiplier"}
                              :nf {:tooltip "number of fingers"}
                              :w {:tooltip "width" :unit "meter"}
                              :l {:tooltip "lenght" :unit "meter"}}}
             "resistor" {::bg twoport-shape
                         ::conn twoport-shape
                         ::sym #'resistor-sym
                         ::props {:resistance {:tooltip "Resistance" :unit "Ohm"}}}
             "capacitor" {::bg twoport-shape
                          ::conn twoport-shape
                          ::sym #'capacitor-sym
                          ::props {:capacitance {:tooltip "Capacitance" :unit "Farad"}}}
             "inductor" {::bg twoport-shape
                         ::conn twoport-shape
                         ::sym #'inductor-sym
                         ::props {:inductance {:tooltip "Inductance" :unit "Henry"}}}
             "vsource" {::bg twoport-shape
                        ::conn twoport-shape
                        ::sym #'vsource-sym
                        ::props {:dc {:tooltip "DC voltage" :unit "Volt"}
                                 :ac {:tooltip "AC voltage" :unit "Volt"}}}
             "isource" {::bg twoport-shape
                        ::conn twoport-shape
                        ::sym #'isource-sym
                        ::props {:dc {:tooltip "DC current" :unit "Ampere"}
                                 :ac {:tooltip "AC current" :unit "Ampere"}}}
             "diode" {::bg twoport-shape
                      ::conn twoport-shape
                      ::sym #'diode-sym
                      ::props {}}
             "wire" {::bg #'wire-bg
                     ::conn []
                     ::sym #'wire-sym
                     ::props {}}
             "label" {::bg []
                      ::conn []
                      ::sym #'label-sym
                      ::props {}}})

(defn build-wire-index [sch]
  (reduce
   (fn [idx {:keys [:_id :x :y :rx :ry :cell]}]
     (cond
       (= cell "wire") (-> idx
                           (update [x y] conj _id)
                           (update [(+ x rx) (+ y ry)] conj _id))
       (contains? models cell) (reduce
                                (fn [idx [rx ry _]] (update idx [(+ x rx) (+ y ry)] conj _id))
                                idx (get-in models [cell ::conn]))
       :else idx)) ;TODO custom components
   {} (vals sch)))

(defn build-current-wire-index []
  (build-wire-index @schematic))

(def wire-index (r/track build-current-wire-index))

(defn viewbox-coord [e]
  (let [^js el (js/document.getElementById "mosaic_canvas")
        m (.inverse (.getScreenCTM el))
        p (point (.-clientX e) (.-clientY e))
        tp (.matrixTransform p m)]
    [(/ (.-x tp) grid-size) (/ (.-y tp) grid-size)]))

(defn viewbox-movement [e]
  (let [^js el (js/document.getElementById "mosaic_canvas")
        m (.inverse (.getScreenCTM el))
        _ (do (set! (.-e m) 0)
              (set! (.-f m) 0)) ; cancel translation
        ^js p (point (.-movementX e) (.-movementY e))
        tp (.matrixTransform p m)] ; local movement
    [(.-x tp) (.-y tp)]))

(defn zoom-schematic [direction ex ey]
  (swap! ui update ::zoom
         (fn [[x y w h]]
           (let [dx (* direction w 0.1)
                 dy (* direction h 0.1)
                 rx (/ (- ex x) w)
                 ry (/ (- ey y) h)]
             [(- x (* dx rx))
              (- y (* dy ry))
              (+ w dx)
              (+ h dy)]))))

(defn scroll-zoom [e]
  (let [[x y] (viewbox-coord e)]
    (zoom-schematic (cm/sign (.-deltaY e)) (* x grid-size) (* y grid-size))))

(defn button-zoom [dir]
  (let [[x y w h] (::zoom @ui)]
    (zoom-schematic dir
                    (+ x (/ w 2))
                    (+ y (/ h 2)))))

(defn transform-selected [sch selected tf]
  (let [f (comp transform-vec tf transform)]
    (update-keys sch selected
                 update :transform f)))

(defn delete-selected []
  (let [selected (::selected @ui)]
    (swap! ui assoc ::selected #{})
    (swap! schematic #(apply dissoc %1 %2) selected)))

(defn remove-wire [sch selected coord])

(defn drag-view [e]
  (swap! ui update ::zoom
         (fn [[x y w h]]
           (let [[dx dy] (viewbox-movement e)]
             [(- x dx)
              (- y dy)
              w h]))))

(defn drag-device [e]
  (let [[dx dy] (map #(/ % grid-size) (viewbox-movement e))]
    (swap! delta
           (fn [d]
             (-> d
                 (update :x #(+ % dx))
                 (update :y #(+ % dy)))))))

(defn drag-wire [e]
  (let [[dx dy] (map #(/ % grid-size) (viewbox-movement e))]
    (swap! delta
           (fn [d]
             (-> d
                 (update :rx #(+ % dx))
                 (update :ry #(+ % dy)))))))

(defn wire-drag [e]
  (case (::dragging @ui)
    ::view (drag-view e)
    ::wire (drag-wire e)
    nil))

(defn cursor-drag [e]
  (case (::dragging @ui)
    ::view (drag-view e)
    ::wire (drag-wire e)
    ::device (drag-device e)
    nil))

(defn eraser-drag [e]
  (let [dragging (::dragging @ui)]
    (case dragging
      ::view (drag-view e)
      ::wire (swap! schematic remove-wire
                    (first (::selected @ui))
                    (viewbox-coord e))
      nil))) ;todo remove devices?

(defn drag [e]
  ;; store mouse position for use outside mouse events
  ;; keyboard shortcuts for example
  (swap! ui assoc ::mouse (viewbox-coord e))
  (case @tool
    ::eraser (eraser-drag e)
    ::wire (wire-drag e)
    ::cursor (cursor-drag e)))

(defn add-wire-segment [[x y]]
  (let [name (make-name "wire")]
    (swap! schematic assoc name
           {:cell "wire"
            :x (js/Math.floor x)
            :y (js/Math.floor y)
            :rx 0 :ry 0})
    (swap! ui assoc
           ::dragging ::wire
           ::selected #{name})))

(defn add-wire [[x y] first?]
  (if first?
    (add-wire-segment [x y]) ; just add a new wire, else finish old wire
    (let [selected (first (::selected @ui))
          {drx :rx dry :ry} @delta
          dev (get @schematic selected)
          x (js/Math.round (+ (:x dev) (:rx dev) drx)) ; use end pos of previous wire instead
          y (js/Math.round (+ (:y dev) (:ry dev) dry))
          on-port (contains? @wire-index [x y])
          same-tile (and (< (js/Math.abs drx) 0.5) (< (js/Math.abs dry) 0.5))]
      (go
        (<! (swap! schematic update selected
                   (fn [{rx :rx ry :ry :as dev}]
                     (assoc dev
                            :rx (js/Math.round (+ rx drx))
                            :ry (js/Math.round (+ ry dry))))))
        (cond
          same-tile (do
                      (delete-selected)
                      (swap! ui assoc ; the dragged wire stayed at the same tile, exit
                             ::dragging nil
                             ::delta {:x 0 :y 0 :rx 0 :ry 0}))
          on-port (swap! ui assoc ; the wire landed on a port or wire, exit
                         ::dragging nil
                         ::delta {:x 0 :y 0 :rx 0 :ry 0})
          :else (do
                  (swap! ui assoc ; add the rounding error to delta
                         ::delta {:x 0 :y 0
                                  :rx (- drx (js/Math.round drx))
                                  :ry (- dry (js/Math.round dry))})
                  (add-wire-segment [x y])))))))

(defn drag-start [k type e]
  ; skip the button press from a drag initiated from a toolbar button
  (let [uiv @ui]
    (when (not= (::dragging uiv) ::device)
      ; primary mouse click
      (when (= (.-button e) 0)
        (.stopPropagation e) ; prevent bg drag
        (letfn [(update-selection [sel]
                  (if (or (contains? sel k)
                          (.-shiftKey e))
                    (sconj sel k)
                    #{k}))
                (drag-type [ui]
                  (assoc ui ::dragging
                         (case @tool
                           ::cursor ::device
                           ::wire ::wire
                           ::eraser type)))]
          (when (not= (::dragging uiv) ::wire)
            (swap! ui (fn [ui]
                        (-> ui
                            (update ::selected update-selection)
                            (drag-type))))))
        (case [@tool type]
          [::eraser ::device] (delete-selected)
          [::eraser ::wire] (swap! schematic remove-wire
                                   (first (::selected @ui))
                                   (viewbox-coord e)) ;; TODO
          [::wire ::device] (add-wire (viewbox-coord e) (nil? (::dragging uiv)))
          nil)))))

(defn drag-start-background [e]
  (cond
    (= (.-button e) 1) (swap! ui assoc ::dragging ::view)
    (= ::wire @tool) (add-wire (viewbox-coord e) (nil? (::dragging @ui)))))

(defn tetris [x y _ _]
  [:rect.tetris {:x x, :y y
                 :width grid-size
                 :height grid-size}])

(defn port [x y _ _]
  [:circle.port {:cx (+ x (/ grid-size 2))
                 :cy (+ y (/ grid-size 2))
                 :r (/ grid-size 10)}])

(defn delta-pos [xy k v]
  (if (contains? @selected k)
    (+ (get v xy) (get @delta xy))
    (get v xy)))

(defn device [size k v & elements]
  (assert (js/isFinite size))
  [:svg.device {:x (* (delta-pos :x k v) grid-size)
                :y (* (delta-pos :y k v) grid-size)
                :width (* size grid-size)
                :height (* size grid-size)
                :class [(:cell v) (when (contains? @selected k) :selected)]}
   [:g.position
    {:on-mouse-down (fn [e] (drag-start k ::device e))}
    (into [:g.transform
           {:width (* size grid-size)
            :height (* size grid-size)
            :transform (.toString (transform (:transform v cm/IV)))}]
          elements)]])

(defn draw-pattern [size pattern prim k v]
  [apply device size k v
    (for [[x y c] pattern]
      ^{:key [x y]} [prim (* x grid-size) (* y grid-size) k v])])


(defn get-model [layer model]
  (let [m (-> models
              (get (:cell model)
                   {::bg #'circuit-shape
                    ::conn #'circuit-conn
                    ::sym #'circuit-sym})
              (get layer))]
    ;; (assert m "no model")
    (cond
      (fn? m) m
      (= layer ::bg) (partial draw-pattern (cm/pattern-size m) m tetris)
      (= layer ::conn) (partial draw-pattern (cm/pattern-size m) m port)
      :else (fn [k _v] (println "invalid model for" k)))))

(defn lines [arcs]
  [:<>
   (for [arc arcs]
     ^{:key arc} [:polyline {:points (map #(* % grid-size) (flatten arc))}])])

(defn arrow [x y size rotate]
  [:polygon.arrow {:transform (str "rotate(" rotate " " (* x grid-size) " " (* y grid-size) ")")
                   :points
                   (map #(* % grid-size)
                        [x y
                         (+ x size) (+ y size)
                         (+ x size) (- y size)])}])

(defn wire-bg [key wire]
  (let [name (or (:name wire) key)
        x (delta-pos :x key wire)
        y (delta-pos :y key wire)
        rx (delta-pos :rx key wire)
        ry (delta-pos :ry key wire)]
    [:g.wire {:on-mouse-down #(drag-start key ::device %)}
     ; TODO drag-start ::wire nodes (with reverse) 
     [:line.wirebb {:x1 (* (+ x 0.5) grid-size)
                    :y1 (* (+ y 0.5) grid-size)
                    :x2 (* (+ x rx 0.5) grid-size)
                    :y2 (* (+ y ry 0.5) grid-size)}]]))


(defn clean-selected [ui sch]
  (update ui ::selected
          (fn [sel]
            (into #{} (filter #(contains? sch %)) sel))))

(defn drag-end [e]
  (let [bg? (= (.-target e) (.-currentTarget e))
        selected (::selected @ui)
        {dx :x dy :y drx :rx dry :ry} @delta
        deselect (fn [ui] (if bg? (assoc ui ::selected #{}) ui))
        end-ui (fn [ui]
                 (-> ui
                     (assoc ::dragging nil)
                     deselect
                     (clean-selected @schematic)))]
    (when-not (= (::dragging @ui) ::wire)
      (go
        (<! (swap! schematic update-keys selected
                   (fn [{x :x y :y :as dev}]
                     (assoc dev
                            :x (js/Math.round (+ x dx))
                            :y (js/Math.round (+ y dy))))))
        (reset! delta {:x 0 :y 0 :rx 0 :ry 0})
        (swap! ui end-ui)))))

(defn wire-sym [key wire]
  (let [name (or (:name wire) key)
        x (delta-pos :x key wire)
        y (delta-pos :y key wire)
        rx (delta-pos :rx key wire)
        ry (delta-pos :ry key wire)]
    [:g.wire {:on-mouse-down #(drag-start key ::device %)}
     ; TODO drag-start ::wire nodes (with reverse) 
     [:line.wire {:x1 (* (+ x 0.5) grid-size)
                  :y1 (* (+ y 0.5) grid-size)
                  :x2 (* (+ x rx 0.5) grid-size)
                  :y2 (* (+ y ry 0.5) grid-size)}]]))

(defn label-sym [key label]
  [device 1 key label
   [lines [[[0.5 0.5]
            [0.3 0.3]
            [-0.5 0.3]
            [-0.5 0.7]
            [0.3 0.7]
            [0.5 0.5]]]]
   [:text {:x (* -0.4 grid-size) :y (* 0.6 grid-size)} (:name label)]])

(defn mosfet-sym [k v]
  (let [shape [[[0.5 1.5]
                [1 1.5]]
               [[1 1]
                [1 2]]
               [[1.5 0.5]
                [1.5 1]
                [1.1 1]
                [1.1 2]
                [1.5 2]
                [1.5 2.5]]
               [[1.5 1.5]
                [1.1 1.5]]]]
    [device 3 k v
     [lines shape]
     (if (= (:cell v) "nmos")
       [arrow 1.2 1.5 0.15 0]
       [arrow 1.4 1.5 0.15 180])]))

(defn bjt-sym [k v]
  (let [shape [[[0.5 1.5]
                [1 1.5]]
               [[1 1.1]
                [1 1.9]]
               [[1.5 0.5]
                [1.5 1]
                [1 1.4]
                [1 1.6]
                [1.5 2]
                [1.5 2.5]]]]
    [device 3 k v
     [lines shape]
     (if (= (:cell v) "npn")
       [arrow 1.4 1.92 0.15 -145]
       [arrow 1.13 1.7 0.15 35])]))

(defn resistor-sym [k v]
  (let [shape [[[0.5 0.5]
                [0.5 0.7]]
               [[0.5 1.3]
                [0.5 1.5]]]]
    [device 2 k v
     [:rect.outline {:x (* 0.4 grid-size)
             :y (* 0.7 grid-size)
             :width (* 0.2 grid-size)
             :height (* 0.6 grid-size)}]
     [lines shape]]))

(defn capacitor-sym [k v]
  (let [shape [[[0.5 0.5]
                [0.5 0.9]]
               [[0.2 0.9]
                [0.8 0.9]]
               [[0.2 1.1]
                [0.8 1.1]]
               [[0.5 1.1]
                [0.5 1.5]]]]
    [device 2 k v
     [lines shape]]))

(defn inductor-sym [k v]
  (let [shape [[[0.5 0.5]
                [0.5 0.7]]
               [[0.5 1.3]
                [0.5 1.5]]]]
    [device 2 k v
     [lines shape]
     [:path {:d "M25,35
                 a5,5 90 0,1 0,10
                 a5,5 90 0,1 0,10
                 a5,5 90 0,1 0,10
                 "}]]))

(defn isource-sym [k v]
  (let [shape [[[0.5 0.5]
                [0.5 0.6]]
               [[0.5 0.85]
                [0.5 1.25]]
               [[0.5 1.4]
                [0.5 1.5]]]]
    [device 2 k v
     [lines shape]
     [:circle.outline
      {:cx (/ grid-size 2)
       :cy grid-size
       :r (* grid-size 0.4)}]
     [arrow 0.5 0.7 0.15 90]]))

(defn vsource-sym [k v]
  (let [shape [[[0.5 0.5]
                [0.5 0.6]]
               [[0.5 1.4]
                [0.5 1.5]]]]
    [device 2 k v
     [lines shape]
     [:circle.outline
      {:cx (/ grid-size 2)
       :cy grid-size
       :r (* grid-size 0.4)}]
     [:text {:x 25 :y 45 :text-anchor "middle"} "+"]
     [:text {:x 25 :y 65 :text-anchor "middle"} "−"]]))

(defn diode-sym [k v]
  (let [shape [[[0.5 0.5]
                [0.5 0.9]]
               [[0.3 1.1]
                [0.7 1.1]]
               [[0.5 1.1]
                [0.5 1.5]]]]
    [device 2 k v
     [lines shape]
     [arrow 0.5 1.1 0.2 270]]))

(defn circuit-shape [k v]
  (let [model (:cell v)
        pattern (get-in @modeldb [(str "models" sep model) :bg] [[0 0 "%"]])]
    (draw-pattern (cm/pattern-size pattern) pattern
                  tetris k v)))

(defn circuit-conn [k v]
  (let [model (:cell v)
        bgptn (get-in @modeldb [(str "models" sep model) :bg] [[0 0 "%"]])
        pattern (get-in @modeldb [(str "models" sep model) :conn] [[0 0 "%"]])]
    (draw-pattern (cm/pattern-size bgptn) pattern
                  port k v)))

(defn ckt-url [cell model]
  (str "?" (.toString (js/URLSearchParams. #js{:schem (str cell "$" model) :db dbname :sync sync}))))

(defn circuit-sym [k v]
  (let [cell (:cell v)
        model (get-in v [:props :model]) 
        pattern (get-in @modeldb [(str "models" sep cell) :bg] [])]
    [device (cm/pattern-size pattern) k v
     [:image {:href (get-in @modeldb [(str "models" sep cell) :sym])
              :on-mouse-down #(.preventDefault %) ; prevent dragging the image
              :on-double-click #(.assign js/window.location (ckt-url cell model))}]]))
  
(defn add-device [cell [x y]]
  (let [name (make-name cell)]
    (swap! schematic assoc name {:transform cm/IV, :cell cell :x x :y y})
    (swap! ui assoc
           ::tool ::cursor
           ::dragging ::device
           ::selected #{name})))

(defn save-url []
  (let [blob (js/Blob. #js[(prn-str @schematic)]
                       #js{:type "application/edn"})]
    (.createObjectURL js/URL blob)))

(defn b64encode [s]
  (-> s
      js/encodeURIComponent
      (clojure.string/replace
       #"%([0-9A-F]{2})"
       #(js/String.fromCharCode (str "0x" (nth % 1))))
      js/btoa))

(defn snapshot []
  (swap! snapshots assoc (str "snapshots" sep group "#" (.toISOString (js/Date. )))
         {:schematic @schematic
          :_attachments {"preview.svg" {
                          :content_type "image/svg+xml"
                          :data (b64encode (str
                                          "<?xml-stylesheet type=\"text/css\" href=\"https://nyancad.github.io/Mosaic/app/css/style.css\" ?>"
                                          (.-outerHTML (js/document.getElementById "mosaic_canvas"))))}}}))

(defn deviceprops [key]
  (let [props (r/cursor (.-cache schematic) [key :props])
        cell (r/cursor (.-cache schematic) [key :cell])
        name (r/cursor (.-cache schematic) [key :name])]
    (fn [key]
      [:<>
       [:h1 @cell ": " (or @name key)]
       [:div.properties
        (when-not (contains? models @cell)
          [:<>
           [:a {:href (ckt-url @cell (:model @props))} "Edit"]
           [:label {:for "cell" :title "cell name"} "cell"]
           [:input {:id "cell"
                    :type "text"
                    :default-value @cell
                    :on-change (debounce #(swap! schematic assoc-in [key :cell] (.. % -target -value)))}]])
        [:label {:for "name" :title "Instance name"} "name"]
        [:input {:id "name"
                 :type "text"
                 :default-value @name
                 :on-change (debounce #(swap! schematic assoc-in [key :name] (.. % -target -value)))}]
        [:label {:for "model" :title "Device model"} "model"]
        [:input {:id "model"
                 :type "text"
                 :default-value (:model @props)
                 :on-change (debounce #(swap! schematic assoc-in [key :props :model] (.. % -target -value)))}]
        (doall (for [[prop meta] (::props (get models @cell))]
                 [:<> {:key prop}
                  [:label {:for prop :title (:tooltip meta)} prop]
                  [:input {:id prop
                           :type "text"
                           :default-value (get @props prop)
                           :on-change (debounce #(swap! schematic assoc-in [key :props prop] (.. % -target -value)))}]]))
        [:label {:for "spice" :title "Extra spice data"} "spice"]
        [:input {:id "spice"
                 :type "text"
                 :default-value (:spice @props)
                 :on-change (debounce #(swap! schematic assoc-in [key :props :spice] (.. % -target -value)))}]]])))

(defn copy []
  (let [sel @selected
        sch @schematic
        devs (map (comp #(dissoc % :_rev :_id) sch) sel)]
    (swap! local assoc (str "local" sep "clipboard") {:data devs})))

(defn cut []
  (copy)
  (delete-selected))

(defn paste []
  (let [devs (get-in @local [(str "local" sep "clipboard") :data])
        devmap (into {} (map (fn [d] [(make-name (:cell d)) d])) devs)]
    (swap! schematic into devmap)
    (reset! selected (set (keys devmap)))))

(defn menu-items []
  [:<>
   [:a {:title "Save Snapshot"
        :on-click snapshot}
    [cm/save]]
   [:select {:on-change #(swap! ui assoc ::theme (.. % -target -value))}
    [:option {:value "tetris"} "Tetris"]
    [:option {:value "eyesore"} "Classic"]]
   [:span.sep]
   [cm/radiobuttons tool
    [[[cm/cursor] ::cursor "Cursor"]
     [[cm/wire] ::wire "Wire"]
     [[cm/eraser] ::eraser "Eraser"]]]
   [:span.sep]
   [:a {:title "Rotate selected clockwise [s]"
        :on-click (fn [_] (swap! schematic transform-selected (::selected @ui) #(.rotate % 90)))}
    [cm/rotatecw]]
   [:a {:title "Rotate selected counter-clockwise [shift+s]"
        :on-click (fn [_] (swap! schematic transform-selected (::selected @ui) #(.rotate % -90)))}
    [cm/rotateccw]]
   [:a {:title "Mirror selected horizontal [shift+f]"
        :on-click (fn [_] (swap! schematic transform-selected (::selected @ui) #(.flipY %)))}
    [cm/mirror-horizontal]]
   [:a {:title "Mirror selected vertical [f]"
        :on-click (fn [_] (swap! schematic transform-selected (::selected @ui) #(.flipX %)))}
    [cm/mirror-vertical]]
   [:a {:title "Delete selected [del]"
        :on-click (fn [_] (delete-selected))}
    [cm/delete]]
   [:a {:title "Copy selected [ctrl+c]"
        :on-click (fn [_] (copy))}
    [cm/copyi]]
   [:a {:title "Cut selected [ctrl+x]"
        :on-click (fn [_] (cut))}
    [cm/cuti]]
   [:a {:title "Paste [ctrl+v]"
        :on-click (fn [_] (paste))}
    [cm/pastei]]
   [:span.sep]
   [:a {:title "zoom in [scroll wheel/pinch]"
        :on-click #(button-zoom -1)}
    [cm/zoom-in]]
   [:a {:title "zoom out [scroll wheel/pinch]"
        :on-click #(button-zoom 1)}
    [cm/zoom-out]]
   [:a {:title "undo [ctrl+z]"
        :on-click undo-schematic}
    [cm/undoi]]
   [:a {:title "redo [ctrl+shift+z]"
        :on-click redo-schematic}
    [cm/redoi]]
   [:span.sep]
   [:a {:title "Add wire label [w]"
        :on-click #(add-device "label" (viewbox-coord %))}
    [cm/label]]
   [:a {:title "Add resistor [r]"
        :on-click #(add-device "resistor" (viewbox-coord %))}
    "R"]
   [:a {:title "Add inductor [l]"
        :on-click #(add-device "inductor" (viewbox-coord %))}
    "L"]
   [:a {:title "Add capacitor [c]"
        :on-click #(add-device "capacitor" (viewbox-coord %))}
    "C"]
   [:a {:title "Add diode [d]"
        :on-click #(add-device "diode" (viewbox-coord %))}
    "D"]
   [:a {:title "Add voltage source [v]"
        :on-click #(add-device "vsource" (viewbox-coord %))}
    "V"]
   [:a {:title "Add current source [i]"
        :on-click #(add-device "isource" (viewbox-coord %))}
    "I"]
   [:a {:title "Add N-channel mosfet [n]"
        :on-click #(add-device "nmos" (viewbox-coord %))}
    "N"]
   [:a {:title "Add PNP bjt"
        :on-click #(add-device "pnp" (viewbox-coord %))}
    "QP"]
   [:a {:title "Add NPN"
        :on-click #(add-device "npn" (viewbox-coord %))}
    "QN"]
   [:a {:title "Add P-channel mosfet [p]"
        :on-click #(add-device "pmos" (viewbox-coord %))}
    "P"]
   [:a {:title "Add subcircuit [x]"
        :on-click #(add-device "ckt" (viewbox-coord %))}
    "X"]])

(defn schematic-elements []
  [:<>
   (for [[k v] @schematic
         :when (= "wire" (:cell v))]
     ^{:key k} [(get-model ::bg v) k v])
   (for [[k v] @schematic
         :when (not= "wire" (:cell v))]
     ^{:key k} [(get-model ::bg v) k v])
   (for [[k v] @schematic]
     ^{:key k} [(get-model ::sym v) k v])
   (for [[k v] @schematic]
     ^{:key k} [(get-model ::conn v) k v])])

(defn schematic-ui []
  [:div#mosaic_app {:class @theme}
   [:div#mosaic_menu
    [menu-items]]
   [:div#mosaic_sidebar
    (when-let [sel (seq @selected)]
      (doall (for [key sel]
               ^{:key key} [deviceprops key])))]
   [:svg#mosaic_canvas {:xmlns "http://www.w3.org/2000/svg"
                        :height "100%"
                        :width "100%"
                        :class @theme ; for export
                        :view-box @zoom
                        :on-wheel scroll-zoom
                        :on-mouse-down drag-start-background
                        :on-mouse-up drag-end
                        :on-mouse-move drag}
    [schematic-elements]]])

(def shortcuts {#{:c} #(add-device "capacitor" (::mouse @ui))
                #{:r} #(add-device "resistor" (::mouse @ui))
                #{:l} #(add-device "inductor" (::mouse @ui))
                #{:d} #(add-device "diode" (::mouse @ui))
                #{:v} #(add-device "vsource" (::mouse @ui))
                #{:i} #(add-device "isource" (::mouse @ui))
                #{:n} #(add-device "nmos" (::mouse @ui))
                #{:p} #(add-device "pmos" (::mouse @ui))
                #{:x} #(add-device "ckt" (::mouse @ui))
                #{:w} #(add-device "label" (::mouse @ui))
                #{:backspace} delete-selected
                #{:delete} delete-selected
                #{:s}        (fn [_] (swap! schematic transform-selected (::selected @ui) #(.rotate % 90)))
                #{:shift :s} (fn [_] (swap! schematic transform-selected (::selected @ui) #(.rotate % -90)))
                #{:shift :f} (fn [_] (swap! schematic transform-selected (::selected @ui) #(.flipY %)))
                #{:f}        (fn [_] (swap! schematic transform-selected (::selected @ui) #(.flipX %)))
                #{:control :c} copy
                #{:control :x} cut
                #{:control :v} paste
                #{:control :z} undo-schematic
                #{:control :shift :z} redo-schematic})

(defn ^:dev/after-load ^:export  render []
  ;; (js/document.addEventListener "keyup" keyboard-shortcuts)
  (set! js/document.onkeyup (partial cm/keyboard-shortcuts shortcuts))
  (rd/render [schematic-ui]
             (.getElementById js/document "mosaic_root")))

(defn ^:export init
  ([] ; get the params from global variables or url parameters
   (let [params (js/URLSearchParams. js/window.location.search)
         group (or js/window.schem (.get params "schem") "myschem")
         dbname (or js/window.db (.get params "db") "schematics")
         sync (or js/window.sync (.get params "sync") cm/default-sync)]
     (init group dbname sync)))
  ([group dbname] ; default sync
   (init group dbname cm/default-sync))
  ([group* dbname* sync*] ; fully specified
   (let [db (pouchdb dbname*)
         schematic* (pouch-atom db group* (r/atom {}))]
     (when (seq sync*) ; pass nil to disable synchronization
       (.sync db sync* #js{:live true, :retry true}))
     (set-validator! (.-cache schematic*)
                     #(or (s/valid? :nyancad.mosaic.common/schematic %) (.log js/console (pr-str %) (s/explain-str :nyancad.mosaic.common/schematic %))))
     (add-watch schematic* ::undo #(cm/newdo undotree %4))
     (set! group group*)
     (set! dbname dbname*)
     (set! sync sync*)
     (set! modeldb (pouch-atom db "models" (r/atom {})))
     (set! snapshots (pouch-atom db "snapshots" (r/atom {})))
     (set! schematic schematic*))
   (render)))

(defn ^:export clear []
  (swap! schematic #(apply dissoc %1 %2) (set (keys @schematic))))