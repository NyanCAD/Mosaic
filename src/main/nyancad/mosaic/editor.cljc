; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [#?(:web    nyancad.mosaic.editor.platform-web
                :vscode nyancad.mosaic.editor.platform-vscode
                :test   nyancad.mosaic.editor.platform-test)
             :refer [group schematic modeldb snapshots simulations local
                     done? syncactive notebook-panel secondary-menu-items
                     editable-model? edit-model send-to-agent resolve-symbol-url
                     init-extra! root model-cursor get-model-async renamable-model-name]]
            [clojure.spec.alpha :as s]
            [cljs.core.async :refer [go <! put! promise-chan]]
            [clojure.math :as math]
            clojure.edn
            clojure.set
            clojure.string
            goog.functions
            [nyancad.hipflask.util :refer [update-keys sep]]
            [nyancad.mosaic.invocation :as invocation]
            [nyancad.mosaic.common :as cm
             :refer [grid-size debounce sconj
                     point transform transform-vec
                     mosfet-shape bjt-conn b64encode]]))
;; @tags factory card dragging
(defn make-names
  "Returns a lazy sequence of available names for a device type (e.g. R1, R2, R3...)"
  [base]
  (let [names (map #(str (cm/initial base) %) (next (range)))]
    (remove #(@schematic (str group sep %)) names)))

;; @tags factory card dragging
(defn make-name [base]
  (first (make-names base)))

(defonce ui (r/atom {::zoom [0 0 500 500]
                      ::theme nil
                      ::tool ::cursor
                      ::domain ::optical
                      ::selected #{}
                      ::mouse [0 0]
                      ::mouse-start [0 0]
                      ::notebook-state ::embedded
                      ::pointer-cache {}
                      ::tab-index 0
                      ::paste-staging nil}))


(s/def ::zoom (s/coll-of number? :count 4))
(s/def ::theme (s/nilable #{"light" "dark" "eyesore"}))
(s/def ::tool #{::cursor ::eraser ::wire ::pan ::device ::probe})
;; @tags mosaic device tray
;; Device-tray placement category. A visibility filter only —
;; see specs/mosaic_device_tray.md. Optional in the spec so hot-reloaded ui
;; atoms without the key stay valid; reads default to ::optical.
(s/def ::domain #{::optical ::electrical})
(s/def ::selected (s/and set? (s/coll-of string?)))
(s/def ::dragging (s/nilable #{::wire ::device ::view ::box}))
;; Staging is a partial device: commit-staged assigns :name on commit.
(s/def ::staging (s/nilable (s/keys :req-un [::cm/type ::cm/x ::cm/y]
                                    :opt-un [::cm/name ::cm/transform ::cm/model
                                             ::cm/nets ::cm/template
                                             ::cm/rx ::cm/ry ::cm/variant ::cm/net])))
(s/def ::notebook-state #{::embedded ::collapsed ::popped-out})
(s/def ::x number?)
(s/def ::y number?)
(s/def ::pointer-cache (s/map-of int? (s/keys :req-un [::x ::y])))
(s/def ::tab-index nat-int?)
;; Shift-drag connectivity. ::stretch is {wire-id -> #{:start :end}} of endpoints
;; that follow the dragged devices: present (the map) while Shift is held during a
;; drag, nil otherwise. It is the single gate — the staging preview and the commit
;; check whether it's set. Recomputed on each Shift press (rare; selection/schematic
;; are frozen mid-drag, so the map is stable), hence no separate cache.
(s/def ::stretch (s/nilable (s/map-of string? set?)))
(s/def ::paste-staging (s/nilable (s/map-of string? map?)))
(s/def ::ui (s/keys :req [::zoom ::theme ::tool ::selected ::notebook-state ::pointer-cache ::tab-index]
                    :opt [::dragging ::staging ::stretch ::paste-staging ::domain]))

(set-validator! ui #(or (s/valid? ::ui %) (.log js/console (pr-str %) (s/explain-str ::ui %))))

(defonce zoom (r/cursor ui [::zoom]))
(defonce theme (r/cursor ui [::theme]))
(defonce tool (r/cursor ui [::tool]))
(defonce domain (r/cursor ui [::domain]))
(defonce selected (r/cursor ui [::selected]))
(defonce delta (r/cursor ui [::delta]))
(defonce staging (r/cursor ui [::staging]))
(defonce paste-staging (r/cursor ui [::paste-staging]))
(defonce notebook-state (r/cursor ui [::notebook-state]))
(defonce tab-index (r/cursor ui [::tab-index]))
(defonce pointer-cache (r/cursor ui [::pointer-cache]))

; Model selector popup state
(defonce model-popup-filter (r/atom ""))
(defonce model-popup-category (r/atom []))

;; @tags mosaic model picker
(defn initial-hide-private-cells
  "The toggle value to boot with: the persisted choice when storage is
   readable, the default (hidden) when it is not. Storage can exist yet be
   denied — an opaque-origin webview such as the Playwright smoke harness
   throws on read — which counts as absent, exactly like the Node test
   build where localStorage does not exist at all."
  []
  (try
    (if (exists? js/localStorage)
      (not= "false" (.getItem js/localStorage "mosaic-hide-private-cells"))
      true)
    (catch :default _ true)))

;; @tags mosaic model picker
(defonce hide-private-cells (r/atom (initial-hide-private-cells)))

;; @tags mosaic model picker
(defn set-hide-private-cells!
  "Persist the picker's hide-private-cells choice across webview reloads
   (localStorage key mosaic-hide-private-cells). Absent or denied storage —
   fresh install, the Node test build, or an opaque-origin webview — cannot
   persist; the choice then lives in memory for the session only."
  [v]
  (reset! hide-private-cells v)
  (try
    (when (exists? js/localStorage)
      (.setItem js/localStorage "mosaic-hide-private-cells" (str v)))
    (catch :default _ nil)))

;; @tags factory resolution
(defn model-entry [model-id & [params]]
  (when (seq model-id)
    @(apply model-cursor modeldb (invocation/lookup-path (cm/model-key model-id) params))))

(defonce ^{:doc "Cancellation generation captured by placement intents; advancing it prevents late insertion."}
  placement-epoch (atom 0))
(defonce ^{:doc "Completion channel of the last queued resolved placement, including its undo checkpoint. Cancellation does not bypass this queue."}
  placement-tail (atom (go true)))

(defonce undotree (cm/newundotree))

(declare build-wire-split-index build-point-index split-wire point-index net-type-index
         build-wire-networks build-netlist build-net-annotations build-net-type-index
         port-wire-start)

;; @tags reactive indices, mosaic port exposure
(defn post-action!
  "Split wires, annotate nets, and record one undo checkpoint after a user action."
  ([] (post-action! @modeldb))
  ([models-snapshot]
   (go
     (<! (done? schematic))
     (let [sch @schematic
           pt-idx (build-point-index sch models-snapshot)]
       (doseq [[w coords] (build-wire-split-index sch pt-idx)]
         (split-wire w coords pt-idx)
         (<! (done? schematic))))
     (<! (done? schematic))
     (let [sch       @schematic
           pt-idx    (build-point-index sch models-snapshot)
           networks  (build-wire-networks sch pt-idx)
           nets      (build-netlist networks)
           net-types (build-net-type-index networks pt-idx)
           updates   (build-net-annotations sch nets net-types)]
       (when (seq updates)
         (swap! schematic (partial merge-with merge) updates)
         (<! (done? schematic))))
     (cm/newdo undotree @schematic))))

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

(declare drag-start drag-end eraser-drag models resolve-field on-pointer-down-element on-pointer-move-element on-pointer-up-bg double-click device-dims device-template circuit-designator device-context-menu)

(defn device [size k v & elements]
  (assert (js/isFinite size))
  (into [:g.device {:on-pointer-down (fn [e] (on-pointer-down-element k e))
                    :on-pointer-move (fn [e] (on-pointer-move-element k e))
                    :on-pointer-up on-pointer-up-bg
                    :on-context-menu (fn [e] (device-context-menu k e))
                    :style {:transform (.toString (.translate (transform (:transform v cm/IV)) (* (:x v) grid-size) (* (:y v) grid-size)))
                            :transform-origin (str (* (+ (:x v) (/ size 2)) grid-size) "px "
                                                   (* (+ (:y v) (/ size 2)) grid-size) "px")}
                    :class [(:type v) (:variant v) (resolve-field (get models (:type v)) ::category v) (when (contains? @selected k) :selected)]}]
        elements))

;; @tags mosaic wire tool
(defn port [x y port-type]
  [:circle.port {:cx (+ x (/ grid-size 2))
                 :cy (+ y (/ grid-size 2))
                 :r (/ grid-size 10)
                 :class port-type
                 :on-pointer-down port-wire-start}])

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
        wire-type (get @net-type-index key)]
    [:g.wire {:on-pointer-down #(on-pointer-down-element key %)
              :on-pointer-move #(on-pointer-move-element key %)
              :on-pointer-up on-pointer-up-bg
              :class [(when (contains? @selected key) :selected)
                      (when wire-type (name wire-type))]}
     [:polyline.wirebb {:points pts}]
     [:polyline.wire {:points pts
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

;; @tags photonic model ports
(defn device-template [dev size]
  (let [[bg-w bg-h] (or (device-dims dev) [1 1])]
    (circuit-designator dev size bg-w bg-h)))

;; Plain port markers (regular exposed port, ground, supply, text). The ideal
;; photonic source/detector are ALSO `port` instances behind the scenes (so SAX
;; and everything else treat them as ordinary exposed ports; only the circulax
;; testbench reads `port.variant`), but they render as full-size components —
;; the classic optical-source "sun" and double-ring photodetector with a tetris
;; background and an adjacent port. For those variants the `port` model's
;; `::sym` dispatch map resolves to `optical-source-elements`/
;; `optical-detector-elements` (see `resolve-field`), so they never reach this
;; function.
;; @tags circulax simulation
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
   [:text {:text-anchor (case (math/round (first (:transform label cm/IV)))
                          1 "end"
                          -1 "start"
                          "middle")
           :dominant-baseline "middle"
           :transform (-> (:transform label cm/IV)
                          transform
                          (.translate (/ grid-size (if (= (:variant label) "text") -4 4)) (/ grid-size -2))
                          .inverse
                          .toString)}
    (if (nil? (:variant label))
      (schem-template label (get label :template (get-in models ["port" ::template])))
      (:name label "net"))]])

;; Ideal optical source symbol: 24-ray "sun", alternating long (r=0.42) / short
;; (r=0.28) rays at 15° intervals. Wire [[1.92 1.5] [2.5 1.5]] connects the 0°
;; ray tip to the adjacent port p1 at (2,1). Drawn at ::size 3 with a tetris
;; background — the `emitter` case of the `port` model's `::sym` dispatch map.
;; @tags circulax simulation
(def optical-source-elements
  {::size 3
   ::elements [[:lines [[[1.5 1.5] [1.92  1.5  ]]   ;   0° long
                        [[1.5 1.5] [1.77  1.572]]   ;  15° short
                        [[1.5 1.5] [1.864 1.71 ]]   ;  30° long
                        [[1.5 1.5] [1.698 1.698]]   ;  45° short
                        [[1.5 1.5] [1.71  1.864]]   ;  60° long
                        [[1.5 1.5] [1.572 1.77 ]]   ;  75° short
                        [[1.5 1.5] [1.5   1.92 ]]   ;  90° long
                        [[1.5 1.5] [1.428 1.77 ]]   ; 105° short
                        [[1.5 1.5] [1.29  1.864]]   ; 120° long
                        [[1.5 1.5] [1.302 1.698]]   ; 135° short
                        [[1.5 1.5] [1.136 1.71 ]]   ; 150° long
                        [[1.5 1.5] [1.23  1.572]]   ; 165° short
                        [[1.5 1.5] [1.08  1.5  ]]   ; 180° long
                        [[1.5 1.5] [1.23  1.428]]   ; 195° short
                        [[1.5 1.5] [1.136 1.29 ]]   ; 210° long
                        [[1.5 1.5] [1.302 1.302]]   ; 225° short
                        [[1.5 1.5] [1.29  1.136]]   ; 240° long
                        [[1.5 1.5] [1.428 1.23 ]]   ; 255° short
                        [[1.5 1.5] [1.5   1.08 ]]   ; 270° long
                        [[1.5 1.5] [1.572 1.23 ]]   ; 285° short
                        [[1.5 1.5] [1.71  1.136]]   ; 300° long
                        [[1.5 1.5] [1.698 1.302]]   ; 315° short
                        [[1.5 1.5] [1.864 1.29 ]]   ; 330° long
                        [[1.5 1.5] [1.77  1.428]]   ; 345° short
                        [[1.92  1.5] [2.5  1.5  ]]]]]})

;; Photodetector: double-walled circle (one grid cell) with two photon arrows
;; (↙), wire to the adjacent port p1 at (2,1). Circle centered at canvas centre
;; (75,75)px = grid (1.5,1.5). Outer r=22px fits in a 50×50 cell. Drawn at
;; ::size 3 with a tetris background — the canonical `detector` port-variant
;; artwork, carrying its port on the RIGHT (matching `optical-source-elements`
;; and the plain port default, so the drawn lead coincides with the port
;; marker under the identity transform). `add-detector` stages a horizontal
;; flip (flip-x) so the port renders on the left at runtime; flip ∘ mirror =
;; identity keeps the on-canvas look unchanged while making transform-direction
;; report :right (the parent-edge the detector sits on).
;; @tags circulax simulation
(def optical-detector-elements
  {::size 3
   ::elements [[:lines [[[2.5 1.5] [1.94 1.5]]]]
               [:circle.outline {:cx 1.5 :cy 1.5 :r 0.44}]
               [:circle.outline {:cx 1.5 :cy 1.5 :r 0.28}]
               [:path {:d "M77,67 L67,77 M70,77 L67,77 L67,73"}]
               [:path {:d "M83,73 L73,83 M77,83 L73,83 L73,80"}]]})

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
;; Ports: (0,1)→px(25,75) left  (1,0)→px(75,25) top
;; Quarter-arc from left port curving up to top port
(def bend-elements
  {::size 3
   ::elements [[:path {:d "M25,75 H50 Q75,75 75,50 V25"}]]})

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

;; Helper: port-name labels for a selected instance. Each label is anchored at
;; the device body edge and reads OUTWARD along its port stem — horizontal on
;; left/right ports, a single -90° head-tilt on top/bottom ports — so long
;; names run past the connection dot instead of into the symbol. The
;; orientation is chosen from the port's outward normal *after* the instance
;; transform, and the text is counter-rotated (then re-oriented) so it is never
;; upside-down or mirrored under rotation/flips — unlike `port-label`, which
;; only counter-rotates to stay flat-upright.
;; @tags photonic model ports
(defn selected-port-labels
  "Render model port names beside a selected instance's port markers, oriented
   along each port's stem (left/right horizontal, top/bottom head-tilted) and
   kept upright/unmirrored under the instance transform."
  [k v locs]
  (when (and (contains? @selected k) (seq locs))
    (let [[a0 b0 c0 d0 _ _] (:transform v cm/IV)   ; instance linear part (e=f=0)
          ;; inverse(M) linear part. Instance transforms are rotations/flips
          ;; (orthogonal, det ±1), so the inverse is the transpose (swap b/c).
          inv (js/DOMMatrixReadOnly. #js [a0 c0 b0 d0 0 0])
          stem (* grid-size 0.5)   ; marker → device body edge
          clear 6                  ; perpendicular lift + gap from the body edge
          base (- stem clear)]     ; along-stem anchor: `clear` out from the edge
      (into [:g.port-labels]
            (for [{:keys [x y side name] :as port} locs
                  :let [mx (* (+ x 0.5) grid-size)
                        my (* (+ y 0.5) grid-size)
                        ;; local outward normal for the canonical side …
                        [ux uy] (case side
                                  :left [-1 0] :right [1 0]
                                  :top [0 -1] :bottom [0 1] [0 0])
                        ;; … rotated into screen space by the instance transform
                        nx (+ (* a0 ux) (* c0 uy))
                        ny (+ (* b0 ux) (* d0 uy))
                        horizontal (>= (js/Math.abs nx) (js/Math.abs ny))
                        visual (if horizontal
                                 (if (neg? nx) :left :right)
                                 (if (neg? ny) :top :bottom))
                        ;; counter-rotate by inverse(M), then re-orient (a -90°
                        ;; head-tilt for ports on a horizontal edge), pivoted at
                        ;; the marker so the label holds its position
                        tm (-> (js/DOMMatrixReadOnly.)
                               (.translate mx my)
                               (.multiply inv)
                               (cond-> (not horizontal) (.rotate -90))
                               (.translate (- mx) (- my)))
                        ;; anchor at the base (body edge) so long labels read
                        ;; OUTWARD past the dot instead of into the symbol
                        [anchor along] (case visual
                                         :right  ["start" (- base)]
                                         :top    ["start" (- base)]
                                         :left   ["end" base]
                                         :bottom ["end" base])]]
               [:text.port-label {:key (str name)
                                 :x (+ mx along) :y (- my clear)
                                 :text-anchor anchor
                                 :dominant-baseline "auto"
                                 :transform (.toString tm)}
               (cm/port-display-name port)])))))

;; The single source of truth for "how big is this device?". Everything that
;; needs a footprint — rendering, placement, the clipboard centroid, the
;; selection anchor — goes through `device-dims` or `device-tile-size` rather
;; than re-deriving the `::bg`/`@modeldb` lookup, because the branches disagree
;; in ways that are invisible for the 1x1 case every builtin happens to use.

;; @tags mosaic clipboard
(defn device-dims
  "Return [width height] of a device doc's tetris rect — the footprint before
   the 2-tile padding added by the `device` renderer — or nil for devices that
   render one bare tile and never reach `draw-background` (plain ports, text,
   wires, groups: everything drawn by `port-sym` and friends).

   Electrical builtins read `::bg` from the static `models` map; subcircuit and
   photonic devices with a `:model` read ports from `@modeldb` via
   `cm/port-perimeter`. A type with no `::conn` entry is a tetris device whose
   dims are unknown — photonic builtins, and subcircuits (`ckt`/`amp`) absent
   from `models` entirely — and defaults to `[1 1]`, i.e. size 3.

   The nil case is the reason this returns an option rather than always `[1 1]`:
   a plain port is one tile, not a 1x1 rect padded to three, and callers that
   guessed wrong put the selection toolbar and the paste centroid a tile off."
  [dev]
  (let [entry (model-entry (:model dev) (:props dev))
        dev (cond-> dev (:generated entry) (assoc :type (:type entry)))
        builtin (get models (:type dev))
        bg (resolve-field builtin ::bg dev)]
    (cond
      (and (vector? bg) (= (count bg) 2)) bg
      (:model dev) (let [ports (:ports entry)]
                     (if (seq ports) (cm/port-perimeter ports (:type dev)) [1 1]))
      (nil? (::conn builtin)) [1 1]
      :else nil)))

;; @tags mosaic selection toolbar
(defn device-tile-size
  "Square tile edge of a device doc in grid cells — what `device` is given as
   its `size`, so it is also the rotation pivot's box. A tetris device is its
   larger dimension plus the 2-tile padding; a device with no tetris rect
   (`device-dims` nil) is a single tile."
  [dev]
  (if-let [[w h] (device-dims dev)]
    (+ 2 (max w h))
    1))

;; @tags photonic model ports
(defn centered-artwork-transform
  "Return an SVG transform that applies a six-number matrix around the center
   of a square symbol. The matrix affects artwork coordinates only."
  [size matrix]
  (when matrix
    (let [center (* (/ size 2) grid-size)]
      (.toString (-> (js/DOMMatrixReadOnly.)
                     (.translate center center)
                     (.multiply (cm/transform matrix))
                     (.translate (- center) (- center)))))))

;; @tags photonic model ports
(defn- photonic-sym
  [builtin-elements k v]
  (let [new-size (device-tile-size v)
        {orig-size ::size, elements ::elements} builtin-elements
        delta (- new-size orig-size)
        offset (* (/ delta 2) grid-size)
        artwork-transform (:artwork-transform (model-entry (:model v) (:props v)))
        artwork (if (zero? delta)
                  (map render-element elements)
                  [(into [:g {:transform (str "translate(" offset "," offset ")")}]
                         (map render-element elements))])]
    (into [device new-size k v
           [device-template v new-size]]
          (if artwork-transform
            [[:g {:transform (centered-artwork-transform new-size artwork-transform)}
              (into [:<>] artwork)]]
            artwork))))

;; @tags photonic model ports, mosaic die-component
(defn circuit-shape [k v]
  (when-not (= "die" (:type v))
    (draw-background (or (device-dims v) [1 1]) k v)))

;; @tags photonic model ports, mosaic die-component
(defn circuit-conn [k v]
  (let [ports (:ports (model-entry (:model v) (:props v)))
        locs (if (seq ports) (cm/port-locations ports (:type v)) [])
        size (device-tile-size v)]
    [device size k v
     (into [:<>]
           (for [{:keys [x y type]} locs]
             ^{:key [x y]} [port (* x grid-size) (* y grid-size) type]))
     ;; Port-name labels live with the markers. Gated to builtin types in
     ;; `models` (photonic, and any future model-driven builtin) so ckt/amp
     ;; subcircuits keep their own interior labels instead of doubling up.
     (when (contains? models (:type v))
       (selected-port-labels k v locs))]))


;; Helper: counter-rotation transform for text that should stay upright
(defn counter-rotate [v x y]
  (.toString (-> (js/DOMMatrix.)
                 (.translate x y)
                 (.multiply (.inverse (transform (:transform v cm/IV))))
                 (.translate (- x) (- y)))))

;; Render the device designator/name just outside the screen-space top-right
;; corner of the (possibly rotated/flipped) bounding box, kept upright.
;; The name is anchored by the transform alone: `schem-template` wraps every
;; line in a tspan with absolute x=0, so glyphs always sit at the text-local
;; origin and any :x/:y on the <text> element would be ignored. The transform
;; T(centre)·M⁻¹·T(g·anchor) sends that origin to the chosen screen point and
;; its M⁻¹ linear part cancels the device transform M, so the text reads
;; horizontally and unmirrored in all 8 orientations. Instance transforms are
;; rotations/flips (orthogonal, det ±1), so M⁻¹ is the transpose (swap b/c) —
;; no .inverse()/DOMPoint, keeping the math reachable from the node test build.
(defn designator-transform
  "DOMMatrix that places a device-local origin just outside the screen-space
   top-right corner of the `[a b c d e f]` `tf`-transformed bbox, and cancels
   `tf`'s linear part so the text stays upright. Pure matrix math (transpose for
   the orthogonal inverse), reachable from the node test build. See
   `circuit-designator`."
  [tf size width height]
  (let [half-size (/ size 2)
        ;; bbox corners in grid units relative to the tile centre (the CSS
        ;; transform-origin), so M maps them to screen offsets from the centre
        corners [[(- (+ 1 width) half-size) (- 1 half-size)]             ; top-right
                 [(- 1 half-size) (- 1 half-size)]                       ; top-left
                 [(- 1 half-size) (- (+ 1 height) half-size)]            ; bottom-left
                 [(- (+ 1 width) half-size) (- (+ 1 height) half-size)]] ; bottom-right
        [a b c d _ _] tf                     ; instance linear part (e=f=0)
        screen (map (fn [[x y]] [(+ (* a x) (* c y)) (+ (* b x) (* d y))]) corners)
        ;; screen-space top-right bbox corner (y-down → maximise x − y)
        [sx sy] (apply max-key (fn [[x y]] (- x y)) screen)
        ;; anchor just right of and above that corner (screen units), so the
        ;; glyphs extend up-and-right, clear of the shape
        ax (+ sx 0.1)
        ay (- sy 0.15)
        inv (js/DOMMatrixReadOnly. #js [a c b d 0 0])]  ; transpose = M⁻¹ (orthogonal)
    (-> (js/DOMMatrixReadOnly.)
        (.translate (* grid-size half-size) (* grid-size half-size))
        (.multiply inv)
        (.translate (* grid-size ax) (* grid-size ay)))))

;; @tags factory resolution
(defn circuit-designator [v size width height]
  (let [text (or (:template v)
                 (when (seq (:model v))
                   (cm/generate-important-template
                      (:props (model-entry (:model v) (:props v)))))
                 (get-in models [(:type v) ::template])
                 "{self.name}")]
    [:text.identifier
     {:transform (.toString (designator-transform (:transform v cm/IV) size width height))}
     (schem-template v text)]))

;; Helper: render port label with counter-rotation
(defn port-label [v x y anchor baseline pname]
  [:text.port-label
   {:key (str pname)
    :x x :y y
    :text-anchor anchor
    :dominant-baseline baseline
    :transform (counter-rotate v x y)}
   pname])

;; @tags factory resolution
(defn circuit-sym-box [k v]
  (let [model (:model v)
        model-def (model-entry model (:props v))
        model-name (or (:name model-def) model)
        ports (:ports model-def)
        has-model? (and model (seq model) model-def)
        has-ports? (and has-model? (seq ports))
        [width height] (if has-ports? (cm/port-perimeter ports (:type v)) [1 1])
        all-locs (cm/port-locations ports (:type v))
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
      ;; Port lines (only when ports exist)
      (when has-ports?
        [:<>
         [lines (for [{:keys [x y]} top-locs] [[(+ x 0.5) (+ y 0.5)] [(+ x 0.5) (+ y 1)]])]
         [lines (for [{:keys [x y]} bottom-locs] [[(+ x 0.5) (+ y 0)] [(+ x 0.5) (+ y 0.5)]])]
         [lines (for [{:keys [x y]} right-locs] [[(+ x 0) (+ y 0.5)] [(+ x 0.5) (+ y 0.5)]])]
         [lines (for [{:keys [x y]} left-locs] [[(+ x 1) (+ y 0.5)] [(+ x 0.5) (+ y 0.5)]])]])
      ;; Port labels inside box
      (when has-ports?
        [:<>
         (for [{:keys [y] :as port} left-locs]
           (port-label v (* 1.15 grid-size) (* (+ y 0.5) grid-size) "start" "middle" (cm/port-display-name port)))
         (for [{:keys [y] :as port} right-locs]
           (port-label v (* (+ width 0.85) grid-size) (* (+ y 0.5) grid-size) "end" "middle" (cm/port-display-name port)))
         (for [{:keys [x] :as port} top-locs]
           (port-label v (* (+ x 0.5) grid-size) (* 1.3 grid-size) "middle" "hanging" (cm/port-display-name port)))
         (for [{:keys [x] :as port} bottom-locs]
           (port-label v (* (+ x 0.5) grid-size) (* (+ height 0.7) grid-size) "middle" "baseline" (cm/port-display-name port)))])
      ;; Symbol image replaces box, otherwise box outline with model name
      (if-let [symbol-url (and has-model? (:symbol model-def)
                              (some-> (resolve-symbol-url (:symbol model-def)
                                        [(:gfp/session model-def) (:gfp/revision model-def)]) deref))]
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

;; @tags factory resolution
(defn circuit-sym-amplifier [k v]
  (let [model (:model v)
        model-def (model-entry model (:props v))
        model-name (or (:name model-def) model)
        ports (:ports model-def)
        has-model? (and model (seq model) model-def)
        has-ports? (and has-model? (seq ports))
        [width height] (if has-ports? (cm/port-perimeter ports (:type v)) [1 1])
        all-locs (cm/port-locations ports (:type v))
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
      (when has-ports?
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
      (when has-ports?
        [:<>
         (for [{:keys [y] :as port} left-locs]
           (port-label v (* 1.15 grid-size) (* (+ y 0.5) grid-size) "start" "middle" (cm/port-display-name port)))
         (for [{:keys [y] :as port} right-locs]
           (port-label v (* (+ width 0.6) grid-size) (* (+ y 0.5) grid-size) "end" "middle" (cm/port-display-name port)))
         ;; Top port labels - follow diagonal edge
         (for [{:keys [x] :as port} top-locs
               :let [px (+ x 0.5)
                     edge-y (+ tri-top (* (- px tri-left) (/ (- tri-apex-y tri-top) width)))]]
           (port-label v (* px grid-size) (* (+ edge-y 0.3) grid-size) "middle" "hanging" (cm/port-display-name port)))
         ;; Bottom port labels - follow diagonal edge
         (for [{:keys [x] :as port} bottom-locs
               :let [px (+ x 0.5)
                     edge-y (+ tri-bottom (* (- px tri-left) (/ (- tri-apex-y tri-bottom) width)))]]
           (port-label v (* px grid-size) (* (- edge-y 0.3) grid-size) "middle" "baseline" (cm/port-display-name port)))])
      ;; Model name or "?"
      [:text.model-name
       {:x cx :y cy
        :text-anchor "middle"
        :dominant-baseline "middle"
        :transform (counter-rotate v cx cy)}
       (if has-model? model-name "?")]]]))

;; @tags mosaic die-component, factory resolution
(defn circuit-sym-die [k v]
  (let [model (:model v)
        model-def (model-entry model (:props v))
        ports (:ports model-def)
        has-ports? (seq ports)
        [width height] (if has-ports? (cm/port-perimeter ports (:type v)) [1 1])
        all-locs (cm/port-locations ports (:type v))
        by-side (group-by :side all-locs)
        size (+ 2 (max width height))]
    [device size k v
     [:<>
      (circuit-designator v size width height)
      (when has-ports?
        [:<>
         (for [{:keys [y] :as port} (:left by-side)]
           (port-label v (* 1.15 grid-size) (* (+ y 0.5) grid-size) "start" "middle" (cm/port-display-name port)))
         (for [{:keys [y] :as port} (:right by-side)]
           (port-label v (* (+ width 0.85) grid-size) (* (+ y 0.5) grid-size) "end" "middle" (cm/port-display-name port)))
         (for [{:keys [x] :as port} (:top by-side)]
           (port-label v (* (+ x 0.5) grid-size) (* 1.3 grid-size) "middle" "hanging" (cm/port-display-name port)))
         (for [{:keys [x] :as port} (:bottom by-side)]
           (port-label v (* (+ x 0.5) grid-size) (* (+ height 0.7) grid-size) "middle" "baseline" (cm/port-display-name port)))])]]))

;; @tags photonic model ports, mosaic die-component
(defn circuit-sym [k v]
  (case (:type v)
    "amp" (circuit-sym-amplifier k v)
    "die" (circuit-sym-die k v)
    (circuit-sym-box k v)))

(def tran-field
  {:name "tran" :tooltip "Transient waveform" :type :tagged-enum
   :tag-key "type"
   :cases
   [{:value "sin" :label "Sinusoidal"
     :children [{:name "offset" :tooltip "DC offset" :default "0"}
                {:name "amplitude" :tooltip "Amplitude" :default "1"}
                {:name "frequency" :tooltip "Frequency (Hz)" :default "1k"}
                {:name "delay" :tooltip "Delay (s)" :default "0"}
                {:name "damping" :tooltip "Damping factor (1/s)" :default "0"}]}
    {:value "pulse" :label "Pulse"
     :children [{:name "initial" :tooltip "Initial value" :default "0"}
                {:name "pulsed" :tooltip "Pulsed value" :default "5"}
                {:name "width" :tooltip "Pulse width (s)" :default "500u"}
                {:name "period" :tooltip "Period (s)" :default "1m"}
                {:name "delay" :tooltip "Delay (s)" :default "0"}
                {:name "rise" :tooltip "Rise time (s)" :default "10n"}
                {:name "fall" :tooltip "Fall time (s)" :default "10n"}]}
    {:value "exp" :label "Exponential"
     :children [{:name "initial" :tooltip "Initial value" :default "0"}
                {:name "pulsed" :tooltip "Pulsed value" :default "5"}
                {:name "rise-delay" :tooltip "Rise delay time (s)" :default "0"}
                {:name "rise-tau" :tooltip "Rise time constant (s)"}
                {:name "fall-delay" :tooltip "Fall delay time (s)"}
                {:name "fall-tau" :tooltip "Fall time constant (s)"}]}
    {:value "sffm" :label "Single-Freq FM"
     :children [{:name "offset" :tooltip "Offset" :default "0"}
                {:name "amplitude" :tooltip "Amplitude" :default "1"}
                {:name "carrier-freq" :tooltip "Carrier frequency (Hz)" :default "1M"}
                {:name "mod-index" :tooltip "Modulation index" :default "5"}
                {:name "signal-freq" :tooltip "Signal frequency (Hz)" :default "10k"}]}]})

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
                                 tran-field]}
             "isource" {::bg cm/twoport-bg
                        ::conn cm/twoport-conn
                        ::sym isource-elements
                        ::category "source"
                        ::template "{self.name}: {self.props.dc}I"
                        ::props [{:name "dc" :tooltip "DC current" :spice "dc"}
                                 {:name "ac" :tooltip "AC current" :spice "ac"}
                                 tran-field]}
             "diode" {::bg cm/twoport-bg
                      ::conn cm/twoport-conn
                      ::sym #'diode-sym
                      ::category "passive"
                      ::props []}
              "led" {::bg [1 1]
                     ::conn [{:name "P" :x 1 :y 0 :type "electric" :side :top}
                             {:name "O" :x 2 :y 1 :type "photonic" :side :right}
                             {:name "N" :x 1 :y 2 :type "electric" :side :bottom}]
                    ::sym #'diode-sym
                    ::category "photonic-active"
                    ::template "{self.name}"
                    ::props []}
              "photodiode" {::bg [1 1]
                            ::conn [{:name "P" :x 1 :y 0 :type "electric" :side :top}
                                    {:name "O" :x 2 :y 1 :type "photonic" :side :right}
                                    {:name "N" :x 1 :y 2 :type "electric" :side :bottom}]
                           ::sym #'diode-sym
                           ::category "photonic-active"
                           ::template "{self.name}"
                           ::props []}
              "modulator" {::bg [1 1]
                           ::conn [{:name "P" :x 1 :y 0 :type "electric" :side :top}
                                   {:name "1" :x 0 :y 1 :type "photonic" :side :left}
                                   {:name "2" :x 2 :y 1 :type "photonic" :side :right}
                                   {:name "N" :x 1 :y 2 :type "electric" :side :bottom}]
                          ::sym #'diode-sym
                          ::category "photonic-active"
                          ::template "{self.name}"
                          ::props []}
             ;; Photonic components
             "straight" {::sym straight-elements
                         ::category "photonic"
                         ::template "{self.name}"
                         ::props []}
             "bend" {::sym bend-elements
                     ::category "photonic"
                     ::template "{self.name}"
                     ::props []}
             "sbend" {::sym sbend-elements
                      ::category "photonic"
                      ::template "{self.name}"
                      ::props []}
             "taper" {::sym taper-elements
                      ::category "photonic"
                      ::template "{self.name}"
                      ::props []}
             "transition" {::sym transition-elements
                           ::category "photonic"
                           ::template "{self.name}"
                           ::props []}
             "terminator" {::sym terminator-elements
                           ::category "photonic"
                           ::template "{self.name}"
                           ::props []}
             "crossing" {::sym crossing-elements
                         ::category "photonic"
                         ::template "{self.name}"
                         ::props []}
             "ring-single" {::sym ring-single-elements
                            ::category "photonic"
                            ::template "{self.name}"
                            ::props []}
             "ring-double" {::sym ring-double-elements
                            ::category "photonic"
                            ::template "{self.name}"
                            ::props []}
             "spiral" {::sym spiral-elements
                       ::category "photonic"
                       ::template "{self.name}"
                       ::props []}
             "splitter-1x2" {::sym splitter-1x2-elements
                             ::category "photonic"
                             ::template "{self.name}"
                             ::props []}
             "coupler" {::sym coupler-elements
                        ::category "photonic"
                        ::template "{self.name}"
                        ::props []}
             "mmi-1x2" {::sym mmi-1x2-elements
                        ::category "photonic"
                        ::template "{self.name}"
                        ::props []}
             "mmi-2x2" {::sym mmi-2x2-elements
                        ::category "photonic"
                        ::template "{self.name}"
                        ::props []}
             "mzi-1x2" {::sym mzi-1x2-elements
                        ::category "photonic"
                        ::template "{self.name}"
                        ::props []}
             "mzi-2x2" {::sym mzi-2x2-elements
                        ::category "photonic"
                        ::template "{self.name}"
                        ::props []}
             "grating-coupler" {::sym grating-coupler-elements
                                ::category "source"
                                ::template "{self.name}"
                                ::props []}
             "wire" {::bg []
                     ::conn []
                     ::sym wire-sym
                     ::props []}
             "polyline" {::bg []
                         ::conn []
                         ;; Polylines render no inline symbol — their airwire
                         ;; ratsnest is drawn by the top-level
                         ;; `schematic-airwires` layer instead.
                         ::sym nil
                         ::props []}
             ;; Ideal photonic source/detector are `port` instances (so SAX and
             ;; everything else treat them as ordinary exposed ports; only the
             ;; circulax testbench reads `port.variant`), but they render as
             ;; full-size components with the classic optical artwork, a tetris
             ;; background and a single adjacent port — exactly the dev
             ;; OpticalSource/OpticalDetector look. `::bg`/`::conn`/`::sym`/
             ;; `::category` are per-variant dispatch maps (resolved by
             ;; `resolve-field`): `emitter`/`detector` get `::size 3` component
             ;; geometry (one photonic pin `P`, the sun/ring symbol), everything
             ;; else the plain size-1 port. The pin keeps the port net-key `P`,
             ;; so connectivity is identical to a plain port.
              ;; @tags circulax simulation
              "port" {::bg   {"emitter" cm/twoport-bg, "detector" cm/twoport-bg, ::default []}
                      ::conn {"emitter"  [{:name "P" :x 2 :y 1 :type "photonic" :side :right}]
                              "detector" [{:name "P" :x 2 :y 1 :type "photonic" :side :right}]
                              ::default  [{:name "P" :x 0 :y 0 :side :right}]}
                     ::sym  {"emitter" optical-source-elements
                             "detector" optical-detector-elements
                             ::default  port-sym}
                     ::category {"emitter" "source", "detector" "photonic-active", ::default nil}
                     ::template "{self.name}"
                     ::props [{:name "power" :variants #{"emitter"} :tooltip "Optical power"                           :unit "mW"  :default 1.0}
                              {:name "phase" :variants #{"emitter"} :tooltip "Phase"                                   :unit "rad" :default 0.0}
                              {:name "wl"    :variants #{"emitter"} :tooltip "Wavelength (or sweep \"start;stop;num\")" :unit "µm"  :default "1.5;1.6;300"}]}
             "text" {::bg []
                     ::conn []
                     ::sym text-sym
                     ::template "Operating Point:\n{res.op}"
                     ::props []}
             "group" {::bg []
                      ::conn []
                      ::sym nil
                      ::props []}})

(defn resolve-field
  "Value of model field `k` for device `dev`. A field whose value is a map with
   a `::default` key is a per-variant dispatch table — look it up by the
   device's `:variant`, falling back to `::default`. Any other value (a plain
   `[w h]`/conn vector, an elements map, a `[k v]` renderer fn) is returned
   verbatim. The `::default` key both marks the map as a dispatch table and
   supplies the fallback, so it never clashes with a `::sym` elements map (which
   has no `::default`). Consumed by every reader of the model geometry —
   rendering (`get-model`), connection indexing (`builtin-locations`,
   `device-port-types`), placement (`add-device`, `drag-staged-device`) and the
   `::category` styling class — so a variant's footprint stays self-consistent."
  [mod k dev]
  (let [v (get mod k)]
    (if (and (map? v) (contains? v ::default))
      (get v (:variant dev) (::default v))
      v)))

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
  (let [x2 (+ x rx) y2 (+ y ry)
        elbow? (and (not (zero? rx)) (not (zero? ry)))]
    [[[x y] [x2 y2]]  ; conn: start and end
     (case variant
       "hv" (concat (map #(vector % y) (exrange x rx))     ; horizontal leg
                    (when elbow? [[x2 y]])                  ; corner (only for real bends)
                    (map #(vector x2 %) (exrange y ry)))    ; vertical leg
       "vh" (concat (map #(vector x %) (exrange y ry))      ; vertical leg
                    (when elbow? [[x y2]])                   ; corner (only for real bends)
                    (map #(vector % y2) (exrange x rx)))     ; horizontal leg
       ;; "d" or nil: straight or diagonal
       (cond
         (zero? rx) (map #(vector x %) (exrange y ry))     ; vertical
         (zero? ry) (map #(vector % y) (exrange x rx))     ; horizontal
         :else []))]))

(defn builtin-locations [{:keys [:x :y :type :transform] :as dev}]
  (let [transform (or transform cm/IV)
        mod (get models type)
        conn (resolve-field mod ::conn dev)
        bg (resolve-field mod ::bg dev)
        [w h] (when (vector? bg) bg)
        size (if (and (number? w) (number? h))
               (+ 2 (max w h))
               (cm/pattern-size conn))]
    [(map (juxt :x :y) (rotate-shape conn size transform x y))
     (map (juxt :x :y) (rotate-shape (for [bx (range (or w 0)) by (range (or h 0))]
                                        {:x (+ (if w 1 0) bx) :y (+ (if h 1 0) by)})
                                      size transform x y))]))

;; @tags reactive indices, mosaic port exposure
(defn circuit-locations [models-snapshot {:keys [:x :y :model :transform :type :props]}]
  (let [transform (or transform cm/IV)
        mod (invocation/lookup models-snapshot (invocation/lookup-path (cm/model-key model) props))
        type (if (:generated mod) (:type mod type) type)
        ports (:ports mod)
        conn (if ports (cm/port-locations ports type) [])
        [w h] (if ports (cm/port-perimeter ports type) [1 1])
        size (+ 2 (max w h))]
    [(map (juxt :x :y) (rotate-shape conn size transform x y))
     (map (juxt :x :y) (rotate-shape (for [bx (range w) by (range h)]
                                        {:x (inc bx) :y (inc by)})
                                      size transform x y))]))

;; @tags reactive indices, mosaic port exposure
(defn device-locations [models-snapshot dev]
  (let [entry (invocation/lookup models-snapshot
                                 (invocation/lookup-path (cm/model-key (:model dev)) (:props dev)))
        dev (cond-> dev (:generated entry) (assoc :type (:type entry)))
        cell (:type dev)]
    (cond
      (= cell "wire") (wire-locations dev)
      (= cell "text") []
      (= cell "polyline") [[] []]
      (and (contains? models cell) (::conn (get models cell)))
      (builtin-locations dev)
      :else (circuit-locations models-snapshot dev))))

(def ^:private port-side-vectors
  {:left [-1 0]
   :right [1 0]
   :top [0 -1]
   :bottom [0 1]})

;; @tags mosaic port exposure
(defn- world-port-direction [transform side]
  (when-let [[sx sy] (port-side-vectors (keyword side))]
    (let [[a b c d _ _] (or transform cm/IV)
          dx (+ (* a sx) (* c sy))
          dy (+ (* b sx) (* d sy))]
      (if (> (abs dx) (abs dy))
        (if (neg? dx) :left :right)
        (if (neg? dy) :top :bottom)))))

;; @tags mosaic port exposure, reactive indices
(defn- transformed-device-ports
  "Return canonical transformed descriptors for a device from one model snapshot."
  [models-snapshot dev]
  (let [entry (invocation/lookup models-snapshot
                                 (invocation/lookup-path (cm/model-key (:model dev)) (:props dev)))
        dev (cond-> dev (:generated entry) (assoc :type (:type entry)))
        cell (:type dev)
        {:keys [x y]} dev
        transform (or (:transform dev) cm/IV)
        builtin? (and (contains? models cell) (::conn (get models cell)))
        [conn size]
        (if builtin?
          (let [mod (get models cell)
                conn (resolve-field mod ::conn dev)
                bg (resolve-field mod ::bg dev)
                [w h] (when (vector? bg) bg)]
            [conn
             (if (and (number? w) (number? h))
               (+ 2 (max w h))
               (cm/pattern-size conn))])
          (let [ports (:ports entry)
                conn (if ports (cm/port-locations ports cell) [])
                [w h] (if ports (cm/port-perimeter ports cell) [1 1])]
            [conn (+ 2 (max w h))]))]
    (map #(assoc % :direction (world-port-direction transform (:side %)))
         (rotate-shape conn size transform x y))))

;; @tags mosaic port exposure, reactive indices
(defn device-port-types
  "Return typed port maps [{:x :y :type ...}] for a device after rotation."
  [models-snapshot dev]
  (let [cell (:type dev)]
    (cond
      (= cell "wire") nil
      (= cell "text") nil
      (= cell "polyline") nil
      :else (let [builtin? (and (contains? models cell) (::conn (get models cell)))]
              (map #(cond-> (dissoc % :direction)
                      builtin? (dissoc :side))
                   (transformed-device-ports models-snapshot dev))))))

;; @tags livewire auto-route lvs arrows, mosaic port exposure
(defn- find-port-world-pos
  "Absolute grid coords of [dev-id port-name] in sch, or nil if either the
   device or the port is missing. Used by `schematic-airwires` to anchor an
   airwire arc at the actual Mosaic device pins (a polyline's own vertices
   live in Livewire's µm/y-down frame and don't apply here)."
  [models-snapshot sch dev-id port-name]
  (when-let [dev (get sch dev-id)]
    (let [target (name port-name)]
      (some (fn [p]
              (when (= (name (:name p)) target)
                [(:x p) (:y p)]))
            (device-port-types models-snapshot dev)))))

(defn- airwire-arc
  "A subtle dashed-red 'airwire' ratsnest arc between two grid points `p1`
   and `p2` (each `[gx gy]`), carrying `title` as a hover tooltip. Returns
   hiccup, or nil when the two points coincide. The visible stroke is a help
   target so the browser exposes the explanation without intercepting the
   rest of the schematic."
  [key p1 p2 title]
  (let [x1 (* (+ (first p1) 0.5) grid-size)
        y1 (* (+ (second p1) 0.5) grid-size)
        x2 (* (+ (first p2) 0.5) grid-size)
        y2 (* (+ (second p2) 0.5) grid-size)
        dx (- x2 x1)
        dy (- y2 y1)
        len (math/sqrt (+ (* dx dx) (* dy dy)))]
    (when (pos? len)
      (let [mx (/ (+ x1 x2) 2)
            my (/ (+ y1 y2) 2)
            sag (* 0.15 len)
            cx (+ mx (* (- (/ dy len)) sag))
            cy (+ my (* (/ dx len) sag))]
        [:g.airwire {:key key}
         [:title title]
         [:path {:d (str "M" x1 "," y1 " Q" cx "," cy " " x2 "," y2)}]]))))

(defn- mismatched-nets? [a b]
  (not (and a b (= a b))))

(defn- net-at
  "Return the schematic net for `dev-id.port-name`."
  [sch dev-id port-name]
  (get-in sch [dev-id :nets (keyword port-name)]))

;; @tags livewire auto-route lvs arrows, reactive indices
(defn- polyline-airwire [models-snapshot sch k v]
  (let [{:keys [start finish]} (:terminals v)
        s-net (net-at sch (:component_id start) (:port_name start))
        f-net (net-at sch (:component_id finish) (:port_name finish))]
    (when (mismatched-nets? s-net f-net)
      (let [p1 (find-port-world-pos models-snapshot sch (:component_id start) (:port_name start))
            p2 (find-port-world-pos models-snapshot sch (:component_id finish) (:port_name finish))]
        (when (and p1 p2)
          (airwire-arc [:poly k] p1 p2
                       "Connection present in layout but missing in schematic"))))))

;; @tags livewire auto-route lvs arrows, reactive indices
(defn- attached-port-airwire [models-snapshot sch k v]
  (let [ap (:attached_port v)
        port-net (get-in v [:nets :P])
        dev-net (net-at sch (:component_id ap) (:port_name ap))]
    (when (and ap (mismatched-nets? port-net dev-net))
      (let [p1 (find-port-world-pos models-snapshot sch k "P")
            p2 (find-port-world-pos models-snapshot sch (:component_id ap) (:port_name ap))]
        (when (and p1 p2)
          (airwire-arc [:port k] p1 p2
                       "Connection present in layout but missing in schematic"))))))

;; @tags livewire auto-route lvs arrows, reactive indices
(defn- build-schematic-airwires [models-snapshot sch]
  (concat
   (keep (fn [[k v]]
           (when (= "polyline" (:type v))
              (polyline-airwire models-snapshot sch k v)))
         sch)
   (keep (fn [[k v]]
           (when (and (= "port" (:type v)) (:attached_port v))
              (attached-port-airwire models-snapshot sch k v)))
         sch)))

;; --- point-index: unified per-point fold ---------------------------------
;; Replaces location-index (:ids/:body-ids), point-type-index (:types/:*-count),
;; and supplies :ports for wire-network attribution in one pass.

(defn- attributable?
  "Does this device carry named ports we can annotate with nets?
   Wires, text, and port docs are excluded."
  [dev]
  (not (cm/schematic-only-types (:type dev))))

(defn- record-ids [idx k id pts]
  (reduce (fn [i pt] (update-in i [pt k] (fnil conj #{}) id)) idx pts))

(defn- record-port [idx dev-id {:keys [x y name type]}]
  ;; Port names are stored as KEYWORDS in the in-memory representation so
  ;; they round-trip cleanly through jsatom/json->clj (which keywordizes
  ;; object keys on load). A string-keyed :nets written to disk reads back
  ;; as keyword-keyed, so we canonicalize at the source.
  (let [pt [x y]
        counter (when type (keyword (str (clojure.core/name type) "-count")))]
    (cond-> idx
      true    (update-in [pt :ports] (fnil conj []) [dev-id (keyword name)])
      type    (update-in [pt :types] (fnil conj #{}) type)
      counter (update-in [pt counter] (fnil inc 0)))))

;; @tags reactive indices, mosaic port exposure
(defn- record-device [models-snapshot idx [id dev]]
  (let [[legacy-connloc bodyloc] (device-locations models-snapshot dev)
        ports (when (attributable? dev)
                (transformed-device-ports models-snapshot dev))
        connloc (if (attributable? dev)
                  (map (juxt :x :y) ports)
                  legacy-connloc)]
    (as-> idx $
      (record-ids $ :ids id connloc)
      (record-ids $ :body-ids id bodyloc)
      (if ports
        (reduce #(record-port %1 id %2) $ ports)
        $))))

;; @tags reactive indices, mosaic port exposure
(defn build-point-index
  "{[x y] → {:ids #{dev-ids with a connection endpoint here}
             :body-ids #{dev-ids with body/path here}
             :ports [[dev-id port-name] ...]   ; attributable devices only
             :types #{\"photonic\" \"electric\"}
             :photonic-count N :electric-count N}}"
  [sch models-snapshot]
  (reduce (partial record-device models-snapshot) {} sch))

;; @tags reactive indices
;; Cached, render-facing form of build-point-index. See the `reactive indices`
;; spec for when to deref this track vs. call the builder directly.
(def point-index (r/track #(build-point-index @schematic @modeldb)))

;; @tags mosaic port exposure
(defn non-text-endpoint-ids
  "Remove text-only port labels from a point-index endpoint ID collection."
  [sch ids]
  (into #{} (remove #(= "text" (get-in sch [% :variant]))) ids))

;; @tags mosaic port exposure
(defn- named-device-ports [models-snapshot dev]
  (filter #(some? (:name %)) (transformed-device-ports models-snapshot dev)))

;; @tags mosaic port exposure
(defn exposable-device-ports
  "Return the selected component's named ports with dashed-NC occupancy."
  [models-snapshot sch pt-idx device-id]
  (let [dev (get sch device-id)]
    (if (and dev (attributable? dev))
      (filterv (fn [{:keys [x y]}]
                 (= #{device-id}
                    (non-text-endpoint-ids sch (get-in pt-idx [[x y] :ids]))))
               (named-device-ports models-snapshot dev))
      [])))

;; @tags mosaic port exposure
(defn port-exposure-state
  "Return properties-action state for device-id, or nil when inapplicable."
  [models-snapshot sch pt-idx device-id]
  (let [dev (get sch device-id)
        ports (when (and dev (attributable? dev))
                (vec (named-device-ports models-snapshot dev)))]
    (when (seq ports)
      {:disabled? (empty? (exposable-device-ports
                           models-snapshot sch pt-idx device-id))})))

(def ^:private marker-transforms
  {:left cm/IV
   :right [-1 0 0 1 0 0]
   :top [0 1 -1 0 0 0]
   :bottom [0 -1 1 0 0 0]})

;; @tags mosaic port exposure
(defn- next-exposure-name [sch]
  (let [reserved (into (set (map str (keys sch)))
                       (keep (comp #(when (seq %) %) str :name))
                       (vals sch))]
    (first
     (remove #(or (contains? reserved %)
                  (contains? reserved (str group sep %)))
             (map #(str "P" %) (next (range)))))))

;; @tags mosaic port exposure
(defn build-port-exposure-docs
  "Build one deterministic co-located port-marker batch without mutating sch."
  [models-snapshot sch pt-idx device-id]
  (:docs
   (reduce
    (fn [{working-sch :sch docs :docs} {:keys [x y direction]}]
      (let [port-name (next-exposure-name working-sch)
            port-id (str group sep port-name)
            port-doc {:type "port"
                      :x x
                      :y y
                      :transform (marker-transforms direction)
                      :name port-name}]
        {:sch (assoc working-sch port-id port-doc)
         :docs (assoc docs port-id port-doc)}))
    {:sch sch :docs {}}
    (exposable-device-ports models-snapshot sch pt-idx device-id))))

;; @tags mosaic port exposure
(defn expose-ports!
  "Write all currently exposable markers as one batch and run one action lifecycle."
  [device-id]
  (go
    (loop [pending (done? schematic)]
      (when pending
        (<! pending)
        (let [next-pending (done? schematic)]
          (when-not (identical? pending next-pending)
            (recur next-pending)))))
    (let [sch @schematic
          models-snapshot @modeldb
          pt-idx (build-point-index sch models-snapshot)
          docs (build-port-exposure-docs models-snapshot sch pt-idx device-id)]
      (when (seq docs)
        (swap! schematic merge docs)
        (<! (post-action! models-snapshot))
        docs))))

;; @tags mosaic make array
(def array-fqn
  "Fully-qualified name of the built-in generic array factory
   (specs/mosaic_make_array.md)."
  "gdsfactory.components.containers.array_component.array")

;; @tags mosaic make array
(defn array-able?
  "Eligibility for the Make array action, given the device's bare `model` and its
   resolved `model-def`. Mirrors `editable-model?`'s arity; see
   specs/mosaic_make_array.md."
  [model model-def]
  (boolean
   (and (seq model)
        (not (clojure.string/ends-with? model ".gsch"))
        (not= model array-fqn)
        (seq (:name model-def)))))

;; @tags mosaic make array
(defn make-array!
  "Transform device `device-id` in place into the generic array of its current
   component: one whole-document `swap!` on `schematic` followed by the standard
   `post-action!` lifecycle (one undo checkpoint), see
   specs/mosaic_make_array.md."
  [device-id]
  (go
    (let [dev (get @schematic device-id)
          model-def (model-entry (:model dev) (:props dev))
          array-def (get @modeldb (cm/model-key array-fqn))
          defaults (or (cm/model-prop-defaults array-def) {})]
      (swap! schematic update device-id
             #(assoc % :type "ckt"
                       :model array-fqn
                       :props (merge defaults {:component (:name model-def)})))
      (<! (post-action!)))))

;; --- Shift-drag connectivity: stretch wires attached to dragged devices ---

(defn connected-wire-ends
  "{wire-id -> #{:start :end}}: non-selected wires whose endpoint sits on a
   selected device's port. point-index :ports excludes wires, so 'a selected id
   has a port at this cell' == 'a selected device pin is here'."
  [sch pt-idx selected]
  (letfn [(stretched [x y k]
            (when (some selected (map first (get-in pt-idx [[x y] :ports]))) k))]
    (into {}
          (for [[id {:keys [type x y rx ry]}] sch
                :when (and (= "wire" type) (not (contains? selected id)))
                :let  [ends (into #{} (remove nil?)
                                  [(stretched x y :start)
                                   (stretched (+ x rx) (+ y ry) :end)])]
                :when (seq ends)]
            [id ends]))))

(defn stretch-wire
  "Move the wire's flagged end(s) by (dx,dy), unrounded (callers round on commit).
   :start moves :x/:y and compensates :rx/:ry so the far end stays put; :end moves
   :rx/:ry. A double-sided wire (#{:start :end}) translates wholesale, as the two
   :rx/:ry compensations cancel.

   Keeping wires orthogonal: a cardinal (axis-aligned) straight bent off-axis
   becomes an hv/vh elbow that keeps the non-dragged end pointing in its original
   direction — a fixed choice that never flips mid-drag. Real diagonals (drawn
   with Shift) and existing elbows are left as-is."
  [{:keys [variant] :as wire} ends dx dy]
  (let [cardinal?   (and (#{nil "d"} variant)
                         (or (zero? (:rx wire)) (zero? (:ry wire))))
        horiz?      (zero? (:ry wire))                  ; original orientation
        drag-start? (and (:start ends) (not (:end ends)))
        w (cond-> wire
            (:start ends) (-> (update :x + dx) (update :y + dy)
                              (update :rx - dx) (update :ry - dy))
            (:end ends)   (-> (update :rx + dx) (update :ry + dy)))]
    (cond-> w
      (and cardinal? (not (zero? (:rx w))) (not (zero? (:ry w))))
      (assoc :variant (if (not= horiz? drag-start?) "hv" "vh")))))

(defn commit-stretch
  "PAtom merge fn (map-keyed swap): move each wire's flagged end(s) by (dx,dy) and
   snap to the grid. A stretch that collapses a wire onto a single cell deletes it
   (nil tombstone) — like `same-tile?` refuses zero-length wires when drawing; the
   coincident ports stay connected via the shared cell, so nothing disconnects.
   Guards on (contains? s id) so conflict retries — which refetch only the still-
   conflicting subset while the full `stretch` map is re-passed — don't fabricate
   docs for already-committed ids via a nil `(get s id)`."
  [sch stretch dx dy]
  (reduce-kv (fn [s id ends]
               (if (contains? s id)
                 (let [w (-> (stretch-wire (get s id) ends dx dy)
                             (update :x math/round)  (update :y math/round)
                             (update :rx math/round) (update :ry math/round))]
                   (assoc s id (when-not (and (zero? (:rx w)) (zero? (:ry w))) w)))
                 s))
             sch stretch))

;; --- wire-networks: shared BFS over the wire graph -----------------------

(def ^:private wire-like #{"wire" "port"})

(defn- doc-endpoints
  "Cells this doc contributes as connection endpoints.
   Wires: both ends. Port docs: their own cell."
  [{:keys [x y rx ry type]}]
  (case type
    "wire" [[x y] [(+ x rx) (+ y ry)]]
    "port" [[x y]]))

(def ^:private empty-component
  {:wires #{} :attributions [] :port-names [] :port-ids [] :wire-names [] :points #{}})

(defn- absorb-cell
  "Record a cell's attributions into the component-in-progress."
  [comp point-idx cell]
  (-> comp
      (update :points conj cell)
      (update :attributions into (:ports (get point-idx cell)))))

(defn- absorb-doc
  "Record a wire-like doc's contribution — :wires for wires, plus :wire-names
   / :port-names and :port-ids when named. :port-ids lets build-netlist map
   each port doc back to its component's net name so we can write the
   cross-editor :nets annotation onto the port itself."
  [comp id dev]
  (let [nm (:name dev)]
    (cond-> comp
      (= "wire" (:type dev))          (update :wires conj id)
      (and (= "wire" (:type dev)) nm) (update :wire-names conj nm)
      (and (= "port" (:type dev)) nm) (update :port-names conj nm)
      (and (= "port" (:type dev)) nm) (update :port-ids conj id))))

(defn- expand-from-cell
  "BFS from `seed-cell`. The queue holds cells; wire-like docs are edges that
   get absorbed when the BFS walks their endpoint cells. Returns
   [component visited-cells visited-docs]."
  [sch point-idx visited-cells visited-docs seed-cell]
  (loop [queue         [seed-cell]
         visited-cells (conj visited-cells seed-cell)
         visited-docs  visited-docs
         comp          (absorb-cell empty-component point-idx seed-cell)]
    (if-let [cell (peek queue)]
      (let [new-doc-ids (for [id (:ids (get point-idx cell))
                              :when (and (wire-like (:type (get sch id)))
                                         (not (visited-docs id)))]
                          id)
            comp'       (reduce (fn [c id] (absorb-doc c id (get sch id)))
                                comp new-doc-ids)
            new-cells   (->> new-doc-ids
                             (mapcat (fn [id] (doc-endpoints (get sch id))))
                             (remove visited-cells)
                             distinct)
            comp''      (reduce #(absorb-cell %1 point-idx %2) comp' new-cells)]
        (recur (into (pop queue) new-cells)
               (into visited-cells new-cells)
               (into visited-docs new-doc-ids)
               comp''))
      [comp visited-cells visited-docs])))

(defn- port-cell-seeds
  "Cells in point-idx carrying device-port attributions, sorted by [x y] for
   deterministic netN numbering."
  [point-idx]
  (->> point-idx
       (filter (fn [[_ v]] (seq (:ports v))))
       (map first)
       sort))

(defn- wire->component-index [components]
  (into {} (for [[i c] (map-indexed vector components)
                 w (:wires c)]
             [w i])))

(defn build-wire-networks
  "Partition device-port cells into connected components via wire-like docs.
   Returns {:components [{:wires :attributions :port-names :wire-names :points} ...]
            :wire->component {wire-id -> component-idx}}.

   Seeds are cells carrying :ports attributions — every device port is a
   first-class seed. Wire-like docs (wires, port-docs) are edges absorbed
   during traversal. Dangling wires without any port contact produce no
   component (they have no netlist consumer)."
  [sch point-idx]
  (loop [[seed-cell & rst] (port-cell-seeds point-idx)
         visited-cells     #{}
         visited-docs      #{}
         components        []]
    (cond
      (nil? seed-cell)         {:components components
                                :wire->component (wire->component-index components)}
      (visited-cells seed-cell) (recur rst visited-cells visited-docs components)
      :else (let [[comp vc vd] (expand-from-cell sch point-idx
                                                 visited-cells visited-docs
                                                 seed-cell)]
              (recur rst vc vd (conj components comp))))))

(def wire-networks
  (r/track #(build-wire-networks @schematic @point-index)))

;; --- net-type-index: a fold over wire-networks ---------------------------

(defn- network-type
  "First port type encountered at any point in the network, or nil."
  [point-idx points]
  (some #(first (get-in point-idx [% :types])) points))

(defn build-net-type-index
  "{doc-id => \"photonic\"|\"electric\"|nil} for every wire AND port doc, keyed
   by the type of the net it belongs to (the first typed device port on the
   net, via `network-type`). One index serves two consumers: wires drive
   schematic wire coloring / LVS, and port docs feed the `:nature` annotation
   the nyanlib indexer reads. Untyped/isolated nets are nil; the port-nature
   consumer defaults those to photonic at read time.

   Feeding port-ids alongside wires is behavior-preserving for the wire
   consumers: a port doc's net type is always either nil (isolated) or equal to
   the wire/device type already present at its point."
  [{:keys [components]} point-idx]
  (reduce
   (fn [acc {:keys [wires port-ids points]}]
     (let [t (network-type point-idx points)]
       (reduce #(assoc %1 %2 t) acc (concat wires port-ids))))
   {} components))

;; @tags reactive indices
;; Cached, render-facing form (wire coloring, LVS dots). The commit pipeline
;; calls build-net-type-index directly on its snapshot instead. See the
;; `reactive indices` spec.
(def net-type-index
  (r/track #(build-net-type-index @wire-networks @point-index)))

;; --- netlist: fold wire-networks into per-device {port net} --------------

(defn- net-name [idx {:keys [port-names wire-names]}]
  (or (first port-names) (first wire-names) (str "net" idx)))

(defn- assign-net [nets net attributions]
  (reduce (fn [n [dev port]] (assoc-in n [dev port] net)) nets attributions))

(defn- component-net-names
  "Per-component net name. Priority: port-doc name > wire name > 'netN'."
  [components]
  (into [] (map-indexed net-name) components))

(defn build-netlist
  "Returns {:devices {dev-id {port-name net-name}}
            :wires   {wire-id net-name}
            :ports   {port-doc-id net-name}} from a wire-networks partition.
   Net name priority: first port-doc name > first wire name > generated 'netN'.

   :ports maps each named port doc to its component's net name so the port can
   carry an explicit cross-editor :nets annotation (consumed by Livewire's
   derive-and-glue and the compile-time nets fallback)."
  [{:keys [components wire->component]}]
  (let [names   (component-net-names components)
        devices (reduce (fn [acc [i comp]]
                          (assign-net acc (nth names i) (:attributions comp)))
                        {} (map-indexed vector components))
        wires   (into {} (for [[wid ci] wire->component] [wid (nth names ci)]))
        ports   (into {} (for [[i comp] (map-indexed vector components)
                               pid (:port-ids comp)]
                           [pid (nth names i)]))]
    {:devices devices :wires wires :ports ports}))

;; @tags nyancir format, reactive indices
(defn build-net-annotations
  "Return {doc-id {:nets …}|{:net …}|{:nature …}} with only the docs whose
   annotation needs to change. Keys of this map drive which docs the pouch/
   JsAtom swap will touch, so unchanged docs are deliberately omitted.

   `net-types` is the {doc-id => \"photonic\"|\"electric\"|nil} map from
   `build-net-type-index`. It is the sole source of a port's `:nature` — nature
   is derived FROM the net type, never the reverse."
  [sch {:keys [devices wires ports]} net-types]
  (letfn [(wire-update [id dev]
            (let [desired (get wires id)]
              (when (not= (:net dev) desired)
                {:net desired})))
          (device-update [id dev]
            (let [desired (get devices id {})
                  current (or (:nets dev) {})]
              (when (not= current desired)
                {:nets desired})))
          (port-update [id dev]
            ;; A port doc carries explicit connectivity as {:nets {:P net}} (so
            ;; Livewire, which never writes :nets, and the compile-time nets
            ;; fallback can resolve what the port exposes) plus :nature, the type
            ;; of the net it sits on. Each is diffed independently so a change to
            ;; one never rewrites the other.
            ;;
            ;; :nets is diffed unconditionally, exactly as device-update does.
            ;; A port that leaves every network must have the annotation
            ;; cleared, not merely left alone: a stale {:P net} keeps the port
            ;; claiming a net that no longer exists, and `post-action!`
            ;; checkpoints after annotating, baking it into every later undo
            ;; state.
            (let [net     (get ports id)
                  desired (if net {:P net} {})
                  nature  (or (get net-types id) "photonic")]
              (cond-> nil
                (not= (or (:nets dev) {}) desired) (assoc :nets desired)
                (not= (:nature dev) nature)        (assoc :nature nature))))
          (entry [id dev]
            (cond
              (= "wire" (:type dev)) (wire-update id dev)
              (= "port" (:type dev)) (port-update id dev)
              (attributable? dev)    (device-update id dev)))]
    (reduce-kv
     (fn [acc id dev]
       (if-let [u (entry id dev)]
         (assoc acc id u)
         acc))
     {} sch)))

;; --- wire-split-index: walk point-index ----------------------------------

(defn build-wire-split-index
  "{wire-id → #{points-to-split-at}}. A wire whose path passes through a
   connection endpoint needs to be split at that point."
  [sch point-idx]
  (letfn [(wire? [id] (= "wire" (:type (get sch id))))
          (accumulate [acc pt {:keys [ids body-ids]}]
            (if (and (seq ids) (seq body-ids))
              (reduce #(update %1 %2 cm/ssconj pt) acc (filter wire? body-ids))
              acc))]
    (reduce-kv accumulate {} point-idx)))

(defn wire-corner
  "Compute the corner position of an elbow wire, or nil for straight/diagonal."
  [{:keys [x y rx ry variant]}]
  (case variant
    "hv" [(+ x rx) y]
    "vh" [x (+ y ry)]
    nil))

(defn split-wire [wirename coords point-idx]
  (let [{:keys [x y rx ry variant] :as wire} (get @schematic wirename)
        x2 (+ x rx)
        y2 (+ y ry)
        [_ body] (wire-locations wire)
        ;; body is already in path order (including corner for elbows)
        split-set (set coords)
        ordered (concat [[x y]] (filter split-set body) [[x2 y2]])
        ;; Check if two points share a non-wire device, or a wire with same corner
        shared-device? (fn [p1 p2 seg-corner]
                         (let [shared (clojure.set/intersection
                                        (:ids (get point-idx p1))
                                        (:ids (get point-idx p2)))]
                           (some #(let [dev (get @schematic %)]
                                    (or (not= "wire" (:type dev))
                                        (= seg-corner (wire-corner dev))))
                                 shared)))
        ;; Surviving segments, in path order. Drop any segment already bridged
        ;; by a device (or a co-cornered wire) — those points are connected
        ;; without a wire of our own.
        kept (for [[[x1 y1] [xn yn]] (partition 2 1 ordered)
                   :let [seg-corner (case variant
                                      "hv" [xn y1]
                                      "vh" [x1 yn]
                                      nil)]
                   :when (not (shared-device? [x1 y1] [xn yn] seg-corner))]
               {:type "wire" :transform cm/IV :variant variant
                :x x1 :y y1 :rx (- xn x1) :ry (- yn y1)})
        seg-id (fn [{sx :x sy :y}] (str wirename "-split-" sx "-" sy))
        additions (zipmap (cons wirename (map seg-id (rest kept))) kept)]
    (if (seq kept)
      (swap! schematic (partial merge-with merge) additions)
      ;; Every segment was redundant: the whole wire is subsumed by devices,
      ;; so remove it rather than leaving it floating.
      (swap! schematic dissoc wirename))))


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

(defn normalize-wheel-delta
  "Normalize wheel deltaY to pixels, clamp to ±300"
  [e]
  (let [dy (.-deltaY e)
        mode (.-deltaMode e)
        scaled (case mode
                 1 (* dy 16)   ; DOM_DELTA_LINE
                 2 (* dy 100)  ; DOM_DELTA_PAGE
                 dy)]           ; DOM_DELTA_PIXEL
    (-> scaled (max -300) (min 300))))

(defn scroll-zoom [e]
  (let [[x y] (cm/viewbox-coord e)
        dy (normalize-wheel-delta e)
        direction (-> (/ dy 50) (max -1) (min 1))]
    (zoom-schematic direction (* x grid-size) (* y grid-size))))

(defn button-zoom [dir]
  (let [[x y w h] (::zoom @ui)]
    (zoom-schematic dir
                    (+ x (/ w 2))
                    (+ y (/ h 2)))))

;; @tags mosaic viewport zoom
(defn fit-viewbox
  [bx by bw bh container-w container-h]
  (let [pad 0.1
        pw (* bw pad) ph (* bh pad)
        cx (- bx pw) cy (- by ph)
        cw (+ bw (* 2 pw)) ch (+ bh (* 2 ph))
        cw (max cw grid-size) ch (max ch grid-size)
        content-ar (/ cw ch)
        container-ar (/ container-w container-h)]
    (if (> container-ar content-ar)
      (let [new-w (* ch container-ar)
            dx (/ (- new-w cw) 2)]
        [(- cx dx) cy new-w ch])
      (let [new-h (/ cw container-ar)
            dy (/ (- new-h ch) 2)]
        [cx (- cy dy) cw new-h]))))

;; @tags mosaic viewport zoom
(defn zoom-to-fit []
  (when-let [^js svg (js/document.querySelector ".mosaic-canvas")]
    (when-let [^js el (.querySelector svg ".schematic-content")]
      (let [bbox (.getBBox el)
            bw (.-width bbox) bh (.-height bbox)]
        (when (and (pos? bw) (pos? bh))
          (let [vb (fit-viewbox (.-x bbox) (.-y bbox) bw bh
                                (.-clientWidth svg) (.-clientHeight svg))]
            (reset! ui (assoc @ui ::zoom vb))))))))

;; @tags factory resolution, nyanlib default contract, mosaic wire tool
(defn- insert-staged! [dev model-def]
  (let [;; Snap to grid on drop (staged x/y follow the cursor unrounded). Wire
        ;; staging is already integer, so this is a no-op there.
        resolved-type (or (:type model-def) (:type dev))
        dev (-> dev
                (cond-> (:model dev)
                  (assoc :type (if (contains? cm/device-types resolved-type) resolved-type "ckt")))
                (update :x math/round) (update :y math/round))
        named (update dev :name (fnil identity (make-name (:type dev))))
        ;; Port devices (ground, supply, labels) keep a fixed/constant display
        ;; :name, so it can't double as a unique id the way "R1", "C1", etc.
        ;; do for other device types. Give ports their own numbered id instead,
        ;; matching the scheme paste already uses for pasted port devices.
        id-name (if (= "port" (:type named)) (make-name "port") (:name named))
        id (str group sep id-name)
        defaults (cm/model-prop-defaults model-def)
        with-props (if defaults
                     (update named :props #(merge defaults %))
                     named)]
    (swap! schematic assoc id with-props)
    (post-action!)))

;; @tags factory resolution
(defn- queue-resolved-placement!
  "Serialize a resolved intent through insertion and its undo checkpoint.
   Return a completion channel; cancellation skips insertion but still releases
   the queue, and insertion failures release it through finally."
  [dev model-def epoch]
  (let [previous @placement-tail
        finished (promise-chan)]
    ;; Only resolved commits enter this queue. Cancellation never bypasses an
    ;; active undo transaction, even when a newer intent has already resolved.
    (reset! placement-tail finished)
    (go
      (<! previous)
      (try
        (when (= epoch @placement-epoch)
          (<! (insert-staged! dev model-def)))
        (finally
          (put! finished true))))))

;; @tags factory resolution, nyanlib default contract
(defn commit-staged
  "Commit a captured device intent, resolving component metadata before insertion.
   Return a completion channel for non-nil dev, or nil for nil dev. The channel's
   yielded value is unspecified; completion also covers failed or cancelled
   resolution. Resolution failures alert without inserting a device."
  [dev]
  (when dev
    (if-let [model (:model dev)]
      (let [epoch @placement-epoch
            result (apply get-model-async modeldb
                          (invocation/lookup-path (cm/model-key model) (:props dev)))]
        (go
          (let [model-def (<! result)]
            (when (= epoch @placement-epoch)
              (if (instance? js/Error model-def)
                (cm/alert (str "Cannot place component: " (ex-message model-def)))
                (<! (queue-resolved-placement! dev model-def epoch)))))))
      (insert-staged! dev nil))))

;; @tags mosaic clipboard, editor keyboard shortcuts
(defn transform-selected [tf]
  (let [f (comp transform-vec tf transform)
        g #(f (or % cm/IV))]
    (cond
      @staging (swap! staging update :transform g)
      @paste-staging (swap! paste-staging update-keys @selected
                            update :transform g)
      :else (do (swap! schematic update-keys @selected
                       update :transform g)
                (post-action!)))))

;; @tags mosaic clipboard, editor keyboard shortcuts
(defn move-selected [dx dy]
  (cond
    @paste-staging
    (swap! paste-staging update-keys @selected
           #(-> % (update :x + dx) (update :y + dy)))

    (seq @selected)
    (do (swap! schematic update-keys @selected
               #(-> % (update :x + dx) (update :y + dy)))
        (post-action!))

    :else
    (swap! ui update ::zoom
           (fn [[x y w h]] [(+ x (* dx grid-size)) (+ y (* dy grid-size)) w h]))))

(defn tab-next []
  (let [type (or (when-let [s @staging] (:type s))
                 (when-let [id (first @selected)]
                   (:type (get @schematic id))))]
    (when type
      (when @staging
        (swap! ui assoc ::staging nil ::tool ::cursor))
      (let [ids (->> @schematic
                     (filter (fn [[_ v]] (= type (:type v))))
                     keys sort vec)
            n (count ids)]
        (when (pos? n)
          (let [idx (swap! tab-index #(mod (inc %) n))]
            (reset! selected #{(nth ids idx)})))))))
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
        dy (- y ys)
        shift? (.-shiftKey e)
        on?   (::stretch @ui)]
    (swap! ui update ::delta assoc :x dx :y dy)
    ;; ::stretch is the live gate. Toggle it on the Shift edges only: compute the
    ;; attached-wire map on press (rare, and stable since selection/schematic are
    ;; frozen mid-drag), drop it on release; leave it untouched in between.
    (cond
      (and shift? (not on?))
      (swap! ui assoc ::stretch (connected-wire-ends @schematic @point-index (::selected @ui)))
      (and (not shift?) on?)
      (swap! ui dissoc ::stretch))))

(defn drag-wire [^js e]
  (let [[x y] (cm/viewbox-coord e)]
    (swap! staging
           (fn [d]
             (let [rx (- x (:x d) 0.5)
                   ry (- y (:y d) 0.5)
                   diagonal? (.-shiftKey e)
                   straight? (or (< (abs rx) 0.5)
                                 (< (abs ry) 0.5))]
               (cond
                 ;; Shift held: diagonal
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

;; @tags mosaic device tray, mosaic model picker
(defn staging-origin
  "The `[:x :y]` that puts device doc `dev`'s visual center on grid point
   `[x y]`, unrounded. `draw-background` insets the tetris rect by one tile, so
   a `[w h]` device's center sits at `[:x + 1 + w/2, :y + 1 + h/2]` — invert
   that. Devices with no tetris rect (`device-dims` nil, e.g. text and plain
   ports) render a single tile at the origin, centered at `[:x + .5, :y + .5]`.

   Shared by `add-device` and `drag-staged-device` so the ghost cannot shift
   between arming and the first pointer move; only `add-device` rounds."
  [dev [x y]]
  (if-let [[w h] (device-dims dev)]
    [(- x (/ w 2) 1) (- y (/ h 2) 1)]
    [(- x 0.5) (- y 0.5)]))

;; @tags mosaic device tray, mosaic model picker
(defn drag-staged-device [e]
  ;; Unrounded: the placement ghost follows the cursor smoothly, like the drag
  ;; ghost; commit-staged rounds so it snaps to the grid on drop.
  (let [[x y] (staging-origin @staging (cm/viewbox-coord e))]
    (swap! staging assoc :x x :y y)))

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

;; @tags mosaic wire tool, editor keyboard shortcuts
(defn momentary-tool!
  "Enter a momentary tool, saving the tool to return to in the one-shot
   ::prev-tool slot. Shared by the pen eraser, middle-click pan, hold-Space pan,
   and the port-press wire gesture; each entry site owns its own guards, this
   owns the slot write. `maybe-restore-tool!` is the matching exit."
  [tool]
  (swap! ui #(assoc % ::prev-tool (::tool %) ::tool tool)))

;; @tags mosaic wire tool
(defn port-wire-start
  "Launch the wire tool momentarily from a port-dot press. Saves ::prev-tool and
   sets ::tool ::wire; completion or Escape restores it (see
   maybe-restore-tool!). Does not stop-propagate and does not arm the wire — the
   pointer-down bubbles to the device/background dispatch, which calls add-wire
   at the click cell (the port) exactly as a normal wire-tool click.

   Fires only for a primary left-click while the cursor tool is active. Every
   other tool owns the click on its own terms: ::device would lose the staged
   device (add-wire overwrites ::staging), ::eraser and ::probe would never
   reach their handlers in drag-start, ::pan would wire instead of pan, and
   ::wire already arms at the port through the normal dispatch. The pen eraser
   presses while the tool is still ::cursor, so it needs its own exclusion."
  [e]
  (when (and (.-isPrimary e)
             (= (.-button e) 0)
             (not (pen-eraser? e))
             (= ::cursor (::tool @ui)))
    (momentary-tool! ::wire)))

;; @tags mosaic wire tool, editor keyboard shortcuts
(defn maybe-restore-tool!
  "Restore ::tool from the one-shot ::prev-tool slot and clear the slot. The
   single restore path: the momentary release sites (on-pointer-up-bg for
   pen-eraser and middle-click, the Space keyup map) and the wire tool's
   staging/dragging clear sites (drag-end non-zero, add-wire on-port/same-tile,
   cancel 0-arity) all go through it.

   No-op when the slot is nil, which happens whenever a restore site fires
   without a matching entry — a sticky-W wire, a keyup whose keydown never
   reached us, a release after Escape already consumed the slot. ::tool is a
   closed set in the ::ui spec, so writing nil there trips the ui validator
   instead of harmlessly doing nothing."
  []
  (when-let [t (::prev-tool @ui)]
    (swap! ui assoc ::tool t ::prev-tool nil)))

;; @tags mosaic wire tool
(defn add-wire [[x y] first?]
  (if first?
    (add-wire-segment [x y]) ; just add a new wire, else finish old wire
    (let [dev (update-keys @staging #{:rx :ry} math/round)
          {rx :rx ry :ry} dev
          x (math/round (+ (:x dev) rx)) ; use end pos of previous wire instead
          y (math/round (+ (:y dev) ry))
          pt (get @point-index [x y])
          on-port (or (seq (:ids pt)) (seq (:body-ids pt)))]
      (cond
        (same-tile? dev) (do (swap! ui assoc ; the dragged wire stayed at the same tile, exit
                                    ::staging nil
                                    ::dragging nil)
                             (maybe-restore-tool!))
        on-port (do (swap! ui assoc ::staging nil ::dragging nil)
                    (commit-staged dev)
                    (maybe-restore-tool!))
        :else (do (swap! ui assoc ::dragging nil)
                  (go (<! (commit-staged dev))
                      (add-wire-segment [x y])))))))

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
          (let [pt-idx @point-index
                newsel (clojure.set/difference
                        (set (filter wire? (mapcat #(:ids (get pt-idx %)) ports)))
                        sel)
                newports (reduce wire-ports #{} (map schem newsel))]
            (recur
             (clojure.set/difference newports ports)
             (into sel newsel)))
          (reset! selected sel))))))

;; @tags mosaic clipboard
(defn- keep-existing
  "Filter a selection set to ids still present in the schematic, dropping
  orphans (e.g. pasted ids from a cancelled paste that were never inserted)."
  [sel sch]
  (into #{} (filter #(contains? sch %)) sel))

;; @tags mosaic clipboard, mosaic wire tool
(defn cancel
  "Cancel current operation and optionally switch to a new tool.
   0-arity: default behavior (keep wire tool if drawing wire, else cursor)
   1-arity: switch to specified tool and clear staging

   Retires in-flight placement intents so late resolution cannot insert them;
   an already-started insertion/undo transaction is allowed to finish.

   Also resets ::delta to zero and cleans ::selected against @schematic so a
   cancelled drag (paste or move) can't leak its pointer offset onto the
   next selection — without the ::delta reset, click-to-reselect after Esc
   flows to drag-end → end-ui, which applies the stale cursor-at-Esc −
   centroid offset to the newly selected instance."
  ([]
   (swap! placement-epoch inc)
   (let [uiv @ui]
     (if (or (::staging uiv) (::dragging uiv) (not= (::tool uiv) ::cursor))
       (let [tool (if (and (::staging uiv) (= (::tool uiv) ::wire)) ::wire ::cursor)]
         (swap! ui assoc
                ::dragging nil
                ::tool tool
                ::staging nil
                ::stretch nil
                ::paste-staging nil
                ::delta {:x 0 :y 0 :rx 0 :ry 0}
                ::selected (keep-existing (::selected uiv) @schematic)))
       (swap! ui assoc ::selected #{}))
     ;; Escape ends a port-armed wire, so the momentary restore fires here.
     (maybe-restore-tool!)))
  ([new-tool]
   (swap! placement-epoch inc)
   (let [uiv @ui]
     (swap! ui assoc
            ::dragging nil
            ::tool new-tool
            ::staging nil
            ::stretch nil
            ::paste-staging nil
            ;; An explicit tool pick wins over the momentary restore: W during a
            ;; port-armed wire makes it sticky, and neither a later release nor
            ;; a completion may drag the user back out of the chosen tool.
            ::prev-tool nil
            ::delta {:x 0 :y 0 :rx 0 :ry 0}
            ::selected (keep-existing (::selected uiv) @schematic)))))

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

;; @tags mosaic wire tool
(defn on-pointer-down-bg [e]
  (cm/clear-context-menu)
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
        (momentary-tool! ::eraser)

        ;; Middle click: momentary pan
        (= (.-button e) 1)
        (do (reset! last-coord [(.-clientX e) (.-clientY e)])
            (momentary-tool! ::pan))

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

;; @tags mosaic wire tool
(defn on-pointer-up-bg [e]
  (swap! ui update ::pointer-cache dissoc (.-pointerId e))
  ;; Restore tool from momentary switch (pen eraser or middle-click pan)
  (when (or (pen-eraser? e) (= (.-button e) 1))
    (maybe-restore-tool!))
  ;; Primary pointer: normal drag end (only if not in gesture)
  (when (and (.-isPrimary e) (< (count (::pointer-cache @ui)) 2))
    (drag-end e)))

(defn remove-pointer
  "Remove pointer from cache. Used for pointercancel."
  [e]
  (swap! ui update ::pointer-cache dissoc (.-pointerId e)))

;; @tags mosaic clipboard, mosaic wire tool
(defn on-pointer-down-element [k e]
  (.stopPropagation e)
  (cm/clear-context-menu)
  (let [id (.-pointerId e)
        entry {:x (.-clientX e) :y (.-clientY e)}
        paste? (some? @paste-staging)
        is-dblclick (and (not paste?) (.-isPrimary e) (check-double-click e))]
    (swap! ui assoc-in [::pointer-cache id] entry)
    (cond
      ;; Double-click: handle and skip normal processing
      is-dblclick
      (double-click k e)

      (= 2 (count (::pointer-cache @ui)))
      (cancel)

      ;; Pen eraser: momentary eraser
      (pen-eraser? e)
      (momentary-tool! ::eraser)

      ;; Middle click: momentary pan
      (= (.-button e) 1)
      (do (reset! last-coord [(.-clientX e) (.-clientY e)])
          (momentary-tool! ::pan))

      ;; The active paste owns primary interaction until pointer-up commits it.
      paste? nil

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

;; @tags photonic model ports, factory resolution
(defn get-model
  [layer model k v]
  ;; Native renderers may never read model metadata themselves. Register demand
  ;; here before selecting the invocation's representation from the symbol table.
  (let [entry (model-entry (:model model) (:props model))
        ;; Invalidated Dschematic still supplies last-known presentation; a cold
        ;; skeleton has neither runtime metadata nor Dschematic presence.
        resolved? (and (:generated entry) (or (:metadata-version entry) (:has-dschematic entry)))
        model (cond-> model resolved? (assoc :type (:type entry)))
        v (cond-> v resolved? (assoc :type (:type entry)))
        symbol-def (or (get models (:type model))
                       {::bg #'circuit-shape
                        ::conn #'circuit-conn
                        ::sym #'circuit-sym})
        ;; resolve per-variant dispatch maps to concrete data/renderer BEFORE the
        ;; cond, so `(fn? m)` only ever sees genuine [k v] renderers
        m (resolve-field symbol-def layer model)]
    (cond
      ;; Explicit no-symbol model (e.g. polyline): render nothing. Its airwire
      ;; ratsnest is drawn by the `schematic-airwires` layer instead.
      (and (= layer ::sym) (nil? m) (contains? symbol-def ::sym)) nil
      (and (nil? m) (= layer ::bg))   ^{:key k} [circuit-shape k v]
      (and (nil? m) (= layer ::conn)) ^{:key k} [circuit-conn k v]
      (and (nil? (::conn symbol-def))
           (= layer ::sym) (map? m)) ^{:key k} [photonic-sym m k v]
      (fn? m) ^{:key k} [m k v]
      (map? m) ^{:key k} [render-symbol-data m k v]
      (= layer ::bg) ^{:key k} [draw-background m k v]
      ;; A static conn pattern on a tetris device (a transistor, an emitter
      ;; port) sizes to the rect; one with no rect (a plain port) sizes to the
      ;; pattern's own extent.
      (= layer ::conn) (let [size (if (device-dims model)
                                    (device-tile-size model)
                                    (cm/pattern-size m))]
                          ^{:key k} [draw-pattern size m port k v])
      :else ^{:key k} [(fn [k _v] (println "invalid model for" k))])))

(defn end-ui [bg? selected dx dy]
  (letfn [(clean-selected [ui sch]
            (update ui ::selected #(keep-existing % sch))) (deselect [ui] (if bg? (assoc ui ::selected #{}) ui))
          (endfn [ui]
            (-> ui
                (assoc ::dragging nil
                       ::delta {:x 0 :y 0 :rx 0 :ry 0}
                       ::stretch nil)
                deselect
                (clean-selected @schematic)))
          (round-coords [{x :x y :y :as dev}]
            (assoc dev
                   :x (math/round (+ x dx))
                   :y (math/round (+ y dy))))]
    ;; ::stretch is the live gate, so commit == preview (WYSIWYG).
    (let [st (::stretch @ui)]                  ; capture before endfn clears it
      (swap! ui endfn)
      (swap! schematic update-keys selected round-coords)
      (when (seq st) (swap! schematic commit-stretch st dx dy))
      (post-action!))))

(defn drag-end-box []
  (let [[x y] (::mouse-start @ui)
        {dx :x dy :y} (::delta @ui)
        x1 (math/floor (min x (+ x dx)))
        y1 (math/floor (min y (+ y dy)))
        x2 (math/floor (max x (+ x dx)))
        y2 (math/floor (max y (+ y dy)))
        pt-idx @point-index
        sel (into #{}
                  (mapcat (fn [pt]
                            (let [p (get pt-idx pt)]
                              (concat (:ids p) (:body-ids p))))
                          (for [x (range x1 (inc x2))
                                y (range y1 (inc y2))]
                            [x y])))]
    (println x1 y1 x2 y2 sel)
    (swap! ui assoc
           ::dragging nil
           ::delta {:x 0 :y 0 :rx 0 :ry 0}
           ::stretch nil
           ::selected (set sel))))

;; @tags mosaic clipboard
(defn commit-paste [staged dx dy]
  (letfn [(round-coords [{x :x y :y :as dev}]
            (assoc dev
                   :x (math/round (+ x dx))
                   :y (math/round (+ y dy))))]
    (let [placed (into {} (map (fn [[k v]] [k (round-coords v)]) staged))]
      (swap! ui assoc
             ::dragging nil
             ::delta {:x 0 :y 0 :rx 0 :ry 0}
             ::stretch nil
             ::paste-staging nil
             ::selected (set (keys placed)))
      (go
        (swap! schematic into placed)
        (<! (done? schematic))
        (post-action!)))))

;; @tags mosaic clipboard, mosaic wire tool
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
          (swap! ui assoc ::staging nil ::dragging nil)
          (maybe-restore-tool!)))
      (= (::tool @ui) ::eraser) (post-action!)
      (= (::tool @ui) ::wire) nil
      @paste-staging (commit-paste @paste-staging dx dy)
      :else (end-ui bg? selected dx dy))))

;; @tags mosaic device tray, mosaic model picker
(defn add-device [cell [x y] & args]
  (let [kwargs (apply array-map args)
        [ox oy] (staging-origin (assoc kwargs :type cell) [x y])
        mx (math/round ox)
        my (math/round oy)]
    (swap! ui assoc
           ::staging (into {:transform cm/IV, :type cell :x mx :y my} kwargs)
           ::tool ::device)))

(defn save-url []
  (let [blob (js/Blob. #js[(prn-str @schematic)]
                       #js{:type "application/edn"})]
    (.createObjectURL js/URL blob)))

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

;; @tags mosaic model picker
(defn picker-type-match?
  "True when a modeldb entry belongs in the picker for device-type. Entries
   without a :type count as ckt (the nyanlib catch-all for cells without a
   dedicated symbol type). Every type filters to itself — amp lists amps, ckt
   lists ckts — except the sentinel ::any, the all-components picker behind the
   photonic tray's library button, which lists every entry whatever its type."
  [device-type entry-type]
  (or (= ::any device-type)
      (= device-type (or entry-type "ckt"))))

;; @tags mosaic model picker
(defn picker-models
  "The modeldb entries a picker for device-type lists, before tags and search."
  [device-type]
  (into {} (filter (fn [[_ m]] (picker-type-match? device-type (:type m))) @modeldb)))

;; @tags mosaic model picker
(defn visible-picker-models
  "The entries the picker lists: cm/filter-models over the type set, then
   the hide-private-cells toggle. The toggle drops `_`-prefixed display
   names (cm/private-cell?) from the list only — never from resolution
   (#1139, #1182, #1059) — and a query that itself starts with `_` bypasses
   it: an explicit ask for a private cell must not meet an empty list. See
   specs/mosaic_model_picker.md."
  [type-models category query hide-privates]
  (let [matched (cm/filter-models type-models category query)]
    (vec (if (and hide-privates (not (clojure.string/starts-with? query "_")))
           (remove cm/private-cell? matched)
           matched))))

;; @tags mosaic model picker
(defn matching-tags
  "The tags that, on their own, still match at least one of models.

   Applied when the picker opens, so a tag carried over from another device
   type's picker cannot filter every row away. Deliberately per-tag: tags that
   each match something are all kept, even when their intersection is empty.
   That case is a filter the user built, and the chips make it visible and
   removable — unlike a tag that cannot match this type at all, which they
   never chose in this context."
  [models tags]
  (vec (filter #(seq (cm/filter-models models [%] "")) tags)))

;; @tags mosaic model picker
(defn- scroll-highlight-into-view!
  "Scroll the highlighted picker row into view. Called on arrow keys and query
   changes only — never on hover: scrolling rows out from under the pointer
   fires pointerenter on a different row and moves the highlight again."
  []
  (when (exists? js/document)
    (when-let [el (js/document.querySelector ".model-selector-popup .model-row.active")]
      (.scrollIntoView el #js {:block "nearest"}))))

;; @tags mosaic model picker
(defn picker-key-down!
  "Keyboard flow for the picker search input: arrows move the highlight
   (wrapping at both ends), Enter confirms the highlighted row, Escape
   cancels. Runs on keydown inside the input; global shortcuts ignore INPUT
   targets, so this is the only Escape path while the input has focus."
  [e filtered highlight confirm!]
  (let [n (count filtered)]
    (case (.-key e)
      "ArrowDown" (do (.preventDefault e)
                      (when (pos? n)
                        (swap! highlight #(mod (inc %) n))
                        (scroll-highlight-into-view!)))
      "ArrowUp" (do (.preventDefault e)
                    (when (pos? n)
                      (swap! highlight #(mod (dec %) n))
                      (scroll-highlight-into-view!)))
      "Enter" (do (.preventDefault e)
                  (when (pos? n)
                    ;; Clamp: a tag-tree click can shrink the list after the
                    ;; highlight moved, leaving it dangling past the end.
                    (confirm! (first (nth filtered (min @highlight (dec n)))))))
      "Escape" (do (.preventDefault e)
                   (reset! cm/modal-content nil))
      nil)))

;; @tags mosaic model picker, terminology
(defn model-selector-popup
  "Modal model picker: tag tree, autofocused search, single-click rows.
   Confirming a row calls (on-select model-id) and closes the modal; Escape
   or Cancel aborts without calling on-select. One component, one behavior
   across both call sites (tray picker-first placement and the deviceprops
   model field) — see specs/mosaic_model_picker.md."
  [_device-type _on-select]
  (let [highlight (r/atom 0)]
    (fn [device-type on-select]
      (let [;; Filter models by device type first
            type-models (picker-models device-type)
            ;; Build tag tree from type-filtered models
            trie (cm/build-tag-index type-models)
            ;; Filter by tags and search text, ranked; hide private cells
            filtered (visible-picker-models type-models
                                            @model-popup-category
                                            @model-popup-filter
                                            @hide-private-cells)
            confirm! (fn [model-id]
                       (on-select model-id)
                       (reset! cm/modal-content nil))]
        [:div.model-selector-popup
         ;; Selected tags sit in the heading, as in libman's "Components:" h2:
         ;; compact, and the only affordance that reaches tags absent from the
         ;; type-scoped tree and from every visible row
         ;; ::any has no type name to print — it is every type at once
         [:h3 (if (= ::any device-type)
                "Select component"
                (str "Select " device-type " component")) " "
          [cm/selected-tag-badges model-popup-category]]
         ;; Search input
         [:input.model-search {:type "text"
                               :placeholder "Filter components..."
                               :auto-focus true
                               :value @model-popup-filter
                               :on-change #(do (reset! model-popup-filter (.. % -target -value))
                                               (reset! highlight 0))
                               :on-key-down #(picker-key-down! % filtered highlight confirm!)}]
         ;; Hide-private-cells toggle: list-only — a `_`-prefixed query
         ;; reaches past it, resolution never filters on it
         [:label.hide-private-cells
          [:input {:type "checkbox"
                   :checked @hide-private-cells
                   :on-change #(set-hide-private-cells! (.. % -target -checked))}]
          " Hide private cells"]
         [:div.model-popup-content
          ;; Tag tree (left)
          [:div.model-categories
           (if (seq trie)
             [cm/tag-tree model-popup-category trie]
             [:div.empty "No categories"])]
          ;; Model list (right): single click confirms
          [:div.model-list
           (if (seq filtered)
             (doall
              (map-indexed
               (fn [i [model-id model]]
                 ^{:key model-id}
                 [:div.model-row {:class (when (= i @highlight) "active")
                                  :on-click #(confirm! model-id)}
                  [cm/model-row-content model model-id
                   #(cm/add-tag! model-popup-category %)]])
               filtered))
             [:div.empty "No matching components"])]]
         ;; Buttons
         [:div.model-popup-buttons
          [:button {:on-click #(reset! cm/modal-content nil)} "Cancel"]]]))))

;; @tags mosaic model picker
(defn open-model-picker!
  "Open the model-selector modal for device-type. (on-select model-id) runs on
   confirm; the modal closes itself. Escape/Cancel aborts without calling
   on-select.

   Clears the search text but deliberately keeps `model-popup-category`: tag
   selection is a browsing context that survives across opens, device types
   and both call sites, while the search string is a per-search transient.
   Tags that cannot match this device type at all are dropped on the way in
   (`matching-tags`); whatever survives stays visible as a removable chip.
   See specs/mosaic_model_picker.md."
  [device-type on-select]
  (reset! model-popup-filter "")
  (swap! model-popup-category #(matching-tags (picker-models device-type) %))
  (reset! cm/modal-content [model-selector-popup device-type on-select]))

;; @tags property editing, terminology, mosaic selection toolbar, model identity, mosaic port exposure, mosaic make array
(defn deviceprops [key]
  (let [props (r/cursor schematic [key :props])
        device-type (r/cursor schematic [key :type])
        model (r/cursor schematic [key :model])
        name (r/cursor schematic [key :name])
        show-prop-insert (r/atom false)]
    (fn [key]
      (let [models-snapshot @modeldb
            sch @schematic
            model-def (model-entry @model @props)
            exposure (port-exposure-state models-snapshot sch @point-index key)
            effective-default (or (when model-def (cm/generate-important-template (:props model-def)))
                                 (::template (get models @device-type))
                                 "{self.name}")]
        [:<>
         [:h1 (or @name key)]
         [:div.device-actions
          (when (editable-model? @model model-def)
            [:button {:type "button"
                      :title "Edit component"
                      :on-click #(edit-model @model)}
             [cm/edit]
             [:span "Edit"]])
          [:button {:type "button"
                    :title "Ask Agent"
                    :on-click (fn [e]
                                (.stopPropagation e)
                                (let [device (get @schematic key)]
                                  (send-to-agent
                                   key
                                   device
                                   (str (or @name key) " (" @device-type ")")))
                                (cm/clear-context-menu))}
           [cm/wand-icon]
            [:span "Ask Agent"]]
           (when exposure
             [:button {:type "button"
                       :title "Expose ports"
                       :disabled (:disabled? exposure)
                       :on-click #(expose-ports! key)}
              [cm/expose-ports]
              [:span "Expose ports"]])
           (when (seq @model)
             (let [able? (array-able? @model model-def)
                   reason (cond
                            (not model-def) "No component definition"
                            (not (seq (:name model-def))) "Component has no name"
                            (clojure.string/ends-with? @model ".gsch")
                            "Schematic subcircuits cannot be arrayed"
                            (= @model array-fqn) "Already an array")]
               [:button {:type "button"
                         :title (or reason "Make array")
                         :disabled (not able?)
                         :on-click #(make-array! key)}
                [cm/grid3x3]
                [:span "Make array"]]))]
         [:div.properties
          [:label {:for "name" :title "Instance name"} "name"]
          [:input {:id "name"
                   :type "text"
                   :default-value @name
                   :on-change (debounce #(do (reset! name (.. % -target -value)) (post-action!)))}]
          [:label {:for "model" :title "Device model"} "model"]
          [:div.field-with-extra
           [:input {:id "model"
                    :type "text"
                    :read-only true
                    :value (or (:name model-def) (when (seq @model) @model) "Ideal")
                    :title (or @model "No component selected")}]
           [:button {:title "Select component"
                      :on-click #(open-model-picker!
                                  @device-type
                                  (fn [model-id]
                                    (reset! model (if model-id (cm/bare-id model-id) ""))
                                    (let [mdef (when model-id @(model-cursor modeldb model-id))
                                          defaults (or (cm/model-prop-defaults mdef) {})]
                                      (reset! props defaults))
                                    (post-action!)))}
             [cm/search]]]
          ; Get properties from built-in device and model
          (let [device-props (::props (get models @device-type) [])
                model-props (mapv cm/schema->field-type (:props model-def []))
                all-props (cm/visible-props (concat device-props model-props)
                                            (get-in @schematic [key :variant]))
                prop-in-template? (fn [path]
                                    (let [path-str (clojure.string/join "." (map clojure.core/name path))
                                          needle (str "self.props." path-str)
                                          tmpl (or (:template (get @schematic key)) effective-default)]
                                      (clojure.string/includes? tmpl needle)))
                insert-prop (fn [path]
                              (when-not (prop-in-template? path)
                                (let [path-str (clojure.string/join "." (map clojure.core/name path))
                                      label (clojure.core/name (last path))]
                                  (swap! schematic update-in [key :template]
                                    (fn [current]
                                      (let [base (or current effective-default)]
                                        (str base "\n" label ": {self.props." path-str "}"))))
                                  (post-action!))))]
            [cm/recursive-editor all-props props
             post-action!
             (when @show-prop-insert
               (fn [path]
                 (let [already? (prop-in-template? path)]
                   [:button.prop-eye
                    {:on-click #(insert-prop path)
                     :title (if already? "Already in template" "Add to template")
                     :disabled already?}
                    [cm/eye]])))])
          [:div.template-header
           [:label {:for "template" :title "Template to display"} "Text"]
           [:button.add-btn
            {:on-click #(swap! show-prop-insert not)
             :class (when @show-prop-insert "active")}
            [cm/plus-circle] [cm/plus-circle-fill] " Add"]]
          [cm/dbfield :textarea {:id "template" :rows 4}
           (r/cursor schematic [key])
           #(or (:template %) effective-default)
           (fn [st v] (swap! st assoc :template v) (post-action!))]]]))))

;; @tags property editing
(defn device-context-menu [k e]
  (.preventDefault e)
  (.stopPropagation e)
  (if (or (::dragging @ui) (not= (::tool @ui) ::cursor))
    (cancel)
    (do
      (swap! ui assoc ::selected #{k})
      (cm/set-context-menu (.-clientX e) (.-clientY e)
                           [:div.device-props [deviceprops k]]))))

;; @tags mosaic clipboard
(defn doc-center
  "Visual center of a single device doc in grid coordinates — the inverse of
  `staging-origin`, which is how `add-device` places a device and what
  `draw-background` renders. Wires are the one shape with no tetris rect *and*
  no single tile, so they center on their segment midpoint."
  [dev]
  (if (= (:type dev) "wire")
    [(+ (:x dev) (/ (:rx dev 0) 2) 0.5)
     (+ (:y dev) (/ (:ry dev 0) 2) 0.5)]
    (if-let [[w h] (device-dims dev)]
      [(+ (:x dev) 1 (/ w 2))
       (+ (:y dev) 1 (/ h 2))]
      [(+ (:x dev) 0.5) (+ (:y dev) 0.5)])))

;; @tags mosaic clipboard
(defn clipboard-centroid
  "Mean of the visual centers (see `doc-center`) of a sequence of device docs.
  Returns [0 0] for an empty sequence so paste is a no-op rather than a
  divide-by-zero. The centroid anchors the paste drag — see `paste`."
  [docs]
  (let [n (count docs)]
    (if (zero? n)
      [0 0]
      (let [centers (map doc-center docs)]
        [(/ (reduce + (map first centers)) n)
         (/ (reduce + (map second centers)) n)]))))

;; @tags mosaic clipboard
(defn copy []
  (let [sel @selected
        sch @schematic
        strip-irl (fn [m] (apply dissoc m (filter #(clojure.string/starts-with? (name %) "irl_") (keys m))))
        devs (map (comp strip-irl #(dissoc % :_rev :_id) sch) sel)]
    (swap! local assoc (str "local" sep "clipboard") {:data devs})))

;; @tags mosaic clipboard
(defn cut []
  (copy)
  (delete-selected))

;; @tags mosaic clipboard
(defn paste []
  (let [docs (get-in @local [(str "local" sep "clipboard") :data])
        [mx my] (::mouse @ui)
        [cx cy] (clipboard-centroid docs)
        dx (math/round (- mx cx))
        dy (math/round (- my cy))
        devmap (->> docs
                    (group-by #(cm/initial (:type %)))
                    (map (fn [[_prefix devices]]
                           (map (fn [nam dev]
                                  (let [id (str group sep nam)
                                        display-name (if (= (:type dev) "port") (:name dev) nam)]
                                    [id (assoc dev :name display-name)]))
                                (make-names (:type (first devices)))
                                devices)))
                    (into {} cat))]
    (when (seq docs)
      (swap! ui assoc
             ::dragging ::device
             ::selected (set (keys devmap))
             ::mouse-start [cx cy]
             ::delta {:x dx :y dy :rx 0 :ry 0}
             ::paste-staging devmap))))

(defn device-active
  ([cell]
   (when (= cell (:type @staging))
     "active"))
  ([cell variant]
   (when (and (= cell (:type @staging))
              (= variant (:variant @staging)))
     "active")))

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

;; Ideal photonic source/detector are placed as `port` variants (like ground
;; and supply), not components. Unlike ground/supply they take no fixed :name —
;; each is a distinct exposed port, so commit-staged auto-names them uniquely.
;; They render as full-size components (via the `port` model's per-variant
;; `::sym`/`::bg`/`::conn` dispatch maps). Both variants' canonical artwork
;; carries the port on the right; the emitter is staged with the identity
;; transform, while the detector is staged with a horizontal flip (flip-x) so
;; its port renders on the left at runtime and transform-direction reports
;; :right — the parent-edge it sits on. This mirrors add-gnd/add-supply, which
;; bake a rotation into the staged transform for the same hierarchy reason.
;; @tags circulax simulation
(defn add-emitter [coord]
  (add-device "port" coord :variant "emitter"))

;; @tags circulax simulation
(defn add-detector [coord]
  (add-device "port" coord :variant "detector"
              :transform (cm/transform-vec (cm/flip-x cm/I))))

;; @tags mosaic device tray, mosaic model picker
(defn ideal-placement?
  "True when a tray type stages immediately as an ideal device rather than
   opening the model picker — the exclusion set from
   specs/mosaic_model_picker.md: passives, sources, all port variants, text."
  [type _variant]
  (contains? #{"port" "text"
               "resistor" "capacitor" "inductor"
               "vsource" "isource"}
             type))

;; @tags mosaic device tray
(defn place-ideal!
  "Stage an ideal (model-less) device from the tray, dispatching port variants
   to their dedicated placement fns (baked transforms/names)."
  [type variant coord]
  (case variant
    "ground" (add-gnd coord)
    "supply" (add-supply coord)
    "text" (add-label coord)
    "emitter" (add-emitter coord)
    "detector" (add-detector coord)
    (add-device type coord)))

;; @tags mosaic device tray, mosaic model picker
(defn staged-type
  "The device type to stage for a picked model: the entry's own :type when this
   build knows it, else the tray button's type.

   The library button's picker is unfiltered (::any), so a bend picked there has
   to stage as a bend or it renders as a generic box instead of its symbol. For
   a type-filtered picker the entry's type is the button's type anyway, so this
   only ever bites on ::any. An untyped entry (the nyanlib catch-all) or a type
   this build does not know falls back to the button's type."
  [fallback model-id]
  (let [t @(model-cursor modeldb model-id :type)]
    (if (contains? cm/device-types t) t fallback)))

;; @tags mosaic device tray, mosaic model picker
(defn arm-device!
  "Single entry point for tray activation, taking the tray variant map. Ideal
   types stage immediately; picker-first types open the model picker — filtered
   to :picker-type when the item overrides it, else to :type — and confirming
   arms the staged device with the chosen model (bare id) at the last known
   canvas mouse position, typed by the model itself (`staged-type`). The ghost
   then follows the cursor."
  [{:keys [type variant picker-type]} coord]
  (if (ideal-placement? type variant)
    (place-ideal! type variant coord)
    (open-model-picker! (or picker-type type)
                        (fn [model-id]
                          (add-device (staged-type type model-id) (::mouse @ui)
                                      :model (cm/bare-id model-id))))))

;; @tags mosaic device tray
(def tray-groups
  "Data-driven device tray inventory (specs/mosaic_device_tray.md). Each item
   has an explicit :domain — ::optical or ::electrical — and one or more
   variants. Single-variant items render as flat buttons, multi-variant items
   as split-button flyout groups. Placement-category membership is explicit
   data, never derived from ::category or port nature. A variant may override
   the picker's type filter with
   :picker-type; ::any lists the whole library."
  [;; Both workflows lead with the whole component library. Optical ports then
   ;; share one split-button flyout whose initial visible action is Port.
   {:id :components :domain ::optical
    :variants [{:title "Add component from the library"
                :type "ckt" :picker-type ::any :icon [cm/chip]}]}
   {:id :ports :domain ::optical
    :variants [{:title "Add port [p]" :type "port"
                :icon [cm/device-icon "port"]}
               {:title "Add laser" :type "port" :variant "emitter"
                :icon [cm/device-icon "emitter"]}
               {:title "Add detector" :type "port" :variant "detector"
                :icon [cm/device-icon "detector"]}]}
   {:id :port-label :domain ::optical
    :variants [{:title "Add port label [shift+t] — name and expose the connected network"
                :type "port" :variant "text"
                :icon [cm/device-icon "port-label"]}]}
   {:id :text-annotation :domain ::optical
    :variants [{:title "Add text annotation [t]" :type "text"
                :icon [cm/text-annotation-icon]}]}

   ;; Electrical placement: the whole component library, then model-free
   ;; elements and their structural terminals. The first variant of each
   ;; multi-variant group is its initial visible action.
   {:id :electrical-components :domain ::electrical
    :variants [{:title "Add component from the library"
                :type "ckt" :picker-type ::any :icon [cm/chip]}]}
   {:id :terminals :domain ::electrical
    :variants [{:title "Add port [p]" :type "port"
                :icon [cm/device-icon "port"]}
               {:title "Add ground [g]" :type "port" :variant "ground"
                :icon [cm/device-icon "ground"]}
               {:title "Add supply [shift+p]" :type "port" :variant "supply"
                :icon [cm/device-icon "supply"]}]}
   {:id :sources :domain ::electrical
    :variants [{:title "Add voltage source [v]" :type "vsource" :icon [cm/device-icon "vsource"]}
               {:title "Add current source [i]" :type "isource" :icon [cm/device-icon "isource"]}]}
   {:id :passives :domain ::electrical
    :variants [{:title "Add resistor" :type "resistor" :icon [cm/device-icon "resistor"]}
               {:title "Add inductor" :type "inductor" :icon [cm/device-icon "inductor"]}
               {:title "Add capacitor" :type "capacitor" :icon [cm/device-icon "capacitor"]}]}])

;; @tags mosaic device tray
(def tray-domains
  "The rocker's placement categories, in order. Doubles as the set of domains
   the tray reserves width for, so adding a category is one row here."
  [{:id ::optical :icon "wave" :title "Optical"}
   {:id ::electrical :icon "bolt" :title "Electrical"}])

;; @tags mosaic device tray
(defn visible-tray-groups
  "Tray items for one placement category. Every item belongs to exactly one
   category; model-backed components remain reachable through Components."
  ([groups dom]
   (filter #(= dom (:domain %)) groups))
  ([]
   (visible-tray-groups tray-groups (or @domain ::optical))))

;; @tags mosaic device tray
(defn tray-slot-deficit
  "How many slots this domain is short of the widest domain. Rendered as filler
   outside the bar so every domain's bar keeps the same right edge — see
   specs/mosaic_device_tray.md."
  ([groups dom]
   (- (apply max (for [{:keys [id]} tray-domains]
                   (count (visible-tray-groups groups id))))
      (count (visible-tray-groups groups dom))))
  ([]
   (tray-slot-deficit tray-groups (or @domain ::optical))))

;; @tags mosaic device tray, mosaic radio groups
(defn domain-switch
  "Placement-category rocker at the right end of the device tray, rendered
   from `tray-domains` through the shared `cm/radiobuttons`. The category is a
   visibility filter only — see specs/mosaic_device_tray.md."
  []
  [:div.domain-switch
   [cm/radiobuttons "mosaic-tray-domain" domain
    ;; label, value, tooltip
    (for [{:keys [id icon title]} tray-domains]
      [[cm/device-icon icon] id title])]])

;; @tags mosaic device tray
(defn tray-button
  "Render one tray variant as a button that arms it on pointer-up. The whole
   variant map goes to `arm-device!`, which reads :picker-type off it."
  [{:keys [title type variant icon] :as item}]
  [:button {:title title
            :class (device-active type variant)
            :on-pointer-up #(arm-device! item (cm/viewbox-coord %))}
   icon])

;; @tags mosaic device tray
(defn split-tray
  "Split-button flyout group: the active variant's icon button plus a slit that
   toggles the flyout — see specs/mosaic_device_tray.md for the geometry. Icon
   click activates the active variant; clicking a flyout variant activates it
   AND makes it the group's new default. The flyout closes on selection, slit
   re-click, or backdrop click. Replaces the old long-press variant-tray."
  [& _variants]
  (let [active (r/atom 0)
        open? (r/atom false)]
    (fn [& variants]
      [:span.split-tray
       (nth variants @active)
       [:button.slit {:title "Show variants"
                      :aria-label "Show variants"
                      :aria-expanded @open?
                      :class (when @open? "open")
                      :on-click #(swap! open? not)}]
       (when @open?
         [:<>
          [:span.tray-backdrop {:on-pointer-down #(reset! open? false)}]
          [:span.tray
           (doall
            (keep-indexed
             (fn [i var]
               (when-not (= i @active)
                 ^{:key i}
                 [:span.tray-variant
                  {:on-pointer-up #(do (reset! active i)
                                       (reset! open? false))}
                  var]))
             variants))]])])))

;; @tags mosaic radio groups, mosaic selection toolbar
(defn menu-items []
  [:<>
   [:div.primary
    [:div.toolbar-group.tools-group
     [cm/radiobuttons "mosaic-tools" tool
    ; label, key, title
      [[[cm/cursor] ::cursor "Cursor [esc]"]
       [[cm/wire] ::wire "Wire [w]"]
       [[cm/eraser] ::eraser "Eraser [e]"]
       [[cm/move] ::pan "Pan [space]"]
       ;; Probe posts the clicked element on the "probe" BroadcastChannel, which
       ;; only the anywidget notebook bridge listens to. The VS Code webview has
       ;; no listener and no simulator to connect to, so the button is dead there
       ;; and is left out of that build.
       #?@(:vscode []
           :default [[[cm/probe] ::probe "Probe nodes in a connected simulator"]])]
      nil nil cancel]]
    ;; Paste acts on the clipboard rather than the selection, so it stays here
    ;; while the selection actions live on the floating selection toolbar.
    [:div.toolbar-group
     [:a {:title "zoom in [scroll wheel/pinch]"
          :on-click #(button-zoom -1)}
      [cm/zoom-in]]
     [:a {:title "zoom out [scroll wheel/pinch]"
          :on-click #(button-zoom 1)}
      [cm/zoom-out]]
     [:a {:title "zoom to fit [Home]"
          :on-click #(zoom-to-fit)}
      [cm/zoom-fit]]
     [:a {:title (str "undo [" cm/mod-key "+z]")
          :on-click undo-schematic}
      [cm/undoi]]
     [:a {:title (str "redo [" cm/mod-key "+shift+z]")
          :on-click redo-schematic}
      [cm/redoi]]
     [:a {:title (str "Paste [" cm/mod-key "+v]")
          :on-click (fn [_] (paste))}
      [cm/pastei]]]]
   [:div.status
    [renamable-model-name modeldb (cm/model-key group)]
    (if @syncactive
      [:span.syncstatus.active {:title "saving changes"} [cm/sync-active]]
      [:span.syncstatus.done   {:title "changes saved"} [cm/sync-done]])]

   [:div.secondary
    [secondary-menu-items ui]
    [:a {:title "Keyboard shortcuts & help"
         :on-click cm/show-onboarding!}
     [cm/help]]
    ]])

;; @tags mosaic device tray
(defn device-tray
  "Filler slots, then the bar: the visible domain's items left to right and the
   domain rocker last, so DOM order is the visual order. The filler makes the
   centered outer shell the same width in every domain, which pins the bar's
   right edge — and with it the rocker — while the bar still hugs its content."
  []
  [:div.devicetray-anchor
   (doall
    (for [i (range (tray-slot-deficit))]
      ^{:key i}
      ;; a button, not a span, so its em-based box matches a real slot's
      [:button.tray-filler]))
   [:div.devicetray.chrome.chrome-float
    (doall
     (for [{:keys [id variants]} (visible-tray-groups)]
       (if (= 1 (count variants))
         ^{:key id}
         [tray-button (first variants)]
         (into ^{:key id}
               [split-tray]
               (map-indexed
                (fn [i variant]
                  ^{:key [id i]}
                  [tray-button variant])
                variants)))))
    [domain-switch]]])

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
  [sch point-idx wire-types]
  (reduce-kv
   (fn [errors pt {:keys [ids types]}]
     (let [wire-type-set (into #{}
                               (keep (fn [id]
                                       (when (= "wire" (:type (get sch id)))
                                         (get wire-types id))))
                               ids)
           all-types (into (or types #{}) wire-type-set)
           n (count (remove #(= (get-in sch [% :type]) "port") ids))
           mismatch? (> (count all-types) 1)
           fork? (and (contains? all-types "photonic") (> n 2))]
       (cond-> errors
         mismatch? (conj {:type :mismatch :pos pt})
         fork? (conj {:type :fork :pos pt}))))
   [] point-idx))

(def wire-errors
  (r/track #(build-wire-errors @schematic @point-index @net-type-index)))

(defn point-connection-type
  "Get the connection type at a point from device ports and wire types."
  [x y ids]
  (let [port-types (get-in @point-index [[x y] :types])
        wire-type-set (into #{}
                            (keep #(get @net-type-index %))
                            ids)
        all-types (into (or port-types #{}) wire-type-set)]
    (first all-types)))

;; @tags nyancir format, livewire auto-route lvs arrows, reactive indices
(defn schematic-airwires
  "Top-level ratsnest layer. Draws dashed-red airwire arcs for two kinds of
   cross-editor connectivity hints:

   (a) Livewire polylines whose two endpoint pins don't agree on a net
       (migrated from the former `polyline-sym`), and
   (b) `type:\"port\"` docs carrying an `:attached_port` (Livewire's rigid
       layout binding) whose net disagrees with the attached device-port's
       net.

   Match rule (both cases): silent only when both pins carry a non-nil net and
   the two nets are equal. Any missing or differing net surfaces an arc. The
   hover text explains that the layout connection is missing from the
   schematic.

   Sits above `schematic-elements` with a stroke-only hover target."
  []
  (into [:<>] (build-schematic-airwires @modeldb @schematic)))

;; @tags mosaic wire tool, mosaic port exposure
(defn schematic-dots []
  (let [icon-size (/ grid-size 2.5)
        icon-offset (- (/ grid-size 2) (/ icon-size 2))
        error-points (into #{} (map :pos) @wire-errors)]
    [:<>
     (doall
      (for [[[x y] {:keys [ids]}] @point-index
            :when (seq ids)
            :let [n (count (non-text-endpoint-ids @schematic ids))
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
          :r (/ grid-size 10)
          :on-pointer-down (when nc? port-wire-start)}]))
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

;; @tags mosaic clipboard
(defn tool-elements []
  (let [{sel ::selected
         dr ::dragging
         v ::staging
         tool ::tool
         st ::stretch
         {x :x y :y :or {x 0 y 0}} ::delta
         [sx sy] ::mouse-start} @ui
        ;; Unrounded: the staged ghost follows the cursor smoothly; the grid
        ;; lines show where it lands. Commit rounds (round-coords / commit-stretch).
        vx (* grid-size x)
        vy (* grid-size y)]
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
      (and @paste-staging (= dr ::device))
      [:g.staging {:style {:transform (str "translate(" vx "px, " vy "px)")}}
       [schematic-elements @paste-staging]]
      (and sel dr)
      [:<>
       ;; Selected devices ride one uniform translate.
       [:g.staging {:style {:transform (str "translate(" vx "px, " vy "px)")}}
        [schematic-elements
         (let [schem @schematic]
           (map #(vector % (get schem %)) sel))]]
       ;; Shift-drag: attached wires are ghosted with only their flagged end moved,
       ;; so they can't ride the group translate — render them displaced instead.
       (when st
         (into [:g.staging]
               (for [[id ends] st
                     :let [w (stretch-wire (get @schematic id) ends x y)]]
                 ^{:key id} [wire-sym id w])))])))

(defn- theme-attrs
  "CSS classes for the current manual theme. :root:has() rules in
   style.css pick up these container classes and set color-scheme."
  []
  (case @theme
    "eyesore" {:class "eyesore dark-cursors"}
    "light"   {:class "light-cursors"}
    "dark"    {:class "dark-cursors"}
    {}))

(defonce canvas-box
  ;; The canvas's viewport rect. Only a resize moves it, so keeping it here
  ;; keeps `getBoundingClientRect` out of the pan/zoom path: with this plus
  ;; `::zoom`, the selection toolbar's placement is pure.
  (r/atom nil))

;; @tags mosaic selection toolbar
(defn watch-canvas-box!
  "Mirror the canvas's viewport rect into `canvas-box` and keep it current as
   the element resizes. Passed by name as the canvas's `:ref` so React runs it
   on mount and unmount only, never per render."
  [^js el]
  (when el
    (let [read! (fn []
                  (let [^js r (.getBoundingClientRect el)]
                    (reset! canvas-box {:left (.-left r) :top (.-top r)
                                        :width (.-width r) :height (.-height r)})))]
      (read!)
      (.observe (js/ResizeObserver. read!) el))))

;; @tags mosaic selection toolbar
(defn selected-devices
  "The ids in `sel` that name devices in `sch` — `::selected` mixes devices,
   wires and text labels through one path. Stale ids are dropped."
  [sch sel]
  (into #{}
        (remove #(contains? #{nil "wire" "text"} (:type (get sch %))))
        sel))

;; @tags mosaic selection toolbar
(defn selection-anchor-rect
  "Rect in SVG user units enclosing the selected devices' tiles, nil when the
   selection is empty. Tile-derived rather than measured, so rotate and flip
   cannot move it."
  [sch sel]
  (cm/union-rect
   (for [id sel
         :let [dev (get sch id)
               tile (* (device-tile-size dev) grid-size)]]
     {:x (* (:x dev) grid-size)
      :y (* (:y dev) grid-size)
      :width tile
      :height tile})))

;; @tags mosaic selection toolbar, property editing
(defn- open-device-props
  "Open the properties popup for device `k` at the button's lower-left corner,
   reusing the right-click overlay so there is one properties surface."
  [k ^js e]
  (let [^js r (.getBoundingClientRect (.-currentTarget e))]
    (cm/set-context-menu (.-left r) (.-bottom r)
                         [:div.device-props [deviceprops k]])))

;; @tags mosaic selection toolbar, editor keyboard shortcuts
(defn selection-toolbar
  "Floating action bar anchored above the selected device tiles. Renders the
   selection's screen rect as `.selection-anchor` and lets CSS hang the bar off
   it; see specs/mosaic_selection_toolbar.md."
  []
  (let [sch @schematic
        sel (selected-devices sch @selected)
        single (when (= 1 (count sel)) (first sel))
        box @canvas-box
        tile (selection-anchor-rect sch sel)
        ;; Half a cell in, off the tile, is the tetris body the bar should
        ;; clear. Done in user units so it scales with zoom on its own.
        anchor (when (and tile box)
                 (cm/rect->screen (cm/user->screen @zoom box)
                                  (cm/inset-rect tile (/ grid-size 2))))]
    (when (and anchor (not (::dragging @ui)))
      [:div.selection-anchor
       ;; Inline rather than in CSS: being out of flow is structural, not
       ;; cosmetic. An unstyled anchor would take up layout and shove the
       ;; canvas, so it must not depend on the stylesheet having loaded.
       {:style {:position "fixed"
                :left (str (:x anchor) "px")
                :top (str (:y anchor) "px")
                :width (str (:width anchor) "px")
                :height (str (:height anchor) "px")}}
       [:div.selection-toolbar.chrome.chrome-float
        [:div.toolbar-group
         [:a {:title "Rotate selected clockwise [r]"
              :on-click #(transform-selected cm/rotate-cw)}
          [cm/rotatecw]]
         [:a {:title "Rotate selected counter-clockwise [shift+r]"
              :on-click #(transform-selected cm/rotate-ccw)}
          [cm/rotateccw]]
         [:a {:title "Flip horizontally [x]"
              :on-click #(transform-selected cm/flip-x)}
          [cm/mirror-vertical]]
         [:a {:title "Flip vertically [y]"
              :on-click #(transform-selected cm/flip-y)}
          [cm/mirror-horizontal]]]
        [:div.toolbar-group
         [:a {:title (str "Cut selected [" cm/mod-key "+x]")
              :on-click #(cut)}
          [cm/cuti]]
         [:a {:title (str "Copy selected [" cm/mod-key "+c]")
              :on-click #(copy)}
          [cm/copyi]]
         [:a {:title "Delete selected [Del / Backspace / D]"
              :on-click #(delete-selected)}
          [cm/delete]]]
        (when single
          [:div.toolbar-group
           [:a {:title "Parameters"
                :on-click #(open-device-props single %)}
            [cm/gear]]])]])))

;; @tags mosaic selection toolbar, mosaic device tray
(defn schematic-ui []
  [:div.mosaic-container (theme-attrs)
   [:div.menu.chrome.chrome-float
    [menu-items]]
   [:div.content-wrapper
    [:div.content
     [device-tray]
     [:svg.mosaic-canvas (merge (theme-attrs)
                          {:xmlns "http://www.w3.org/2000/svg"
                           :ref watch-canvas-box!
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
      [:g.schematic-content
       [schematic-elements @schematic]
       [schematic-airwires]
       [schematic-dots]]
      [tool-elements]]]
    [notebook-panel notebook-state]]
   [selection-toolbar]
   [cm/contextmenu]
   [cm/modal]])

;; @tags mosaic clipboard, editor keyboard shortcuts
(defn- unless-active
  "Wrap a keyup shortcut so it no-ops while a drag or paste-staging is
  active. Suppresses the device-placement shortcuts (V/I/P/G/T/Shift+T/
  Shift+P) during a drag or paste: the Cmd+V paste fires on keydown, so
  releasing Cmd before V leaves V's keyup with no modifier and would
  otherwise match the plain #{:v} vsource binding, spawning a voltage
  source mid-paste. Also prevents placing a device mid-drag generally."
  [f]
  (fn [& args]
    (when-not (or (::dragging @ui) @paste-staging)
      (apply f args))))

;; @tags editor keyboard shortcuts
(def shortcuts {#{:v} (unless-active #(add-device "vsource" (::mouse @ui)))
                #{:i} (unless-active #(add-device "isource" (::mouse @ui)))
                #{:p} (unless-active #(add-device "port" (::mouse @ui)))
                #{:g} (unless-active #(add-gnd (::mouse @ui)))
                #{:shift :p} (unless-active #(add-supply (::mouse @ui)))
                #{:t} (unless-active #(add-device "text" (::mouse @ui)))
                #{:shift :t} (unless-active #(add-label (::mouse @ui)))
                #{:w} #(cancel ::wire)
                #{:e} #(cancel ::eraser)
                #{:escape} #(do (cm/clear-context-menu) (cancel) (cm/clear-modal))
                #{(keyword " ")} maybe-restore-tool!
                #{:r}        (fn [_] (transform-selected cm/rotate-cw))
                #{:shift :r} (fn [_] (transform-selected cm/rotate-ccw))
                #{:x}        (fn [_] (transform-selected cm/flip-x))
                #{:y}        (fn [_] (transform-selected cm/flip-y))
                #{:arrowup}    #(move-selected 0 -1)
                #{:arrowdown}  #(move-selected 0 1)
                #{:arrowleft}  #(move-selected -1 0)
                #{:arrowright} #(move-selected 1 0)
                #{(keyword "`")} tab-next
                #{:home} zoom-to-fit})

(def immediate-shortcuts
  {#{:backspace} delete-selected
   #{:delete} delete-selected
   #{:d} delete-selected
   #{(keyword " ")} (fn [] (momentary-tool! ::pan))
   (hash-set cm/mod-bind :c) copy
   (hash-set cm/mod-bind :x) cut
   (hash-set cm/mod-bind :v) paste
   (hash-set cm/mod-bind :z) undo-schematic
   (hash-set cm/mod-bind :shift :z) redo-schematic})


(defn ^:dev/after-load ^:export  render []
  (set! js/document.onkeyup (partial cm/keyboard-shortcuts shortcuts))
  (set! js/document.onkeydown (partial cm/keyboard-shortcuts immediate-shortcuts))
  (rdc/render root [schematic-ui]))

;; @tags factory card dragging
(defn- on-drop [e]
  (.preventDefault e)
  (.stopPropagation e)
  (let [dt (.-dataTransfer e)
        fqn (some-> (.getData dt "text/x-gfp-factory") js/JSON.parse js->clj (get "factory"))]
    (when (seq fqn)
      (let [[x y] (cm/viewbox-coord e)]
        (commit-staged {:type "ckt" :model fqn :transform cm/IV :x x :y y})))))

(defn ^:export init []
  (.addEventListener js/document "dragover" #(.preventDefault %))
  (.addEventListener js/document "drop" on-drop)
  (.addEventListener js/document "mosaic-zoom-to-fit" (fn [_] (zoom-to-fit)))
  (init-extra!)
  (render))

(defn ^:export clear []
  (swap! schematic #(apply dissoc %1 %2) (set (keys @schematic))))
