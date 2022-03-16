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
                     mosfet-shape bjt-conn]]))


(def params (js/URLSearchParams. js/window.location.search))
(def group (or (.get params "schem") "myschem"))
(def dbname (or (.get params "db") "schematics"))
(def sync (or (.get params "sync") cm/default-sync))
(defonce db (pouchdb dbname))
(defonce schematic (pouch-atom db group (r/atom {})))
(set-validator! (.-cache schematic)
                #(or (s/valid? :nyancad.mosaic.common/schematic %) (.log js/console (pr-str %) (s/explain-str :nyancad.mosaic.common/schematic %))))

(defonce modeldb (pouch-atom db "models" (r/atom {})))
(defonce snapshots (pouch-atom db "snapshots" (r/atom {})))
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
                     ::mouse [0 0]
                     ::mouse-start [0 0]}))

(s/def ::zoom (s/coll-of number? :count 4))
(s/def ::theme #{"tetris" "eyesore"})
(s/def ::tool #{::cursor ::eraser ::wire ::pan ::device})
(s/def ::selected (s/and set? (s/coll-of string?)))
(s/def ::dragging (s/nilable #{::wire ::device ::view}))
(s/def ::staging (s/nilable :nyancad.mosaic.common/device))
(s/def ::ui (s/keys :req [::zoom ::theme ::tool ::selected]
                    :opt [::dragging ::staging]))

(set-validator! ui #(or (s/valid? ::ui %) (.log js/console (pr-str %) (s/explain-str ::ui %))))

(defonce zoom (r/cursor ui [::zoom]))
(defonce theme (r/cursor ui [::theme]))
(defonce tool (r/cursor ui [::tool]))
(defonce selected (r/cursor ui [::selected]))
(defonce delta (r/cursor ui [::delta]))
(defonce staging (r/cursor ui [::staging]))


(defonce undotree (cm/newundotree))
(add-watch schematic ::undo #(cm/newdo undotree %4))

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

(declare drag-start)

(defn device [size k v & elements]
  (assert (js/isFinite size))
  (into [:g.device {:on-mouse-down (fn [e] (drag-start k e))
              :style {:transform (.toString (.translate (transform (:transform v cm/IV)) (* (:x v) grid-size) (* (:y v) grid-size)))
                      :transform-origin (str (* (+ (:x v) (/ size 2)) grid-size) "px "
                                             (* (+ (:y v) (/ size 2)) grid-size) "px")}
              :class [(:cell v) (when (contains? @selected k) :selected)]}]
          elements))

(defn port [x y _ _]
  [:circle.port {:cx (+ x (/ grid-size 2))
                 :cy (+ y (/ grid-size 2))
                 :r (/ grid-size 10)}])

(defn draw-background [[width height] k v]
  [device (+ 2 (max width height)) k v
   [:rect.tetris {:x grid-size :y grid-size
                  :width (* width grid-size)
                  :height (* height grid-size)}]])

(defn draw-pattern [size pattern prim k v]
  [apply device size k v
    (for [[x y c] pattern]
      ^{:key [x y]} [prim (* x grid-size) (* y grid-size) k v])])


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

(defn wire-sym [key wire]
  (let [name (or (:name wire) key)
        x (:x wire)
        y (:y wire)
        rx (:rx wire)
        ry (:ry wire)]
    [:g.wire {:on-mouse-down #(drag-start key %)}
     ; TODO drag-start ::wire nodes (with reverse) 
     [:line.wirebb {:x1 (* (+ x 0.5) grid-size)
                    :y1 (* (+ y 0.5) grid-size)
                    :x2 (* (+ x rx 0.5) grid-size)
                    :y2 (* (+ y ry 0.5) grid-size)}]
     [:line.wire {:x1 (* (+ x 0.5) grid-size)
                  :y1 (* (+ y 0.5) grid-size)
                  :x2 (* (+ x rx 0.5) grid-size)
                  :y2 (* (+ y ry 0.5) grid-size)}]]))

(defn port-sym [key label]
  [device 1 key label
   [lines [[[0.5 0.5]
            [0.3 0.3]
            [0 0.3]
            [0 0.7]
            [0.3 0.7]
            [0.5 0.5]]]]
   [:text {:text-anchor "middle"
           :dominant-baseline "middle"
           :transform (-> (:transform label)
                          transform
                          (.translate (/ grid-size 2) (/ grid-size -2))
                          .inverse
                          .toString)}
    (:name label)]])

(defn mosfet-sym [k v]
  (let [shape [[[0.5 1.5]
                [1.1 1.5]]
               [[1.1 1.1]
                [1.1 1.9]]
               [[1.5 0.5]
                [1.5 1.1]
                [1.2 1.1]
                [1.2 1.9]
                [1.5 1.9]
                [1.5 2.5]]
               [[1.5 1.5]
                [1.2 1.5]]]]
    [device 3 k v
     [lines shape]
     (if (= (:cell v) "nmos")
       [arrow 1.3 1.5 0.12 0]
       [arrow 1.4 1.5 0.12 180])]))

(defn bjt-sym [k v]
  (let [shape [[[0.5 1.5]
                [1.15 1.5]]
               [[1.15 1.15]
                [1.15 1.85]]
               [[1.5 0.5]
                [1.5 1.1]
                [1.15 1.4]
                [1.15 1.6]
                [1.5 1.9]
                [1.5 2.5]]]]
    [device 3 k v
     [lines shape]
     (if (= (:cell v) "npn")
       [arrow 1.4 1.81 0.12 -140]
       [arrow 1.25 1.68 0.12 40])]))

(defn resistor-sym [k v]
  (let [shape [[[1.5 0.5]
                [1.5 1.1]]
               [[1.5 1.9]
                [1.5 2.5]]]]
    [device 3 k v
     [:rect.outline {:x (* 1.35 grid-size)
             :y (* 1.1 grid-size)
             :width (* 0.3 grid-size)
             :height (* 0.8 grid-size)}]
     [lines shape]]))

(defn capacitor-sym [k v]
  (let [shape [[[1.5 0.5]
                [1.5 1.4]]
               [[1.1 1.4]
                [1.9 1.4]]
               [[1.1 1.6]
                [1.9 1.6]]
               [[1.5 1.6]
                [1.5 2.5]]]]
    [device 3 k v
     [lines shape]]))

(defn inductor-sym [k v]
  (let [shape [[[1.5 0.5]
                [1.5 1.1]]
               [[1.5 1.9]
                [1.5 2.5]]]]
    [device 3 k v
     [lines shape]
     [:path {:d "M75,55
                 a5,5 90 0,0 0,10
                 a5,5 90 0,0 0,10
                 a5,5 90 0,0 0,10
                 a5,5 90 0,0 0,10
                 "}]]))

(defn isource-sym [k v]
  (let [shape [[[1.5 0.5]
                [1.5 1.1]]
               [[1.5 1.35]
                [1.5 1.75]]
               [[1.5 1.9]
                [1.5 2.5]]]]
    [device 3 k v
     [lines shape]
     [:circle.outline
      {:cx (* grid-size 1.5)
       :cy (* grid-size 1.5)
       :r (* grid-size 0.4)}]
     [arrow 1.5 1.2 0.15 90]]))

(defn vsource-sym [k v]
  (let [shape [[[1.5 0.5]
                [1.5 1.1]]
               [[1.5 1.9]
                [1.5 2.5]]]]
    [device 3 k v
     [lines shape]
     [:circle.outline
      {:cx (* grid-size 1.5)
       :cy (* grid-size 1.5)
       :r (* grid-size 0.4)}]
     [:text {:x 75 :y 70 :text-anchor "middle"} "+"]
     [:text {:x 75 :y 90 :text-anchor "middle"} "−"]]))

(defn diode-sym [k v]
  (let [shape [[[1.5 0.5]
                [1.5 1.4]]
               [[1.3 1.6]
                [1.7 1.6]]
               [[1.5 1.6]
                [1.5 2.5]]]]
    [device 3 k v
     [lines shape]
     [arrow 1.5 1.6 0.2 270]]))

(defn circuit-shape [k v]
  (let [model (:cell v)
        size (get-in @modeldb [(str "models" sep model) :bg] [1 1])]
    (draw-background size k v)))

(defn circuit-conn [k v]
  (let [model (:cell v)
        [width height] (get-in @modeldb [(str "models" sep model) :bg] [1 1])
        pattern (get-in @modeldb [(str "models" sep model) :conn] [[1 1 "%"]])]
    (draw-pattern (+ 2 (max width height)) pattern
                  port k v)))

(defn ckt-url [cell model]
  (str "?" (.toString (js/URLSearchParams. #js{:schem (str cell "$" model) :db dbname :sync sync}))))

(defn circuit-sym [k v]
  (let [cell (:cell v)
        model (get-in v [:props :model]) 
        [width height] (get-in @modeldb [(str "models" sep cell) :bg] [1 1])]
    [device (+ 2 (max width height)) k v
     [:image {:href (get-in @modeldb [(str "models" sep cell) :sym])
              :on-mouse-down #(.preventDefault %) ; prevent dragging the image
              :on-double-click #(.assign js/window.location (ckt-url cell model))}]]))
(def models {"pmos" {::bg cm/active-bg
                     ::conn mosfet-shape
                     ::sym mosfet-sym
                     ::props {:m {:tooltip "multiplier"}
                              :nf {:tooltip "number of fingers"}
                              :w {:tooltip "width" :unit "meter"}
                              :l {:tooltip "lenght" :unit "meter"}}}
             "nmos" {::bg cm/active-bg
                     ::conn mosfet-shape
                     ::sym mosfet-sym
                     ::props {:m {:tooltip "multiplier"}
                              :nf {:tooltip "number of fingers"}
                              :w {:tooltip "width" :unit "meter"}
                              :l {:tooltip "lenght" :unit "meter"}}}
             "npn" {::bg cm/active-bg
                     ::conn bjt-conn
                     ::sym bjt-sym
                     ::props {:m {:tooltip "multiplier"}
                              :nf {:tooltip "number of fingers"}
                              :w {:tooltip "width" :unit "meter"}
                              :l {:tooltip "lenght" :unit "meter"}}}
             "pnp" {::bg cm/active-bg
                     ::conn bjt-conn
                     ::sym bjt-sym
                     ::props {:m {:tooltip "multiplier"}
                              :nf {:tooltip "number of fingers"}
                              :w {:tooltip "width" :unit "meter"}
                              :l {:tooltip "lenght" :unit "meter"}}}
             "resistor" {::bg cm/twoport-bg
                         ::conn cm/twoport-conn
                         ::sym resistor-sym
                         ::props {:resistance {:tooltip "Resistance" :unit "Ohm"}}}
             "capacitor" {::bg cm/twoport-bg
                          ::conn cm/twoport-conn
                          ::sym capacitor-sym
                          ::props {:capacitance {:tooltip "Capacitance" :unit "Farad"}}}
             "inductor" {::bg cm/twoport-bg
                         ::conn cm/twoport-conn
                         ::sym inductor-sym
                         ::props {:inductance {:tooltip "Inductance" :unit "Henry"}}}
             "vsource" {::bg cm/twoport-bg
                        ::conn cm/twoport-conn
                        ::sym vsource-sym
                        ::props {:dc {:tooltip "DC voltage" :unit "Volt"}
                                 :ac {:tooltip "AC voltage" :unit "Volt"}}}
             "isource" {::bg cm/twoport-bg
                        ::conn cm/twoport-conn
                        ::sym isource-sym
                        ::props {:dc {:tooltip "DC current" :unit "Ampere"}
                                 :ac {:tooltip "AC current" :unit "Ampere"}}}
             "diode" {::bg cm/twoport-bg
                      ::conn cm/twoport-conn
                      ::sym diode-sym
                      ::props {}}
             "wire" {::bg []
                     ::conn []
                     ::sym wire-sym
                     ::props {}}
             "port" {::bg []
                      ::conn [[0 0 "P"]]
                      ::sym port-sym
                      ::props {}}})

(defn rotate-shape [shape [a b c d e f] devx, devy]
  (let [size (cm/pattern-size shape)
        mid (- (/ size 2) 0.5)]
    (map (fn [[px py p]]
           (let [x (- px mid)
                 y (- py mid)
                 nx (+ (* a x) (* c y) e)
                 ny (+ (* b x) (* d y) f)]
             [(js/Math.round (+ devx nx mid))
               (js/Math.round (+ devy ny mid))])) shape)))

(defn build-wire-index [sch]
  (reduce
   (fn [idx {:keys [:_id :x :y :rx :ry :cell :transform]}]
     (cond
       (= cell "wire") (-> idx
                           (update [x y] conj _id)
                           (update [(+ x rx) (+ y ry)] conj _id))
       (contains? models cell) (reduce #(update %1 %2 conj _id) idx (rotate-shape (get-in models [cell ::conn]) transform x y))
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

(defn commit-staged [dev]
  (swap! schematic assoc (make-name (:cell dev)) dev))

(defn transform-selected [tf]
  (let [f (comp transform-vec tf transform)]
    (if @staging
      (swap! staging
             update :transform f)
      (swap! @schematic update-keys @selected
             update :transform f))))

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
  (let [[x y] (viewbox-coord e)
        [xs ys] (::mouse-start @ui)
        dx (- x xs)
        dy (- y ys)]
    (swap! delta assoc :x dx :y dy)))

(defn drag-wire [e]
  (let [[x y] (viewbox-coord e)]
    (swap! staging
           (fn [d]
             (let [rx (- x (:x d) 0.5)
                   ry (- y (:y d) 0.5)]
               (cond
                 (.-ctrlKey e) (assoc d :rx rx :ry ry)
                 (> (js/Math.abs rx) (js/Math.abs ry)) (assoc d :rx rx :ry 0)
                 :else (assoc d :rx 0 :ry ry)))))))

(defn drag-staged-device [e]
  (let [[x y] (viewbox-coord e)
        [width height] (get-in models [(:cell @staging) ::bg])
        xm (js/Math.round (- x width 0.5))
        ym (js/Math.round (- y height 0.5))]
    (swap! staging assoc :x xm :y ym)))

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
    ::cursor (cursor-drag e)
    ::pan (when (> (.-buttons e) 0) (drag-view e))
    ::device (drag-staged-device e)))

(defn add-wire-segment [[x y]]
  (swap! ui assoc
         ::staging {:cell "wire"
                    :x (js/Math.floor x)
                    :y (js/Math.floor y)
                    :rx 0 :ry 0}
         ::dragging ::wire))

(defn add-wire [[x y] first?]
  (if first?
    (add-wire-segment [x y]) ; just add a new wire, else finish old wire
    (let [dev (update-keys @staging #{:rx :ry} js/Math.round)
          {rx :rx ry :ry} dev
          x (js/Math.round (+ (:x dev) rx)) ; use end pos of previous wire instead
          y (js/Math.round (+ (:y dev) ry))
          on-port (contains? @wire-index [x y])
          same-tile (and (< (js/Math.abs rx) 0.5) (< (js/Math.abs ry) 0.5))]
      (cond
        same-tile (swap! ui assoc ; the dragged wire stayed at the same tile, exit
                         ::staging nil
                         ::dragging nil)
        on-port (go (<! (commit-staged dev)) ; the wire landed on a port or wire, commit and exit
                    (swap! ui assoc
                           ::staging nil
                           ::dragging nil))
        :else (go
                (<! (commit-staged dev)) ; commit and start new segment
                (add-wire-segment [x y]))))))

(defn drag-start [k e]
  (swap! ui assoc ::mouse-start (viewbox-coord e))
  (let [uiv @ui
        update-selection
        (fn [sel]
          (if (or (contains? sel k)
                  (.-shiftKey e))
            (sconj sel k)
            #{k}))
        drag-type
        (fn [ui]
          (assoc ui ::dragging ; can we refactor this whole thing out?
                 (case (::tool ui)
                   ::cursor ::device
                   ::wire ::wire
                   ::eraser ::device
                   ::pan ::view)))]
    ; skip the mouse down when initiated from a toolbar button
    ; only when primary mouse click
    (when (and (not= (::tool uiv) ::device)
               (= (.-button e) 0))
      (.stopPropagation e) ; prevent bg drag
      (if (= (::tool uiv) ::wire)
        (add-wire (viewbox-coord e) (nil? (::dragging uiv)))
        (swap! ui (fn [ui]
                    (-> ui
                        (update ::selected update-selection)
                        (drag-type))))))))

(defn drag-start-background [e]
  (cond
    (= (.-button e) 1) (swap! ui assoc ::dragging ::view)
    (and (= (.-button e) 0)
         (= ::wire @tool)) (add-wire (viewbox-coord e) (nil? (::dragging @ui)))))

(defn cancel []
  (swap! ui assoc
           ::dragging nil
           ::tool ::cursor
           ::staging nil))

(defn context-menu [e]
  (when (or (::dragging @ui)
            (not= (::tool @ui) ::cursor))
    (cancel)
    (.preventDefault e)))


(defn get-model [layer model k v]
  (let [m (-> models
              (get (:cell model)
                   {::bg #'circuit-shape
                    ::conn #'circuit-conn
                    ::sym #'circuit-sym})
              (get layer))]
    ;; (assert m "no model")
    (cond
      (fn? m) ^{:key k} [m k v]
      (= layer ::bg) ^{:key k} [draw-background m k v]
      (= layer ::conn) ^{:key k} [draw-pattern (cm/pattern-size m) m port k v]
      :else ^{:key k} [(fn [k _v] (println "invalid model for" k))])))


(defn clean-selected [ui sch]
  (update ui ::selected
          (fn [sel]
            (into #{} (filter #(contains? sch %)) sel))))

(defn drag-end [e]
  (.stopPropagation e)
  (let [bg? (= (.-target e) (.-currentTarget e))
        selected (::selected @ui)
        {dx :x dy :y} @delta
        deselect (fn [ui] (if bg? (assoc ui ::selected #{}) ui))
        end-ui (fn [ui]
                 (-> ui
                     (assoc ::dragging nil)
                     deselect
                     (clean-selected @schematic)))]
    (if (= (::tool @ui) ::device)
      (commit-staged @staging)
      (when-not (= (::dragging @ui) ::wire)
        (go
          (<! (swap! schematic update-keys selected
                     (fn [{x :x y :y :as dev}]
                       (assoc dev
                              :x (js/Math.round (+ x dx))
                              :y (js/Math.round (+ y dy))))))
          (reset! delta {:x 0 :y 0 :rx 0 :ry 0})
          (swap! ui end-ui))))))

  
(defn add-device [cell [x y]]
  (let [[width height] (get-in models [cell ::bg])
        mx (- x (/ width 2) 1)
        my (- y (/ height 2) 1)]
    (swap! ui assoc
           ::staging {:transform cm/IV, :cell cell :x mx :y my}
           ::tool ::device)))

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
  (let [props (r/cursor schematic [key :props])
        cell (r/cursor schematic [key :cell])
        name (r/cursor schematic [key :name])]
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
                    :on-change (debounce #(reset! cell (.. % -target -value)))}]])
        [:label {:for "name" :title "Instance name"} "name"]
        [:input {:id "name"
                 :type "text"
                 :default-value @name
                 :on-change (debounce #(reset! name (.. % -target -value)))}]
        [:label {:for "model" :title "Device model"} "model"]
        [:select {:id "model"
                  :type "text"
                  :default-value (:model @props)
                  :on-change #(swap! props assoc :model (.. % -target -value))}
         [:option]
         (for [m (keys (get-in  @modeldb [(str "models" sep @cell) :models]))]
           [:option m])]
        (doall (for [[prop meta] (::props (get models @cell))]
                 [:<> {:key prop}
                  [:label {:for prop :title (:tooltip meta)} prop]
                  [:input {:id prop
                           :type "text"
                           :default-value (get @props prop)
                           :on-change (debounce #(swap! props assoc prop (.. % -target -value)))}]]))
        [:label {:for "spice" :title "Extra spice data"} "spice"]
        [:input {:id "spice"
                 :type "text"
                 :default-value (:spice @props)
                 :on-change (debounce #(swap! props assoc :spice (.. % -target -value)))}]]])))

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
   ; inactive, active, key, title
    [[[cm/cursor] [cm/cursor] ::cursor "Cursor"]
     [[cm/wire] [cm/wire] ::wire "Wire"]
     [[cm/eraser] [cm/eraser] ::eraser "Eraser"]
     [[cm/move] [cm/move] ::pan "Pan"]]]
   [:span.sep]
   [:a {:title "Rotate selected clockwise [s]"
        :on-click (fn [_] (transform-selected #(.rotate % 90)))}
    [cm/rotatecw]]
   [:a {:title "Rotate selected counter-clockwise [shift+s]"
        :on-click (fn [_] (transform-selected #(.rotate % -90)))}
    [cm/rotateccw]]
   [:a {:title "Mirror selected horizontal [shift+f]"
        :on-click (fn [_] (transform-selected #(.flipY %)))}
    [cm/mirror-horizontal]]
   [:a {:title "Mirror selected vertical [f]"
        :on-click (fn [_] (transform-selected #(.flipX %)))}
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
   [:a {:title "Add port [p]"
        :on-click #(add-device "port" (viewbox-coord %))}
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
   [:a {:title "Add N-channel mosfet [m]"
        :on-click #(add-device "nmos" (viewbox-coord %))}
    "N"]
   [:a {:title "Add P-channel mosfet [shift+m]"
        :on-click #(add-device "pmos" (viewbox-coord %))}
    "P"]
   [:a {:title "Add NPN BJT [b]"
        :on-click #(add-device "npn" (viewbox-coord %))}
    "QN"]
   [:a {:title "Add PNP BJT [shift+b]"
        :on-click #(add-device "pnp" (viewbox-coord %))}
    "QP"]
   [:a {:title "Add subcircuit [x]"
        :on-click #(add-device "ckt" (viewbox-coord %))}
    "X"]])

(defn schematic-elements [schem]
  [:<>
   (for [[k v] schem
         :when (= "wire" (:cell v))]
     (get-model ::bg v k v))
   (for [[k v] schem
         :when (not= "wire" (:cell v))]
     (get-model ::bg v k v))
   (for [[k v] schem]
     (get-model ::sym v k v))
   (for [[k v] schem]
     (get-model ::conn v k v))])

(defn schematic-dots []
  [:<>
   (for [[[x y] ids] @wire-index
         :let [n (count ids)]
         :when (not= n 2)]
     [:circle
      {:key [x y]
       :class (if (> n 2) "wire" "nc")
       :cx (* grid-size (+ x 0.5))
       :cy (* grid-size (+ y 0.5))
       :r (/ grid-size 10)}])])

(defn tool-elements []
  (let [{sel ::selected dr ::dragging v ::staging {x :x y :y} ::delta} @ui
        vx (* grid-size (js/Math.round x))
        vy (* grid-size (js/Math.round y))]
     (if v
       [:g.toolstaging
        (get-model ::bg v ::stagingbg v)
        (get-model ::sym v ::stagingsym v)
        (get-model ::conn v ::stagingconn v)]
       (when (and sel dr)
         [:g.staging {:style {:transform (str "translate(" vx "px, " vy "px)")}}
          [schematic-elements
           (let [schem @schematic]
             (map #(vector % (get schem %)) sel))]]))))

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
                        :class [@theme @tool] ; for export
                        :view-box @zoom
                        :on-wheel scroll-zoom
                        :on-mouse-down drag-start-background
                        :on-mouse-up drag-end
                        :on-mouse-move drag
                        :on-context-menu context-menu}
    [:defs
     [:pattern {:id "gridfill",
                :pattern-units "userSpaceOnUse"
                :width grid-size
                :height grid-size}
      [:line.grid {:x1 0 :y1 0 :x2 grid-size :y2 0}]
      [:line.grid {:x1 0 :y1 0 :x2 0 :y2 grid-size}]]]
    [:rect {:fill "url(#gridfill)"
            :on-mouse-up drag-end
            :x (* -50 grid-size)
            :y (* -50 grid-size)
            :width (* 100 grid-size)
            :height (* 100 grid-size)}]
    [schematic-elements @schematic]
    [schematic-dots]
    [tool-elements]]])

(def shortcuts {#{:c} #(add-device "capacitor" (::mouse @ui))
                #{:r} #(add-device "resistor" (::mouse @ui))
                #{:l} #(add-device "inductor" (::mouse @ui))
                #{:d} #(add-device "diode" (::mouse @ui))
                #{:v} #(add-device "vsource" (::mouse @ui))
                #{:i} #(add-device "isource" (::mouse @ui))
                #{:m} #(add-device "nmos" (::mouse @ui))
                #{:shift :m} #(add-device "pmos" (::mouse @ui))
                #{:b} #(add-device "npn" (::mouse @ui))
                #{:shift :b} #(add-device "pnp" (::mouse @ui))
                #{:x} #(add-device "ckt" (::mouse @ui))
                #{:p} #(add-device "port" (::mouse @ui))
                #{:backspace} delete-selected
                #{:delete} delete-selected
                #{:w} (fn [_] ; right away start a wire
                        (add-wire (::mouse @ui) (nil? (::dragging @ui)))
                        (swap! ui assoc ::tool ::wire))
                #{:escape} cancel
                #{(keyword " ")} (fn [] (swap! ui #(assoc % ::tool (::prev-tool %))))
                #{:s}        (fn [_] (transform-selected #(.rotate % 90)))
                #{:shift :s} (fn [_] (transform-selected #(.rotate % -90)))
                #{:shift :f} (fn [_] (transform-selected #(.flipY %)))
                #{:f}        (fn [_] (transform-selected #(.flipX %)))
                #{:control :c} copy
                #{:control :x} cut
                #{:control :v} paste
                #{:control :z} undo-schematic
                #{:control :shift :z} redo-schematic})

(def immediate-shortcuts
  {#{(keyword " ")} (fn [] (swap! ui #(assoc % ::tool ::pan ::prev-tool (::tool %))))})

(defn ^:dev/after-load ^:export  render []
  (set! js/document.onkeyup (partial cm/keyboard-shortcuts shortcuts))
  (set! js/document.onkeydown (partial cm/keyboard-shortcuts immediate-shortcuts))
  (rd/render [schematic-ui]
             (.getElementById js/document "mosaic_editor")))

(defn ^:export init []
  (when (seq sync) ; pass nil to disable synchronization
    (.sync db sync #js{:live true, :retry true}))
  (render))

(defn ^:export clear []
  (swap! schematic #(apply dissoc %1 %2) (set (keys @schematic))))