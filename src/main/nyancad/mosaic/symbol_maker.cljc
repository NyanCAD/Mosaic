; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.symbol-maker
  "Pure core of the symbol maker: the drawing frame derived from model ports,
   grid snapping, shape geometry, and the self-contained SVG file format.

   Coordinates are grid units relative to the frame's top-left corner, the
   origin of the saved SVG viewBox. Mosaic stretches a ckt symbol image over the
   instance box, so frame-local units times `cm/grid-size` are file pixels.
   A document is {:version 1 :target T :frame [w h] :shapes [shape ...]} where a
   shape is a Mosaic element vector with string keys:
     [\"lines\" [[[x y] ...] ...] {\"token\" t}]
     [\"rect\" {\"x\" \"y\" \"width\" \"height\" \"token\"}]
     [\"ellipse\" {\"cx\" \"cy\" \"rx\" \"ry\" \"token\"}]
     [\"text\" {\"x\" \"y\" \"token\"} content]"
  (:require [clojure.string :as str]
            [nyancad.mosaic.common :as cm]
            [nyancad.mosaic.symbol-file :as sf]))

;; @tags mosaic symbol maker
(def metadata-id sf/metadata-id)

;; @tags mosaic symbol maker
(def owned-svg? sf/owned-svg?)

;; @tags mosaic symbol maker
(def invalidate-symbol-paths sf/invalidate-symbol-paths)

;; @tags mosaic symbol maker
(def tokens
  "Semantic colour tokens, in palette order. The first is the default."
  ["ink" "accent" "fill"])

;; --- Frame ---

;; @tags mosaic symbol maker, photonic model ports
(defn frame-geometry
  "Frame size and port placements Mosaic's circuit-sym-box uses for `ports`.
   Returns {:frame [w h] :ports locs}; locs are `cm/port-locations` results in
   instance-tile coordinates (the box spans tiles 1..w × 1..h). No ports gives
   a [1 1] frame without placements."
  [ports type]
  (if (seq ports)
    {:frame (cm/port-perimeter ports type)
     :ports (vec (cm/port-locations ports type))}
    {:frame [1 1] :ports []}))

;; @tags mosaic symbol maker
(defn frame-ready?
  "True when edits may be committed: the target's ports have resolved into
   `geometry`, or there is no target to wait for. Committing earlier would
   record a placeholder frame."
  [target geometry]
  (or (nil? target) (some? geometry)))

;; @tags mosaic symbol maker
(defn snap
  "Round `v` to the nearest tenth of a grid unit unless `free?` (Alt held)."
  ([v] (snap v false))
  ([v free?]
   (if free? v (/ (js/Math.round (* v 10)) 10))))

;; @tags mosaic symbol maker
(defn cancel-action
  "What Escape or a right click does to editor state, as in the Mosaic editor:
   :finish-line ends the line being drawn and keeps the Line tool, :exit-tool
   aborts any gesture and returns to Select, nil means nothing to cancel."
  [{:keys [draft drag tool]}]
  (cond
    (seq draft) :finish-line
    (or drag (not= tool :select)) :exit-tool))

;; @tags mosaic symbol maker
(defn infer-target
  "Target of a file without metadata, from its workspace-relative path.
   Only `.gsch` sidecars (`<nyancir-id>.svg`) identify a target."
  [rel-path]
  (when (string? rel-path)
    (let [path (str/replace rel-path "\\" "/")]
      (when (str/ends-with? path ".gsch.svg")
        {:kind "nyancir" :id (subs path 0 (- (count path) (count ".svg")))}))))

;; --- Shapes ---

;; @tags mosaic symbol maker
(defn- tidy
  "Drop floating-point noise from a derived coordinate (1e-6 grid units)."
  [v]
  (/ (js/Math.round (* v 1e6)) 1e6))

;; @tags mosaic symbol maker
(defn shape-attrs
  "Attribute map of a shape; `lines` keeps it after the arcs."
  [[tag a b]]
  (if (= tag "lines") b a))

;; @tags mosaic symbol maker
(defn shape-token
  "Colour token of a shape, defaulting to ink."
  [shape]
  (get (shape-attrs shape) "token" (first tokens)))

;; @tags mosaic symbol maker
(defn with-token
  "Return `shape` recoloured with `token`."
  [[tag :as shape] token]
  (if (= tag "lines")
    (assoc-in shape [2 "token"] token)
    (assoc-in shape [1 "token"] token)))

;; @tags mosaic symbol maker
(defn shape-bounds
  "Bounding box [x0 y0 x1 y1] of a shape in grid units."
  [[tag a]]
  (case tag
    "lines" (let [points (apply concat a)
                  xs (map first points)
                  ys (map second points)]
              [(apply min xs) (apply min ys) (apply max xs) (apply max ys)])
    "rect" (let [{:strs [x y width height]} a]
             [x y (+ x width) (+ y height)])
    "ellipse" (let [{:strs [cx cy rx ry]} a]
                [(- cx rx) (- cy ry) (+ cx rx) (+ cy ry)])
    "text" (let [{:strs [x y]} a]
             [x y x y])))

;; @tags mosaic symbol maker
(defn bounds-overlap?
  "True when two [x0 y0 x1 y1] boxes touch or overlap."
  [[ax0 ay0 ax1 ay1] [bx0 by0 bx1 by1]]
  (and (<= ax0 bx1) (<= bx0 ax1) (<= ay0 by1) (<= by0 ay1)))

;; @tags mosaic symbol maker
(defn in-frame?
  "True unless `shape` lies entirely outside the [w h] frame."
  [[w h] shape]
  (bounds-overlap? (shape-bounds shape) [0 0 w h]))

;; @tags mosaic symbol maker
(defn translate-shape
  "Move a shape by [dx dy] grid units."
  [[tag a :as shape] [dx dy]]
  (let [shift (fn [v d] (tidy (+ v d)))
        move-xy (fn [attrs kx ky] (-> attrs (update kx shift dx) (update ky shift dy)))]
    (case tag
      "lines" (assoc shape 1 (mapv (fn [arc] (mapv (fn [[x y]] [(shift x dx) (shift y dy)]) arc)) a))
      "rect" (assoc shape 1 (move-xy a "x" "y"))
      "ellipse" (assoc shape 1 (move-xy a "cx" "cy"))
      "text" (assoc shape 1 (move-xy a "x" "y")))))

;; @tags mosaic symbol maker
(defn rect-between
  "Rectangle shape spanning two corner points, or nil when degenerate."
  [[x0 y0] [x1 y1] token]
  (let [width (tidy (js/Math.abs (- x1 x0)))
        height (tidy (js/Math.abs (- y1 y0)))]
    (when (and (pos? width) (pos? height))
      ["rect" {"x" (min x0 x1) "y" (min y0 y1)
               "width" width "height" height "token" token}])))

;; @tags mosaic symbol maker
(defn ellipse-between
  "Ellipse inscribed in the box spanning two points, or nil when degenerate.
   `circle?` (Shift held) makes the box square along the larger drag axis."
  [[x0 y0] [x1 y1] circle? token]
  (let [dx (- x1 x0)
        dy (- y1 y0)
        side (max (js/Math.abs dx) (js/Math.abs dy))
        [dx dy] (if circle?
                  [(* side (if (neg? dx) -1 1)) (* side (if (neg? dy) -1 1))]
                  [dx dy])
        rx (tidy (/ (js/Math.abs dx) 2))
        ry (tidy (/ (js/Math.abs dy) 2))]
    (when (and (pos? rx) (pos? ry))
      ["ellipse" {"cx" (tidy (+ x0 (/ dx 2))) "cy" (tidy (+ y0 (/ dy 2)))
                  "rx" rx "ry" ry "token" token}])))

;; @tags mosaic symbol maker
(defn polyline-shape
  "Lines shape through `points` with consecutive duplicates removed, or nil
   when fewer than two distinct points remain."
  [points token]
  (let [points (vec (dedupe points))]
    (when (< 1 (count points))
      ["lines" [points] {"token" token}])))

;; --- Rendering ---

;; @tags mosaic symbol maker
(defn- px
  "Format grid units as file pixels, rounded to 1/1000 px."
  [v]
  (str (/ (js/Math.round (* v cm/grid-size 1000)) 1000)))

;; @tags mosaic symbol maker
(defn shape-hiccup
  "SVG hiccup for a shape in file pixels, classed `shape <token>`.
   Every coordinate, including radii and text anchors, is scaled by the grid
   size. Usable both by Reagent and by `doc->svg`."
  [[tag a :as shape]]
  (let [attrs (shape-attrs shape)
        class (str "shape " (shape-token shape))
        scaled (fn [ks] (into {:class class} (map (fn [k] [(keyword k) (px (get attrs k))])) ks))]
    (case tag
      "lines" (into [:g]
                    (map (fn [arc]
                           [:polyline {:class class
                                       :points (str/join " " (map (fn [[x y]] (str (px x) "," (px y))) arc))}]))
                    a)
      "rect" [:rect (scaled ["x" "y" "width" "height"])]
      "ellipse" [:ellipse (scaled ["cx" "cy" "rx" "ry"])]
      "text" [:text (scaled ["x" "y"]) (str (nth shape 2 ""))])))

;; @tags mosaic symbol maker
(defn symbol-css
  "Self-styling rules for symbol shapes, scoped under `scope`. light-dark()
   resolves through the OS colour scheme inside an isolated <image>, where VS
   Code theme variables are absent."
  [scope]
  (let [ink "light-dark(#1f2328, #e6edf3)"
        accent "light-dark(#0969da, #58a6ff)"
        fill "light-dark(#afb8c1, #57606a)"]
    (str scope " { color-scheme: light dark; }\n"
         scope " .shape { fill: none; stroke-width: 3; stroke-linecap: round; stroke-linejoin: round; }\n"
         scope " .ink { stroke: " ink "; }\n"
         scope " .accent { stroke: " accent "; }\n"
         scope " .fill { stroke: " ink "; fill: " fill "; }\n"
         scope " text.shape { stroke: none; font: 20px sans-serif; text-anchor: middle; dominant-baseline: middle; }\n"
         scope " text.ink { fill: " ink "; }\n"
         scope " text.accent { fill: " accent "; }\n"
         scope " text.fill { fill: " ink "; }\n")))

;; --- File format ---

;; @tags mosaic symbol maker
(defn- escape-text
  "Escape XML character data."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

;; @tags mosaic symbol maker
(defn- unescape-text
  "Invert `escape-text` (and attribute quote escaping)."
  [s]
  (-> s
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&amp;" "&")))

;; @tags mosaic symbol maker
(defn- xml
  "Serialize hiccup with string/keyword tags into XML text."
  [node]
  (if (vector? node)
    (let [[tag & body] node
          [attrs children] (if (map? (first body)) [(first body) (rest body)] [{} body])]
      (str "<" (name tag)
           (apply str (map (fn [[k v]]
                             (str " " (name k) "=\"" (str/replace (escape-text v) "\"" "&quot;") "\""))
                           attrs))
           (if (seq children)
             (str ">" (apply str (map xml children)) "</" (name tag) ">")
             "/>")))
    (escape-text node)))

;; @tags mosaic symbol maker
(defn doc->svg
  "Serialize a document into a standalone, self-styled SVG whose viewBox is
   the frame in pixels. The metadata keeps every shape, so a frame that shrinks
   never loses drawing; only shapes touching the frame are rendered, since the
   viewBox would clip the rest."
  [{:keys [frame shapes] :as doc}]
  (let [[w h] frame
        width (px w)
        height (px h)]
    (xml (into [:svg {:xmlns "http://www.w3.org/2000/svg"
                      :viewBox (str "0 0 " width " " height)
                      :width width
                      :height height}
                [:metadata {:id metadata-id} (js/JSON.stringify (clj->js doc))]
                [:style (symbol-css ":root")]]
               (map shape-hiccup (filter #(in-frame? frame %) shapes))))))

;; @tags mosaic symbol maker
(def ^:private metadata-pattern
  (re-pattern (str sf/metadata-open "([\\s\\S]*?)</metadata>")))

;; @tags mosaic symbol maker
(defn- coord?
  "True for a finite number."
  [v]
  (and (number? v) (js/isFinite v)))

;; @tags mosaic symbol maker
(defn- valid-shape?
  "True when `shape` is a well-formed element the renderer can draw: a known
   tag, an attribute map with finite coordinates, positive extents, and lines
   whose every arc has at least two finite points."
  [shape]
  (and (vector? shape)
       (let [[tag a b] shape
             attrs (if (= tag "lines") b a)
             coords? (fn [& ks] (every? #(coord? (get attrs %)) ks))]
         (and (or (nil? attrs) (map? attrs))
              (case tag
                "lines" (and (vector? a) (seq a)
                             (every? (fn [arc]
                                       (and (vector? arc) (< 1 (count arc))
                                            (every? #(and (vector? %) (= 2 (count %)) (every? coord? %)) arc)))
                                     a))
                "rect" (and (coords? "x" "y" "width" "height")
                            (pos? (get attrs "width")) (pos? (get attrs "height")))
                "ellipse" (and (coords? "cx" "cy" "rx" "ry")
                               (pos? (get attrs "rx")) (pos? (get attrs "ry")))
                "text" (and (coords? "x" "y") (string? b))
                false)))))

;; @tags mosaic symbol maker
(defn- read-frame
  "A frame of two positive numbers, else the unit frame."
  [frame]
  (if (and (vector? frame) (= 2 (count frame)) (every? #(and (coord? %) (pos? %)) frame))
    frame
    [1 1]))

;; @tags mosaic symbol maker
(defn svg->doc
  "Parse SVG text. A symbol maker file yields {:doc doc}; any other SVG,
   including one whose metadata is not a readable JSON object, yields
   {:background svg}. Malformed shapes and frames in readable metadata are
   dropped or defaulted rather than breaking the editor."
  [svg]
  (or (when-let [[_ json] (and (string? svg) (re-find metadata-pattern svg))]
        (try
          (let [data (js->clj (js/JSON.parse (unescape-text json)))
                target (get data "target")]
            (when (map? data)
              {:doc {:version (get data "version" 1)
                     :target (when (and (map? target) (string? (get target "kind")))
                               (into {} (map (fn [[k v]] [(keyword k) v])) target))
                     :frame (read-frame (get data "frame"))
                     :shapes (let [shapes (get data "shapes")]
                               (if (vector? shapes) (filterv valid-shape? shapes) []))}}))
          (catch :default _ nil)))
      {:background svg}))

;; --- Port overlay ---

;; @tags mosaic symbol maker, photonic model ports
(defn port-overlay-items
  "Frame-local drawing data for port stubs, markers and labels, matching where
   circuit-sym-box draws them. Each item has :name :type :stub [[x y] [x y]]
   :marker [x y] and :label {:x :y :anchor :baseline :text}."
  [{:keys [frame ports]}]
  (let [[w h] frame]
    (mapv (fn [{:keys [x y side type] :as port}]
            (let [cx (- x 0.5)
                  cy (- y 0.5)
                  [edge label-x label-y anchor baseline]
                  (case (keyword side)
                    :left [[0 cy] 0.15 cy "start" "middle"]
                    :right [[w cy] (- w 0.15) cy "end" "middle"]
                    :top [[cx 0] cx 0.3 "middle" "hanging"]
                    :bottom [[cx h] cx (- h 0.3) "middle" "baseline"])]
              {:name (:name port)
               :type type
               :stub [edge [cx cy]]
               :marker [cx cy]
               :label {:x label-x :y label-y :anchor anchor :baseline baseline
                       :text (cm/port-display-name port)}}))
          ports)))
