(ns nyancad.mosaic.frontend
  (:require [reagent.core :as r]
            [reagent.dom :as rd]))

(def grid-size 50)

(defonce state (r/atom {::zoom [0 0 1000 500]}))

(defn sign [n] (if (> n 0) 1 -1))

(defn viewbox-coord [e]
  (let [^js el (.-currentTarget e)
        p (.fromPoint js/DOMPoint (clj->js {:x (.-clientX e) :y (.-clientY e)}))
        m (.inverse (.getScreenCTM el))
        tp (.matrixTransform p m)]
    [(.-x tp) (.-y tp)]))

(defn viewbox-movement [e]
  (let [^js el (.-currentTarget e)
        m (.inverse (.getScreenCTM el))
        _ (do (set! (.-e m) 0)
              (set! (.-f m) 0)) ; cancel translation
        ^js p (.fromPoint js/DOMPoint (clj->js {:x (.-movementX e) :y (.-movementY e)}))
        tp (.matrixTransform p m)] ; local movement
    [(.-x tp) (.-y tp)]))

(defn zoom-schematic [e]
  (swap! state update-in [::zoom]
    (fn [[x y w h]]
      (let [dx (* (sign (.-deltaY e)) w 0.1)
            dy (* (sign (.-deltaY e)) h 0.1)
            [ex ey] (viewbox-coord e)
            rx (/ (- ex x) w)
            ry (/ (- ey y) h)]
        [(- x (* dx rx))
         (- y (* dy ry))
         (+ w dx)
         (+ h dy)]))))

(defn drag-schematic [e]
  (when (::dragging @state)
    (swap! state update-in [::zoom]
           (fn [[x y w h]]
             (let [[dx dy] (viewbox-movement e)]
               [(- x dx)
                (- y dy)
                w h])))))

(defn tetris [pattern]
  [:g
  (for [[y s] (map-indexed #(vector (* grid-size %1) %2) pattern)
        [x c] (map-indexed #(vector (* grid-size %1) %2) s)
        :when (not= c " ")]
    [:rect.tetris {:x x, :y y, :width grid-size, :height grid-size, :key [x y]}])])

(defn ports [pattern]
  [:g
  (for [[y s] (map-indexed #(vector (* grid-size %1) %2) pattern)
        [x c] (map-indexed #(vector (* grid-size %1) %2) s)
        :when (not= c " ")]
    [:circle.port {:cx (+ x (/ grid-size 2)), :cy (+ y (/ grid-size 2)), :r (/ grid-size 10), :key [x y]}])])

(defn lines [arcs]
  [:g
   (for [arc arcs]
     [:polyline {:points (map #(* % grid-size) (flatten arc))}])])

(defn arrow [x y size]
   [:polygon.arrow {:points 
     (map #(* % grid-size)
      [x y,
       (+ x size) (+ y size)
       (+ x size) (- y size)])}])

(defn mosfet [x y & flags]
  (let [t [" #"
           "##"
           " #"]
        p [" #"
           "##"
           " #"]
        arcs [
    [[0.5 1.5]
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
  [:svg.device.mosfet {:x (* x grid-size),
                       :y (* y grid-size),
                       :width (* 3 grid-size),
                       :height (* 3 grid-size),
                       :class flags}
   [:g {:width (* 3 grid-size),
        :height (* 3 grid-size)}
    [tetris t]
    [lines arcs]
    [arrow 1.2 1.5 0.15]
    [ports p]]]))

(defn schematic-canvas []
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :height "100%"
         :width "100%"
         :view-box (::zoom @state)
         :on-wheel zoom-schematic
         :on-mouse-down #(when (= (.-button %) 1) (swap! state assoc ::dragging true))
         :on-mouse-up #(when (= (.-button %) 1) (swap! state assoc ::dragging false))
         :on-mouse-move drag-schematic}
   [mosfet 0 0 :pmos :ccw]
   [mosfet 2 1 :nmos]
   [mosfet 1 3 :pmos :cw]
   [mosfet -1 2 :nmos :mirror]
   [mosfet 4 0 :pmos :ccw]
   [mosfet 6 1 :nmos]
   [mosfet 5 3 :pmos :cw]
   [mosfet 3 2 :nmos :mirror]
   [mosfet 0 4 :pmos :ccw]
   [mosfet 2 5 :nmos]
   [mosfet 1 7 :pmos :cw]
   [mosfet -1 6 :nmos :mirror]
   [mosfet 4 4 :pmos :ccw]
   [mosfet 6 5 :nmos]
   [mosfet 5 7 :pmos :cw]
   [mosfet 3 6 :nmos :mirror]
   ])

(defn ^:dev/after-load init []
  (rd/render [schematic-canvas]
             (.getElementById js/document "root")))