(ns nyancad.mosaic.frontend
  (:require [reagent.core :as r]
            [reagent.dom :as rd]))

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

(defn schematic-canvas []
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :height "100%"
         :width "100%"
         :view-box (::zoom @state)
         :on-wheel (fn [e] (swap! state update-in [::zoom]
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
         :on-mouse-down #(swap! state assoc ::dragging true)
         :on-mouse-up #(swap! state assoc ::dragging false)
         :on-mouse-move (fn [e]
                          (when (::dragging @state)
                            (swap! state update-in [::zoom]
                                   (fn [[x y w h]]
                                     (let [[dx dy] (viewbox-movement e)]
                                       [(- x dx)
                                        (- y dy)
                                        w h])))))}

   [:circle {:cx 50, :cy 50, :r 100}]])

(defn ^:dev/after-load init []
  (rd/render [schematic-canvas]
             (.getElementById js/document "root")))