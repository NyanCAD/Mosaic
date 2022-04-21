(ns nyancad.mosaic.editor
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [shadow.resource :as rc]
            [nyancad.hipflask :refer [pouch-atom pouchdb update-keys sep watch-changes]]
            [clojure.spec.alpha :as s]
            [cljs.core.async :refer [go go-loop <!]]
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
(def dburl (if js/window.dburl (.-href (js/URL. dbname js/window.dburl)) dbname))
(def sync (or (.get params "sync") nil))
(defonce db (pouchdb dburl))
(defonce schematic (pouch-atom db group (r/atom {})))
(set-validator! (.-cache schematic)
                #(or (s/valid? :nyancad.mosaic.common/schematic %) (.log js/console (pr-str %) (s/explain-str :nyancad.mosaic.common/schematic %))))

(defonce modeldb (pouch-atom db "models" (r/atom {})))
(defonce snapshots (pouch-atom db "snapshots" (r/atom {})))
(defonce watcher (watch-changes db schematic modeldb snapshots))
(defonce local (pouch-atom (pouchdb "local") "local"))

(defn initial [cell]
  (case cell
    "resistor" "R"
    "inductor" "L"
    "capacitor" "C"
    "diode" "D"
    "vsource" "V"
    "isource" "I"
    "npn" "Q"
    "pnp" "Q"
    "pmos" "M"
    "nmos" "M"
    "wire" "W"
    "port" "P"
    "X"))

(defn make-name [base]
  (let [ids (map #(str group sep (initial base) %) (next (range)))]
    (first (remove @schematic ids))))

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
(s/def ::dragging (s/nilable #{::wire ::device ::view ::box}))
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
  (let [del (reduce disj (set (keys @schematic)) (keys state))
        norev (reduce #(update %1 %2 dissoc :_rev) state (keys state))]
    (go
      (remove-watch schematic ::undo)
      (<! (swap! schematic into norev)) ; update
      (<! (swap! schematic #(apply dissoc %1 %2) del)) ; delete
      (add-watch schematic ::undo #(cm/newdo undotree %4)))))

(defn undo-schematic []
  (when-let [st (cm/undo undotree)]
    (restore st)))

(defn redo-schematic []
  (when-let [st (cm/redo undotree)]
    (restore st)))

(declare drag-start eraser-drag)

(defn device [size k v & elements]
  (assert (js/isFinite size))
  (into [:g.device {:on-mouse-down (fn [e] (drag-start k e))
                    :on-mouse-move (fn [e] (eraser-drag k e))
                    :style {:transform (.toString (.translate (transform (:transform v cm/IV)) (* (:x v) grid-size) (* (:y v) grid-size)))
                            :transform-origin (str (* (+ (:x v) (/ size 2)) grid-size) "px "
                                                   (* (+ (:y v) (/ size 2)) grid-size) "px")}
                    :class [(:cell v) (:variant v) (when (contains? @selected k) :selected)]}]
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
    [:g.wire {:on-mouse-down #(drag-start key %)
              :on-mouse-move #(eraser-drag key %)
              :class (when (contains? @selected key) :selected)}
     ; TODO drag-start ::wire nodes (with reverse) 
     [:line.wirebb {:x1 (* (+ x 0.5) grid-size)
                    :y1 (* (+ y 0.5) grid-size)
                    :x2 (* (+ x rx 0.5) grid-size)
                    :y2 (* (+ y ry 0.5) grid-size)}]
     [:line.wire {:x1 (* (+ x 0.5) grid-size)
                  :y1 (* (+ y 0.5) grid-size)
                  :x2 (* (+ x rx 0.5) grid-size)
                  :y2 (* (+ y ry 0.5) grid-size)}]]))

(defn device-label [dev width]
  [:text.identifier
   {:text-anchor "left"
    :dominant-baseline "bottom"
    :transform (-> (:transform dev)
                   transform
                   (.translate (* grid-size (/ width -2)) (* grid-size (/ width -2)))
                   .inverse
                   (.translate (* grid-size 0.05) (* grid-size -0.65))
                   .toString)}
    (:name dev)])

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
     [lines [[[0.5 0.5]
              [0.3 0.3]
              [0 0.3]
              [0 0.7]
              [0.3 0.7]
              [0.5 0.5]]]])
   [:text {:text-anchor (case (js/Math.round (first (:transform label)))
                               1 "end"
                               -1 "start"
                               "middle")
           :dominant-baseline "middle"
           :transform (-> (:transform label)
                          transform
                          (.translate (/ grid-size 3) (/ grid-size -2))
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
     [device-label v 3]
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
     [device-label v 3]
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
     [device-label v 3]
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
     [device-label v 3]
     [lines shape]]))

(defn inductor-sym [k v]
  (let [shape [[[1.5 0.5]
                [1.5 1.1]]
               [[1.5 1.9]
                [1.5 2.5]]]]
    [device 3 k v
     [device-label v 3]
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
     [device-label v 3]
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
     [device-label v 3]
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
     [device-label v 3]
     [lines shape]
     [arrow 1.5 1.6 0.2 270]]))

(defn circuit-shape [k v]
  (let [model (:cell v)
        size (get-in @modeldb [(str "models" sep model) :bg] [1 1])]
    (draw-background size k v)))

(defn circuit-conn [k v]
  (let [model (:cell v)
        [width height] (get-in @modeldb [(str "models" sep model) :bg] [1 1])
        pattern (get-in @modeldb [(str "models" sep model) :conn] [])]
    (draw-pattern (+ 2 (max width height)) pattern
                  port k v)))

(defn ckt-url [cell model]
  (str "?" (.toString (js/URLSearchParams. #js{:schem (str cell "$" model) :db dbname :sync sync}))))

(defn circuit-sym [k v]
  (let [cell (:cell v)
        model (get-in v [:props :model])
        [width height] (get-in @modeldb [(str "models" sep cell) :bg] [1 1])
        sym (get-in @modeldb [(str "models" sep cell) :sym])]
    [device (+ 2 (max width height)) k v
     (if sym
       [:image {:href sym
                :on-mouse-down #(.preventDefault %) ; prevent dragging the image
                :on-double-click #(.assign js/window.location (ckt-url cell model))}]
       [:<>
        [lines (for [x (range 1.5 (+ 1.5 width))] [[x 0.5] [x 1.0]])]
        [lines (for [y (range 1.5 (+ 1.5 height))] [[0.5 y] [1.0 y]])]
        [lines (for [x (range 1.5 (+ 1.5 width))] [[x (+ height 1.5)] [x (+ height 1)]])]
        [lines (for [y (range 1.5 (+ 1.5 height))] [[(+ width 1.5) y] [(+ width 1) y]])]
        [:rect.outline
         {:x grid-size :y grid-size
          :width (* grid-size width)
          :height (* grid-size height)}]])]))

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

(defn exrange [start width]
  (next (take-while #(not= % (+ start width))
                    (iterate #(+ % (cm/sign width)) start))))

(defn wire-locations [{:keys [:_id :x :y :rx :ry]}]
  [[[x y] [(+ x rx) (+ y ry)]]
   (cond
     (= rx 0) (map #(vector x %) (exrange y ry))
     (= ry 0) (map #(vector % y) (exrange x rx))
     :else [])])

(defn builtin-locations [{:keys [:_id :x :y :cell :transform]}]
  (let [mod (get models cell)
        conn (::conn mod)
        [w h] (::bg mod)]
    [(rotate-shape conn transform x y)
     (rotate-shape (for [x (range w) y (range h)]
                     [(inc x) (inc y) "%"])
                   transform x y)]))

(defn circuit-locations [{:keys [:_id :x :y :cell :transform]}]
  (let [mod (get @modeldb (str "models" sep cell))
        conn (:conn mod)
        [w h] (:bg mod)]
    [(rotate-shape conn transform x y)
     (rotate-shape (for [x (range w) y (range h)]
                     [(inc x) (inc y) "%"])
                   transform x y)]))

(defn device-locations [dev]
  (let [cell (:cell dev)]
    (cond
      (= cell "wire") (wire-locations dev)
      (contains? models cell) (builtin-locations dev) 
      :else (circuit-locations dev))))

(defn build-location-index [sch]
  (loop [connidx {} bodyidx {} [dev & other] (vals sch)]
    (let [[connloc bodyloc] (device-locations dev)
          nconnidx (reduce #(update %1 %2 sconj (:_id dev)) connidx connloc)
          nbodyidx (reduce #(update %1 %2 sconj (:_id dev)) bodyidx bodyloc)]
      (if other
        (recur nconnidx nbodyidx other)
        [nconnidx nbodyidx]))))

; [conn body]
(def location-index (r/track #(build-location-index @schematic)))

(defn build-wire-split-index [[connidx bodyidx]]
  (reduce (fn [result [key val]]
            (if (contains? connidx key)
              (->> val
                   (filter #(= "wire" (get-in @schematic [% :cell])))
                   (reduce #(update %1 %2 cm/ssconj key) result))
              result))
          {} bodyidx))

(defn split-wire [wirename coords]
  (let [{:keys [:x :y :rx :ry]} (get @schematic wirename)
        x2 (+ x rx)
        y2 (+ y ry)
        allcoords (cm/ssconj coords [x y] [x2 y2])
        widx (first @location-index)
        wires (loop [w wirename
                     [[x1 y1] & [[x2 y2] & _ :as other]] allcoords
                     schem {}]
                (if other
                  (recur (name (gensym wirename)) other
                        ;; if the start and end point cover the same device, skip
                         (if (empty? (clojure.set/intersection (get widx [x1 y1]) (get widx [x2 y2])))
                           (assoc schem w
                                  {:cell "wire" :transform cm/IV
                                   :x x1 :y y1 :rx (- x2 x1) :ry (- y2 y1)})
                           schem))
                  schem))]
    (swap! schematic (partial merge-with merge) wires)))

(defn split-wires []
  (remove-watch schematic ::split) ; don't recursively split
  (remove-watch schematic ::undo) ; don't add splitting to undo tree
  (go (doseq [[w coords] (build-wire-split-index @location-index)]
        (<! (split-wire w coords)))
      (add-watch schematic ::undo #(cm/newdo undotree %4))
      (add-watch schematic ::split split-wires)))

(add-watch schematic ::split split-wires)


(defn viewbox-coord [e]
  (let [^js el (js/document.getElementById "mosaic_canvas")
        m (.inverse (.getScreenCTM el))
        p (point (.-clientX e) (.-clientY e))
        tp (.matrixTransform p m)]
    [(/ (.-x tp) grid-size) (/ (.-y tp) grid-size)]))

(def last-coord (atom [0 0]))
(defn viewbox-movement [e]
  (let [^js el (js/document.getElementById "mosaic_canvas")
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

(defn scroll-zoom [e]
  (let [[x y] (viewbox-coord e)]
    (zoom-schematic (cm/sign (.-deltaY e)) (* x grid-size) (* y grid-size))))

(defn button-zoom [dir]
  (let [[x y w h] (::zoom @ui)]
    (zoom-schematic dir
                    (+ x (/ w 2))
                    (+ y (/ h 2)))))

(defn commit-staged [dev]
  (let [id (make-name (:cell dev))
        name (last (clojure.string/split id sep))]
    (swap! schematic assoc id
           (if (:name dev)
             dev
             (assoc dev :name name)))))

(defn transform-selected [tf]
  (let [f (comp transform-vec tf transform)]
    (if @staging
      (swap! staging
             update :transform f)
      (swap! schematic update-keys @selected
             update :transform f))))

(defn delete-selected []
  (let [selected (::selected @ui)]
    (swap! ui assoc ::selected #{})
    (swap! schematic #(apply dissoc %1 %2) selected)))

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

(defn drag-wire [^js e]
  (let [[x y] (viewbox-coord e)]
    (swap! staging
           (fn [d]
             (let [rx (- x (:x d) 0.5)
                   ry (- y (:y d) 0.5)]
               (cond
                 (.-ctrlkey e) (assoc d :rx rx :ry ry)
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
    ::box (drag-device e)
    nil))

; updating the PouchDB atom is a fast but async operation
; while it is in flight, drag events for the same device could stack up
; this has been shown to peg the CPU
(def inflight-deletions (atom #{}))
(defn eraser-drag [k e]
  (when (and (= (.-buttons e) 1)
             (= @tool ::eraser)
             (not (contains? @inflight-deletions k)))
    (go (swap! inflight-deletions conj k)
        (<! (swap! schematic dissoc k))
        (swap! inflight-deletions disj k))))

(defn drag [e]
  ;; store mouse position for use outside mouse events
  ;; keyboard shortcuts for example
  (swap! ui assoc ::mouse (viewbox-coord e))
  (case @tool
    ::wire (wire-drag e)
    ::pan (when (> (.-buttons e) 0) (drag-view e))
    ::device (if (= (::dragging @ui) ::view) (drag-view e) (drag-staged-device e))
    (cursor-drag e)))

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
          [conn body] @location-index
          on-port (or (contains? conn [x y])
                      (contains? body [x y]))
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

(defn select-connected []
  (let [schem @schematic
        wire (some schem @selected)
        wire? (fn [wirename] (let [wire (get schem wirename)]
                               (and (= (:cell wire) "wire") wire)))
        wire-ports (fn [init wire]
                     (if (= (:cell wire) "wire")
                       (conj init [(:x wire) (:y wire)]
                             [(+ (:x wire) (:rx wire)) (+ (:y wire) (:ry wire))])
                       init))]
    (when wire
      (loop [ports (wire-ports #{} wire)
             sel #{(:_id wire)}]
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

(defn cancel []
  (let [uiv @ui]
    (if (and (::staging uiv) (= (::tool uiv) ::wire))
      (swap! ui assoc
             ::dragging nil
             ::staging nil)
      (swap! ui assoc
             ::dragging nil
             ::tool ::cursor
             ::staging nil))))

(defn drag-start [k e]
  (swap! ui assoc ::mouse-start (viewbox-coord e))
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
                   ::pan ::view)))]
    ; skip the mouse down when initiated from a toolbar button
    ; only when primary mouse click
    (when (and (not= (::tool uiv) ::device)
               (= (.-button e) 0))
      (.stopPropagation e) ; prevent bg drag
      (if (= (.-detail e) 1)
        (case (::tool uiv)
          ::wire (add-wire (viewbox-coord e) (nil? (::dragging uiv)))
          ::eraser (eraser-drag k e)
          (swap! ui (fn [ui]
                      (-> ui
                          (update ::selected update-selection)
                          (drag-type)))))
        (case (::tool uiv)
          ::cursor (select-connected)
          ::wire (cancel))))))

(defn drag-start-box [e]
  (swap! ui assoc
         ::selected #{}
         ::dragging ::box
         ::mouse-start (viewbox-coord e)))

(defn drag-start-background [e]
  (reset! last-coord [(.-clientX e) (.-clientY e)])
  (cond
    (= (.-button e) 1) (swap! ui assoc ::dragging ::view)
    (and (= (.-button e) 0)
         (= ::cursor @tool)) (drag-start-box e) 
    (and (= (.-button e) 0)
         (= ::wire @tool)) (add-wire (viewbox-coord e) (nil? (::dragging @ui)))))

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
                    :x (js/Math.round (+ x dx))
                    :y (js/Math.round (+ y dy))))]
    (swap! ui endfn)
    (swap! schematic update-keys selected round-coords)))

(defn drag-end-box []
  (let [[x y] (::mouse-start @ui)
        {dx :x dy :y} (::delta @ui)
        x1 (js/Math.floor (js/Math.min x (+ x dx)))
        y1 (js/Math.floor (js/Math.min y (+ y dy)))
        x2 (js/Math.floor (js/Math.max x (+ x dx)))
        y2 (js/Math.floor (js/Math.max y (+ y dy)))
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
    (println (::tool @ui) (::dragging @ui))
    (cond
      (and (= (::tool @ui) ::device)
           (not= (.-button e) 1)) (commit-staged @staging)
      (= (::dragging @ui) ::box) (drag-end-box)
      (= (::dragging @ui) ::wire) nil
      :else (end-ui bg? selected dx dy))))


(defn add-device [cell [x y] & args]
  (let [kwargs (apply array-map args)
        [width height] (get-in models [cell ::bg])
        mx (js/Math.round (- x (/ width 2) 1))
        my (js/Math.round (- y (/ height 2) 1))]
    (swap! ui assoc
           ::staging (into {:transform cm/IV, :cell cell :x mx :y my} kwargs)
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
  (swap! snapshots assoc (str "snapshots" sep group "#" (.toISOString (js/Date.)))
         {:schematic @schematic
          :_attachments {"preview.svg" {:content_type "image/svg+xml"
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
           [:select {:id "cell"
                     :type "text"
                     :default-value @cell
                     :on-change #(reset! cell (.. % -target -value))}
            [:option]
            (for [[k m] @modeldb]
              [:option {:key k} (:name m)])]])
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
         [:option {:value nil} "Ideal"]
         (for [m (keys (get-in  @modeldb [(str "models" sep @cell) :models]))]
           [:option {:key m} m])]
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
        xf (map (fn [d] [(make-name (:cell d))
                         (update d :name gensym)]))
        devmap (into {} xf devs)]
    (swap! schematic into devmap)
    (swap!  ui assoc
            ::dragging ::device
            ::selected (set (keys devmap)))))

(defn simulator-url []
    (doto (js/URL. js/window.simulatorurl js/window.location)
      (.. -searchParams (append "schem" group))
      (.. -searchParams (append "db" (or (and (seq sync) sync) dburl)))))

(defn menu-items []
  [:<>
   [:div.primary
    [cm/radiobuttons tool
   ; inactive, active, key, title
     [[[cm/cursor] [cm/cursor] ::cursor "Cursor [esc]"]
      [[cm/wire] [cm/wire] ::wire "Wire [w]"]
      [[cm/eraser] [cm/eraser] ::eraser "Eraser [e]"]
      [[cm/move] [cm/move] ::pan "Pan [space]"]]]
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
   [:div.secondary
    [:a {:href (simulator-url)
         :target "simulator"
         :title "Open simulator"}
     [cm/simulate]]
    [:a {:href js/window.notebookurl
         :target "jupyter"
         :title "Open JupyterLab"}
     [cm/notebook]]
    [:a {:href ".."
         :target "libman"
         :title "Open library manager"}
     [cm/library]]
    [:a {:title "Save Snapshot"
         :on-click snapshot}
     [cm/save]]
    [:select {:on-change #(swap! ui assoc ::theme (.. % -target -value))}
     [:option {:value "tetris"} "Tetris"]
     [:option {:value "eyesore"} "Classic"]]]])


(defn device-active [cell]
  (when (= cell (:cell @staging))
    "active"))

(defn icon-image [name]
  (let [icon (case name
               "resistor" (rc/inline "icons/resistor.svg")
               "capacitor" (rc/inline "icons/capacitor.svg")
               "inductor" (rc/inline "icons/inductor.svg")
               "diode" (rc/inline "icons/diode.svg")
               "vsource" (rc/inline "icons/vsource.svg")
               "isource" (rc/inline "icons/isource.svg")
               "pmos" (rc/inline "icons/pmos.svg")
               "nmos" (rc/inline "icons/nmos.svg")
               "npn" (rc/inline "icons/npn.svg")
               "pnp" (rc/inline "icons/pnp.svg")
               "")]
    [:span {:dangerouslySetInnerHTML {:__html icon}}]))

(defn variant-tray [& variants]
  (let [active (r/atom 0)
        timeout (r/atom nil)]
    (fn [& variants]
      (let [varwraps (map-indexed
                      (fn [i var]
                        [:span {:key i
                                :on-mouse-up #(reset! active i)}
                         var]) variants)]
        [:details
         {;:on-toggle js/console.log
          :on-mouse-down
          (fn [e]
            (let [ct (.-currentTarget e)]
              (reset! timeout (js/setTimeout
                               #(set! (.-open ct) true)
                               300))))
          :on-mouse-up
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

(defn device-tray []
  [:<>
   [variant-tray
    [:button {:title "Add port [p]"
              :class (device-active "port")
              :on-mouse-up #(add-device "port" (viewbox-coord %))}
     [cm/label]]
    [:button {:title "Add ground [g]"
              :class (device-active "port")
              :on-mouse-up #(add-gnd (viewbox-coord %))}
     "GND"]
    [:button {:title "Add power supply [shift+p]"
              :class (device-active "port")
              :on-mouse-up #(add-supply (viewbox-coord %))}
     "VDD"]]
   [:button {:title "Add resistor [r]"
             :class (device-active "resistor")
             :on-mouse-up #(add-device "resistor" (viewbox-coord %))}
    [icon-image "resistor"]]
   [:button {:title "Add inductor [l]"
             :class (device-active "inductor")
             :on-mouse-up #(add-device "inductor" (viewbox-coord %))}
    [icon-image "inductor"]]
   [:button {:title "Add capacitor [c]"
             :class (device-active "capacitor")
             :on-mouse-up #(add-device "capacitor" (viewbox-coord %))}
    [icon-image "capacitor"]]
   [:button {:title "Add diode [d]"
             :class (device-active "diode")
             :on-mouse-up #(add-device "diode" (viewbox-coord %))}
    [icon-image "diode"]]
   [variant-tray
    [:button {:title "Add voltage source [v]"
              :class (device-active "vsource")
              :on-mouse-up #(add-device "vsource" (viewbox-coord %))}
     [icon-image "vsource"]]
    [:button {:title "Add current source [i]"
              :class (device-active "isource")
              :on-mouse-up #(add-device "isource" (viewbox-coord %))}
     [icon-image "isource"]]]
   [variant-tray
    [:button {:title "Add N-channel mosfet [m]"
              :class (device-active "nmos")
              :on-mouse-up #(add-device "nmos" (viewbox-coord %))}
     [icon-image "nmos"]]
    [:button {:title "Add P-channel mosfet [shift+m]"
              :class (device-active "pmos")
              :on-mouse-up #(add-device "pmos" (viewbox-coord %))}
     [icon-image "pmos"]]
    [:button {:title "Add NPN BJT [b]"
              :class (device-active "npn")
              :on-mouse-up #(add-device "npn" (viewbox-coord %))}
     [icon-image "npn"]]
    [:button {:title "Add PNP BJT [shift+b]"
              :class (device-active "pnp")
              :on-mouse-up #(add-device "pnp" (viewbox-coord %))}
     [icon-image "pnp"]]]
   [:button {:title "Add subcircuit [x]"
             :class (device-active "ckt")
             :on-mouse-up #(add-device "ckt" (viewbox-coord %))}
    [cm/chip]]])

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
   (for [[[x y] ids] (first @location-index)
         :let [n (count ids)]
         :when (not= n 2)]
     [:circle
      {:key [x y]
       :class (if (> n 2) "wire" "nc")
       :cx (* grid-size (+ x 0.5))
       :cy (* grid-size (+ y 0.5))
       :r (/ grid-size 10)}])])

(defn tool-elements []
  (let [{sel ::selected
         dr ::dragging
         v ::staging
         {x :x y :y} ::delta
         [sx sy] ::mouse-start} @ui
        vx (* grid-size (js/Math.round x))
        vy (* grid-size (js/Math.round y))]
    (cond
      v [:g.toolstaging
         (get-model ::bg v ::stagingbg v)
         (get-model ::sym v ::stagingsym v)
         (get-model ::conn v ::stagingconn v)]
      (= dr ::box) [:rect.select
                    {:x (* (min sx (+ sx x)) grid-size)
                     :y (* (min sy (+ sy y)) grid-size)
                     :width (js/Math.abs (* x grid-size))
                     :height (js/Math.abs (* y grid-size))}]
      (and sel dr) [:g.staging {:style {:transform (str "translate(" vx "px, " vy "px)")}}
                    [schematic-elements
                     (let [schem @schematic]
                       (map #(vector % (get schem %)) sel))]])))

(defn schematic-ui []
  [:div#mosaic_app {:class @theme}
   [:div.menu.chrome
    [menu-items]]
   [:div.content
    [:div.devicetray.chrome
     [device-tray]]
    (when-let [sel (seq @selected)]
      [:div.sidebar
       (doall (for [key sel]
                ^{:key key} [deviceprops key]))])
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
             :x (* -500 grid-size)
             :y (* -500 grid-size)
             :width (* 1000 grid-size)
             :height (* 1000 grid-size)}]
     [schematic-elements @schematic]
     [schematic-dots]
     [tool-elements]]]])

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
                #{:g} #(add-gnd (::mouse @ui))
                #{:shift :p} #(add-supply (::mouse @ui))
                #{:backspace} delete-selected
                #{:delete} delete-selected
                #{:w} (fn [_] ; right away start a wire or not?
                        ;; (add-wire (::mouse @ui) (nil? (::dragging @ui)))
                        (swap! ui assoc ::tool ::wire))
                #{:e} #(swap! ui assoc ::tool ::eraser ::staging nil)
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