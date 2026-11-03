; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.symbol-maker.editor
  "Reagent UI of the symbol maker webview (VS Code custom editor Mosaic.symbol).

   The host document is the only source of truth and the only history: every
   committed edit posts the whole new SVG to the extension host, which applies
   it as one document edit and echoes the text back. Transient gesture state
   (drags, the line being drawn, inline text entry) lives in `state` and never
   reaches the document until committed."
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [clojure.string :as str]
            [cljs.core.async :refer [go <!]]
            [react-bootstrap-icons :as icons]
            [nyancad.mosaic.jsatom :refer [vscode request!]]
            [nyancad.mosaic.jsatom.protocol :as protocol]
            [nyancad.mosaic.common :as cm]
            [nyancad.mosaic.symbol-maker :as sm]
            [nyancad.mosaic.symbol-maker.svg :as svg]))

;; @tags mosaic symbol maker
(defn- injected
  "Decode a value the extension host injected as a hidden input."
  [id]
  (some-> (js/document.getElementById id) .-value js/decodeURIComponent))

;; @tags mosaic symbol maker
(defonce state
  (r/atom {:text (or (injected "document") "")
           :rel-path (injected "relative-path")
           :geometry nil
           :load-error nil
           :tool :select
           :token (first sm/tokens)
           :selected #{}
           :zoom nil
           :drag nil
           :draft []
           :cursor nil
           :editing nil
           :space? false}))

;; @tags mosaic symbol maker
(def ^:private tools
  [{:tool :select :label "Select" :key "v" :icon icons/HandIndex}
   {:tool :line :label "Line" :key "l" :icon icons/VectorPen}
   {:tool :rect :label "Rectangle" :key "r" :icon icons/Square}
   {:tool :ellipse :label "Ellipse" :key "e" :icon icons/Circle}
   {:tool :text :label "Text" :key "t" :icon icons/Type}
   {:tool :pan :label "Pan" :key "h" :icon icons/ArrowsMove}])

;; @tags mosaic symbol maker
(def ^:private tool-keys
  (into {} (map (juxt :key :tool)) tools))

;; --- Document model ---

 ;; @tags mosaic symbol maker
(defonce ^:private parsed-cache (atom nil))

;; @tags mosaic symbol maker
(defn- parsed-document
  "Validate imported images once per document text change, never per pointer render."
  [text rel-path]
  (let [key [text rel-path]]
    (if (= key (:key @parsed-cache))
      (:value @parsed-cache)
      (let [{:keys [doc background]} (sm/svg->doc text)
            doc (or doc {:version 1 :target (sm/infer-target rel-path) :frame [1 1] :shapes []})
            value (try
                    {:doc (if (> (:version doc) 1) (assoc doc :shapes [])
                            (update doc :shapes
                                    (fn [shapes]
                                      (mapv (fn [shape]
                                              (if (= "image" (first shape))
                                                (assoc-in shape [1 "href"] (:href (svg/import-data-uri (get-in shape [1 "href"]))))
                                                shape)) shapes))))
                     :background (not-empty background)}
                    (catch :default error {:doc (assoc doc :shapes []) :error (.-message error)}))]
        (reset! parsed-cache {:key key :value value})
        value))))

;; @tags mosaic symbol maker
(defn- model
  "Current v1 document and model-driven frame; imported artwork stays opaque."
  [{:keys [text rel-path geometry]}]
  (let [{:keys [doc background error]} (parsed-document text rel-path)]
    {:doc doc :background background :import-error error
     :read-only? (> (:version doc) 1)
     :frame (or (:frame geometry) (:frame doc))}))

;; @tags mosaic symbol maker
(defn- current-shapes []
  (:shapes (:doc (model @state))))

;; @tags mosaic symbol maker
(defn- ready?
  "True once the target's frame is known, so edits record the right frame."
  ([] (ready? @state))
  ([s] (let [m (model s)]
         (and (not (:read-only? m)) (nil? (:import-error m))
              (sm/frame-ready? (:target (:doc m)) (:geometry s))))))

