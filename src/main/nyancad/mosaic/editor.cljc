; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [shadow.resource :as rc]
            #?@(:vscode [[nyancad.mosaic.editor.platform-vscode
                           :refer [group schematic modeldb snapshots simulations local
                                   done? syncactive notebook-panel secondary-menu-items
                                   open-schematic resolve-symbol-url init-extra!]]]
                :cljs [[nyancad.mosaic.editor.platform-web
                         :refer [group schematic modeldb snapshots simulations local
                                 done? syncactive notebook-panel secondary-menu-items
                                 open-schematic resolve-symbol-url init-extra!]]])
            [clojure.spec.alpha :as s]
            [cljs.core.async :refer [go go-loop <!]]
            [clojure.math :as math]
            clojure.edn
            clojure.set
            clojure.string
            goog.functions
            [nyancad.hipflask.util :refer [update-keys sep]]
            [nyancad.mosaic.common :as cm
             :refer [grid-size debounce sconj
                     point transform transform-vec
                     mosfet-shape bjt-conn]]))


(defn make-names
  "Returns a lazy sequence of available names for a device type (e.g. R1, R2, R3...)"
  [base]
  (let [names (map #(str (cm/initial base) %) (next (range)))]
    (remove #(@schematic (str group sep %)) names)))

(defn make-name [base]
  (first (make-names base)))

(defonce ui (r/atom {::zoom [0 0 500 500]
                     ::theme nil
                     ::tool ::cursor
                     ::selected #{}
                     ::mouse [0 0]
                     ::mouse-start [0 0]
                     ::notebook-popped-out false
                     ::pointer-cache {}}))

(s/def ::zoom (s/coll-of number? :count 4))
(s/def ::theme (s/nilable #{"light" "dark" "eyesore"}))
(s/def ::tool #{::cursor ::eraser ::wire ::pan ::device ::probe})
(s/def ::selected (s/and set? (s/coll-of string?)))
(s/def ::dragging (s/nilable #{::wire ::device ::view ::box}))
(s/def ::staging (s/nilable :nyancad.mosaic.common/device))
(s/def ::notebook-popped-out boolean?)
(s/def ::x number?)
(s/def ::y number?)
(s/def ::pointer-cache (s/map-of int? (s/keys :req-un [::x ::y])))
(s/def ::ui (s/keys :req [::zoom ::theme ::tool ::selected ::notebook-popped-out ::pointer-cache]
                    :opt [::dragging ::staging]))

(set-validator! ui #(or (s/valid? ::ui %) (.log js/console (pr-str %) (s/explain-str ::ui %))))

(defonce zoom (r/cursor ui [::zoom]))
(defonce theme (r/cursor ui [::theme]))
(defonce tool (r/cursor ui [::tool]))
(defonce selected (r/cursor ui [::selected]))
(defonce delta (r/cursor ui [::delta]))
(defonce staging (r/cursor ui [::staging]))
(defonce notebook-popped-out (r/cursor ui [::notebook-popped-out]))
(defonce pointer-cache (r/cursor ui [::pointer-cache]))

; Model selector popup state
(defonce model-popup-filter (r/atom ""))
(defonce model-popup-category (r/atom []))

(defonce undotree (cm/newundotree))

(declare build-wire-split-index build-location-index split-wire location-index wire-type-index)

(defn post-action!
  "Record undo checkpoint and split wires after a user action completes.
   Returns a channel that completes when done."
  []
  (go
    (<! (done? schematic))
    (cm/newdo undotree @schematic)
    (let [loc-idx (build-location-index @schematic)]
      (doseq [[w coords] (build-wire-split-index loc-idx)]
        (split-wire w coords loc-idx)
        (<! (done? schematic))))))

(defn restore [state]
  (let [;; Ensure keys are strings (schematic uses string device IDs)
        state-str (into {} (map (fn [[k v]] [(name k) v]) state))
        del (reduce disj (set (keys @schematic)) (keys state-str))
        norev (reduce #(update %1 %2 dissoc :_rev) state-str (keys state-str))]
    (go
      (swap! schematic into norev)
      (<! (done? schematic))
      (swap! schematic #(apply dissoc %1 %2) del)
      (<! (done? schematic)))))

(defn undo-schematic []
  (when-let [st (cm/undo undotree)]
    (restore st)))

(defn redo-schematic []
  (when-let [st (cm/redo undotree)]
    (restore st)))

(declare drag-start drag-end eraser-drag models on-pointer-down-element on-pointer-move-element on-pointer-up-bg double-click device-template circuit-designator)

(defn device [size k v & elements]
  (assert (js/isFinite size))
  (into [:g.device {:on-pointer-down (fn [e] (on-pointer-down-element k e))
                    :on-pointer-move (fn [e] (on-pointer-move-element k e))
                    :on-pointer-up on-pointer-up-bg
                    :style {:transform (.toString (.translate (transform (:transform v cm/IV)) (* (:x v) grid-size) (* (:y v) grid-size)))
                            :transform-origin (str (* (+ (:x v) (/ size 2)) grid-size) "px "
                                                   (* (+ (:y v) (/ size 2)) grid-size) "px")}
                    :class [(:type v) (:variant v) (::category (get models (:type v))) (when (contains? @selected k) :selected)]}]
        elements))

(defn port [x y port-type]
  [:circle.port {:cx (+ x (/ grid-size 2))
                 :cy (+ y (/ grid-size 2))
                 :r (/ grid-size 10)
                 :class port-type}])

(defn draw-background [[width height] k v]
  [device (+ 2 (max width height)) k v
   [:rect.tetris {:x grid-size :y grid-size
                  :width (* width grid-size)
                  :height (* height grid-size)}]])

(defn draw-pattern [size pattern prim k v]
  [apply device size k v
   (for [{:keys [x y type]} pattern]
     ^{:key [x y]} [prim (* x grid-size) (* y grid-size) type])])

(defn lines [arcs]
  [:<>
   (for [arc arcs]
     ^{:key arc} [:polyline {:points (map #(* % grid-size) (flatten arc))}])])

(defn arrow
  ([x y size rotate] (arrow x y size size rotate))
  ([x y width height rotate]
    [:polygon.arrow {:transform (str "rotate(" rotate " " (* x grid-size) " " (* y grid-size) ")")
                     :points
                     (map #(* % grid-size)
                          [x y
                           (+ x width) (+ y height)
                           (+ x width) (- y height)])}]))

(def ^:private scale-keys #{:x :y :width :height :cx :cy :r})

(defn- scale-attrs [attrs]
  (reduce-kv (fn [m k v]
               (assoc m k (if (scale-keys k) (* v grid-size) v)))
             {} attrs))

(defn- render-element [[tag & args]]
  (let [[attrs & children] (if (map? (first args)) args (cons {} args))]
    (case tag
      :lines [lines (first args)]
      :arrow [arrow (:x attrs) (:y attrs) (:size attrs) (:rotate attrs)]
      :path  [:path attrs]
      :text  (into [:text attrs] children)
      ;; Default: scale coordinate attrs, pass through class from tag
      (let [scaled (cond-> (scale-attrs attrs)
                     (:points attrs) (update :points #(map (partial * grid-size) %)))]
        (into [tag scaled] children)))))

(defn- render-symbol-data [{:keys [::size ::elements]} k v]
  (into [device size k v
         [device-template v size]]
        (map render-element elements)))

(defn wire-sym [key wire]
  (let [{:keys [x y rx ry variant]} wire
        x1 (* (+ x 0.5) grid-size)
        y1 (* (+ y 0.5) grid-size)
        x2 (* (+ x rx 0.5) grid-size)
        y2 (* (+ y ry 0.5) grid-size)
        [mx my] (case variant "hv" [x2 y1] "vh" [x1 y2] nil)
        pts (if mx
              (str x1 "," y1 " " mx "," my " " x2 "," y2)
              (str x1 "," y1 " " x2 "," y2))
        wire-type (get @wire-type-index key)
        r (if (= wire-type "photonic") (/ grid-size 2) 0)
        d (if mx
            (let [dx1 (math/signum (- x1 mx)) dy1 (math/signum (- y1 my))
                  dx2 (math/signum (- x2 mx)) dy2 (math/signum (- y2 my))]
              (str "M" x1 "," y1 "L" (+ mx (* dx1 r)) "," (+ my (* dy1 r))
                   "Q" mx "," my " " (+ mx (* dx2 r)) "," (+ my (* dy2 r))
                   "L" x2 "," y2))
            (str "M" x1 "," y1 "L" x2 "," y2))]
    [:g.wire {:on-pointer-down #(on-pointer-down-element key %)
              :on-pointer-move #(on-pointer-move-element key %)
              :on-pointer-up on-pointer-up-bg
              :class [(when (contains? @selected key) :selected)
                      (when wire-type (name wire-type))]}
     [:polyline.wirebb {:points pts}]
     [:path.wire {:d d :fill "none"
                  :class (when wire-type (name wire-type))}]]))

(defn schem-template [dev fmt]
  (let [res (if-let [l (last @simulations)] (val l) {})
        schem (into {}
                    (comp (filter #(contains? % :name))
                          (map #(vector (keyword (:name %)) %)))
                    (vals @schematic))
        text (cm/format fmt {:res res, :schem schem :self dev})]
    (map-indexed
     (fn [idx line]
       [:tspan {:key line :x "0" :dy (if (zero? idx) "0" "1.2em")} line])
     (clojure.string/split-lines text))))

(defn text-sym [key text]
  (let [x (:x text)
        y (:y text)
        content (schem-template text (get text :template (get-in models ["text" ::template])))]
    [:g.text {:on-pointer-down #(on-pointer-down-element key %)
              :on-pointer-move #(on-pointer-move-element key %)
              :on-pointer-up on-pointer-up-bg
              :class (when (contains? @selected key) :selected)
              :transform (str "translate(" (* (+ x 0.1) grid-size) ", " (* (+ y 0.3) grid-size) ")")}
     [:text
      content]]))

(defn device-template [dev size]
  (let [bg (::bg (get models (:type dev)))
        [bg-w bg-h] (if (vector? bg) bg [1 1])]
    (circuit-designator dev size bg-w bg-h)))

(defn port-sym [key label]
  [device 1 key label
   (case (:variant label)
     "ground" [lines [[[0.5 0.5]
                       [0.3 0.5]]
                      [[0.0 0.5]
                       [0.3 0.2]
                       [0.3 0.8]
                       [0.0 0.5]]]]
     "supply" [lines [[[0.5 0.5]
                       [0.0 0.5]]
                      [[0.0 0.2]
                       [0.0 0.8]]]]
     "text" nil
     [lines [[[0.5 0.5]
              [0.3 0.3]
              [0 0.3]
              [0 0.7]
              [0.3 0.7]
              [0.5 0.5]]]])
   [:text {:text-anchor (case (math/round (first (:transform label)))
                          1 "end"
                          -1 "start"
                          "middle")
           :dominant-baseline "middle"
           :transform (-> (:transform label)
                          transform
                          (.translate (/ grid-size (if (= (:variant label) "text") -4 4)) (/ grid-size -2))
                          .inverse
                          .toString)}
    (if (nil? (:variant label))
      (schem-template label (get label :template (get-in models ["port" ::template])))
      (:name label "net"))]])

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
     [device-template v 3]
     (if (= (:type v) "nmos")
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
     [device-template v 3]
     [lines shape]
     (if (= (:type v) "npn")
       [arrow 1.4 1.81 0.12 -140]
       [arrow 1.25 1.68 0.12 40])]))

(def resistor-elements
  {::size 3
   ::elements [[:rect.outline {:x 1.35 :y 1.1 :width 0.3 :height 0.8}]
               [:lines [[[1.5 0.5] [1.5 1.1]]
                        [[1.5 1.9] [1.5 2.5]]]]]})

(def capacitor-elements
  {::size 3
   ::elements [[:lines [[[1.5 0.5] [1.5 1.4]]
                        [[1.1 1.4] [1.9 1.4]]
                        [[1.1 1.6] [1.9 1.6]]
                        [[1.5 1.6] [1.5 2.5]]]]]})

(def inductor-elements
  {::size 3
   ::elements [[:lines [[[1.5 0.5] [1.5 1.1]]
                        [[1.5 1.9] [1.5 2.5]]]]
               [:path {:d "M75,55 a5,5 90 0,0 0,10 a5,5 90 0,0 0,10 a5,5 90 0,0 0,10 a5,5 90 0,0 0,10"}]]})

(def isource-elements
  {::size 3
   ::elements [[:lines [[[1.5 0.5] [1.5 1.1]]
                        [[1.5 1.25] [1.5 1.65]]
                        [[1.5 1.9] [1.5 2.5]]]]
               [:circle.outline {:cx 1.5 :cy 1.5 :r 0.4}]
               [:arrow {:x 1.5 :y 1.8 :size 0.15 :rotate -90}]]})

(def vsource-elements
  {::size 3
   ::elements [[:lines [[[1.5 0.5] [1.5 1.1]]
                        [[1.5 1.9] [1.5 2.5]]]]
               [:circle.outline {:cx 1.5 :cy 1.5 :r 0.4}]
               [:text {:x 75 :y 70 :text-anchor "middle"} "+"]
               [:text {:x 75 :y 90 :text-anchor "middle"} "−"]]})

(defn diode-sym [k v]
  (let [shape [[[1.5 0.5] [1.5 1.4]]
               [[1.3 1.7] [1.7 1.7]]
               [[1.5 1.7] [1.5 2.5]]]
        tp (:type v)]
    [device 3 k v
     [lines shape]
     [device-template v 3]
     [arrow 1.5 1.7 0.4 0.2 270]
     ;; LED: light arrows pointing away from diode (lower-right, ~27° from horizontal)
     (when (= tp "led")
       [:<>
        [lines [[[1.75 1.375] [1.88 1.44]]]]
        [arrow 1.93 1.465 0.05 -153]
        [lines [[[1.75 1.575] [1.88 1.64]]]]
        [arrow 1.93 1.665 0.05 -153]])
     ;; Photodiode: light arrows pointing toward diode (upper-left, ~27° from horizontal)
     (when (= tp "photodiode")
       [:<>
        [lines [[[1.93 1.45] [1.78 1.375]]]]
        [arrow 1.72 1.35 0.05 27]
        [lines [[[1.93 1.65] [1.78 1.575]]]]
        [arrow 1.72 1.55 0.05 27]])
     ;; Optical butt-coupling: line with T-cap at the device facet
     (when (or (= tp "led") (= tp "photodiode"))
       [lines [[[2.5 1.5] [2.0 1.5]]
               [[2.0 1.3] [2.0 1.7]]]])
     ;; Modulator: optical transmission line through the diode
     (when (= tp "modulator")
       [lines [[[0.5 1.5] [2.5 1.5]]]])]))

;; Photonic component symbols
;;
;; Coordinate reference:
;;   bg [w,h] → device size = 2 + max(w,h), bg rect at pixel (50,50) size (w*50, h*50)
;;   Port at grid (gx,gy) → pixel center ((gx+0.5)*50, (gy+0.5)*50)
;;   Lines use grid coords (auto-scaled by grid-size=50)
;;   Paths use raw pixel coords in the size*50 px canvas

;; bg [1,1], size 3, 150x150px
;; Ports: (0,1)→px(25,75)  (2,1)→px(125,75)
(def straight-elements
  {::size 3
   ::elements [[:lines [[[0.5 1.5] [2.5 1.5]]]]]})

;; bg [1,1], size 3, 150x150px
;; Ports: (0,1)→px(25,75) left  (1,2)→px(75,125) bottom
;; Quarter-arc from left port curving down to bottom port
(def bend-elements
  {::size 3
   ::elements [[:path {:d "M25,75 H50 Q75,75 75,100 V125"}]]})

;; bg [1,2], size 4, 200x200px
;; Ports: (0,1)→px(25,75)  (2,2)→px(125,125)
;; S-curve connecting offset ports
(def sbend-elements
  {::size 4
   ::elements [[:path {:d "M25,75 C75,75 75,125 125,125"}]]})

;; bg [1,1], size 3, 150x150px
;; Ports: (0,1)→px(25,75)  (2,1)→px(125,75)
(def taper-elements
  {::size 3
   ::elements [[:lines [[[0.5 1.5] [1.0 1.5]]
                        [[2.0 1.5] [2.5 1.5]]]]
               [:polygon.outline {:points [1.0 1.2 2.0 1.4 2.0 1.6 1.0 1.8]}]]})

;; bg [1,1], size 3, 150x150px
;; Ports: (0,1)→px(25,75)  (2,1)→px(125,75)
(def transition-elements
  {::size 3
   ::elements [[:lines [[[0.5 1.5] [1.0 1.5]]
                        [[2.0 1.5] [2.5 1.5]]]]
               [:polygon.outline {:points [1.0 1.35 1.5 1.45 2.0 1.35
                                           2.0 1.65 1.5 1.55 1.0 1.65]}]]})

;; bg [1,1], size 3, 150x150px
;; Port: (0,1)→px(25,75) only
(def terminator-elements
  {::size 3
   ::elements [[:lines [[[0.5 1.5] [1.3 1.5]]]]
               [:polygon.outline {:points [1.3 1.3 1.7 1.5 1.3 1.7]}]]})

;; bg [1,1], size 3, 150x150px
;; Ports: (0,1)→px(25,75)  (2,1)→px(125,75)  (1,0)→px(75,25)  (1,2)→px(75,125)
(def crossing-elements
  {::size 3
   ::elements [[:lines [[[0.5 1.5] [2.5 1.5]]
                        [[1.5 0.5] [1.5 2.5]]]]]})

;; bg [1,2], size 4, 200x200px
;; bg rect at (50,50) size 50x100, center at px(75,100) = grid(1.5,2.0)
;; Ring centered on bg, waveguide passes below ring
;; Ports: (0,2)→px(25,125)  (2,2)→px(125,125)
(def ring-single-elements
  {::size 4
   ::elements [[:lines [[[0.5 2.5] [2.5 2.5]]]]
               [:circle.outline {:cx 1.5 :cy 2.0 :r 0.45}]]})

;; bg [1,2], size 4, 200x200px
;; Two waveguides top and bottom, ring centered on bg between them
;; Ports: (0,1)→px(25,75)  (2,1)→px(125,75)  (0,2)→px(25,125)  (2,2)→px(125,125)
(def ring-double-elements
  {::size 4
   ::elements [[:lines [[[0.5 1.5] [2.5 1.5]]
                        [[0.5 2.5] [2.5 2.5]]]]
               [:circle.outline {:cx 1.5 :cy 2.0 :r 0.45}]]})

;; bg [1,1], size 3, 150x150px
;; Ports: (0,1)→px(25,75)  (2,1)→px(125,75)
;; Spiral: alternating semicircular arcs spiraling inward
;; Left lead to spiral start at px(50,75), right lead from px(100,75)
(def spiral-elements
  {::size 3
   ::elements [[:lines [[[0.5 1.5] [1.0 1.5]]]]
               [:lines [[[2.5 1.5] [2.0 1.5]]]]
               [:path {:d (str "M50,75 "
                               "A20.83,20.83 0 0,0 91.67,75 "
                               "A12.5,12.5 0 0,0 66.67,75 "
                               "A4.17,4.17 0 0,0 75,75 "
                               "A4.17,4.17 0 0,1 83.33,75 "
                               "A12.5,12.5 0 0,1 58.33,75 "
                               "A20.83,20.83 0 0,1 100,75"
                               )}]
               ]})

;; bg [1,1], size 3. Ports: split-1x2-conn
;; Y-junction: two S-curves sharing input
(def splitter-1x2-elements
  {::size 5
   ::elements [[:path {:d "M25,125 C65,125 85,75 125,75"}]
               [:path {:d "M25,125 C65,125 85,175 125,175"}]]})

;; bg [1,2], size 4, 200x200px
;; Ports like ring-double: (0,1)(2,1)(0,2)(2,2) → px (25,75)(125,75)(25,125)(125,125)
;; Directional coupler: two waveguides approaching closely in the middle
(def coupler-elements
  {::size 4
   ::elements [[:path {:d "M25,75 C55,75 45,95 75,95 C105,95 95,75 125,75"}]
               [:path {:d "M25,125 C55,125 45,105 75,105 C105,105 95,125 125,125"}]]})

;; Coupler-ring: 2x2 with ports inside, no padding
;; size 2, 100x100px. Ports: (0,0)(1,0)(0,1)(1,1) → px (25,25)(75,25)(25,75)(75,75)
(defn coupler-ring-bg [k v]
  [device 2 k v
   [:rect.tetris {:x 0 :y 0 :width (* 2 grid-size) :height (* 2 grid-size)}]])

(def coupler-ring-elements
  {::size 2
   ::elements [[:path {:d "M25,25 A25,23 0 0,0 75,25"}]
               [:path {:d "M25,75 A25,23 0 0,1 75,75"}]]})

;; bg [1,3], size 5, 250x250px — ckt-block style rect with port leads
;; MMI 1x2: Ports: (0,2)→px(25,125), (2,1)→px(125,75), (2,3)→px(125,175)
(def mmi-1x2-elements
  {::size 5
   ::elements [[:lines [[[0.5 2.5] [1.0 2.5]]
                        [[2.0 1.5] [2.5 1.5]]
                        [[2.0 3.5] [2.5 3.5]]]]
               [:rect.outline {:x 1.0 :y 1.0 :width 1.0 :height 3.0}]]})

;; bg [1,3], size 5, 250x250px — ckt-block style rect with port leads
;; MMI 2x2: Ports: (0,1)→px(25,75), (2,1)→px(125,75), (0,3)→px(25,175), (2,3)→px(125,175)
(def mmi-2x2-elements
  {::size 5
   ::elements [[:lines [[[0.5 1.5] [1.0 1.5]]
                        [[0.5 3.5] [1.0 3.5]]
                        [[2.0 1.5] [2.5 1.5]]
                        [[2.0 3.5] [2.5 3.5]]]]
               [:rect.outline {:x 1.0 :y 1.0 :width 1.0 :height 3.0}]]})

;; bg [1,1], size 3. Ports: split-1x2-conn
;; MZI 1x2: S-curve from center input to top/bottom outputs
;; bg [1,3], size 5, 250x250px
;; Ports: (0,2)→px(25,125) input, (2,1)→px(125,75) top, (2,3)→px(125,175) bottom
;; Each arm: 4 quarter-circle arcs (r=12.5), alternating sweep, tangent-continuous
(def mzi-1x2-elements
  {::size 5
   ::elements [[:path {:d (str "M25,125 H50 "
                               "A12.5,12.5 0 0,0 62.5,112.5 "
                               "V62.5 "
                               "A12.5,12.5 0 0,1 87.5,62.5 "
                               "V110 "
                               "A12.5,12.5 0 0,0 112.5,110 "
                               "V87.5 "
                               "A12.5,12.5 0 0,1 125,75")}]
               [:path {:d (str "M25,125 H50 "
                               "A12.5,12.5 0 0,1 62.5,137.5 "
                               "V187.5 "
                               "A12.5,12.5 0 0,0 87.5,187.5 "
                               "V140 "
                               "A12.5,12.5 0 0,1 112.5,140 "
                               "V162.5 "
                               "A12.5,12.5 0 0,0 125,175")}]]})

;; MZI 2x2: sinusoidal wave with two coupling dips per arm
;; bg [1,3], size 5, 250x250px
;; Ports: (0,1)→px(25,75), (2,1)→px(125,75), (0,3)→px(25,175), (2,3)→px(125,175)
;; Each arm: 5 arcs (quarter+semi+semi+semi+quarter, r=12.5), alternating sweep
;; Top arm dips to y=100 at x=50,100; bottom rises to y=150 at x=50,100
(def mzi-2x2-elements
  {::size 5
   ::elements [[:path {:d (str "M25,75 "
                               "A12.5,12.5 0 0,1 37.5,87.5 "
                               "V110 "
                               "A12.5,12.5 0 0,0 62.5,110 "
                               "V62.5 "
                               "A12.5,12.5 0 0,1 87.5,62.5 "
                               "V110 "
                               "A12.5,12.5 0 0,0 112.5,110 "
                               "V87.5 "
                               "A12.5,12.5 0 0,1 125,75")}]
               [:path {:d (str "M25,175 "
                               "A12.5,12.5 0 0,0 37.5,162.5 "
                               "V140 "
                               "A12.5,12.5 0 0,1 62.5,140 "
                               "V187.5 "
                               "A12.5,12.5 0 0,0 87.5,187.5 "
                               "V140 "
                               "A12.5,12.5 0 0,1 112.5,140 "
                               "V162.5 "
                               "A12.5,12.5 0 0,0 125,175")}]]})


;; bg [1,1], size 3. Port: (0,1)→px(25,75) left only
;; Grating coupler: narrow concentric arcs (~60°) opening right, wifi-style
;; Each arc at radius R from center (45,75), spanning ±30° from horizontal
;; Start: (45+R*cos30°, 75-R*sin30°)  End: (45+R*cos30°, 75+R*sin30°)
(def grating-coupler-elements
  {::size 3
   ::elements [[:lines [[[0.5 1.5] [0.9 1.5]]]]
               [:path {:d "M57,67 A15,15 0 0,1 57,83"}]
               [:path {:d "M66,61 A25,25 0 0,1 66,89"}]
               [:path {:d "M75,55 A35,35 0 0,1 75,95"}]
               [:path {:d "M84,49 A45,45 0 0,1 84,101"}]]})

(defn circuit-shape [k v]
  (let [model (:model v)
        ports (get-in @modeldb [(cm/model-key model) :ports])
        shape (when (= (:type v) "amp") :amp)
        size (if ports (cm/port-perimeter ports shape) [1 1])]
    (draw-background size k v)))

(defn circuit-conn [k v]
  (let [model (:model v)
        ports (get-in @modeldb [(cm/model-key model) :ports])
        shape (when (= (:type v) "amp") :amp)
        [width height] (if ports (cm/port-perimeter ports shape) [1 1])
        pattern (if ports (cm/port-locations ports shape) [])]
    (draw-pattern (+ 2 (max width height)) pattern
                  port k v)))


;; Helper: counter-rotation transform for text that should stay upright
(defn counter-rotate [v x y]
  (.toString (-> (js/DOMMatrix.)
                 (.translate x y)
                 (.multiply (.inverse (transform (:transform v))))
                 (.translate (- x) (- y)))))

;; Helper: render designator at screen-space top-right corner
(defn circuit-designator [v size width height]
  (let [half-size (/ size 2)
        corners [[(- (+ 1 width) half-size) (- 1 half-size)]             ; top-right
                 [(- 1 half-size) (- 1 half-size)]                       ; top-left
                 [(- 1 half-size) (- (+ 1 height) half-size)]            ; bottom-left
                 [(- (+ 1 width) half-size) (- (+ 1 height) half-size)]] ; bottom-right
        mat (transform (:transform v))
        rotated (map (fn [[x y]]
                       (let [pt (.transformPoint mat (js/DOMPoint. x y))]
                         [(.-x pt) (.-y pt)]))
                     corners)
        [dx dy] (apply max-key (fn [[x y]] (- x y)) rotated)
        desig-offset-x (+ dx 0.1)
        desig-offset-y (+ dy 0.25)]
    (when-let [text (get v :template (get-in models [(:type v) ::template] "{self.name}"))]
      [:text.identifier
       {:transform (-> (:transform v) transform
                       (.translate (* grid-size (/ size -2)) (* grid-size (/ size -2)))
                       .inverse
                       (.translate (* grid-size desig-offset-x) (* grid-size desig-offset-y))
                       .toString)}
       (schem-template v text)])))

;; Helper: render port label with counter-rotation
(defn port-label [v x y anchor baseline pname]
  [:text.port-label
   {:key (str pname)
    :x x :y y
    :text-anchor anchor
    :dominant-baseline baseline
    :transform (counter-rotate v x y)}
   pname])

(defn circuit-sym-box [k v]
  (let [model (:model v)
        model-def (get @modeldb (cm/model-key model))
        model-name (or (:name model-def) model)
        ports (:ports model-def)
        has-model? (and model (seq model) ports)
        [width height] (if ports (cm/port-perimeter ports) [1 1])
        all-locs (cm/port-locations ports)
        by-side (group-by :side all-locs)
        top-locs (:top by-side)
        bottom-locs (:bottom by-side)
        left-locs (:left by-side)
        right-locs (:right by-side)
        size (+ 2 (max width height))
        ;; Box center in device-local coords (pixels)
        cx (* grid-size (+ 1 (/ width 2)))
        cy (* grid-size (+ 1 (/ height 2)))]
    [device size k v
     [:<>
      (circuit-designator v size width height)
      ;; Port lines (only when model selected)
      (when has-model?
        [:<>
         [lines (for [{:keys [x y]} top-locs] [[(+ x 0.5) (+ y 0.5)] [(+ x 0.5) (+ y 1)]])]
         [lines (for [{:keys [x y]} bottom-locs] [[(+ x 0.5) (+ y 0)] [(+ x 0.5) (+ y 0.5)]])]
         [lines (for [{:keys [x y]} right-locs] [[(+ x 0) (+ y 0.5)] [(+ x 0.5) (+ y 0.5)]])]
         [lines (for [{:keys [x y]} left-locs] [[(+ x 1) (+ y 0.5)] [(+ x 0.5) (+ y 0.5)]])]])
      ;; Port labels inside box
      (when has-model?
        [:<>
         (for [{:keys [y name]} left-locs]
           (port-label v (* 1.15 grid-size) (* (+ y 0.5) grid-size) "start" "middle" name))
         (for [{:keys [y name]} right-locs]
           (port-label v (* (+ width 0.85) grid-size) (* (+ y 0.5) grid-size) "end" "middle" name))
         (for [{:keys [x name]} top-locs]
           (port-label v (* (+ x 0.5) grid-size) (* 1.3 grid-size) "middle" "hanging" name))
         (for [{:keys [x name]} bottom-locs]
           (port-label v (* (+ x 0.5) grid-size) (* (+ height 0.7) grid-size) "middle" "baseline" name))])
      ;; Symbol image replaces box, otherwise box outline with model name
      (if-let [symbol-url (and has-model? (some-> model-def :symbol resolve-symbol-url deref))]
        [:image {:href symbol-url
                 :x grid-size :y grid-size
                 :width (* grid-size width)
                 :height (* grid-size height)
                 :preserveAspectRatio "xMidYMid meet"
                 :on-mouse-down #(.preventDefault %)}]
        [:<>
         [:rect.outline
          {:class (when-not has-model? "placeholder")
           :x grid-size :y grid-size
           :width (* grid-size width)
           :height (* grid-size height)}]
         [:text.model-name
          {:x cx :y cy
           :text-anchor "middle"
           :dominant-baseline "middle"
           :transform (counter-rotate v cx cy)}
          (if has-model? model-name "?")]])]]))

(defn circuit-sym-amplifier [k v]
  (let [model (:model v)
        model-def (get @modeldb (cm/model-key model))
        model-name (or (:name model-def) model)
        ports (:ports model-def)
        has-model? (and model (seq model) ports)
        [width height] (if ports (cm/port-perimeter ports :amp) [1 1])
        all-locs (cm/port-locations ports :amp)
        by-side (group-by :side all-locs)
        top-locs (:top by-side)
        bottom-locs (:bottom by-side)
        left-locs (:left by-side)
        right-locs (:right by-side)
        size (+ 2 (max width height))
        ;; Triangle vertices (pointing right)
        tri-left 1
        tri-right (+ 1 width)
        tri-top 1
        tri-bottom (+ 1 height)
        tri-apex-y (+ 1 (/ height 2))
        ;; Triangle centroid (offset left since triangle tapers right)
        cx (* (+ tri-left (/ width 3)) grid-size)
        cy (* tri-apex-y grid-size)]
    [device size k v
     [:<>
      (circuit-designator v size width height)
      ;; Triangle outline
      [:polygon.outline
       {:class (when-not has-model? "placeholder")
        :points (str (* tri-left grid-size) "," (* tri-top grid-size) " "
                     (* tri-left grid-size) "," (* tri-bottom grid-size) " "
                     (* tri-right grid-size) "," (* tri-apex-y grid-size))}]
      (when has-model?
        [:<>
         ;; Top ports - lines extend down to meet triangle's top edge
         [lines (for [{:keys [x y]} top-locs
                      :let [tri-y (+ tri-top (* (- (+ x 0.5) tri-left) (/ (- tri-apex-y tri-top) width)))]]
                  [[(+ x 0.5) (+ y 0.5)] [(+ x 0.5) tri-y]])]
         ;; Bottom ports - lines extend up to meet triangle's bottom edge
         [lines (for [{:keys [x y]} bottom-locs
                      :let [tri-y (+ tri-bottom (* (- (+ x 0.5) tri-left) (/ (- tri-apex-y tri-bottom) width)))]]
                  [[(+ x 0.5) (+ y 0.5)] [(+ x 0.5) tri-y]])]
         ;; Left ports - lines extend right to left edge
         [lines (for [{:keys [x y]} left-locs]
                  [[(+ x 0.5) (+ y 0.5)] [tri-left (+ y 0.5)]])]
         ;; Right ports - lines extend left to meet triangle edge
         [lines (for [{:keys [x y]} right-locs
                      :let [port-dy (- (+ y 0.5) tri-apex-y)
                            tri-x (if (< port-dy 0)
                                    (+ tri-right (* port-dy (/ width (- tri-top tri-apex-y))))
                                    (+ tri-right (* port-dy (/ width (- tri-bottom tri-apex-y)))))]]
                  [[(+ x 0.5) (+ y 0.5)] [tri-x (+ y 0.5)]])]])
      ;; Port labels inside triangle
      (when has-model?
        [:<>
         (for [{:keys [y name]} left-locs]
           (port-label v (* 1.15 grid-size) (* (+ y 0.5) grid-size) "start" "middle" name))
         (for [{:keys [y name]} right-locs]
           (port-label v (* (+ width 0.6) grid-size) (* (+ y 0.5) grid-size) "end" "middle" name))
         ;; Top port labels - follow diagonal edge
         (for [{:keys [x name]} top-locs
               :let [px (+ x 0.5)
                     edge-y (+ tri-top (* (- px tri-left) (/ (- tri-apex-y tri-top) width)))]]
           (port-label v (* px grid-size) (* (+ edge-y 0.3) grid-size) "middle" "hanging" name))
         ;; Bottom port labels - follow diagonal edge
         (for [{:keys [x name]} bottom-locs
               :let [px (+ x 0.5)
                     edge-y (+ tri-bottom (* (- px tri-left) (/ (- tri-apex-y tri-bottom) width)))]]
           (port-label v (* px grid-size) (* (- edge-y 0.3) grid-size) "middle" "baseline" name))])
      ;; Model name or "?"
      [:text.model-name
       {:x cx :y cy
        :text-anchor "middle"
        :dominant-baseline "middle"
        :transform (counter-rotate v cx cy)}
       (if has-model? model-name "?")]]]))

(defn circuit-sym [k v]
  (case (:type v)
    "amp" (circuit-sym-amplifier k v)
    (circuit-sym-box k v)))

(def models {"pmos" {::bg cm/active-bg
                     ::conn mosfet-shape
                     ::sym mosfet-sym
                     ::category "pchan"
                     ::template "{self.name}"
                     ::props []}
             "nmos" {::bg cm/active-bg
                     ::conn mosfet-shape
                     ::sym mosfet-sym
                     ::category "nchan"
                     ::template "{self.name}"
                     ::props []}
             "npn" {::bg cm/active-bg
                    ::conn bjt-conn
                    ::sym bjt-sym
                    ::category "nchan"
                    ::template "{self.name}"
                    ::props []}
             "pnp" {::bg cm/active-bg
                    ::conn bjt-conn
                    ::sym bjt-sym
                    ::category "pchan"
                    ::template "{self.name}"
                    ::props []}
             "resistor" {::bg cm/twoport-bg
                         ::conn cm/twoport-conn
                         ::sym resistor-elements
                         ::category "passive"
                         ::template "{self.name}: {self.props.resistance}Ω"
                         ::props [{:name "resistance" :tooltip "Resistance value" :spice "resistance"}]}
             "capacitor" {::bg cm/twoport-bg
                          ::conn cm/twoport-conn
                          ::sym capacitor-elements
                          ::category "passive"
                          ::template "{self.name}: {self.props.capacitance}F"
                          ::props [{:name "capacitance" :tooltip "Capacitance value" :spice "capacitance"}]}
             "inductor" {::bg cm/twoport-bg
                         ::conn cm/twoport-conn
                         ::sym inductor-elements
                         ::category "passive"
                         ::template "{self.name}: {self.props.inductance}H"
                         ::props [{:name "inductance" :tooltip "Inductance value" :spice "inductance"}]}
             "vsource" {::bg cm/twoport-bg
                        ::conn cm/twoport-conn
                        ::sym vsource-elements
                        ::category "source"
                        ::template "{self.name}: {self.props.dc}V"
                        ::props [{:name "dc" :tooltip "DC voltage" :spice "dc"}
                                 {:name "ac" :tooltip "AC voltage" :spice "ac"}
                                 {:name "tran" :tooltip "Transient voltage" :spice "tran"}]}
             "isource" {::bg cm/twoport-bg
                        ::conn cm/twoport-conn
                        ::sym isource-elements
                        ::category "source"
                        ::template "{self.name}: {self.props.dc}I"
                        ::props [{:name "dc" :tooltip "DC current" :spice "dc"}
                                 {:name "ac" :tooltip "AC current" :spice "ac"}
                                 {:name "tran" :tooltip "Transient current" :spice "tran"}]}
             "diode" {::bg cm/twoport-bg
                      ::conn cm/twoport-conn
                      ::sym #'diode-sym
                      ::category "passive"
                      ::props []}
             "led" {::bg [1 1]
                    ::conn [{:name "P" :x 1 :y 0 :type "electric"}
                            {:name "O" :x 2 :y 1 :type "photonic"}
                            {:name "N" :x 1 :y 2 :type "electric"}]
                    ::sym #'diode-sym
                    ::category "photonic-active"
                    ::template "{self.name}"
                    ::props []}
             "photodiode" {::bg [1 1]
                           ::conn [{:name "P" :x 1 :y 0 :type "electric"}
                                   {:name "O" :x 2 :y 1 :type "photonic"}
                                   {:name "N" :x 1 :y 2 :type "electric"}]
                           ::sym #'diode-sym
                           ::category "photonic-active"
                           ::template "{self.name}"
                           ::props []}
             "modulator" {::bg [1 1]
                          ::conn [{:name "P" :x 1 :y 0 :type "electric"}
                                  {:name "1" :x 0 :y 1 :type "photonic"}
                                  {:name "2" :x 2 :y 1 :type "photonic"}
                                  {:name "N" :x 1 :y 2 :type "electric"}]
                          ::sym #'diode-sym
                          ::category "photonic-active"
                          ::template "{self.name}"
                          ::props []}
             ;; Photonic components
             "straight" {::bg [1 1]
                         ::conn cm/horizontal-conn
                         ::sym straight-elements
                         ::category "photonic"
                         ::template "{self.name}"
                         ::props []}
             "bend" {::bg [1 1]
                     ::conn (cm/ascii-patern "photonic"
                             ["   "
                              "1  "
                              " 2 "])
                     ::sym bend-elements
                     ::category "photonic"
                     ::template "{self.name}"
                     ::props []}
             "sbend" {::bg [1 2]
                      ::conn (cm/ascii-patern "photonic"
                              ["   "
                               "1  "
                               "  2"])
                      ::sym sbend-elements
                      ::category "photonic"
                      ::template "{self.name}"
                      ::props []}
             "taper" {::bg [1 1]
                      ::conn cm/horizontal-conn
                      ::sym taper-elements
                      ::category "photonic"
                      ::template "{self.name}"
                      ::props []}
             "transition" {::bg [1 1]
                           ::conn cm/horizontal-conn
                           ::sym transition-elements
                           ::category "photonic"
                           ::template "{self.name}"
                           ::props []}
             "terminator" {::bg [1 1]
                           ::conn (cm/ascii-patern "photonic"
                                   ["   "
                                    "1  "
                                    "   "])
                           ::sym terminator-elements
                           ::category "photonic"
                           ::template "{self.name}"
                           ::props []}
             "crossing" {::bg [1 1]
                         ::conn cm/cross-conn
                         ::sym crossing-elements
                         ::category "photonic"
                         ::template "{self.name}"
                         ::props []}
             "ring-single" {::bg [1 2]
                            ::conn (cm/ascii-patern "photonic"
                                    ["   "
                                     "   "
                                     "1 2"])
                            ::sym ring-single-elements
                            ::category "photonic"
                            ::template "{self.name}"
                            ::props []}
             "ring-double" {::bg [1 2]
                            ::conn (cm/ascii-patern "photonic"
                                    ["   "
                                     "1 2"
                                     "3 4"])
                            ::sym ring-double-elements
                            ::category "photonic"
                            ::template "{self.name}"
                            ::props []}
             "spiral" {::bg [1 1]
                       ::conn cm/horizontal-conn
                       ::sym spiral-elements
                       ::category "photonic"
                       ::template "{self.name}"
                       ::props []}
             "splitter-1x2" {::bg [1 3]
                             ::conn (cm/ascii-patern "photonic"
                                     ["   "
                                      "  2"
                                      "1  "
                                      "  3"
                                      "   "])
                             ::sym splitter-1x2-elements
                             ::category "photonic"
                             ::template "{self.name}"
                             ::props []}
             "coupler" {::bg [1 2]
                        ::conn (cm/ascii-patern "photonic"
                                ["   "
                                 "1 2"
                                 "3 4"])
                        ::sym coupler-elements
                        ::category "photonic"
                        ::template "{self.name}"
                        ::props []}
             "coupler-ring" {::bg #'coupler-ring-bg
                             ::conn (cm/ascii-patern "photonic"
                                     ["12"
                                      "34"])
                             ::sym coupler-ring-elements
                             ::category "photonic"
                             ::template "{self.name}"
                             ::props []}
             "mmi-1x2" {::bg [1 3]
                        ::conn (cm/ascii-patern "photonic"
                                ["   "
                                 "  2"
                                 "1  "
                                 "  3"
                                 "   "])
                        ::sym mmi-1x2-elements
                        ::category "photonic"
                        ::template "{self.name}"
                        ::props []}
             "mmi-2x2" {::bg [1 3]
                        ::conn (cm/ascii-patern "photonic"
                                ["   "
                                 "1 2"
                                 "   "
                                 "3 4"
                                 "   "])
                        ::sym mmi-2x2-elements
                        ::category "photonic"
                        ::template "{self.name}"
                        ::props []}
             "mzi-1x2" {::bg [1 3]
                        ::conn (cm/ascii-patern "photonic"
                                ["   "
                                 "  2"
                                 "1  "
                                 "  3"
                                 "   "])
                        ::sym mzi-1x2-elements
                        ::category "photonic"
                        ::template "{self.name}"
                        ::props []}
             "mzi-2x2" {::bg [1 3]
                        ::conn (cm/ascii-patern "photonic"
                                ["   "
                                 "1 2"
                                 "   "
                                 "3 4"
                                 "   "])
                        ::sym mzi-2x2-elements
                        ::category "photonic"
                        ::template "{self.name}"
                        ::props []}
"grating-coupler" {::bg [1 1]
                                ::conn (cm/ascii-patern "photonic"
                                        ["   "
                                         "1  "
                                         "   "])
                                ::sym grating-coupler-elements
                                ::category "source"
                                ::template "{self.name}"
                                ::props []}
             "wire" {::bg []
                     ::conn []
                     ::sym wire-sym
                     ::props []}
             "port" {::bg []
                     ::conn [{:name "P" :x 0 :y 0}]
                     ::sym port-sym
                     ::template "{self.name}: {res.op[self.name.toLowerCase()]:.2f}V"
                     ::props []}
             "text" {::bg []
                     ::conn []
                     ::sym text-sym
                     ::template "Operating Point:\n{res.op}"
                     ::props []}})

(defn rotate-shape
  ([shape transform devx devy]
   (rotate-shape shape (cm/pattern-size shape) transform devx devy))
  ([shape size [a b c d e f] devx devy]
   (let [mid (- (/ size 2) 0.5)]
     (map (fn [port]
            (let [px (:x port)
                  py (:y port)
                  x (- px mid)
                  y (- py mid)
                  nx (+ (* a x) (* c y) e)
                  ny (+ (* b x) (* d y) f)]
              (assoc port
                     :x (math/round (+ devx nx mid))
                     :y (math/round (+ devy ny mid))))) shape))))

(defn exrange [start width]
  (next (take-while #(not= % (+ start width))
                    (iterate #(+ % (cm/sign width)) start))))

(defn wire-locations [{:keys [x y rx ry variant]}]
  (let [x2 (+ x rx) y2 (+ y ry)]
    [[[x y] [x2 y2]]  ; conn: start and end
     (case variant
       "hv" (concat (map #(vector % y) (exrange x rx))     ; horizontal leg
                    [[x2 y]]                               ; corner
                    (map #(vector x2 %) (exrange y ry)))   ; vertical leg
       "vh" (concat (map #(vector x %) (exrange y ry))     ; vertical leg
                    [[x y2]]                               ; corner
                    (map #(vector % y2) (exrange x rx)))   ; horizontal leg
       ;; "d" or nil: straight or diagonal
       (cond
         (zero? rx) (map #(vector x %) (exrange y ry))     ; vertical
         (zero? ry) (map #(vector % y) (exrange x rx))     ; horizontal
         :else []))]))

(defn builtin-locations [{:keys [:x :y :type :transform]}]
  (let [mod (get models type)
        conn (::conn mod)
        bg (::bg mod)
        [w h] (when (vector? bg) bg)
        size (if (and (number? w) (number? h))
               (+ 2 (max w h))
               (cm/pattern-size conn))]
    [(map (juxt :x :y) (rotate-shape conn size transform x y))
     (map (juxt :x :y) (rotate-shape (for [bx (range (or w 0)) by (range (or h 0))]
                                        {:x (+ (if w 1 0) bx) :y (+ (if h 1 0) by)})
                                      size transform x y))]))

(defn circuit-locations [{:keys [:x :y :model :transform :type]}]
  (let [mod (get @modeldb (cm/model-key model))
        ports (:ports mod)
        shape (when (= type "amp") :amp)
        conn (if ports (cm/port-locations ports shape) [])
        [w h] (if ports (cm/port-perimeter ports shape) [1 1])
        size (+ 2 (max w h))]
    [(map (juxt :x :y) (rotate-shape conn size transform x y))
     (map (juxt :x :y) (rotate-shape (for [bx (range w) by (range h)]
                                        {:x (inc bx) :y (inc by)})
                                      size transform x y))]))

(defn device-locations [dev]
  (let [cell (:type dev)]
    (cond
      (= cell "wire") (wire-locations dev)
      (= cell "text") []
      (contains? models cell) (builtin-locations dev)
      :else (circuit-locations dev))))

(defn build-location-index [sch]
  (loop [connidx {} bodyidx {} [[id dev] & other] (seq sch)]
    (let [[connloc bodyloc] (device-locations dev)
          nconnidx (reduce #(update %1 %2 sconj id) connidx connloc)
          nbodyidx (reduce #(update %1 %2 sconj id) bodyidx bodyloc)]
      (if other
        (recur nconnidx nbodyidx other)
        [nconnidx nbodyidx]))))

; [conn body]
(def location-index (r/track #(build-location-index @schematic)))

(defn device-port-types
  "Return typed port maps [{:x :y :type ...}] for a device after rotation."
  [dev]
  (let [cell (:type dev)
        {:keys [x y transform model]} dev]
    (cond
      (= cell "wire") nil
      (= cell "text") nil
      (contains? models cell)
      (let [mod (get models cell)
            conn (::conn mod)
            bg (::bg mod)
            [w h] (when (vector? bg) bg)
            size (if (and (number? w) (number? h))
                   (+ 2 (max w h))
                   (cm/pattern-size conn))]
        (rotate-shape conn size transform x y))
      :else
      (let [mod (get @modeldb (cm/model-key model))
            ports (:ports mod)
            shape (when (= (:type dev) "amp") :amp)
            conn (if ports (cm/port-locations ports shape) [])]
        (rotate-shape conn transform x y)))))

(defn build-point-type-index
  "Build {[x y] => {:types #{\"photonic\" \"electric\"} :photonic-count N :electric-count N}}
   from all non-wire devices in the schematic."
  [sch]
  (reduce
   (fn [idx [_id dev]]
     (if-let [ports (device-port-types dev)]
       (reduce
        (fn [idx port]
          (let [pt [(:x port) (:y port)]
                t (:type port)]
            (if t
              (-> idx
                  (update-in [pt :types] (fnil conj #{}) t)
                  (update-in [pt (keyword (str (name t) "-count"))] (fnil inc 0)))
              idx)))
        idx ports)
       idx))
   {} sch))

(def point-type-index (r/track #(build-point-type-index @schematic)))

(defn build-wire-type-index
  "Propagate port types to wires via BFS through connected wire networks.
   Returns {wire-id => \"photonic\"|\"electric\"|nil}."
  [sch [connidx _] point-types]
  (let [wires (into {} (filter #(= "wire" (:type (val %)))) sch)
        ;; Build adjacency: for each wire, find other wires sharing endpoints
        wire-endpoints (into {}
                             (map (fn [[id {:keys [x y rx ry]}]]
                                    [id [[x y] [(+ x rx) (+ y ry)]]]))
                             wires)
        ;; BFS from typed points to find wire types
        wire-types (atom {})
        visited (atom #{})]
    (doseq [[wire-id endpoints] wire-endpoints
            :when (not (contains? @visited wire-id))]
      ;; BFS from this wire through connected wires
      (loop [queue (conj #queue [] wire-id)
             network-type nil
             network #{}]
        (if-let [wid (peek queue)]
          (if (contains? network wid)
            (recur (pop queue) network-type network)
            (let [eps (get wire-endpoints wid)
                  ;; Check if any endpoint has a typed port
                  ep-type (some (fn [pt]
                                  (let [types (get-in point-types [pt :types])]
                                    (first types)))
                                eps)
                  new-type (or network-type ep-type)
                  ;; Find connected wires at endpoints
                  neighbors (for [pt eps
                                  neighbor-id (get connidx pt)
                                  :when (and (contains? wires neighbor-id)
                                             (not (contains? network neighbor-id)))]
                              neighbor-id)]
              (recur (into (pop queue) neighbors) new-type (conj network wid))))
          ;; Done with this network
          (do
            (swap! visited into network)
            (doseq [wid network]
              (swap! wire-types assoc wid network-type))))))
    @wire-types))

(def wire-type-index
  (r/track #(build-wire-type-index @schematic @location-index @point-type-index)))

(defn build-wire-split-index [[connidx bodyidx]]
  (reduce (fn [result [key val]]
            (if (contains? connidx key)
              (->> val
                   (filter #(= "wire" (get-in @schematic [% :type])))
                   (reduce #(update %1 %2 cm/ssconj key) result))
              result))
          {} bodyidx))

(defn wire-corner
  "Compute the corner position of an elbow wire, or nil for straight/diagonal."
  [{:keys [x y rx ry variant]}]
  (case variant
    "hv" [(+ x rx) y]
    "vh" [x (+ y ry)]
    nil))

(defn split-wire [wirename coords loc-idx]
  (let [{:keys [x y rx ry variant] :as wire} (get @schematic wirename)
        x2 (+ x rx)
        y2 (+ y ry)
        [_ body] (wire-locations wire)
        ;; body is already in path order (including corner for elbows)
        split-set (set coords)
        ordered (concat [[x y]] (filter split-set body) [[x2 y2]])
        ;; Check if two points share a non-wire device, or a wire with same corner
        shared-device? (fn [p1 p2 seg-corner]
                         (let [connidx (first loc-idx)
                               shared (clojure.set/intersection (get connidx p1) (get connidx p2))]
                           (some #(let [dev (get @schematic %)]
                                    (or (not= "wire" (:type dev))
                                        (= seg-corner (wire-corner dev))))
                                 shared)))
        wires (loop [w wirename
                     [[x1 y1] & [[x2 y2] & _ :as other]] ordered
                     schem {}]
                (if other
                  (let [seg-corner (case variant
                                     "hv" [x2 y1]
                                     "vh" [x1 y2]
                                     nil)]
                    (recur (name (gensym wirename)) other
                           (if (shared-device? [x1 y1] [x2 y2] seg-corner)
                             schem
                             (assoc schem w
                                    {:type "wire" :transform cm/IV :variant variant
                                     :x x1 :y y1 :rx (- x2 x1) :ry (- y2 y1)}))))
                  schem))]
    (swap! schematic (partial merge-with merge) wires)))


(def last-coord (atom [0 0]))

;; Double-click detection for pointer events (which don't have detail property)
(def last-pointerdown (atom {:time 0 :x 0 :y 0}))
(def dblclick-threshold-ms 400)
(def dblclick-threshold-px 10)

(defn check-double-click
  "Check if this pointerdown is a double-click based on timing and position.
   Returns true if double-click detected, and updates the tracking state."
  [e]
  (let [now (js/Date.now)
        cx (.-clientX e)
        cy (.-clientY e)
        prev @last-pointerdown
        dt (- now (:time prev))
        dist (math/hypot (- cx (:x prev)) (- cy (:y prev)))
        is-dblclick (and (< dt dblclick-threshold-ms)
                         (< dist dblclick-threshold-px))]
    (reset! last-pointerdown {:time now :x cx :y cy})
    is-dblclick))

(defn viewbox-movement [e]
  (let [^js el (js/document.querySelector ".mosaic-canvas")
        m (.inverse (.getScreenCTM el))
        _ (do (set! (.-e m) 0)
              (set! (.-f m) 0)) ; cancel translation
        [lx ly] @last-coord
        nx (.-clientX e)
        ny (.-clientY e)
        mx (- nx lx)
        my (- ny ly)
        ^js p (point mx my)
        tp (.matrixTransform p m)] ; local movement
    (reset! last-coord [nx ny])
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

;; Pointer event helpers for touch gestures

(defn pointer-distance
  "Calculate distance between two pointers"
  [cache]
  (let [[{x1 :x y1 :y} {x2 :x y2 :y}] (vals cache)]
    (math/hypot (- x1 x2) (- y1 y2))))

(defn pointer-center
  "Calculate center point between two pointers (screen coords)"
  [cache]
  (let [[{x1 :x y1 :y} {x2 :x y2 :y}] (vals cache)]
    [(/ (+ x1 x2) 2) (/ (+ y1 y2) 2)]))

(defn apply-pinch-delta
  "Apply incremental pan and zoom from two-finger gesture"
  [old-dist new-dist old-center new-center]
  (let [[ocx ocy] old-center
        [ncx ncy] new-center
        scale (/ old-dist new-dist)
        el (js/document.querySelector ".mosaic-canvas")
        m (.inverse (.getScreenCTM el))
        ;; Transform centers to viewbox coords
        op (.matrixTransform (point ocx ocy) m)
        np (.matrixTransform (point ncx ncy) m)
        ;; Pan delta in viewbox coords
        pdx (- (.-x op) (.-x np))
        pdy (- (.-y op) (.-y np))]
    (swap! ui update ::zoom
           (fn [[x y w h]]
             (let [;; Zoom around old center
                   cx (.-x op)
                   cy (.-y op)
                   nw (* w scale)
                   nh (* h scale)
                   nx (+ cx (* (- x cx) scale) pdx)
                   ny (+ cy (* (- y cy) scale) pdy)]
               [nx ny nw nh])))))

(defn pen-eraser?
  "Check if event is from pen eraser button"
  [e]
  (and (= "pen" (.-pointerType e))
       (= 5 (.-button e))))

(defn scroll-zoom [e]
  (let [[x y] (cm/viewbox-coord e)]
    (zoom-schematic (cm/sign (.-deltaY e)) (* x grid-size) (* y grid-size))))

(defn button-zoom [dir]
  (let [[x y w h] (::zoom @ui)]
    (zoom-schematic dir
                    (+ x (/ w 2))
                    (+ y (/ h 2)))))

(defn commit-staged [dev]
  (when (s/valid? :nyancad.mosaic.common/device dev)
    (let [name (make-name (:type dev))
          id (str group sep name)]
      (swap! schematic assoc id
             (if (:name dev)
               dev
               (assoc dev :name name)))
      (post-action!))))

(defn transform-selected [tf]
  (let [f (comp transform-vec tf transform)]
    (if @staging
      (swap! staging
             update :transform f)
      (do (swap! schematic update-keys @selected
                 update :transform f)
          (post-action!)))))

(defn delete-selected []
  (let [selected (::selected @ui)]
    (swap! ui assoc ::selected #{})
    (swap! schematic #(apply dissoc %1 %2) selected)
    (post-action!)))

(defn drag-view [e]
  (swap! ui update ::zoom
         (fn [[x y w h]]
           (let [[dx dy] (viewbox-movement e)]
             [(- x dx)
              (- y dy)
              w h]))))

(defn drag-device [e]
  (let [[x y] (cm/viewbox-coord e)
        [xs ys] (::mouse-start @ui)
        dx (- x xs)
        dy (- y ys)]
    (swap! delta assoc :x dx :y dy)))

(defn drag-wire [^js e]
  (let [[x y] (cm/viewbox-coord e)]
    (swap! staging
           (fn [d]
             (let [rx (- x (:x d) 0.5)
                   ry (- y (:y d) 0.5)
                   diagonal? (.-ctrlKey e)
                   straight? (or (< (abs rx) 0.5)
                                 (< (abs ry) 0.5))]
               (cond
                 ;; Ctrl held: diagonal
                 diagonal? (assoc d :rx rx :ry ry :variant "d")
                 ;; Axis-aligned: snap to dominant axis
                 straight? (if (> (abs rx) (abs ry))
                             (assoc d :rx rx :ry 0 :variant "d")
                             (assoc d :rx 0 :ry ry :variant "d"))
                 ;; Elbow mode: lock direction on first significant movement
                 :else
                 (let [variant (or (#{"hv" "vh"} (:variant d))
                                   (if (> (abs rx) (abs ry)) "hv" "vh"))]
                   (assoc d :rx rx :ry ry :variant variant))))))))

(defn drag-staged-device [e]
  (let [[x y] (cm/viewbox-coord e)
        bg (get-in models [(:type @staging) ::bg])
        [width height] (when (vector? bg) bg)
        xm (math/round (- x (or width 0) 0.5))
        ym (math/round (- y (or height 0) 0.5))]
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
    ::box (drag-device e)
    nil))

; updating the PouchDB atom is a fast but async operation
; while it is in flight, drag events for the same device could stack up
; this has been shown to peg the CPU
(def inflight-deletions (atom #{}))
(defn eraser-drag [k e]
  (when (and (> (.-buttons e) 0)
             (.-isPrimary e)
             (< (count (::pointer-cache @ui)) 2)
             (= @tool ::eraser)
             (not (contains? @inflight-deletions k))
             (contains? @schematic k))
    (swap! inflight-deletions conj k)
    (go (<! (swap! schematic dissoc k))
        (swap! inflight-deletions disj k))))

(defn drag [e]
  ;; store mouse position for use outside mouse events
  ;; keyboard shortcuts for example
  (swap! ui assoc ::mouse (cm/viewbox-coord e))
  (case @tool
    ::wire (wire-drag e)
    ::pan (when (> (.-buttons e) 0) (drag-view e))
    ::device (if (= (::dragging @ui) ::view) (drag-view e) (drag-staged-device e))
    (cursor-drag e)))

(defn same-tile?
  "Check if wire staging endpoint is on the starting tile."
  [dev]
  (and (< (abs (:rx dev)) 0.5) (< (abs (:ry dev)) 0.5)))

(defn add-wire-segment [[x y]]
  (swap! ui assoc
         ::staging {:type "wire"
                    :x (math/floor x)
                    :y (math/floor y)
                    :rx 0 :ry 0
                    :variant "d"}
         ::dragging ::wire))

(defn add-wire [[x y] first?]
  (if first?
    (add-wire-segment [x y]) ; just add a new wire, else finish old wire
    (let [dev (update-keys @staging #{:rx :ry} math/round)
          {rx :rx ry :ry} dev
          x (math/round (+ (:x dev) rx)) ; use end pos of previous wire instead
          y (math/round (+ (:y dev) ry))
          [conn body] @location-index
          on-port (or (contains? conn [x y])
                      (contains? body [x y]))]
      (cond
        (same-tile? dev) (swap! ui assoc ; the dragged wire stayed at the same tile, exit
                                ::staging nil
                                ::dragging nil)
        on-port (go (<! (commit-staged dev)) ; the wire landed on a port or wire, commit and exit
                    (swap! ui assoc
                           ::staging nil
                           ::dragging nil))
        :else (go
                (<! (commit-staged dev)) ; commit and start new segment
                (add-wire-segment [x y]))))))

(defn select-connected []
  (let [schem @schematic
        wire-key (first (filter schem @selected))
        wire (get schem wire-key)
        wire? (fn [wirename] (let [wire (get schem wirename)]
                               (and (= (:type wire) "wire") wire)))
        wire-ports (fn [init wire]
                     (if (= (:type wire) "wire")
                       (conj init [(:x wire) (:y wire)]
                             [(+ (:x wire) (:rx wire)) (+ (:y wire) (:ry wire))])
                       init))]
    (when wire
      (loop [ports (wire-ports #{} wire)
             sel #{wire-key}]
        (if (seq ports)
          (let [[connidx _] @location-index
                newsel (clojure.set/difference
                        (set (filter wire? (mapcat connidx ports)))
                        sel)
                newports (reduce wire-ports #{} (map schem newsel))]
            (recur
             (clojure.set/difference newports ports)
             (into sel newsel)))
          (reset! selected sel))))))

(defn cancel
  "Cancel current operation and optionally switch to a new tool.
   0-arity: default behavior (keep wire tool if drawing wire, else cursor)
   1-arity: switch to specified tool and clear staging"
  ([]
   (let [uiv @ui]
     (if (and (::staging uiv) (= (::tool uiv) ::wire))
       (swap! ui assoc
              ::dragging nil
              ::staging nil)
       (swap! ui assoc
              ::dragging nil
              ::tool ::cursor
              ::staging nil))))
  ([new-tool]
   (swap! ui assoc
          ::dragging nil
          ::tool new-tool
          ::staging nil)))

(defonce probechan (js/BroadcastChannel. "probe"))
(defn probe-element [k]
  (println k)
  (.postMessage probechan k))

(defn drag-start [k e]
  (swap! ui assoc ::mouse-start (cm/viewbox-coord e))
  (reset! last-coord [(.-clientX e) (.-clientY e)])
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
                   ::pan ::view
                   ::probe nil)))]
    ; skip the mouse down when initiated from a toolbar button
    ; only when primary mouse click
    (when (and (not= (::tool uiv) ::device)
               (= (.-button e) 0))
      (case (::tool uiv)
        ::wire (add-wire (cm/viewbox-coord e) (nil? (::dragging uiv)))
        ::eraser (eraser-drag k e)
        ::probe (probe-element k)
        (swap! ui (fn [ui]
                    (-> ui
                        (update ::selected update-selection)
                        (drag-type))))))))

(defn drag-start-box [e]
  (swap! ui assoc
         ::selected #{}
         ::dragging ::box
         ::mouse-start (cm/viewbox-coord e)))

(defn drag-start-background [e]
  (reset! last-coord [(.-clientX e) (.-clientY e)])
  (cond
    (and (= (.-button e) 0)
         (= nil (::dragging @ui))
         (= ::cursor @tool)) (drag-start-box e)
    (and (= (.-button e) 0)
         (= ::wire @tool)) (add-wire (cm/viewbox-coord e) (nil? (::dragging @ui)))))

(defn context-menu [e]
  (when (or (::dragging @ui)
            (not= (::tool @ui) ::cursor))
    (cancel)
    (.preventDefault e)))

(defn double-click [_k _e]
  (when (= (::tool @ui) ::cursor)
    (select-connected)))

;; Pointer event handlers for touch/stylus support

(defn on-pointer-down-bg [e]
  (let [id (.-pointerId e)
        entry {:x (.-clientX e) :y (.-clientY e)}]
    ;; Update double-click tracking state (consumed by element handler)
    (when (.-isPrimary e) (check-double-click e))
    (swap! ui assoc-in [::pointer-cache id] entry)
    (let [cache (::pointer-cache @ui)]
      (cond
        ;; Second finger: cancel current operation, start gesture
        (= 2 (count cache))
        (cancel)

        ;; Pen eraser: momentary eraser
        (pen-eraser? e)
        (swap! ui #(assoc % ::prev-tool (::tool %) ::tool ::eraser))

        ;; Middle click: momentary pan
        (= (.-button e) 1)
        (do (reset! last-coord [(.-clientX e) (.-clientY e)])
            (swap! ui #(assoc % ::prev-tool (::tool %) ::tool ::pan)))

        ;; Primary pointer: normal interaction
        (.-isPrimary e)
        (drag-start-background e)))))

(defn on-pointer-move-bg [e]
  (let [old-cache (::pointer-cache @ui)
        id (.-pointerId e)
        entry {:x (.-clientX e) :y (.-clientY e)}]
    ;; Two-finger gesture: incremental pan/zoom
    (when (and (= 2 (count old-cache)) (contains? old-cache id))
      (let [new-cache (assoc old-cache id entry)]
        (apply-pinch-delta
         (pointer-distance old-cache) (pointer-distance new-cache)
         (pointer-center old-cache) (pointer-center new-cache))))
    ;; Only update cache for pointers we're already tracking
    (when (contains? old-cache id)
      (swap! ui assoc-in [::pointer-cache id] entry))
    ;; Single pointer: normal drag
    (when (and (.-isPrimary e) (< (count old-cache) 2))
      (drag e))))

(defn on-pointer-up-bg [e]
  (swap! ui update ::pointer-cache dissoc (.-pointerId e))
  ;; Restore tool from momentary switch (pen eraser or middle-click pan)
  (when (or (pen-eraser? e) (= (.-button e) 1))
    (swap! ui #(assoc % ::tool (::prev-tool %))))
  ;; Primary pointer: normal drag end (only if not in gesture)
  (when (and (.-isPrimary e) (< (count (::pointer-cache @ui)) 2))
    (drag-end e)))

(defn remove-pointer
  "Remove pointer from cache. Used for pointercancel."
  [e]
  (swap! ui update ::pointer-cache dissoc (.-pointerId e)))

(defn on-pointer-down-element [k e]
  (.stopPropagation e)
  (let [id (.-pointerId e)
        entry {:x (.-clientX e) :y (.-clientY e)}
        is-dblclick (and (.-isPrimary e) (check-double-click e))]
    (swap! ui assoc-in [::pointer-cache id] entry)
    (cond
      ;; Double-click: handle and skip normal processing
      is-dblclick
      (double-click k e)

      (= 2 (count (::pointer-cache @ui)))
      (cancel)

      ;; Pen eraser: momentary eraser
      (pen-eraser? e)
      (swap! ui #(assoc % ::prev-tool (::tool %) ::tool ::eraser))

      ;; Middle click: momentary pan
      (= (.-button e) 1)
      (do (reset! last-coord [(.-clientX e) (.-clientY e)])
          (swap! ui #(assoc % ::prev-tool (::tool %) ::tool ::pan)))

      (.-isPrimary e)
      (drag-start k e))))

(defn on-pointer-move-element [k e]
  (let [old-cache (::pointer-cache @ui)
        id (.-pointerId e)
        entry {:x (.-clientX e) :y (.-clientY e)}]
    (when (and (= 2 (count old-cache)) (contains? old-cache id))
      (let [new-cache (assoc old-cache id entry)]
        (apply-pinch-delta
         (pointer-distance old-cache) (pointer-distance new-cache)
         (pointer-center old-cache) (pointer-center new-cache))))
    (when (contains? old-cache id)
      (swap! ui assoc-in [::pointer-cache id] entry))
    (when (and (.-isPrimary e) (< (count old-cache) 2))
      (eraser-drag k e))))

(defn get-model [layer model k v]
  (let [model-entry (get models (:type model)
                         {::bg #'circuit-shape
                          ::conn #'circuit-conn
                          ::sym #'circuit-sym})
        m (get model-entry layer)]
    ;; (assert m "no model")
    (cond
      (fn? m) ^{:key k} [m k v]
      (map? m) ^{:key k} [render-symbol-data m k v]
      (= layer ::bg) ^{:key k} [draw-background m k v]
      (= layer ::conn) (let [bg (::bg model-entry)
                             [w h] (when (vector? bg) bg)
                             size (if (and (number? w) (number? h))
                                    (+ 2 (max w h))
                                    (cm/pattern-size m))]
                          ^{:key k} [draw-pattern size m port k v])
      :else ^{:key k} [(fn [k _v] (println "invalid model for" k))])))

(defn end-ui [bg? selected dx dy]
  (letfn [(clean-selected [ui sch]
            (update ui ::selected
                    (fn [sel]
                      (into #{} (filter #(contains? sch %)) sel)))) (deselect [ui] (if bg? (assoc ui ::selected #{}) ui))
          (endfn [ui]
            (-> ui
                (assoc ::dragging nil
                       ::delta {:x 0 :y 0 :rx 0 :ry 0})
                deselect
                (clean-selected @schematic)))
          (round-coords [{x :x y :y :as dev}]
            (assoc dev
                   :x (math/round (+ x dx))
                   :y (math/round (+ y dy))))]
    (swap! ui endfn)
    (swap! schematic update-keys selected round-coords)
    (post-action!)))

(defn drag-end-box []
  (let [[x y] (::mouse-start @ui)
        {dx :x dy :y} (::delta @ui)
        x1 (math/floor (min x (+ x dx)))
        y1 (math/floor (min y (+ y dy)))
        x2 (math/floor (max x (+ x dx)))
        y2 (math/floor (max y (+ y dy)))
        [connidx bodyidx] @location-index
        sel (apply clojure.set/union
                   (for [x (range x1 (inc x2))
                         y (range y1 (inc y2))]
                     (get connidx [x y])))
        sel (apply clojure.set/union sel
                   (for [x (range x1 (inc x2))
                         y (range y1 (inc y2))]
                     (get bodyidx [x y])))]
    (println x1 y1 x2 y2 sel)
    (swap! ui assoc
           ::dragging nil
           ::delta {:x 0 :y 0 :rx 0 :ry 0}
           ::selected (set sel))))

(defn drag-end [e]
  (.stopPropagation e)
  (let [bg? (= (.-target e) (.-currentTarget e))
        selected (::selected @ui)
        {dx :x dy :y} @delta]
    (cond
      (and (= (::tool @ui) ::device)
           (= (.-button e) 0)) (commit-staged @staging)
      (= (::dragging @ui) ::box) (drag-end-box)
      (= (::dragging @ui) ::wire)
      (let [dev (update-keys @staging #{:rx :ry} math/round)]
        (when-not (same-tile? dev)
          (commit-staged dev)
          (swap! ui assoc ::staging nil ::dragging nil)))
      (= (::tool @ui) ::eraser) (post-action!)
      :else (end-ui bg? selected dx dy))))

(defn add-device [cell [x y] & args]
  (let [kwargs (apply array-map args)
        bg (get-in models [cell ::bg])
        [width height] (when (vector? bg) bg)
        mx (math/round (- x (/ (or width 0) 2) (if width 1 0)))
        my (math/round (- y (/ (or height 0) 2) (if height 1 0)))]
    (swap! ui assoc
           ::staging (into {:transform cm/IV, :type cell :x mx :y my} kwargs)
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
  (swap! snapshots assoc (str group "$snapshots" sep (.toISOString (js/Date.)))
         {:schematic @schematic
          :_attachments {:preview.svg {:content_type "image/svg+xml"
                                       :data (b64encode (str
                                                         "<?xml-stylesheet type=\"text/css\" href=\"https://nyancad.com/css/style.css\" ?>"
                                                         (.-outerHTML (js/document.querySelector ".mosaic-canvas"))))}}}))

(defn snapshot-timestamp
  "Extract timestamp from snapshot key. Key format: {group}$snapshots:{timestamp}"
  [snapshot-key]
  (second (clojure.string/split snapshot-key #":" 2)))

(defn download-snapshot [snapshot-key snapshot-data]
  (let [clean-data (dissoc snapshot-data :_rev :_id)
        json (js/JSON.stringify (clj->js clean-data) nil 2)
        blob (js/Blob. #js[json] #js{:type "application/json"})
        url (.createObjectURL js/URL blob)
        a (.createElement js/document "a")
        ;; Replace colons with underscores for cross-platform filename compatibility
        filename (clojure.string/replace (snapshot-timestamp snapshot-key) ":" "_")]
    (set! (.-href a) url)
    (set! (.-download a) (str filename ".json"))
    (.click a)
    (.revokeObjectURL js/URL url)))

(defn upload-snapshot [file]
  (.then (.text file)
         (fn [text]
           (let [data (-> (js/JSON.parse text)
                          (js->clj :keywordize-keys true)
                          (dissoc :_rev :_id))
                 filename (.-name file)
                 ;; Normalize underscores back to colons (filenames can't have colons)
                 timestamp (-> filename
                               (clojure.string/replace #"\.json$" "")
                               (clojure.string/replace "_" ":"))
                 snapshot-key (str group "$snapshots" sep timestamp)]
             (swap! snapshots assoc snapshot-key data)))))

(defn restore-snapshot [snapshot-data]
  (restore (:schematic snapshot-data))
  (reset! cm/modal-content nil))

(defn delete-snapshot [snapshot-key]
  (swap! snapshots dissoc snapshot-key))

(defn history-panel []
  (let [file-input-id "snapshot-upload-input"]
    [:div.history-panel
     [:div.modal-header
      [:h2 "Snapshot History"]
      [:button.close {:on-click #(reset! cm/modal-content nil)} "\u00D7"]]

     [:div.upload-section
      [:button {:on-click snapshot}
       [cm/save] " Take Snapshot"]
      [:input {:type "file"
               :id file-input-id
               :accept ".json"
               :style {:display "none"}
               :on-change (fn [e]
                            (when-let [file (-> e .-target .-files (aget 0))]
                              (upload-snapshot file)
                              (set! (.-value (.-target e)) "")))}]
      [:button {:on-click #(.click (js/document.getElementById file-input-id))}
       [cm/upload] " Upload JSON..."]]

     [:div.snapshot-list
      (let [snapshot-entries (filter (fn [[k v]]
                                       (and (not (or (= k "_rev") (= k "_id")))
                                            (:schematic v)))
                                     @snapshots)]
        (if (empty? snapshot-entries)
          [:p.empty "No snapshots yet. Click 'Take Snapshot' to create one."]
          (for [[k v] (reverse (sort-by key snapshot-entries))]
            ^{:key k}
            [:details.snapshot-item
             [:summary
              [:span.timestamp (snapshot-timestamp k)]
              [:span.actions
               [:button {:title "Download JSON"
                         :on-click (fn [e]
                                     (.stopPropagation e)
                                     (download-snapshot k v))}
                [cm/download]]
               [:button {:title "Restore this snapshot"
                         :on-click (fn [e]
                                     (.stopPropagation e)
                                     (restore-snapshot v))}
                [cm/history]]
               [:button {:title "Delete snapshot"
                         :on-click (fn [e]
                                     (.stopPropagation e)
                                     (delete-snapshot k))}
                [cm/delete]]]]
             [:div.preview
              (if-let [svg-data (get-in v [:_attachments :preview.svg :data])]
                [:object {:data (cm/base64->blob-url svg-data "image/svg+xml")
                          :type "image/svg+xml"}
                 "Snapshot preview"]
                [:p.empty "No preview"])]])))]]))

(defn show-history-panel []
  (reset! cm/modal-content [history-panel]))

(defn model-selector-popup
  "Popup for selecting a device model with tag tree and search"
  [device-type on-select]
  (let [selected (r/atom nil)]
    (fn [device-type on-select]
      (let [;; Filter models by device type first
            type-models (into {} (filter (fn [[_ m]] (= (:type m "ckt") device-type)) @modeldb))
            ;; Build tag tree from type-filtered models
            trie (cm/build-tag-index type-models)
            ;; Filter by tags and search text
            filtered (cm/filter-models type-models @model-popup-category @model-popup-filter)]
        [:div.model-selector-popup
         [:h3 "Select Model"]
         ;; Search input
         [:input.model-search {:type "text"
                               :placeholder "Filter models..."
                               :value @model-popup-filter
                               :on-change #(reset! model-popup-filter (.. % -target -value))}]
         [:div.model-popup-content
          ;; Tag tree (left)
          [:div.model-categories
           (if (seq trie)
             [cm/tag-tree model-popup-category trie]
             [:div.empty "No categories"])]
          ;; Model list (right)
          [:div.model-list
           [cm/model-list selected filtered nil nil
            #(when-not (some #{%} @model-popup-category)
               (swap! model-popup-category conj %))]]]
         ;; Buttons
         [:div.model-popup-buttons
          [:button {:on-click #(reset! cm/modal-content nil)} "Cancel"]
          [:button.primary {:on-click #(do (on-select @selected)
                                           (reset! cm/modal-content nil))
                            :disabled (nil? @selected)} "Select"]]]))))

(defn deviceprops [key]
  (let [props (r/cursor schematic [key :props])
        device-type (r/cursor schematic [key :type])
        model (r/cursor schematic [key :model])
        name (r/cursor schematic [key :name])]
    (fn [key]
      (let [model-def (get @modeldb (cm/model-key @model))]
        [:<>
         [:h1 (or @name key)]
         [:div.properties
          (when (and (seq @model) (not (cm/has-code-models? model-def)))
            [:a {:href "#" :on-click #(do (.preventDefault %) (open-schematic @model))} "Edit"])
          [:label {:for "name" :title "Instance name"} "name"]
          [:input {:id "name"
                   :type "text"
                   :default-value @name
                   :on-change (debounce #(do (reset! name (.. % -target -value)) (post-action!)))}]
          [:label {:for "model" :title "Device model"} "model"]
          [:div.model-field
           [:input {:id "model"
                    :type "text"
                    :read-only true
                    :value (or (:name model-def) (when (seq @model) @model) "Ideal")
                    :title (or @model "No model selected")}]
           [:button {:on-click #(do
                                  (reset! model-popup-filter "")
                                  (reset! model-popup-category [])
                                  (reset! cm/modal-content
                                          [model-selector-popup @device-type
                                           (fn [model-id]
                                             (reset! model (if model-id (cm/bare-id model-id) ""))
                                             (post-action!))]))}
            [cm/search]]]
          ; Get properties from built-in device and model
          (let [device-props (::props (get models @device-type) [])
                model-props (:props model-def [])]
            [cm/recursive-editor (concat device-props model-props) props post-action!])
          [:label {:for "template" :title "Template to display"} "Text"]
          [:textarea {:id "template"
                      :default-value (get-in @schematic [key :template] (::template (get models @device-type)))
                      :on-change (debounce #(do (swap! schematic assoc-in [key :template] (.. % -target -value)) (post-action!)))}]]]))))

(defn copy []
  (let [sel @selected
        sch @schematic
        devs (map (comp #(dissoc % :_rev :_id) sch) sel)]
    (swap! local assoc (str "local" sep "clipboard") {:data devs})))

(defn cut []
  (copy)
  (delete-selected))

(defn paste []
  (let [devmap (->> (get-in @local [(str "local" sep "clipboard") :data])
                    (group-by :type)
                    (map (fn [[typ devices]]
                           (map (fn [name dev]
                                  (let [id (str group sep name)
                                        display-name (if (= typ "port") (:name dev) name)]
                                    [id (assoc dev :name display-name)]))
                                (make-names typ)
                                devices)))
                    (into {} cat))]
    (go
      (swap! schematic into devmap)
      (<! (done? schematic))
      (swap! ui assoc
             ::dragging ::device
             ::selected (set (keys devmap)))
      (post-action!))))

(defonce ^:private theme-clicks (atom []))

(defn- dark-mode?
  ([] (dark-mode? @theme))
  ([t]
   (case t
     "dark" true
     "light" false
     (.-matches (js/window.matchMedia "(prefers-color-scheme: dark)")))))

(defn toggle-theme! []
  (let [now (.now js/Date)
        clicks (swap! theme-clicks #(conj (filterv (fn [t] (> t (- now 2000))) %) now))
        eyesore? (>= (count clicks) 5)]
    (when eyesore? (reset! theme-clicks []))
    (swap! ui update ::theme
           #(if eyesore? "eyesore"
                (if (dark-mode? %) "light" "dark")))))

(defn menu-items []
  [:<>
   [:div.primary
    [cm/radiobuttons tool
   ; label, key, title
     [[[cm/cursor] ::cursor "Cursor [esc]"]
      [[cm/wire] ::wire "Wire [w]"]
      [[cm/eraser] ::eraser "Eraser [e]"]
      [[cm/move] ::pan "Pan [space]"]
      [[cm/probe] ::probe "Probe nodes in a connected simulator"]]
     nil nil cancel]
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
     [cm/redoi]]]
   [:div.status
    [cm/renamable (r/cursor modeldb [(cm/model-key group) :name]) "Untitled"]
    (if @syncactive
      [:span.syncstatus.active {:title "saving changes"} [cm/sync-active]]
      [:span.syncstatus.done   {:title "changes saved"} [cm/sync-done]])]

   [:div.secondary
    [secondary-menu-items notebook-popped-out]
    [:a {:title "Toggle light/dark theme"
         :on-click #(toggle-theme!)}
     (if (dark-mode?) [cm/sun-icon] [cm/moon-icon])]
    [:a {:title "Keyboard shortcuts & help"
         :on-click cm/show-onboarding!}
     [cm/help]]
    [:a {:title "Snapshot History"
         :on-click show-history-panel}
     [cm/history]]]])

(defn device-active [cell]
  (when (= cell (:type @staging))
    "active"))

(defn variant-tray [& _variants]
  (let [active (r/atom 0)
        timeout (r/atom nil)]
    (fn [& variants]
      (let [varwraps (map-indexed
                      (fn [i var]
                        [:span {:key i
                                :on-pointer-up #(reset! active i)}
                         var]) variants)]
        [:details
         {;:on-toggle js/console.log
          :on-pointer-down
          (fn [e]
            (let [ct (.-currentTarget e)]
              (reset! timeout (js/setTimeout
                               #(set! (.-open ct) true)
                               300))))
          :on-pointer-up
          (fn [e]
            (js/clearTimeout @timeout)
            (set! (.. e -currentTarget -open) false))}
         [:summary {:on-click #(.preventDefault %)}
          (nth variants @active)]
         [:span.tray
          (take @active varwraps)
          (drop (inc @active) varwraps)]]))))

(defn add-gnd [coord]
  (add-device "port" coord
              :variant "ground"
              :transform (cm/transform-vec (.rotate cm/I -90))
              :name "GND"))

(defn add-supply [coord]
  (add-device "port" coord
              :variant "supply"
              :transform (cm/transform-vec (.rotate cm/I 90))
              :name "VDD"))

(defn add-label [coord]
  (add-device "port" coord
              :variant "text"
              :transform (cm/transform-vec (.rotate cm/I 90))))

(defn device-tray []
  [:<>
   [variant-tray
    [:button {:title "Add port [p]"
              :class (device-active "port")
              :on-pointer-up #(add-device "port" (cm/viewbox-coord %))}
     [cm/device-icon "port"]]
    [:button {:title "Add wire label [t]"
              :class (device-active "port")
              :on-pointer-up #(add-label (cm/viewbox-coord %))}
     [cm/namei]]
    [:button {:title "Add ground [g]"
              :class (device-active "port")
              :on-pointer-up #(add-gnd (cm/viewbox-coord %))}
     [cm/device-icon "ground"]]
    [:button {:title "Add power supply [shift+p]"
              :class (device-active "port")
              :on-pointer-up #(add-supply (cm/viewbox-coord %))}
     [cm/device-icon "supply"]]
    [:button {:title "Add text area [shift+t]"
              :class (device-active "port")
              :on-pointer-up #(add-device "text" (cm/viewbox-coord %))}
     [cm/text]]]
   [:button {:title "Add resistor [r]"
             :class (device-active "resistor")
             :on-pointer-up #(add-device "resistor" (cm/viewbox-coord %))}
    [cm/device-icon "resistor"]]
   [:button {:title "Add inductor [l]"
             :class (device-active "inductor")
             :on-pointer-up #(add-device "inductor" (cm/viewbox-coord %))}
    [cm/device-icon "inductor"]]
   [:button {:title "Add capacitor [c]"
             :class (device-active "capacitor")
             :on-pointer-up #(add-device "capacitor" (cm/viewbox-coord %))}
    [cm/device-icon "capacitor"]]
   [:button {:title "Add diode [d]"
             :class (device-active "diode")
             :on-pointer-up #(add-device "diode" (cm/viewbox-coord %))}
    [cm/device-icon "diode"]]
   [variant-tray
    [:button {:title "Add voltage source [v]"
              :class (device-active "vsource")
              :on-pointer-up #(add-device "vsource" (cm/viewbox-coord %))}
     [cm/device-icon "vsource"]]
    [:button {:title "Add current source [i]"
              :class (device-active "isource")
              :on-pointer-up #(add-device "isource" (cm/viewbox-coord %))}
     [cm/device-icon "isource"]]]
   [variant-tray
    [:button {:title "Add N-channel mosfet [m]"
              :class (device-active "nmos")
              :on-pointer-up #(add-device "nmos" (cm/viewbox-coord %))}
     [cm/device-icon "nmos"]]
    [:button {:title "Add P-channel mosfet [shift+m]"
              :class (device-active "pmos")
              :on-pointer-up #(add-device "pmos" (cm/viewbox-coord %))}
     [cm/device-icon "pmos"]]
    [:button {:title "Add NPN BJT [b]"
              :class (device-active "npn")
              :on-pointer-up #(add-device "npn" (cm/viewbox-coord %))}
     [cm/device-icon "npn"]]
    [:button {:title "Add PNP BJT [shift+b]"
              :class (device-active "pnp")
              :on-pointer-up #(add-device "pnp" (cm/viewbox-coord %))}
     [cm/device-icon "pnp"]]]
   [variant-tray
    [:button {:title "Add subcircuit [x]"
              :class (device-active "ckt")
              :on-pointer-up #(add-device "ckt" (cm/viewbox-coord %))}
     [cm/chip]]
    [:button {:title "Add amplifier [a]"
              :class (device-active "amp")
              :on-pointer-up #(add-device "amp" (cm/viewbox-coord %))}
     [cm/amp-icon]]]
   [variant-tray
    [:button {:title "Add ring resonator"
              :class (device-active "ring-single")
              :on-pointer-up #(add-device "ring-single" (cm/viewbox-coord %))}
     [cm/device-icon "ring-single"]]
    [:button {:title "Add straight waveguide"
              :class (device-active "straight")
              :on-pointer-up #(add-device "straight" (cm/viewbox-coord %))}
     [cm/device-icon "straight"]]
    [:button {:title "Add bend"
              :class (device-active "bend")
              :on-pointer-up #(add-device "bend" (cm/viewbox-coord %))}
     [cm/device-icon "bend"]]
    [:button {:title "Add S-bend"
              :class (device-active "sbend")
              :on-pointer-up #(add-device "sbend" (cm/viewbox-coord %))}
     [cm/device-icon "sbend"]]
    [:button {:title "Add taper"
              :class (device-active "taper")
              :on-pointer-up #(add-device "taper" (cm/viewbox-coord %))}
     [cm/device-icon "taper"]]
    [:button {:title "Add transition"
              :class (device-active "transition")
              :on-pointer-up #(add-device "transition" (cm/viewbox-coord %))}
     [cm/device-icon "transition"]]
    [:button {:title "Add terminator"
              :class (device-active "terminator")
              :on-pointer-up #(add-device "terminator" (cm/viewbox-coord %))}
     [cm/device-icon "terminator"]]
    [:button {:title "Add crossing"
              :class (device-active "crossing")
              :on-pointer-up #(add-device "crossing" (cm/viewbox-coord %))}
     [cm/device-icon "crossing"]]
    [:button {:title "Add double ring resonator"
              :class (device-active "ring-double")
              :on-pointer-up #(add-device "ring-double" (cm/viewbox-coord %))}
     [cm/device-icon "ring-double"]]
    [:button {:title "Add spiral"
              :class (device-active "spiral")
              :on-pointer-up #(add-device "spiral" (cm/viewbox-coord %))}
     [cm/device-icon "spiral"]]
    [:button {:title "Add 1x2 splitter"
              :class (device-active "splitter-1x2")
              :on-pointer-up #(add-device "splitter-1x2" (cm/viewbox-coord %))}
     [cm/device-icon "splitter-1x2"]]
    [:button {:title "Add directional coupler"
              :class (device-active "coupler")
              :on-pointer-up #(add-device "coupler" (cm/viewbox-coord %))}
     [cm/device-icon "coupler"]]
    [:button {:title "Add ring coupler"
              :class (device-active "coupler-ring")
              :on-pointer-up #(add-device "coupler-ring" (cm/viewbox-coord %))}
     [cm/device-icon "coupler-ring"]]
    [:button {:title "Add MMI 1x2"
              :class (device-active "mmi-1x2")
              :on-pointer-up #(add-device "mmi-1x2" (cm/viewbox-coord %))}
     [cm/device-icon "mmi-1x2"]]
    [:button {:title "Add MMI 2x2"
              :class (device-active "mmi-2x2")
              :on-pointer-up #(add-device "mmi-2x2" (cm/viewbox-coord %))}
     [cm/device-icon "mmi-2x2"]]
    [:button {:title "Add MZI 1x2"
              :class (device-active "mzi-1x2")
              :on-pointer-up #(add-device "mzi-1x2" (cm/viewbox-coord %))}
     [cm/device-icon "mzi-1x2"]]
    [:button {:title "Add MZI 2x2"
              :class (device-active "mzi-2x2")
              :on-pointer-up #(add-device "mzi-2x2" (cm/viewbox-coord %))}
     [cm/device-icon "mzi-2x2"]]
    [:button {:title "Add LED/laser diode"
              :class (device-active "led")
              :on-pointer-up #(add-device "led" (cm/viewbox-coord %))}
     [cm/device-icon "led"]]
    [:button {:title "Add photodiode"
              :class (device-active "photodiode")
              :on-pointer-up #(add-device "photodiode" (cm/viewbox-coord %))}
     [cm/device-icon "photodiode"]]
    [:button {:title "Add PN modulator"
              :class (device-active "modulator")
              :on-pointer-up #(add-device "modulator" (cm/viewbox-coord %))}
     [cm/device-icon "modulator"]]
    [:button {:title "Add grating coupler"
              :class (device-active "grating-coupler")
              :on-pointer-up #(add-device "grating-coupler" (cm/viewbox-coord %))}
     [cm/device-icon "grating-coupler"]]]])

(defn schematic-elements [schem]
  [:<>
   (for [[k v] schem
         :when (= "wire" (:type v))]
     (get-model ::bg v k v))
   (for [[k v] schem
         :when (not= "wire" (:type v))]
     (get-model ::bg v k v))
   (for [[k v] schem]
     (get-model ::sym v k v))
   (for [[k v] schem]
     (get-model ::conn v k v))])

(defn build-wire-errors
  "Detect type mismatches and photonic forks.
   Checks both device port types and wire types at each connection point."
  [sch connidx point-types wire-types]
  (reduce-kv
   (fn [errors pt ids]
     (let [;; Collect types at this point: from device ports and from wires
           port-types (get-in point-types [pt :types])
           wire-type-set (into #{}
                               (keep (fn [id]
                                       (when (= "wire" (:type (get sch id)))
                                         (get wire-types id))))
                               ids)
           all-types (into (or port-types #{}) wire-type-set)
           n (count (remove #(= (get-in sch [% :variant]) "text") ids))
           mismatch? (> (count all-types) 1)
           fork? (and (contains? all-types "photonic") (> n 2))]
       (cond-> errors
         mismatch? (conj {:type :mismatch :pos pt})
         fork? (conj {:type :fork :pos pt}))))
   [] connidx))

(def wire-errors
  (r/track #(build-wire-errors @schematic (first @location-index) @point-type-index @wire-type-index)))

(defn point-connection-type
  "Get the connection type at a point from device ports and wire types."
  [x y ids]
  (let [port-types (get-in @point-type-index [[x y] :types])
        wire-type-set (into #{}
                            (keep #(get @wire-type-index %))
                            ids)
        all-types (into (or port-types #{}) wire-type-set)]
    (first all-types)))

(defn schematic-dots []
  (let [icon-size (/ grid-size 2.5)
        icon-offset (- (/ grid-size 2) (/ icon-size 2))
        error-points (into #{} (map :pos) @wire-errors)]
    [:<>
     (doall
      (for [[[x y] ids] (first @location-index)
            :let [n (count (remove #(= (get-in @schematic [% :variant]) "text") ids))
                  nc? (< n 2)
                  pt (when nc? (point-connection-type x y ids))]
            :when (and (not= n 2)
                       (not (contains? error-points [x y])))]
        [:circle
         {:key [x y]
          :class [(if nc? "nc" "wire")
                  pt]
          :cx (* grid-size (+ x 0.5))
          :cy (* grid-size (+ y 0.5))
          :r (/ grid-size 10)}]))
     (doall
      (for [{err-type :type [x y] :pos} @wire-errors]
        [:g.wire-error
         {:key [:error x y err-type]
          :class (case err-type :mismatch "type-error" :fork "fork-error")
          :transform (str "translate(" (+ (* grid-size x) icon-offset) "," (+ (* grid-size y) icon-offset) ")")}
         [:title (case err-type
                   :mismatch "Type mismatch: incompatible domains meet"
                   :fork "Photonic fork: waveguides cannot branch")]
         [:circle {:cx (/ icon-size 2) :cy (/ icon-size 2) :r (/ icon-size 3) :fill "white"}]
          [cm/exclamation-diamond {:size icon-size}]]))]))

(defn tool-elements []
  (let [{sel ::selected
         dr ::dragging
         v ::staging
         tool ::tool
         {x :x y :y} ::delta
         [sx sy] ::mouse-start} @ui
        vx (* grid-size (math/round x))
        vy (* grid-size (math/round y))]
    (cond
      ;; Only render staging when actively placing a device or drawing a wire
      (and v (or (= tool ::device)
                 (= (:type v) "wire")))
      [:g.toolstaging
       (get-model ::bg v ::stagingbg v)
       (get-model ::sym v ::stagingsym v)
       (get-model ::conn v ::stagingconn v)]
      (= dr ::box) [:rect.select
                    {:x (* (min sx (+ sx x)) grid-size)
                     :y (* (min sy (+ sy y)) grid-size)
                     :width (abs (* x grid-size))
                     :height (abs (* y grid-size))}]
      (and sel dr) [:g.staging {:style {:transform (str "translate(" vx "px, " vy "px)")}}
                    [schematic-elements
                     (let [schem @schematic]
                       (map #(vector % (get schem %)) sel))]])))

(defn- set-color-scheme!
  "Set color-scheme on :root so light-dark() CSS function responds correctly."
  [scheme]
  (.. js/document -documentElement -style (setProperty "color-scheme" scheme)))

(defn- theme-attrs
  "CSS attributes for the current theme. Returns {:class ...}"
  []
  (case @theme
    "eyesore" (do (set-color-scheme! "light") {:class "eyesore dark-cursors"})
    "light"   (do (set-color-scheme! "light") {})
    "dark"    (do (set-color-scheme! "dark") {:class "dark-cursors"})
    (do (set-color-scheme! "light dark") {})))

(defn schematic-ui []
  [:div.mosaic-container (theme-attrs)
   [:div.menu.chrome
    [menu-items]]
   [:div.content-wrapper
    [:div.content
     [:div.devicetray.chrome
      [device-tray]]
     (when-let [sel (seq @selected)]
       [:div.sidebar
        (doall (for [key sel]
                 ^{:key key} [deviceprops key]))])
     [:svg.mosaic-canvas (merge (theme-attrs)
                          {:xmlns "http://www.w3.org/2000/svg"
                           :height "100%"
                           :width "100%"
                           :class [@tool] ; for export
                           :view-box @zoom
                           :on-wheel scroll-zoom
                           :on-pointer-down on-pointer-down-bg
                           :on-pointer-up on-pointer-up-bg
                           :on-pointer-move on-pointer-move-bg
                           :on-pointer-cancel remove-pointer
                           :on-context-menu context-menu})
      [:defs
       [:pattern {:id "gridfill",
                  :pattern-units "userSpaceOnUse"
                  :width grid-size
                  :height grid-size}
        [:line.grid {:x1 0 :y1 0 :x2 grid-size :y2 0}]
        [:line.grid {:x1 0 :y1 0 :x2 0 :y2 grid-size}]]
]
      [:rect {:fill "url(#gridfill)"
              :on-pointer-up on-pointer-up-bg
              :x (* -500 grid-size)
              :y (* -500 grid-size)
              :width (* 1000 grid-size)
              :height (* 1000 grid-size)}]
      [schematic-elements @schematic]
      [schematic-dots]
      [tool-elements]]]
    [notebook-panel notebook-popped-out]]
   [cm/contextmenu]
   [cm/modal]])

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
                #{:a} #(add-device "amp" (::mouse @ui))
                #{:p} #(add-device "port" (::mouse @ui))
                #{:g} #(add-gnd (::mouse @ui))
                #{:shift :p} #(add-supply (::mouse @ui))
                #{:t} #(add-device "text" (::mouse @ui))
                #{:shift :t} #(add-label (::mouse @ui))
                #{:backspace} delete-selected
                #{:delete} delete-selected
                #{:w} #(cancel ::wire)
                #{:e} #(cancel ::eraser)
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
             (.querySelector js/document ".mosaic-app.mosaic-editor")))

(defn ^:export init []
  (init-extra!)
  (render))

(defn ^:export clear []
  (swap! schematic #(apply dissoc %1 %2) (set (keys @schematic))))
