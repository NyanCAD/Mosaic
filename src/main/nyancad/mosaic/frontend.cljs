(ns nyancad.mosaic.frontend
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [react-bootstrap-icons :as icons]))

(def grid-size 50)

(defn sign [n] (if (> n 0) 1 -1))

(def I (js/DOMMatrixReadOnly.))
(defn point [x y] (.fromPoint js/DOMPointReadOnly (clj->js {:x x :y y})))

(defonce state
  (r/atom
   {
    ::zoom [0 0 500 500],
    ::theme :tetris
    ::tool :cursor
    ::wires #{}
    ::schematic {
                ;;  :mos1 {:x (+ 0  0), :y (+ 0 0), :transform (.rotate I 270), :cell :pmos}
                ;;  :mos2 {:x (+ 2 0), :y (+ 1 0), :transform (.rotate I 0), :cell :nmos}
                ;;  :mos3 {:x (+ 1 0), :y (+ 3 0), :transform (.rotate I 90), :cell :pmos}
                ;;  :mos4 {:x (+ -1 0), :y (+ 2 0), :transform (.rotate I 180), :cell :nmos}
                ;;  :mos5 {:x (+ 0  4), :y (+ 0 0), :transform (.rotate I 270), :cell :pmos}
                ;;  :mos6 {:x (+ 2 4), :y (+ 1 0), :transform (.rotate I 0), :cell :nmos}
                ;;  :mos7 {:x (+ 1 4), :y (+ 3 0), :transform (.rotate I 90), :cell :pmos}
                ;;  :mos8 {:x (+ -1 4), :y (+ 2 0), :transform (.rotate I 180), :cell :nmos}
                ;;  :mos1a {:x (+ 0  0), :y (+ 0 4), :transform (.rotate I 270), :cell :pmos}
                ;;  :mos2a {:x (+ 2 0), :y (+ 1 4), :transform (.rotate I 0), :cell :nmos}
                ;;  :mos3a {:x (+ 1 0), :y (+ 3 4), :transform (.rotate I 90), :cell :pmos}
                ;;  :mos4a {:x (+ -1 0), :y (+ 2 4), :transform (.rotate I 180), :cell :nmos}
                ;;  :mos5a {:x (+ 0  4), :y (+ 0 4), :transform (.rotate I 270), :cell :pmos}
                ;;  :mos6a {:x (+ 2 4), :y (+ 1 4), :transform (.rotate I 0), :cell :nmos}
                ;;  :mos7a {:x (+ 1 4), :y (+ 3 4), :transform (.rotate I 90), :cell :pmos}
                ;;  :mos8a {:x (+ -1 4), :y (+ 2 4), :transform (.rotate I 180), :cell :nmos}
                ;;  imos2 {:x (+ 2 0), :y (+ 1 0), :transform (.rotate I 0), :cell :nmos}
                ;;  imos2 {:x (+ 2 0), :y (+ 1 0), :transform (.rotate I 0), :cell :nmos}
    }}))

(def mosfet-shape
  [" #"
   "##"
   " #"])