;; @tags mosaic symbol maker
(defn- commit!
  "Make `shapes` the document content and send it to the host as one edit.
   The current frame is recorded; the background layer is dropped. Nothing is
   committed until the frame is ready."
  [shapes]
  (when (ready?)
    (let [{:keys [doc frame]} (model @state)
          svg (sm/doc->svg (assoc doc :frame frame :shapes (vec shapes)))]
      (swap! state assoc :text svg)
      (.postMessage vscode #js{:type "edit" :text svg}))))

 ;; @tags mosaic symbol maker
(defn- insert-source! [source]
  (try
    (let [shape (sm/fit-image (:frame (model @state)) (svg/import-source source))
          shapes (current-shapes)]
      (commit! (conj shapes shape))
      (swap! state assoc :selected #{(count shapes)} :tool :select :edit-error nil :library? false))
    (catch :default error (swap! state assoc :edit-error (.-message error)))))

;; @tags mosaic symbol maker
(defn- import-svg! []
  (go
    (let [^js response (<! (request! {:type "import-symbol-svg"}))]
      (cond
        (.-error response) (swap! state assoc :edit-error (.-error response))
        (aget response "cancelled") nil
        (string? (.-content response)) (insert-source! (.-content response))
        :else (swap! state assoc :edit-error "Could not read SVG file")))))

;; --- Target ports ---

;; @tags mosaic symbol maker
(defn- target-key
  "Factory entry key of a target."
  [{:keys [kind fqn id]}]
  (case kind
    "python" (str "models:" fqn)
    "nyancir" (str "models:" id)
    nil))

;; @tags mosaic symbol maker
(defn- apply-entries!
  "Adopt the target's frame from decoded factory entries that contain it.
   Returns true when the target's entry was present."
  [decoded]
  (if (instance? js/Error decoded)
    (do (js/console.error "Symbol maker received malformed factory entries:" decoded)
        false)
    (let [key (target-key (:target (:doc (model @state))))
          entry (and key (get-in decoded [:entries key]))]
      (when entry
        (swap! state assoc
               :geometry (sm/frame-geometry (:ports entry) (:type entry))
               :load-error nil))
      (boolean entry))))

;; @tags mosaic symbol maker
(defn- geometry-unavailable!
  "Fall back to the file's recorded frame without ports so drawing is not
   blocked forever, and say why."
  [reason]
  (js/console.error "Symbol maker could not resolve its target:" reason)
  (swap! state (fn [s]
                 (cond-> (assoc s :load-error (str reason))
                   (nil? (:geometry s)) (assoc :geometry {:frame (:frame (:doc (model s)))
                                                          :ports []})))))

;; @tags mosaic symbol maker
(defn- load-geometry!
  "Request the target's entry: a Python factory resolves on demand, a nyancir
   subcircuit comes from the catalogue snapshot."
  []
  (let [{:keys [kind fqn]} (:target (:doc (model @state)))
        message (case kind
                  "python" {:type "resolve-factory" :fqn fqn :path []}
                  "nyancir" {:type "factory-entries"}
                  nil)]
    (when message
      (go
        (let [^js response (<! (request! message))]
          (if-let [error (.-error response)]
            (geometry-unavailable! error)
            (when-not (apply-entries! (protocol/decode-entries (.-value response)))
              (geometry-unavailable! "the component has no catalogue entry"))))))))

;; --- Viewport ---

;; @tags mosaic symbol maker
(defn- pane-size
  "The canvas pane in CSS pixels, or nil before the wrapper is in the DOM.
   Symbol Maker has no resize hook, so this is read at render time and the grid
   covers the canvas far past the viewBox: a pane resized before the next
   re-render still lands on grid instead of on a band (#5493)."
  []
  (when-let [^js el (js/document.querySelector ".sm-canvas-wrap")]
    [(.-clientWidth el) (.-clientHeight el)]))

;; @tags mosaic symbol maker
(defn- fit-zoom
  "Viewbox showing the frame plus a margin wide enough for port stubs and
   labels, expanded to the pane's aspect ratio so no band of the canvas is left
   without background."
  [frame]
  (sm/fit-viewbox frame (pane-size)))

;; @tags mosaic symbol maker
(defn- client-delta->local
  "Convert a screen-space pointer delta into viewBox pixels."
  [dx dy]
  (let [^js el (js/document.querySelector ".mosaic-canvas")
        m (.inverse (.getScreenCTM el))]
    (set! (.-e m) 0)
    (set! (.-f m) 0)
    (let [p (.matrixTransform (js/DOMPoint. dx dy) m)]
      [(.-x p) (.-y p)])))

;; @tags mosaic symbol maker
(defn- on-wheel!
  "Zoom about the pointer."
  [frame ^js e]
  (let [[ex ey] (map #(* % cm/grid-size) (cm/viewbox-coord e))
        direction (if (pos? (.-deltaY e)) 1 -1)]
    (swap! state update :zoom
           (fn [zoom]
             (let [[x y w h] (or zoom (fit-zoom frame))
                   dw (* direction w 0.1)
                   dh (* direction h 0.1)]
               [(- x (* dw (/ (- ex x) w)))
                (- y (* dh (/ (- ey y) h)))
                (+ w dw)
                (+ h dh)])))))

;; --- Gestures ---

;; @tags mosaic symbol maker
(defn- pointer-point
  "Pointer position in frame grid units, snapped unless Alt is held."
  [^js e]
  (let [[x y] (cm/viewbox-coord e)
        free? (.-altKey e)]
    [(sm/snap x free?) (sm/snap y free?)]))

;; @tags mosaic symbol maker
(defn- pan-gesture?
  "Middle button, Space held, or the Pan tool."
  [^js e {:keys [tool space?]}]
  (or (= 1 (.-button e)) space? (= tool :pan)))

;; @tags mosaic symbol maker
(defn- start-drag!
  [^js e drag]
  (.setPointerCapture (.-currentTarget e) (.-pointerId e))
  (swap! state assoc :drag drag))

;; @tags mosaic symbol maker
(defn- container-point
  "Pointer position relative to the canvas wrapper, for HTML overlays."
  [^js e]
  (let [rect (.getBoundingClientRect (.closest (.-target e) ".sm-canvas-wrap"))]
    [(- (.-clientX e) (.-left rect)) (- (.-clientY e) (.-top rect))]))

;; @tags mosaic symbol maker
(defn- finish-line!
  "Commit the line being drawn when it has two distinct points."
  []
  (let [{:keys [draft token]} @state]
    (swap! state assoc :draft [])
    (when-let [shape (sm/polyline-shape draft token)]
      (commit! (conj (current-shapes) shape)))))

;; @tags mosaic symbol maker
(defn- finish-text!
  "Commit inline text entry; empty text is discarded."
  []
  (let [{:keys [editing token]} @state]
    (swap! state assoc :editing nil)
    (when (and editing (not (str/blank? (:value editing))))
      (let [[x y] (:at editing)]
        (commit! (conj (current-shapes) ["text" {"x" x "y" y "token" token} (:value editing)]))))))

;; @tags mosaic symbol maker
(defn- on-canvas-down!
  [frame ^js e]
  (let [{:keys [tool zoom] :as s} @state
        point (pointer-point e)]
    (cond
      (pan-gesture? e s)
      (do (.preventDefault e)
          (start-drag! e {:kind :pan
                          :client [(.-clientX e) (.-clientY e)]
                          :zoom (or zoom (fit-zoom frame))}))

      (or (not= 0 (.-button e)) (not (ready? s)))
      nil

      :else
      (case tool
        :select (do (when-not (.-shiftKey e) (swap! state assoc :selected #{}))
                    (start-drag! e {:kind :box :start point :current point}))
        :line (swap! state #(-> % (update :draft conj point) (assoc :cursor point)))
        (:rect :ellipse) (start-drag! e {:kind tool :start point :current point})
        :text (do (.preventDefault e)
                  (swap! state assoc :editing {:at point :client (container-point e) :value ""}))
        nil))))

;; @tags mosaic symbol maker
(defn- on-shape-down!
  "Select a shape (Shift toggles) and start moving the selection."
  [index ^js e]
  (let [{:keys [tool selected] :as s} @state]
    (when (and (= :select tool) (= 0 (.-button e)) (not (pan-gesture? e s)) (ready? s))
      (.stopPropagation e)
      (swap! state assoc :selected
             (cond
               (.-shiftKey e) (if (contains? selected index) (disj selected index) (conj selected index))
               (contains? selected index) selected
               :else #{index}))
      (let [point (pointer-point e)]
        (start-drag! e {:kind :move :start point :current point})))))

;; @tags mosaic symbol maker
(defn- on-canvas-move!
  [^js e]
  (let [{:keys [drag]} @state]
    (if (= :pan (:kind drag))
      (let [[cx cy] (:client drag)
            [dx dy] (client-delta->local (- (.-clientX e) cx) (- (.-clientY e) cy))
            [x y w h] (:zoom drag)]
        (swap! state assoc :zoom [(- x dx) (- y dy) w h]))
      (let [point (pointer-point e)]
        (swap! state #(cond-> (assoc % :cursor point)
                        (:drag %) (assoc-in [:drag :current] point)
                        (:drag %) (assoc-in [:drag :shift?] (.-shiftKey e))))))))

;; @tags mosaic symbol maker
(defn- on-canvas-up!
  [^js e]
  (let [{:keys [drag token selected]} @state
        {:keys [kind start current]} drag
        shapes (current-shapes)]
    (swap! state assoc :drag nil)
    (case kind
      :resize (when (not= (:point drag) current)
                (commit! (assoc (vec shapes) (:index drag)
                                (sm/resize-image (:original drag) (:anchor drag) current (.-shiftKey e)))))
      :move (let [delta (mapv - current start)]
              (when (not= delta [0 0])
                (commit! (map-indexed (fn [i shape]
                                        (if (contains? selected i) (sm/translate-shape shape delta) shape))
                                      shapes))))
      :box (when (not= start current)
             (let [[x0 y0] start
                   [x1 y1] current
                   box [(min x0 x1) (min y0 y1) (max x0 x1) (max y0 y1)]]
               (swap! state update :selected into
                      (keep-indexed (fn [i shape]
                                      (when (sm/bounds-overlap? (sm/shape-bounds shape) box) i))
                                    shapes))))
      :rect (when-let [shape (sm/rect-between start current token)]
              (commit! (conj shapes shape)))
      :ellipse (when-let [shape (sm/ellipse-between start current (.-shiftKey e) token)]
                 (commit! (conj shapes shape)))
      nil)))

;; @tags mosaic symbol maker
(defn- delete-selection! []
  (let [{:keys [selected]} @state]
    (when (seq selected)
      (let [shapes (current-shapes)]
        (swap! state assoc :selected #{})
        (commit! (keep-indexed (fn [i shape] (when-not (contains? selected i) shape)) shapes))))))

;; @tags mosaic symbol maker
(defn- choose-token!
  "Set the palette token; recolour the selection when there is one."
  [token]
  (let [{:keys [selected]} @state]
    (swap! state assoc :token token)
    (when (seq selected)
      (commit! (map-indexed (fn [i shape] (if (and (contains? selected i) (not= "image" (first shape))) (sm/with-token shape token) shape))
                            (current-shapes))))))

;; @tags mosaic symbol maker
(defn- choose-tool! [tool]
  (swap! state assoc :tool tool :draft [] :editing nil))

;; @tags mosaic symbol maker
(defn- cancel!
  "Escape or right click: end the line being drawn, else abort the gesture and
   return to Select. Returns false when there was nothing to cancel."
  []
  (case (sm/cancel-action @state)
    :finish-line (do (finish-line!) true)
    :exit-tool (do (swap! state assoc :tool :select :drag nil :draft [] :editing nil) true)
    false))

;; @tags mosaic symbol maker
(defn- on-key-down!
  [^js e]
  (when-not (instance? js/HTMLInputElement (.-target e))
    (let [key (.-key e)]
      (case key
        " " (do (.preventDefault e) (swap! state assoc :space? true))
        ("Delete" "Backspace") (delete-selection!)
        "Enter" (finish-line!)
        "Escape" (when-not (cancel!)
                   (swap! state assoc :selected #{}))
        (when-let [tool (get tool-keys (str/lower-case key))]
          (when-not (or (.-ctrlKey e) (.-metaKey e) (.-altKey e))
            (choose-tool! tool)))))))

;; @tags mosaic symbol maker
(defn- on-key-up! [^js e]
  (when (= " " (.-key e))
    (swap! state assoc :space? false)))

;; --- Rendering ---

;; @tags mosaic symbol maker
(def ^:private editor-css
  ".mosaic-symbol-maker { display: flex; flex-direction: column; height: 100%; }
.sm-toolbar { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; padding: 4px 8px; border-bottom: 1px solid var(--color-border); }
.sm-toolbar .sm-group { display: flex; gap: 2px; align-items: center; }
.sm-toolbar button.active { outline: 2px solid var(--color-accent); }
.sm-toolbar .sm-target { margin-left: auto; color: var(--color-text-muted); }
.sm-swatch { display: inline-block; width: 12px; height: 12px; margin-right: 4px; border-radius: 2px; vertical-align: middle; }
.sm-notice { padding: 4px 8px; background: var(--color-surface-alt); color: var(--color-text-strong); }
.sm-canvas-wrap { position: relative; flex: 1; min-height: 0; }
.sm-canvas-wrap svg { display: block; width: 100%; height: 100%; touch-action: none; user-select: none; }
.sm-text-input { position: absolute; transform: translate(-50%, -50%); min-width: 6em; }
.symbol-maker-canvas .grid-minor { fill: none; stroke: var(--canvas-grid); stroke-width: 0.3; }
.symbol-maker-canvas .grid-major { fill: none; stroke: var(--canvas-grid); stroke-width: 1; }
.symbol-maker-canvas .outside { fill: var(--color-shadow); pointer-events: none; }
.symbol-maker-canvas .frame { fill: none; stroke: var(--canvas-text-muted); stroke-dasharray: 4 3; pointer-events: none; }
.symbol-maker-canvas .background { opacity: 0.5; pointer-events: none; }
.symbol-maker-canvas .sm-shape rect.shape, .symbol-maker-canvas .sm-shape ellipse.shape { pointer-events: all; }
.symbol-maker-canvas .sm-shape .hit .shape { stroke: transparent !important; fill: none !important; stroke-width: 12px; pointer-events: stroke; }
.symbol-maker-canvas .selection-box { fill: none; stroke: var(--color-badge); stroke-dasharray: 3 2; pointer-events: none; }
.symbol-maker-canvas .select-box { fill: #0000001a; stroke: var(--color-badge); pointer-events: none; }
.symbol-maker-canvas .draft { opacity: 0.6; pointer-events: none; }
.sm-library { padding: 8px; max-height: 40%; overflow: auto; }
.sm-library-items { display: grid; grid-template-columns: repeat(auto-fill, minmax(100px, 1fr)); gap: 6px; }
.sm-library-items button { display: flex; flex-direction: column; align-items: center; }
.sm-library-items img { width: 70px; height: 60px; object-fit: contain; background: white; }
.symbol-maker-canvas .sm-shape image { pointer-events: all; }
.symbol-maker-canvas .sm-resize-handle { fill: var(--color-badge); cursor: nwse-resize; }
.symbol-maker-canvas .port-overlay { pointer-events: none; }
.symbol-maker-canvas .port-overlay polyline { fill: none; stroke: var(--canvas-stroke); stroke-width: 2; }
.symbol-maker-canvas .port-overlay circle { fill: var(--canvas-stroke); }
.symbol-maker-canvas .port-overlay .photonic circle { fill: var(--canvas-wire-photonic); }
.symbol-maker-canvas .port-overlay text { fill: var(--canvas-text-muted); font: 12px sans-serif; }")

;; @tags mosaic symbol maker
(defn- px [v] (* v cm/grid-size))

;; @tags mosaic symbol maker
(defn- points-attr [points]
  (str/join " " (map (fn [[x y]] (str (px x) "," (px y))) points)))

;; @tags mosaic symbol maker, photonic model ports
(defn- port-overlay
  "Read-only port stubs, markers and labels where Mosaic draws them."
  [geometry]
  (into [:g.port-overlay]
        (map (fn [{:keys [name type stub marker label]}]
               (let [[mx my] marker]
                 ^{:key name}
                 [:g {:class type}
                  [:polyline {:points (points-attr stub)}]
                  [:circle {:cx (px mx) :cy (px my) :r (/ cm/grid-size 10)}]
                  [:text {:x (px (:x label)) :y (px (:y label))
                          :text-anchor (:anchor label)
                          :dominant-baseline (:baseline label)}
                   (:text label)]])))
        (sm/port-overlay-items geometry)))

;; @tags mosaic symbol maker
(defn- displayed-shapes
  "Preview image resizing or the current selection move without a document edit."
  [shapes {:keys [drag selected]}]
  (case (:kind drag)
    :resize (assoc (vec shapes) (:index drag)
                   (sm/resize-image (:original drag) (:anchor drag) (:current drag) (:shift? drag)))
    :move (let [delta (mapv - (:current drag) (:start drag))]
            (map-indexed (fn [i shape] (if (contains? selected i) (sm/translate-shape shape delta) shape)) shapes))
    shapes))

;; @tags mosaic symbol maker
(defn- draft-shape
  "Preview of the shape being drawn, or nil."
  [{:keys [drag draft cursor tool token]}]
  (let [{:keys [kind start current shift?]} drag]
    (case kind
      :rect (sm/rect-between start current token)
      :ellipse (sm/ellipse-between start current shift? token)
      (when (and (= :line tool) (seq draft))
        (sm/polyline-shape (cond-> draft cursor (conj cursor)) token)))))

;; @tags mosaic symbol maker
(defn- canvas
  [{:keys [doc background frame]} s]
  (let [[w h] frame
        zoom (or (:zoom s) (fit-zoom frame))
        [gx gy gw gh] (sm/canvas-bounds zoom)
        {:keys [selected drag geometry]} s
        shapes (displayed-shapes (:shapes doc) s)]
    [:svg.mosaic-canvas.symbol-maker-canvas
     {:class (name (:tool s))
      :viewBox (str/join " " zoom)
      :on-pointer-down #(on-canvas-down! frame %)
      :on-pointer-move on-canvas-move!
      :on-pointer-up on-canvas-up!
      :on-pointer-cancel #(swap! state assoc :drag nil)
      :on-double-click #(when (= :line (:tool @state)) (finish-line!))
      :on-wheel #(on-wheel! frame %)
      :on-context-menu (fn [^js e] (.preventDefault e) (cancel!))}
     [:style (sm/symbol-css ".symbol-maker-canvas")]
     [:defs
      [:pattern#sm-minor {:width 5 :height 5 :patternUnits "userSpaceOnUse"}
       [:path.grid-minor {:d "M 5 0 L 0 0 0 5"}]]
      [:pattern#sm-major {:width cm/grid-size :height cm/grid-size :patternUnits "userSpaceOnUse"}
       [:rect {:width cm/grid-size :height cm/grid-size :fill "url(#sm-minor)"}]
       [:path.grid-major {:d (str "M " cm/grid-size " 0 L 0 0 0 " cm/grid-size)}]]]
     [:rect {:x gx :y gy :width gw :height gh :fill "url(#sm-major)"}]
     [:path.outside {:fill-rule "evenodd"
                     :d (str "M " gx " " gy " h " gw " v " gh " h " (- gw) " Z "
                             "M 0 0 h " (px w) " v " (px h) " h " (- (px w)) " Z")}]
     [:rect.frame {:x 0 :y 0 :width (px w) :height (px h)}]
     (when background
       [:image.background {:href (str "data:image/svg+xml;base64," (cm/b64encode background))
                           :x 0 :y 0 :width (px w) :height (px h)
                           :preserveAspectRatio "xMidYMid meet"}])
     (into [:g.shapes]
           (map-indexed
            (fn [i shape]
              ^{:key i}
              [:g.sm-shape {:on-pointer-down #(on-shape-down! i %)}
               (when (= "lines" (first shape))
                 [:g.hit (sm/shape-hiccup shape)])
               (sm/shape-hiccup shape)]))
           shapes)
     (into [:g]
           (keep-indexed
            (fn [i shape]
              (when (contains? selected i)
                (let [[x0 y0 x1 y1] (sm/shape-bounds shape)
                      pad 0.1]
                  ^{:key i}
                  [:g
                   [:rect.selection-box {:x (px (- x0 pad)) :y (px (- y0 pad))
                                         :width (px (+ (- x1 x0) pad pad))
                                         :height (px (+ (- y1 y0) pad pad))}]
                   (when (= "image" (first shape))
                     (doall
                      (for [[label point anchor] [["top left" [x0 y0] [x1 y1]]
                                                  ["top right" [x1 y0] [x0 y1]]
                                                  ["bottom left" [x0 y1] [x1 y0]]
                                                  ["bottom right" [x1 y1] [x0 y0]]]]
                        ^{:key label}
                        [:rect.sm-resize-handle
                         {:aria-label (str "Resize image " label)
                          :x (- (px (first point)) 5) :y (- (px (second point)) 5) :width 10 :height 10
                          :on-pointer-down (fn [event]
                                             (when (and (= :select (:tool s)) (= 0 (.-button event))
                                                        (not (pan-gesture? event s)) (ready? s))
                                               (.stopPropagation event)
                                               (start-drag! event {:kind :resize :index i :original (nth (:shapes doc) i)
                                                                   :anchor anchor :point point :current point})))}])))]))))
           shapes)
     (when-let [shape (draft-shape s)]
       [:g.draft (sm/shape-hiccup shape)])
     (when (= :box (:kind drag))
       (let [[x0 y0] (:start drag)
             [x1 y1] (:current drag)]
         [:rect.select-box {:x (px (min x0 x1)) :y (px (min y0 y1))
                            :width (px (js/Math.abs (- x1 x0)))
                            :height (px (js/Math.abs (- y1 y0)))}]))
     (when geometry
       [port-overlay geometry])]))

;; @tags mosaic symbol maker
(defn- target-label [{:keys [kind fqn id]}]
  (case kind
    "python" fqn
    "nyancir" id
    "No target — ports unknown"))

;; @tags mosaic symbol maker
(defn- toolbar
  [{:keys [doc frame]} {:keys [tool token] :as s}]
  (let [locked? (not (ready? s))]
    [:div.sm-toolbar
     (into [:div.sm-group]
           (map (fn [{t :tool :keys [label key icon]}]
                  ^{:key t}
                  [:button {:class (when (= t tool) "active")
                            :title (str label " (" (str/upper-case key) ")")
                            :aria-label label
                            :disabled (and locked? (not= t :pan))
                            :on-click #(choose-tool! t)}
                   [:> icon]]))
           tools)
     (into [:div.sm-group]
           (map (fn [t]
                  ^{:key t}
                  [:button {:class (when (= t token) "active")
                            :title (str "Colour: " t)
                            :disabled locked?
                            :on-click #(choose-token! t)}
                   [:svg.sm-swatch.symbol-maker-canvas {:viewBox "0 0 12 12"}
                    [:rect {:class (str "shape " t) :x 1 :y 1 :width 10 :height 10}]]
                   t]))
           sm/tokens)
     [:button {:title "Zoom to fit the frame"
               :aria-label "Zoom to fit"
               :on-click #(swap! state assoc :zoom nil)}
      [:> icons/ArrowsFullscreen]]
     [:button {:aria-label "Import SVG" :disabled locked? :on-click import-svg!} "Import SVG"]
     [:button {:aria-label "Symbol Library" :disabled locked? :on-click #(swap! state update :library? not)} "Symbol Library"]
     [:span.sm-target (str (target-label (:target doc)) " · " (first frame) "×" (second frame))]]))

;; @tags mosaic symbol maker
(defn- frame-notice
  "Notice while the target's ports load, when they could not be resolved, or
   when a drawn symbol's recorded frame differs from the target's current
   frame. Seeds and other empty files adopt the frame silently."
  [{:keys [doc]} {:keys [geometry load-error] :as s}]
  (let [recorded (:frame doc)
        current (:frame geometry)]
    (cond
      (not (ready? s))
      [:div.sm-notice "Loading ports… Drawing is available once the frame is known."]

      load-error
      [:div.sm-notice
       (str "Could not resolve the component's ports (" load-error "); drawing against the recorded "
            (first recorded) "×" (second recorded) " frame.")]

      (and current (seq (:shapes doc)) (not= recorded current))
      [:div.sm-notice
       (str "The component's ports now give a " (first current) "×" (second current)
            " frame; this symbol was drawn for " (first recorded) "×" (second recorded)
            ". Shapes keep their grid coordinates; shapes outside the frame are kept but not shown in schematics.")])))

;; @tags mosaic symbol maker
(defn- text-input [{:keys [editing]}]
  (when-let [{:keys [client value]} editing]
    (let [[left top] client]
      [:input.sm-text-input
       {:auto-focus true
        :value value
        :style {:left left :top top}
        :on-change #(swap! state assoc-in [:editing :value] (.. % -target -value))
        :on-key-down (fn [^js e]
                       (case (.-key e)
                         "Enter" (finish-text!)
                         "Escape" (swap! state assoc :editing nil)
                         nil))
        :on-blur finish-text!}])))

 ;; @tags mosaic symbol maker
(defn- library-picker [s]
  (when (:library? s)
    (let [category (:library-category s)
          entries (sm/catalog-entries (:catalog s) (:library-query s) category)]
      [:div.sm-library
       [:input {:aria-label "Search symbols" :placeholder "Search symbols" :value (or (:library-query s) "")
                :on-change #(swap! state assoc :library-query (.. % -target -value))}]
       [:select {:aria-label "Symbol category" :value (or category "")
                 :on-change #(swap! state assoc :library-category (.. % -target -value))}
        [:option {:value ""} "All categories"]
        (doall (for [category (sort (set (map :category (:catalog s))))]
                 ^{:key category} [:option {:value category} category]))]
       (when-not (seq entries) [:p "No symbols found"])
       (into [:div.sm-library-items]
             (map (fn [{:keys [id name svg]}]
                    ^{:key id} [:button {:title name :aria-label (str "Insert " name) :on-click #(insert-source! svg)}
                                [:img {:src (str "data:image/svg+xml;base64," (cm/b64encode svg)) :alt ""}]
                                [:span name]])) entries)])))

;; @tags mosaic symbol maker
(defn- load-catalog! []
  (when-let [url (injected "library-catalog-url")]
    (-> (js/fetch url)
        (.then (fn [response] (if (.-ok response) (.json response)
                                (throw (js/Error. "Could not load symbol library")))))
        (.then (fn [data] (swap! state assoc :catalog (js->clj data :keywordize-keys true))))
        (.catch (fn [error] (swap! state assoc :edit-error (.-message error)))))))

;; @tags mosaic symbol maker
(defn- symbol-maker []
  (let [s @state
        m (model s)]
    [:<>
     [:style editor-css]
     [toolbar m s]
     [frame-notice m s]
     (when (:read-only? m) [:div.sm-notice "This symbol uses an unsupported metadata version and is read-only."])
     (when-let [error (or (:edit-error s) (:import-error m))] [:div.sm-notice {:role "alert"} error])
     [library-picker s]
     [:div.sm-canvas-wrap
      [canvas m s]
      [text-input s]]]))

;; --- Host messages ---

;; @tags mosaic symbol maker
(defn- on-host-message!
  "Re-render from document text the host sends; refresh the frame from
   factory entry pushes."
  [^js event]
  (let [^js data (.-data event)]
    (case (.-type data)
      "document"
      (let [text (.-text data)]
        (when (not= text (:text @state))
          (let [old-target (:target (:doc (model @state)))]
            (swap! state assoc :text text :selected #{})
            (when (not= old-target (:target (:doc (model @state))))
              (swap! state assoc :geometry nil :load-error nil)
              (load-geometry!)
  (load-catalog!)))))

      "factory-entries"
      (apply-entries! (protocol/decode-entries (.-value data)))

      nil)))

;; @tags mosaic symbol maker
(defonce ^:private root
  (delay (rdc/create-root (js/document.querySelector ".mosaic-app.mosaic-symbol-maker"))))

;; @tags mosaic symbol maker
(defn ^:export init []
  (.addEventListener js/window "message" on-host-message!)
  (.addEventListener js/window "keydown" on-key-down!)
  (.addEventListener js/window "keyup" on-key-up!)
  (rdc/render @root [symbol-maker])
  (load-geometry!)
  (load-catalog!))
