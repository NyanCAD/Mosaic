(ns nyancad.mosaic.frontend
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [react-bootstrap-icons :as icons]))

(def grid-size 50)

(defn sign [n] (if (> n 0) 1 -1))

; like conj but nil defaults to set
(defn sconj
  ([s val] (conj (or s #{}) val))
  ([s val & vals] (apply conj (or s #{}) val vals)))

(defn update-keys
  ([m keys f] (into m (map #(vector % (f (get m %)))) keys))
  ([m keys f & args] (into m (map #(vector % (apply f (get m %) args))) keys)))

(def I (js/DOMMatrixReadOnly.))
(defn point [x y] (.fromPoint js/DOMPointReadOnly (clj->js {:x x :y y})))

(defonce state
  (r/atom
   {::ui {::zoom [0 0 500 500]
          ::theme :tetris
          ::tool ::cursor
          ::selected #{}}
    ::schematic {:mos1 {:x (+ 0  0), :y (+ 0 0), :transform (.rotate I 270), :cell :pmos}
                 :mos2 {:x (+ 2 0), :y (+ 1 0), :transform (.rotate I 0), :cell :nmos}
                 :mos3 {:x (+ 1 0), :y (+ 3 0), :transform (.rotate I 90), :cell :pmos}
                 :mos4 {:x (+ -1 0), :y (+ 2 0), :transform (.rotate I 180), :cell :nmos}
                 :mos5 {:x (+ 0  4), :y (+ 0 0), :transform (.rotate I 270), :cell :pmos}
                 :mos6 {:x (+ 2 4), :y (+ 1 0), :transform (.rotate I 0), :cell :nmos}
                 :mos7 {:x (+ 1 4), :y (+ 3 0), :transform (.rotate I 90), :cell :pmos}
                 :mos8 {:x (+ -1 4), :y (+ 2 0), :transform (.rotate I 180), :cell :nmos}
                 :mos1a {:x (+ 0  0), :y (+ 0 4), :transform (.rotate I 270), :cell :pmos}
                 :mos2a {:x (+ 2 0), :y (+ 1 4), :transform (.rotate I 0), :cell :nmos}
                 :mos3a {:x (+ 1 0), :y (+ 3 4), :transform (.rotate I 90), :cell :pmos}
                 :mos4a {:x (+ -1 0), :y (+ 2 4), :transform (.rotate I 180), :cell :nmos}
                 :mos5a {:x (+ 0  4), :y (+ 0 4), :transform (.rotate I 270), :cell :pmos}
                 :mos6a {:x (+ 2 4), :y (+ 1 4), :transform (.rotate I 0), :cell :nmos}
                 :mos7a {:x (+ 1 4), :y (+ 3 4), :transform (.rotate I 90), :cell :pmos}
                 :mos8a {:x (+ -1 4), :y (+ 2 4), :transform (.rotate I 180), :cell :nmos}
                 :wire1 {:x 0 :y 0 :transform I :cell :wire :wires #{[0 -1] [0 0]}}
                 :wire2 {:x 0 :y 0 :transform I :cell :wire :wires #{[-1 -1] [-1 0]}}}}))

(defonce schematic (r/cursor state [::schematic]))
(defonce ui (r/cursor state [::ui]))

(def mosfet-shape
  [" #"
   "##"
   " #"])

(declare mosfet-sym wire-sym wire-bg)
; should probably be in state eventually
(def models {::bg {:pmos mosfet-shape
                   :nmos mosfet-shape
                   :wire #'wire-bg}
             ::conn {:pmos mosfet-shape
                     :nmos mosfet-shape
                     :wire []}
             ::sym {:pmos #'mosfet-sym
                    :nmos #'mosfet-sym
                    :wire #'wire-sym}})

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
  (apply zoom-schematic (sign (.-deltaY e)) (viewbox-coord e)))

(defn button-zoom [dir]
  (let [[x y w h] (::zoom @ui)]
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

(defn transform-selected [st tf]
  (let [selected (get-in st [::ui ::selected])]
    (update st ::schematic
            update-keys selected
            update :transform tf)))

(defn delete-selected [st]
  (let [selected (get-in st [::ui ::selected])]
    (-> (apply update st ::schematic dissoc selected)
        (assoc-in [::ui ::selected] #{}))))

(defn remove-wire [st e]
  (let [[x y] (map #(.floor js/Math (/ % grid-size)) (viewbox-coord e))
        selected (first (get-in st [::ui ::selected]))
        xo (get-in st [::schematic selected :x])
        yo (get-in st [::schematic selected :y])
        coord [(- x xo) (- y yo)]]
    (update-in st [::schematic selected :wires] disj coord)))

(defn drag-view [e]
  (swap! ui update ::zoom
         (fn [[x y w h]]
           (let [[dx dy] (viewbox-movement e)]
             [(- x dx)
              (- y dy)
              w h]))))

(defn drag-device [e]
  (let [[dx dy] (map #(/ % grid-size) (viewbox-movement e))
        [nx ny] (map #(/ % grid-size) (viewbox-coord e))]
    (swap! state
      (fn [st]
        (let [selected (get-in st [::ui ::selected])]
          (update st ::schematic update-keys selected
            (fn [device]
              (-> device
                  (update :x #(+ (or % nx) dx))
                  (update :y #(+ (or % ny) dy))))))))))

(defn drag-wire [e]
  (let [[x y] (map #(.floor js/Math (/ % grid-size)) (viewbox-coord e))]
    (swap! state
           (fn [st]
             (let [selected (first (get-in st [::ui ::selected]))
                   xo (get-in st [::schematic selected :x])
                   yo (get-in st [::schematic selected :y])
                   coord [(- x xo) (- y yo)]]
               (update-in st [::schematic selected :wires] sconj coord))))))

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
      ::wire (swap! state remove-wire e)
      nil))) ;todo remove devices?

(defn drag [e]
  (case (::tool @ui)
    ::eraser (eraser-drag e)
    ::wire (wire-drag e)
    ::cursor (cursor-drag e)))
  
(defn add-wire [st]
  (let [name (keyword (gensym :wire))]
    (-> st
        (assoc-in [::schematic name] ; X/Y will be set on drag
                  {:transform I, :cell :wire})
        (update ::ui assoc
                ::dragging ::wire
                ::selected #{name}))))

(defn drag-start [k type e]
  ; skip the button press from a drag initiated from a toolbar button
  (when-not (::dragging @ui) 
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
                       (case (::tool ui)
                         ::cursor ::device
                         ::wire ::wire
                         ::eraser type)))
              (update-schematic [st]
                (case [(get-in st [::ui ::tool]) type]
                  [::eraser ::device] (delete-selected st)
                  [::eraser ::wire] (remove-wire st e) ;; TODO
                  [::wire ::device] (add-wire st)
                  st))]
        (swap! state (fn [st]
                       (-> st
                           (update-in [::ui ::selected] update-selection)
                           (update ::ui drag-type)
                           update-schematic)))))))

(defn drag-start-background [e]
  (swap! state
         (fn [st]
           (cond
             (= (.-button e) 1) (assoc-in st [::ui ::dragging] ::view)
             (= ::wire (get-in st [::ui ::tool])) (add-wire st)
             :else st))))
  
(defn drag-end [e]
  (letfn [(deselect [st e]
            (if (= (.-target e) (.-currentTarget e))
              (assoc-in st [::ui ::selected] #{})
              st))
          (end [st]
            (-> st
                (assoc-in [::ui ::dragging] nil)
                (update ::schematic
                           update-keys (get-in st [::ui ::selected])
                           update-keys [:x :y] #(.round js/Math %))
                (deselect e)))]
    (swap! state end)))

(defn tetris [x y _ _]
  [:rect.tetris {:x x, :y y
                 :width grid-size
                 :height grid-size
                 }])

(defn port [x y _ _]
  [:circle.port {:cx (+ x (/ grid-size 2)),
                 :cy (+ y (/ grid-size 2)),
                 :r (/ grid-size 10)}])

(defn device [size k v & elements]
  [:svg.device {:x (* (:x v) grid-size)
                :y (* (:y v) grid-size)
                :width (* size grid-size)
                :height (* size grid-size)
                :class [(:cell v) (when (contains? (::selected @ui) k) :selected)]}
   [:g.position
    {:on-mouse-down (fn [e] (drag-start k ::device e))}
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


(defn get-model [layer model]
  (let [m (get (get models layer) (:cell model))]
    (assert m "no model")
    (cond
      (fn? m) m
      (= layer ::bg) (partial draw-pattern m tetris)
      (= layer ::conn) (partial draw-pattern m port)
      :else (throw (js/Error. "invalid model")))))

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


(defn wire-bg [name net]
  (let [wires (:wires net)
        xo (:x net)
        yo (:y net)
        wires (set (map (fn [[x y]] [(+ xo x) (+ yo y)]) wires))]
    [:g {:on-mouse-down #(drag-start name ::wire %)}
     (doall (for [[x y] wires]
              [:rect.tetris.wire {:x (* x grid-size)
                                  :y (* y grid-size)
                                  :key [x y]
                                  :width grid-size
                                  :height grid-size
                                  :class (when (contains? (::selected @ui) name) :selected)}]))]))

(defn draw-wire [x y wires]
  (let [neigbours [[x (+ y 1)] [x (- y 1)] [(+ x 1) y] [(- x 1) y]]
        is-wire (filter #(contains? wires %)
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

(defn wire-sym [name net]
  (let [wires (:wires net)
        xo (:x net)
        yo (:y net)
        wires (set (map (fn [[x y]] [(+ xo x) (+ yo y)]) wires))]
    [:g {:on-mouse-down #(drag-start name ::wire %)}
     (for [[x y] wires]
          ^{:key [x y]} [draw-wire x y wires])]))

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
           (let [name (keyword (gensym cell))]
             (-> st
                 (assoc-in [::schematic name] ; X/Y will be set on drag
                           {:transform I, :cell cell})
                 (update ::ui assoc
                         ::tool ::cursor
                         ::dragging ::device
                         ::selected #{name}))))))

; icons
(def zoom-in (r/adapt-react-class icons/ZoomIn))
(def zoom-out (r/adapt-react-class icons/ZoomOut))
(def rotatecw (r/adapt-react-class icons/ArrowClockwise))
(def rotateccw (r/adapt-react-class icons/ArrowCounterclockwise))
(def mirror-vertical (r/adapt-react-class icons/SymmetryVertical))
(def mirror-horizontal (r/adapt-react-class icons/SymmetryHorizontal))
(def cursor (r/adapt-react-class icons/HandIndex))
(def eraser (r/adapt-react-class icons/Eraser))
(def wire (r/adapt-react-class icons/Pencil))
(def delete (r/adapt-react-class icons/Trash))

(defn radiobuttons [key m]
  [:<>
   (doall (for [[icon name disp] m]
            [:<> {:key name}
             [:input {:type "radio"
                      :name key
                      :id name
                      :value name
                      :checked (= name (get @ui key))
                      :on-change #(swap! ui assoc key name)}]
             [:label {:for name :title disp} [icon]]]))])

(defn schematic-canvas []
  [:div#app {:class (::theme @ui)}
   [:div#menu
      [:select {:on-change #(swap! ui assoc ::theme (.. % -target -value))}
     [:option {:value "tetris"} "Tetris"]
     [:option {:value "eyesore"} "Classic"]]
    [:span.sep]
    [radiobuttons ::tool
     [[cursor ::cursor "Cursor"]
      [wire ::wire "Wire"]
      [eraser ::eraser "Eraser"]]]
    [:span.sep]
    [:a {:title "Rotate selected clockwise"
         :on-click (fn [_] (swap! state transform-selected #(.rotate % -90)))}
     [rotatecw]]
    [:a {:title "Rotate selected counter-clockwise"
         :on-click (fn [_] (swap! state transform-selected #(.rotate % 90)))}
     [rotateccw]]
    [:a {:title "Mirror selected horizontal"
         :on-click (fn [_] (swap! state transform-selected #(.flipX %)))}
     [mirror-horizontal]]
    [:a {:title "Mirror selected vertical"
         :on-click (fn [_] (swap! state transform-selected #(.flipY %)))}
     [mirror-vertical]]
    [:a {:title "Delect selected"
         :on-click (fn [_] (swap! state delete-selected))}
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
    (doall (for [sel (::selected @ui)]
             [:<> {:key sel}
              [:h1 (get-in schematic [sel :cell]) ": " sel]
              [:form.properties
               [:label {:for "width"} "width"] [:input {:name "width" :type "number"}]
               [:label {:for "length"} "length"] [:input {:name "length" :type "number"}]]]))]
   [:svg#canvas {:xmlns "http://www.w3.org/2000/svg"
          :height "100%"
          :width "100%"
          :view-box (::zoom @ui)
          :on-wheel scroll-zoom
          :on-mouse-down drag-start-background
          :on-mouse-up drag-end
          :on-mouse-move drag}
    (for [[k v] @schematic
          :when (= :wire (:cell v))]
      ^{:key k} [(get-model ::bg v) k v])
    (for [[k v] @schematic
          :when (not= :wire (:cell v))]
      ^{:key k} [(get-model ::bg v) k v])
    (for [[k v] @schematic]
      ^{:key k} [(get-model ::sym v) k v])
    (for [[k v] @schematic]
      ^{:key k} [(get-model ::conn v) k v])]])

(defn ^:dev/after-load init []
  (rd/render [schematic-canvas]
             (.getElementById js/document "root")))