(declare mosfet-sym)
; should probably be in state eventually
(def models {::bg {:pmos mosfet-shape
                   :nmos mosfet-shape}
             ::conn {:pmos mosfet-shape
                     :nmos mosfet-shape}
             ::sym {:pmos #'mosfet-sym
                    :nmos #'mosfet-sym}})

(defn viewbox-coord [e]
  (let [^js el (.-currentTarget e)
        p (point (.-clientX e) (.-clientY e))
        m (.inverse (.getScreenCTM el))
        tp (.matrixTransform p m)]
    [(.-x tp) (.-y tp)]))

(defn viewbox-movement [e]
  (let [^js el (.-currentTarget e)
        m (.inverse (.getScreenCTM el))
        _ (do (set! (.-e m) 0)
              (set! (.-f m) 0)) ; cancel translation
        ^js p (point (.-movementX e) (.-movementY e))
        tp (.matrixTransform p m)] ; local movement
    [(.-x tp) (.-y tp)]))

(defn zoom-schematic [direction ex ey]
  (swap! state update-in [::zoom]
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
  (apply zoom-schematic (sign (.-deltaY e)) (viewbox-coord e)))

(defn button-zoom [dir]
  (let [[x y w h] (::zoom @state)]
    (zoom-schematic dir (+ x (/ w 2)) (+ y (/ h 2)))))

(defn port-locations [pattern v]
  (let [size (apply max (count pattern) (map count pattern))
        mid (.floor js/Math (/ size 2))]
     (for [[y s] (map-indexed vector pattern)
           [x c] (map-indexed vector s)
           :when (not= c " ")
           :let [gx (:x v)
                 gy (:y v)
                 p (.transformPoint (:transform v) (point (- x mid) (- y mid)))
                 nx (+ (.-x p) mid)
                 ny (+ (.-y p) mid)]]
         [(.round js/Math (+ gx nx)) (.round js/Math (+ gy ny))])))

(defn update-ports [st]
    (assoc st ::ports
           (set (mapcat #(port-locations
                       (get (::bg models) (:cell %)) %)
                     (vals (::schematic st))))))

(defn remove-wire [e]
  (let [coord (map #(.floor js/Math (/ % grid-size)) (viewbox-coord e))]
    (swap! state update-in [::wires] disj coord)))

(defn drag-view [e]
  (swap! state update-in [::zoom]
         (fn [[x y w h]]
           (let [[dx dy] (viewbox-movement e)]
             [(- x dx)
              (- y dy)
              w h]))))

(defn cursor-drag [e]
  (let [dragging (::dragging @state)]
    (case dragging
      ::view (drag-view e)
      ::wire (let [coord (map #(.floor js/Math (/ % grid-size)) (viewbox-coord e))]
               (when-not (contains? (::ports @state) coord)
                 (swap! state update-in [::wires] conj coord)))
      nil nil
      (swap! state (fn [st]
        (update-in st [::schematic dragging] (fn [d]
          (let [[x y] (map #(/ % grid-size) (viewbox-coord e))]
            (assoc d
                   :x (- x (::offsetx st))
                   :y (- y (::offsety st)))))))))))

(defn eraser-drag [e]
  (let [dragging (::dragging @state)]
    (case dragging
      ::view (drag-view e)
      ::wire (remove-wire e)
      nil))) ;todo remove devices?

(defn drag [e]
  (case (::tool @state)
    :eraser (eraser-drag e)
    :cursor (cursor-drag e)))
  
(defn drag-start-wire [e]
  (when (= (.-button e) 0)
    (when (= (::tool @state) :eraser) (remove-wire e))
    (swap! state #(-> %
                      update-ports
                      (assoc ::dragging ::wire)))
    (.stopPropagation e)))
    
(defn drag-start-device [k v e]
  (when (= (.-button e) 0)
    (if (= (::tool @state) :eraser)
      (swap! state update-in [::schematic] dissoc k)
      (let [[x y] (map #(/ % grid-size) (viewbox-coord e))]
        (swap! state assoc
               ::dragging k
               ::offsetx x
               ::offsety y)))))

(defn drag-end [e]
  (swap! state
    (fn [st]
      (update-ports
       (if-let [target (::dragging st)]
         (if (contains? #{::view ::wire} target)
           (assoc st ::dragging nil)
           (-> st
               (assoc ::dragging nil)
               (update-in [::schematic target :x] #(.round js/Math %))
               (update-in [::schematic target :y] #(.round js/Math %))))
         st)))))

(defn tetris [x y k v]
  [:rect.tetris {:x x, :y y
                 :width grid-size
                 :height grid-size
                 }])

(defn port [x y k v]
  [:circle.port {:cx (+ x (/ grid-size 2)),
                 :cy (+ y (/ grid-size 2)),
                 :r (/ grid-size 10)
                 :on-mouse-down drag-start-wire}])

(defn device [size k v & elements]
  [:svg.device {:x (* (:x v) grid-size)
                :y (* (:y v) grid-size)
                :width (* size grid-size)
                :height (* size grid-size)
                :class [(:cell v) (when (= k (::selected @state)) :selected)]}
   [:g.position
    {:on-mouse-down (fn [e]
                      (swap! state assoc ::selected k)
                      (drag-start-device k v e))}
    (into [:g.transform
           {:width (* size grid-size)
            :height (* size grid-size)
            :transform (.toString (:transform v))}]
          elements)]])

(defn draw-pattern [pattern prim k v]
  (let [size (apply max (count pattern) (map count pattern))]
    [apply device size k v
     (for [[y s] (map-indexed #(vector (* grid-size %1) %2) pattern)
           [x c] (map-indexed #(vector (* grid-size %1) %2) s)
           :when (not= c " ")]
         ^{:key [x y]} [prim x y k v])]))


(defn lines [arcs]
  [:<>
   (for [arc arcs]
     ^{:key arc} [:polyline {:points (map #(* % grid-size) (flatten arc))}])])

(defn arrow [x y size]
   [:polygon.arrow {:points 
     (map #(* % grid-size)
      [x y,
       (+ x size) (+ y size)
       (+ x size) (- y size)])}])


(defn wire-bg [x y]
  [:rect.tetris.wire {:x (* x grid-size),
                      :y (* y grid-size),
                      :width grid-size,
                      :height grid-size,
                      :on-mouse-down drag-start-wire}])

(defn draw-wire [x y wires]
  (let [neigbours [[x (+ y 1)] [x (- y 1)] [(+ x 1) y] [(- x 1) y]]
        ports (::ports @state)
        is-wire (filter #(or (contains? wires %)
                             (contains? ports %))
                        neigbours)
        num (count is-wire)]
  [:<>
   (when (= num 3) [:circle.wire {:cx (* (+ x 0.5) grid-size)
                                  :cy (* (+ y 0.5) grid-size)
                                  :r (/ grid-size 10)}])
   (for [[x2 y2] is-wire]
        ;;  :when (or (< x x2) (< y y2))]
     [:line.wire {:x1 (* (+ x 0.5) grid-size)
                  :y1 (* (+ y 0.5) grid-size)
                  :x2 (* (+ x2 0.5) grid-size)
                  :y2 (* (+ y2 0.5) grid-size)
                  :key [x y x2 y2]}])]))

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
     (if (= (:cell v) :nmos)
       [arrow 1.2 1.5 0.15]
       [arrow 1.35 1.5 -0.15])]))

(defn add-device [cell]
  (swap! state
         (fn [st]
           (let [name (keyword (gensym))]
             (-> st
                 (assoc-in [::schematic name]
                           {:x 0, :y 0, :transform I, :cell cell})
                 (assoc ::tool :cursor)
                 (assoc ::dragging name))))))

; icons
(def zoom-in (r/adapt-react-class icons/ZoomIn))
(def zoom-out (r/adapt-react-class icons/ZoomOut))
(def rotatecw (r/adapt-react-class icons/ArrowClockwise))
(def rotateccw (r/adapt-react-class icons/ArrowCounterclockwise))
(def mirror-vertical (r/adapt-react-class icons/SymmetryVertical))
(def mirror-horizontal (r/adapt-react-class icons/SymmetryHorizontal))
(def cursor (r/adapt-react-class icons/Cursor))
(def eraser (r/adapt-react-class icons/Eraser))
(def delete (r/adapt-react-class icons/Trash))
(def nmos (r/adapt-react-class icons/Cpu))
(def pmos (r/adapt-react-class icons/Cpu))

(defn radiobuttons [key m]
  [:<>
   (doall (for [[icon name disp] m]
            [:<> {:key name}
             [:input {:type "radio"
                      :name key
                      :id name
                      :value name
                      :checked (= name (get @state key))
                      :on-change #(swap! state assoc key name)}]
             [:label {:for name :title disp} [icon]]]))])

(defn transform-selected [tf]
  (swap! state (fn [st]
                 (if (::selected st)
                   (-> st
                       (update-in [::schematic (::selected st) :transform] tf)
                       update-ports)
                   st))))

(defn delete-selected []
  (swap! state (fn [st]
                 (update-in st [::schematic] dissoc (::selected st)))))

(defn schematic-canvas []
  [:div#app {:class (::theme @state)}
   [:div#menu
      [:select {:on-change #(swap! state assoc ::theme (.. % -target -value))}
     [:option {:value "tetris"} "Tetris"]
     [:option {:value "eyesore"} "Classic"]]
    [:span.sep]
    [radiobuttons ::tool
     [[cursor :cursor "Cursor"]
      [eraser :eraser "Eraser"]]]
    [:span.sep]
    [:a {:title "Rotate selected clockwise"
         :on-click (fn [e] (transform-selected #(.rotate % -90)))}
     [rotatecw]]
    [:a {:title "Rotate selected counter-clockwise"
         :on-click (fn [e] (transform-selected #(.rotate % 90)))}
     [rotateccw]]
    [:a {:title "Mirror selected horizontal"
         :on-click (fn [e] (transform-selected #(.flipX %)))}
     [mirror-horizontal]]
    [:a {:title "Mirror selected vertical"
         :on-click (fn [e] (transform-selected #(.flipY %)))}
     [mirror-vertical]]
    [:a {:title "Delect selected"
         :on-click (fn [e] (delete-selected))}
     [delete]]
    [:span.sep]
    [:a {:title "zoom in [scroll wheel/pinch]"
         :on-click #(button-zoom -1)}
     [zoom-in]]
    [:a {:title "zoom out [scroll wheel/pinch]"
         :on-click #(button-zoom 1)}
     [zoom-out]]
    [:span.sep]
    [:a {:title "Add N-channel mosfet"
         :on-click #(add-device :nmos)}
     "N"]
    [:a {:title "Add P-channel mosfet"
         :on-click #(add-device :pmos)}
     "P"]]
   [:div#sidebar
    (when-let [sel (::selected @state)]
      [:<>
       [:h1 (:cell (get (::schematic @state) sel)) ": " sel]
       [:form.properties
        [:label {:for "width"} "width"] [:input {:name "width" :type "number"}]
        [:label {:for "length"} "length"] [:input {:name "length" :type "number"}]]])]
   [:svg#canvas {:xmlns "http://www.w3.org/2000/svg"
          :height "100%"
          :width "100%"
          :view-box (::zoom @state)
          :on-wheel scroll-zoom
          :on-click #(when (= (.-target %) (.-currentTarget %)) (swap! state assoc ::selected nil))
          :on-mouse-down #(when (= (.-button %) 1) (swap! state assoc ::dragging ::view))
          :on-mouse-up drag-end
          :on-mouse-move drag}
    (for [[x y] (::wires @state)]
      ^{:key [x y]} [wire-bg x y])
    (for [[k v] (::schematic @state)]
      ^{:key k} [draw-pattern (get (::bg models) (:cell v)) tetris k v])
    (let [wires (::wires @state)]
      (for [[x y] wires]
        ^{:key [x y]} [draw-wire x y wires]))
    (for [[k v] (::schematic @state)]
      ^{:key k} [(get (::sym models) (:cell v)) k v])
    (for [[k v] (::schematic @state)]
      ^{:key k} [draw-pattern (get (::conn models) (:cell v)) port k v])]])

(defn ^:dev/after-load init []
  (rd/render [schematic-canvas]
             (.getElementById js/document "root")))