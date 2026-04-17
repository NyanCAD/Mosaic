; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.common
  (:require [reagent.core :as r]
            [react-bootstrap-icons :as icons]
            [clojure.spec.alpha :as s]
            reagent.ratom
            [nyancad.hipflask.util :refer [json->clj]]
            #?@(:vscode [nyancad.mosaic.jsatom]
                :cljs [nyancad.hipflask])
            clojure.edn
            clojure.set
            clojure.string
            [clojure.pprint :refer [pprint]]
            [clojure.zip :as zip]
            goog.functions
            [shadow.resource :as rc]))

; allow taking a cursor of a pouch/json atom
#?(:vscode (extend-type ^js nyancad.mosaic.jsatom/JsAtom reagent.ratom/IReactiveAtom)
   :cljs (extend-type ^js nyancad.hipflask/PAtom reagent.ratom/IReactiveAtom))

(def grid-size 50)
(def debounce #(goog.functions/debounce % 1000))

(defn dbfield
  "Debounced two-way binding between a text input and a cursor/atom.
   Keystrokes update the input immediately; writes to st are debounced.
   valfn: extract display text from atom state  (st → string)
   changefn: write raw text back into atom      (st, string → nil)"
  [typ props st valfn changefn]
  (let [int  (r/atom (valfn @st))   ; display text for instant UI feedback
        ext  (r/atom @st)           ; snapshot of st — detects external changes
        dbfn (debounce changefn)]   ; changefn called once per edit, after debounce
    (fn [typ props st valfn changefn]
      (when (not= @ext @st)         ; st changed externally (remote sync, other component)
        (reset! int (valfn @st))    ; re-extract display text
        (reset! ext @st))           ; update snapshot
      [typ (assoc props
                  :value @int
                  :on-change (fn [e]
                               (let [val (.. e -target -value)]
                                 (reset! int val)       ; immediate: update display text
                                 (dbfn st val))))])))

(defn combobox-field [props element-cursor index-cursor vector-cursor listfn valfn changefn]
  [:div.combobox-group
   [dbfield :input (merge {:class "combobox-input"} props) element-cursor valfn changefn]
   [:select {:class "combobox-select"
             :value @index-cursor
             :on-change #(reset! index-cursor (js/parseInt (.. % -target -value)))}
    (doall (map-indexed
            (fn [i item]
              [:option {:key i :value i} (valfn item)])
            (listfn @vector-cursor)))]])

(declare recursive-editor dissjoc x-circle x-circle-fill plus-circle plus-circle-fill)

(defn- leaf-editor
  "Render a single leaf field: label + input/textarea/select/csv.
   Optional extra-el is injected inline next to the input (like the model selector button)."
  [{:keys [name tooltip type options placeholder default] :or {type :input}} cursor on-change extra-el]
  (let [k (keyword name)
        ph (or placeholder "")
        valfn #(or (get % k) default "")
        wrap (fn [f] (fn [st v] (f st v) (when on-change (on-change))))
        input (case type
                :textarea [dbfield :textarea {:id name :rows 4 :placeholder ph} cursor
                           valfn (wrap #(swap! %1 assoc k %2))]
                :select   [:select {:id name :value (or (get @cursor k) default "")
                                    :on-change #(do (swap! cursor assoc k (.. % -target -value))
                                                    (when on-change (on-change)))}
                           (for [{:keys [value label]} options]
                             [:option {:key value :value value} label])]
                :checkbox [:input {:id name :type "checkbox"
                                   :checked (true? (get @cursor k))
                                   :on-change #(do (swap! cursor assoc k (.. % -target -checked))
                                                   (when on-change (on-change)))}]
                :csv      [dbfield :input {:id name :type "text" :placeholder ph} cursor
                           #(clojure.string/join " " (get % k []))
                           (wrap #(swap! %1 assoc k (clojure.string/split %2 #"[, ]+" -1)))]
                ;; default: text input
                          [dbfield :input {:id name :type "text" :placeholder ph} cursor
                           valfn (wrap #(swap! %1 assoc k %2))])]
    [:<>
     [:label {:for name :title tooltip} name]
     (if extra-el
       [:div.field-with-extra input extra-el]
       input)]))

(defn- list-editor
  "Render a list of maps: fieldset per item with add/remove, recurse per item."
  [{:keys [tooltip children type] :or {type :label}} list-cursor on-change wrap-leaf path]
  [:<>
   [type tooltip]
   (doall
    (for [[idx _] (map-indexed vector (or @list-cursor []))]
      (let [item-cursor (r/cursor list-cursor [idx])]
        [:div.fieldset-item {:key idx :role "group"}
         [:div.fieldset-legend (if (seq (:name @item-cursor)) (:name @item-cursor) "untitled")
          [:button.remove-btn {:on-click #(do (swap! list-cursor dissjoc idx)
                                              (when on-change (on-change)))
                               :title "Remove"} [x-circle] [x-circle-fill]]]
         [recursive-editor children item-cursor on-change wrap-leaf (conj path idx)]])))
   [:button.add-btn {:on-click #(do (swap! list-cursor (fnil conj [])
                                          (into {} (map (fn [{:keys [name default type]}] [(keyword name) (if (some? default) default (if (= type :checkbox) false ""))]) children)))
                                    (when on-change (on-change)))}
    [plus-circle] [plus-circle-fill] " Add"]])

(defn recursive-editor
  "Render editors for nested data. Each field is either a leaf or a list of maps.
   fields: [{:name :tooltip :type :children ...}]
   cursor: cursor to a map
   on-change: optional callback fired after data changes (debounced for text fields)
   wrap-leaf: optional (fn [path editor]) to wrap each leaf with extra UI
   path: keyword vector tracking current nesting depth"
  ([fields cursor] (recursive-editor fields cursor nil))
  ([fields cursor on-change] (recursive-editor fields cursor on-change nil []))
  ([fields cursor on-change wrap-leaf] (recursive-editor fields cursor on-change wrap-leaf []))
  ([fields cursor on-change wrap-leaf path]
   [:<>
    (doall
     (for [{:keys [name children] :as field} fields]
       (let [new-path (conj path (keyword name))]
         [:<> {:key name}
          (if children
            [list-editor field (r/cursor cursor [(keyword name)]) on-change wrap-leaf new-path]
            [leaf-editor field cursor on-change
             (when wrap-leaf (wrap-leaf new-path))])])))]))

(defn sign [n] (if (> n 0) 1 -1))

; like conj but coerces to set
(def sconj (fnil conj #{}))
(def ssconj (fnil conj (sorted-set)))

(defn bisect-left
  ([a x] (bisect-left a x identity))
  ([a x key]
   (loop [lo 0 hi (count a)]
     (if (< lo hi)
       (let [mid (quot (+ lo hi) 2)]
         (if (< (compare (key (get a mid)) x) 0)
           (recur (inc mid) hi)
           (recur lo mid)))
       lo))))

(defn insert [v i x] (vec (concat (subvec v 0 i) [x] (subvec v i))))
(defn dissjoc [v i] (vec (concat (subvec v 0 i) (subvec v (inc i)))))

(defn set-coord [v c]
  (let [v (vec v)
        kf #(subvec % 0 2)
        xy (kf c)
        idx (bisect-left v xy kf)
        val (get v idx)]
    (if (and (vector? val) (= xy (kf val)))
      (assoc v idx c)
      (insert v idx c))))

(defn remove-coord [v c]
  (let [v (vec v)
        kf #(subvec % 0 2)
        xy (kf c)
        idx (bisect-left v xy kf)
        val (get v idx)]
    (if (and (vector? val) (= xy (kf val)))
      (dissjoc v idx)
      v)))

(defn has-coord [v c]
  (let [v (vec v)
        kf #(subvec % 0 2)
        xy (kf c)
        idx (bisect-left v xy kf)
        val (get v idx)]
    (and (vector? val) (= xy (kf val)))))

(defn transform [[a b c d e f]]
  (.fromMatrix js/DOMMatrixReadOnly
               #js {:a a, :b b, :c c, :d d, :e e, :f f}))
(defn transform-vec [obj]
  [(.-a obj) (.-b obj) (.-c obj) (.-d obj) (.-e obj) (.-f obj)])
(defn point [x y] (.fromPoint js/DOMPointReadOnly (clj->js {:x x :y y})))
(def I (js/DOMMatrixReadOnly.))
(def IV (transform-vec I))

(defn viewbox-coord [e]
  (let [^js el (js/document.querySelector ".mosaic-canvas")
        m (.inverse (.getScreenCTM el))
        p (point (.-clientX e) (.-clientY e))
        tp (.matrixTransform p m)]
    [(/ (.-x tp) grid-size) (/ (.-y tp) grid-size)]))

(defn initial [device-type]
  (case device-type
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
    "amp" "U"
    "X"))

(defn transform-direction
  "Determine cardinal direction from transformation matrix.
  Returns one of: :left, :right, :top, :bottom based on the final orientation."
  [{tfm :transform}]
  (let [matrix (transform tfm)
        pt (.transformPoint matrix (point 1 0))
        x (.-x pt)
        y (.-y pt)
        abs-x (abs x)
        abs-y (abs y)]
    (if (> abs-x abs-y)
      (if (< x 0) :right :left)
      (if (< y 0) :bottom :top))))

(extend-type js/DOMMatrixReadOnly
  IPrintWithWriter
  (-pr-writer [obj writer _opts]
    (write-all writer
               "#transform ["
               (.-a obj) " "
               (.-b obj) " "
               (.-c obj) " "
               (.-d obj) " "
               (.-e obj) " "
               (.-f obj)
               "]")))

(s/def ::x number?)
(s/def ::y number?)
(s/def ::name string?)
(s/def ::transform (s/coll-of number? :count 6))
(s/def ::model (s/nilable string?))

; Device type specs
(def device-types #{"pmos" "nmos" "npn" "pnp" "resistor" "capacitor"
                    "inductor" "vsource" "isource" "diode" "led" "photodiode" "modulator"
                    "ckt" "amp"
                    "straight" "bend" "sbend" "taper" "transition"
                    "terminator" "crossing" "ring-single" "ring-double" "spiral"
                    "splitter-1x2" "coupler" "coupler-ring"
                    "mmi-1x2" "mmi-2x2" "mzi-1x2" "mzi-2x2"
                    "grating-coupler"})

(def schematic-only-types #{"wire" "port" "text"})

(s/def ::type (clojure.set/union device-types schematic-only-types))

(s/def ::variant #{"hv" "vh" "d"})
(s/def ::nets (s/map-of string? string?))
(s/def ::net string?)

(defmulti device-spec :type)
(defmethod device-spec "wire" [_]
  (s/keys :req-un [::rx ::ry ::type ::x ::y]
          :opt-un [::variant ::net]))
(defmethod device-spec :default [_]
  (s/keys :req-un [::type ::transform ::x ::y]
          :opt-un [::model ::nets]))
(s/def ::device (s/multi-spec device-spec ::type))
(s/def ::schematic (s/map-of string? ::device))

; Model specs for modeldb
(s/def ::tags (s/coll-of string? :kind vector?))
(s/def ::side #{"top" "bottom" "left" "right"})
(s/def ::port-type #{"electric" "photonic"})
(s/def ::port-entry (s/keys :req-un [::name ::side]))
(s/def ::ports (s/coll-of ::port-entry :kind vector?))
(s/def ::code string?)
(s/def ::language string?)
(s/def ::spice-type string?)
(s/def ::implementation string?)
(s/def ::library string?)
(s/def ::sections (s/coll-of string? :kind vector?))
(s/def ::port-order (s/coll-of string? :kind vector?))
(s/def ::params (s/map-of string? string?))
(s/def ::model-entry (s/keys :req-un [::language]
                              :opt-un [::name ::implementation ::spice-type
                                       ::library ::sections ::code
                                       ::port-order ::params]))
(s/def ::models (s/coll-of ::model-entry :kind vector?))

; Parameter specs for unified props system
(s/def ::tooltip string?)
(s/def ::parameter (s/keys :req-un [::name]
                           :opt-un [::tooltip]))
(s/def ::props (s/coll-of ::parameter :kind vector?))

(s/def ::symbol (s/nilable string?))
(s/def ::model-def (s/keys :req-un [::name]
                           :opt-un [::type ::tags ::ports ::models ::props ::symbol]))
(s/def ::modeldb (s/map-of string? ::model-def))

(defn has-code-models?
  "Check if a model definition has code model entries (vs. schematic-only)."
  [model]
  (boolean (seq (:models model))))

; https://clojure.atlassian.net/browse/CLJS-3207
(s/assert ::x 0)

(defn newundotree [] (atom (zip/seq-zip (list nil))))

(defn undo-state [ut]
  (some-> ut zip/down zip/rightmost zip/node))

(defn newdo [ut state]
  (swap! ut #(-> %
                 (zip/insert-child (list state))
                 zip/down))
  (undo-state @ut))

(defn undo [ut]
  (swap! ut #(if (undo-state (zip/up %)) (zip/up %) %))
  (undo-state @ut))

(defn redo [ut]
  (swap! ut #(if (undo-state (zip/down %)) (zip/down %) %))
  (undo-state @ut))

(defn ascii-patern
  ([pattern] (ascii-patern "electric" pattern))
  ([port-type pattern]
   (for [[y s] (map-indexed vector pattern)
         [x c] (map-indexed vector s)
         :when (not= c " ")]
     {:name (str c) :x x :y y :type port-type})))

(def active-bg [1 1])

(def mosfet-shape
  (ascii-patern
   [" D"
    "GB"
    " S"]))

(def bjt-conn
  (ascii-patern
   [" C"
    "B "
    " E"]))

(def twoport-bg [1 1])

(def twoport-conn
  (ascii-patern
   [" P"
    "  "
    " N"]))

;; Photonic connection patterns (horizontal orientation)
(def horizontal-conn
  "Two ports, left and right"
  (ascii-patern "photonic"
   ["   "
    "1 2"
    "   "]))

(def split-1x2-conn
  "One port left, two ports right"
  (ascii-patern "photonic"
   ["  2"
    "1  "
    "  3"]))

(def split-2x2-conn
  "Two ports left, two ports right"
  (ascii-patern "photonic"
   ["1 3"
    "   "
    "2 4"]))

(def cross-conn
  "Four ports in a cross pattern"
  (ascii-patern "photonic"
   [" 3 "
    "1 2"
    " 4 "]))

(defn pattern-size [pattern]
  (let [size (inc (apply max (mapcat (juxt :x :y) pattern)))]
    (if (js/isFinite size)
      size
      1)))

(defn- group-ports-by-side
  "Group ports by :side, accepting both keyword and string values."
  [ports]
  (let [by-side (group-by (comp keyword :side) ports)]
    by-side))

(defn port-perimeter
  "Calculate device perimeter [width height] based on ports.
   Ports are [{:name :side :type}]. Optional shape parameter: :amp constrains aspect ratio."
  ([ports] (port-perimeter ports nil))
  ([ports shape]
   (let [by-side (group-ports-by-side ports)
         left-n (count (:left by-side))
         right-n (count (:right by-side))
         top-n (count (:top by-side))
         bottom-n (count (:bottom by-side))
         raw-height (max 1 left-n right-n)
         raw-width (max 1 top-n bottom-n)
         ;; Widen to odd if parities differ on opposite sides
         height (if (and (not= (odd? left-n) (odd? right-n))
                         (even? raw-height))
                  (inc raw-height)
                  raw-height)
         base-width (if (and (not= (odd? top-n) (odd? bottom-n))
                             (even? raw-width))
                      (inc raw-width)
                      raw-width)
         ;; For amp: ensure minimum width proportional to height
         width (if (= shape :amp)
                 (max base-width (int (Math/ceil (/ height 2))))
                 base-width)]
     [width height])))

(defn spread-ports
  "Spread n ports in size slots. Gap in middle if n < size."
  [n size]
  (cond
    (= n size) (vec (range 1 (inc n)))
    (= n 0) []
    (< n size)
    (let [mid (quot (inc size) 2)
          half (quot n 2)
          first-half (range 1 (inc half))
          second-half (range (- size half -1) (inc size))]
      (vec (if (odd? n)
             (concat first-half [mid] second-half)
             (concat first-half second-half))))
    :else (vec (range 1 (inc n)))))

(defn port-locations
  "Calculate port positions from [{:name :side :type}].
   Returns flat list of port maps with :x :y added.
   Optional shape parameter: :amp left-aligns top/bottom ports."
  ([ports] (port-locations ports nil))
  ([ports shape]
   (let [[width height] (port-perimeter ports shape)
         by-side (group-ports-by-side ports)
         left (or (:left by-side) [])
         right (or (:right by-side) [])
         top (or (:top by-side) [])
         bottom (or (:bottom by-side) [])
         ;; Spread ports with gap in middle when fewer than dimension
         left-ys (spread-ports (count left) height)
         right-ys (spread-ports (count right) height)
         ;; For amp: left-align top/bottom (triangle narrows to right)
         top-xs (if (= shape :amp)
                  (vec (range 1 (inc (count top))))
                  (spread-ports (count top) width))
         bottom-xs (if (= shape :amp)
                     (vec (range 1 (inc (count bottom))))
                     (spread-ports (count bottom) width))
         left-locs (map-indexed (fn [i p] (assoc p :side :left :x 0 :y (nth left-ys i))) left)
         right-locs (map-indexed (fn [i p] (assoc p :side :right :x (inc width) :y (nth right-ys i))) right)
         top-locs (map-indexed (fn [i p] (assoc p :side :top :x (nth top-xs i) :y 0)) top)
         bottom-locs (map-indexed (fn [i p] (assoc p :side :bottom :x (nth bottom-xs i) :y (inc height))) bottom)]
     (concat top-locs bottom-locs left-locs right-locs))))

; icons
(def zoom-in (r/adapt-react-class icons/ZoomIn))
(def zoom-out (r/adapt-react-class icons/ZoomOut))
(def redoi (r/adapt-react-class icons/Arrow90degRight))
(def undoi (r/adapt-react-class icons/Arrow90degLeft))
(def rotatecw (r/adapt-react-class icons/ArrowClockwise))
(def rotateccw (r/adapt-react-class icons/ArrowCounterclockwise))
(def mirror-vertical (r/adapt-react-class icons/SymmetryVertical))
(def mirror-horizontal (r/adapt-react-class icons/SymmetryHorizontal))
(def cursor (r/adapt-react-class icons/HandIndex))
(def eraser (r/adapt-react-class icons/Eraser))
(def move (r/adapt-react-class icons/ArrowsMove))
(def wire (r/adapt-react-class icons/Pencil))
(def label (r/adapt-react-class icons/Tag))
(def delete (r/adapt-react-class icons/Trash))
(def save (r/adapt-react-class icons/Save))
(def copyi (r/adapt-react-class icons/Files))
(def cuti (r/adapt-react-class icons/Scissors))
(def pastei (r/adapt-react-class icons/Clipboard))
(def chip (r/adapt-react-class icons/Cpu))
(def docs (r/adapt-react-class icons/Book))
(def library (r/adapt-react-class icons/Collection))
(def login (r/adapt-react-class icons/PersonCircle))
(def probe (r/adapt-react-class icons/Search))
(def codemodel (r/adapt-react-class icons/FileCode))
(def schemmodel (r/adapt-react-class icons/FileDiff))
(def add-model (r/adapt-react-class icons/FilePlus))
(def download (r/adapt-react-class icons/Download))
(def sync-active (r/adapt-react-class icons/ArrowRepeat))
(def sync-done (r/adapt-react-class icons/Check))
(def text (r/adapt-react-class icons/Paragraph))
(def namei (r/adapt-react-class icons/Fonts))
(def edit (r/adapt-react-class icons/PencilSquare))
(def help (r/adapt-react-class icons/QuestionCircle))
(def external-link (r/adapt-react-class icons/BoxArrowUpRight))
(def simulate (r/adapt-react-class icons/PlayCircle))
(def amp-icon (r/adapt-react-class icons/CaretRight))
(def photonic-icon (r/adapt-react-class icons/Lightbulb))
(def search (r/adapt-react-class icons/Search))
(def exclamation-triangle (r/adapt-react-class icons/ExclamationTriangleFill))
(def exclamation-diamond (r/adapt-react-class icons/ExclamationDiamondFill))
(def x-circle (r/adapt-react-class icons/XCircle))
(def x-circle-fill (r/adapt-react-class icons/XCircleFill))
(def plus-circle (r/adapt-react-class icons/PlusCircle))
(def plus-circle-fill (r/adapt-react-class icons/PlusCircleFill))
(def eye (r/adapt-react-class icons/Eye))
(def history (r/adapt-react-class icons/ClockHistory))
(def sun-icon (r/adapt-react-class icons/SunFill))
(def moon-icon (r/adapt-react-class icons/MoonFill))
(def upload (r/adapt-react-class icons/Upload))

(defn radiobuttons
  ([cursor m] (radiobuttons cursor m nil nil nil))
  ([cursor m dblclk ctxclk] (radiobuttons cursor m dblclk ctxclk nil))
  ([cursor m dblclk ctxclk on-change]
   [:<>
    (doall (for [[label name disp] m]
             [:<> {:key name}
              [:input {:type "radio"
                       :id name
                       :value name
                       :checked (= name @cursor)
                       :on-change #(if on-change
                                     (on-change name)
                                     (reset! cursor name))}]
              [:label {:for name
                       :title disp
                       :on-double-click (when dblclk (dblclk name))
                       :on-context-menu (when ctxclk (ctxclk name))}
               label]]))]))

;; Model browser utilities - shared between libman and editor

(defn vec-startswith
  "Check if vector v starts with prefix"
  [v prefix]
  (= (subvec v 0 (min (count v) (count prefix))) prefix))

(defn build-tag-index
  "Build a hierarchical index from first 2 tags of model documents."
  [models]
  (reduce (fn [index [_id model]]
            (let [tags (vec (take 2 (or (:tags model) [])))]
              (if (seq tags)
                (assoc-in index tags {})
                index)))
          {} models))

(defn tag-tree
  "Render a collapsible tag tree.
   Nodes toggle membership in selected-atom (a vector of active tags).
   Opening a node closes its siblings at the same level."
  [selected-atom trie]
  (let [siblings (set (keys trie))]
    [:<>
     (doall (for [[tag subtrie] trie
                  :let [active (some #{tag} @selected-atom)]]
              [:details.tree {:key tag
                              :open active
                              :on-toggle #(do
                                            (.stopPropagation %)
                                            (if (.. % -target -open)
                                              (swap! selected-atom
                                                     (fn [v] (conj (vec (remove siblings v)) tag)))
                                              (swap! selected-atom
                                                     (fn [v] (vec (remove #{tag} v))))))}
               [:summary tag]
               (when (seq subtrie)
                 [:div.detailbody
                  [tag-tree selected-atom subtrie]])]))]))

(defn parse-prop-tag
  "Parse a property tag like \"type:ckt\" into [\"type\" \"ckt\"], or nil if plain tag."
  [tag]
  (let [idx (clojure.string/index-of tag ":")]
    (when idx
      [(subs tag 0 idx) (subs tag (inc idx))])))

(defn filter-models
  "Filter models by active tags and search text.
   sel-tags: vector of active tags. Plain tags match via set containment on :tags.
   Property tags like \"type:ckt\" match the corresponding model field."
  [models sel-tags search-text]
  (let [plain-tags (set (remove parse-prop-tag sel-tags))
        prop-tags (keep parse-prop-tag sel-tags)]
    (filter (fn [[model-id model]]
              (let [model-tags (set (or (:tags model) []))
                    model-name (get model :name model-id)
                    tags-match (every? model-tags plain-tags)
                    props-match (every? (fn [[k v]] (= (str (get model (keyword k))) v)) prop-tags)
                    name-match (or (empty? search-text)
                                   (clojure.string/includes?
                                    (clojure.string/lower-case model-name)
                                    (clojure.string/lower-case search-text)))]
                (and tags-match props-match name-match)))
            models)))

(defn model-list
  "Render a list of models as radio buttons.
   selected-atom: atom holding selected model id
   models: seq of [model-id model] pairs
   on-dblclick: optional (fn [model-id] -> event-handler) for double-click
   on-contextmenu: optional (fn [model-id] -> event-handler) for right-click
   on-tag-click: optional (fn [tag] -> nil) called when a tag badge is clicked"
  ([selected-atom models] (model-list selected-atom models nil nil nil))
  ([selected-atom models on-dblclick on-contextmenu]
   (model-list selected-atom models on-dblclick on-contextmenu nil))
  ([selected-atom models on-dblclick on-contextmenu on-tag-click]
   (if (seq models)
     [radiobuttons selected-atom
      (doall (for [[model-id model] models
                   :let [schem? (not (has-code-models? model))
                         icon (if schem? schemmodel codemodel)
                         type-badge (str "type:" (:type model "ckt"))
                         extra-tags (drop 2 (:tags model))
                         all-badges (cons type-badge extra-tags)]]
               [[:span [icon] " " (get model :name model-id)
                 (for [tag all-badges]
                   [:span.tag-badge {:key tag
                                     :class [(when (parse-prop-tag tag) "prop-tag")
                                             (when on-tag-click "clickable")]
                                     :on-click (when on-tag-click
                                                 #(do (.stopPropagation %)
                                                      (on-tag-click tag)))}
                    tag])]
                model-id
                (get model :name model-id)]))
      on-dblclick
      on-contextmenu]
     [:div.empty "No models found"])))

(defn renamable
  ([cursor] (renamable cursor nil))
  ([cursor default]
   (r/with-let [active (r/atom false)]
     (if (or @active (and (empty? @cursor) (not default)))
       [:form {:on-submit (fn [^js e]
                            (.preventDefault e)
                            (reset! cursor (.. e -target -elements -namefield -value))
                            (reset! active false))}
        [:input {:type :text
                 :name "namefield"
                 :auto-focus true
                 :default-value @cursor
                 :on-blur (fn [e]
                            (reset! cursor (.. e -target -value))
                            (reset! active false))}]]
       [:span {:on-click #(reset! active true)}
        (or @cursor default)
        " " [edit]]))))

(defn keyset [e]
  (letfn [(conj-when [s e c] (if c (conj s e) s))]
    (-> #{(keyword (clojure.string/lower-case (.-key e)))}
        (conj-when :control (.-ctrlKey e))
        (conj-when :alt (.-altKey e))
        (conj-when :shift (.-shiftKey e))
        (conj-when :os (.-metaKey e)))))

(defn keyboard-shortcuts [shortcuts e]
  (when-not (or ;; it's a repeat event
             (.-repeat e)
             ;; the user is typing, ignore
             (= "INPUT" (.. e -target -tagName))
             (= "TEXTAREA" (.. e -target -tagName)))
    (println (keyset e))
    ((get shortcuts (keyset e) #()))))

(defn pprint-str [data]
  (-> data
      pprint
      with-out-str
      clojure.string/trim))

(defn format [s state]
  (-> s
      (clojure.string/replace
       #"(?<=[^{]|^)\{([^{}:]+)(?::(?:\.(\d+))?([ef])?)?\}(?=[^}]|$)"
       (fn [[_ code precision type]]
         (try
           (let [key-names (map name (keys state))
                 values (map clj->js (vals state))
                 func (apply js/Function (concat key-names [(str "return (" code ")")]))
                 result (json->clj (apply func values))
                 fres (js/parseFloat result)]
             (case type
               "e" (.toExponential fres (js/parseInt precision))
               "f" (.toFixed fres (js/parseInt precision))
               (if (map? result)
                 (pprint-str result)
                 (or result "?"))))
           (catch js/Error e
             (js/console.warn "Warning formatting" s e)
             "?"))))
      (clojure.string/replace #"\{\{|\}\}" first)))

(defn random-name [] (str (random-uuid)))

(defn base64->blob-url
  "Convert base64 string to blob URL for use with object tag"
  [base64-data content-type]
  (let [binary-string (js/atob base64-data)
        len (.-length binary-string)
        bytes (js/Uint8Array. len)]
    (doseq [i (range len)]
      (aset bytes i (.charCodeAt binary-string i)))
    (let [blob (js/Blob. #js[bytes] #js{:type content-type})]
      (.createObjectURL js/URL blob))))

(defn generate-important-template
  "Build a template string from props marked :important. Returns nil if none."
  [props]
  (let [important (filter :important props)]
    (when (seq important)
      (clojure.string/join "\n"
        (cons "{self.name}"
              (map #(str (:name %) ": {self.props." (:name %) "}")
                   important))))))

;; Model ID utilities
(defn model-key
  "Convert a bare model ID to a database key with 'models:' prefix.
   Returns nil if input is nil. Asserts that non-nil input is not already prefixed."
  [bare-id]
  (when bare-id
    (assert (not (clojure.string/starts-with? bare-id "models:"))
            (str "model-key expects bare ID, got prefixed: " bare-id))
    (str "models:" bare-id)))

(defn bare-id
  "Extract bare ID from a model database key, removing 'models:' prefix.
   Returns nil if input is nil. Asserts that non-nil input is prefixed."
  [model-key]
  (when model-key
    (assert (clojure.string/starts-with? model-key "models:")
            (str "bare-id expects prefixed model key, got bare ID: " model-key))
    (subs model-key 7)))

;; Authentication utilities
(def couchdb-url "https://api.nyancad.com/")

(defn get-current-user []
  (.getItem js/localStorage "username"))

(defn set-current-user [username]
  (.setItem js/localStorage "username" username))

(defn clear-current-user []
  (.removeItem js/localStorage "username"))

(defn str-to-hex
  "Encode string as UTF-8 bytes in hex, matching CouchDB's userdb- naming.
   Each byte is zero-padded to 2 hex digits, consistent with Python's
   str_to_hex in nyancad-server."
  [s]
  (->> (js/Array.from (.encode (js/TextEncoder.) s))
       (map #(-> % (.toString 16) (.padStart 2 "0")))
       (apply str)))

;; Workspace support - read from URL param
(def url-params (js/URLSearchParams. js/window.location.search))
(def current-workspace (.get url-params "ws"))  ; nil if not set (uses personal library)

;; User's workspace list (fetched on demand)
(defonce user-workspaces (r/atom []))

(defn get-db-name []
  "Get the database name for current context (workspace or personal library)."
  (when-let [username (get-current-user)]
    (if current-workspace
      current-workspace                          ; ws-slug
      (str "userdb-" (str-to-hex username)))))

(defn get-sync-url []
  (when-let [db-name (get-db-name)]
    (str couchdb-url db-name)))

;; Reactive authentication state
(defonce auth-state (r/atom false))

(defn init-auth-state!
  "Initialize authentication state from localStorage."
  []
  (reset! auth-state (not (nil? (get-current-user)))))

(defn is-authenticated? []
  @auth-state)

(defn logout!
  "Clear authentication state completely."
  []
  (clear-current-user)
  (reset! auth-state false))

;; Onboarding state utilities
(defn onboarding-shown? []
  (= "true" (.getItem js/localStorage "onboarding-shown")))

(defn set-onboarding-shown! []
  (.setItem js/localStorage "onboarding-shown" "true"))

;; Modal dialog functionality
(defonce modal-content (r/atom nil))

(defn modal []
  [:div.modal.window
   {:class (if @modal-content "visible" "hidden")}
   @modal-content])

(defonce context-content (r/atom {:x 0 :y 0 :body nil}))

(defn contextmenu []
  (let [{:keys [:x :y :body]} @context-content]
    [:div.contextmenu.window
     {:style {:top y, :left, x}
      :class (if body "visible" "hidden")}
     body]))

(defn prompt [text cb]
  (reset! modal-content
          [:form {:on-submit (fn [^js e]
                               (js/console.log e)
                               (.preventDefault e)
                               (let [name (.. e -target -elements -valuefield -value)]
                                 (when (seq name)
                                   (cb name)))
                               (reset! modal-content nil))}
           [:div text]
           [:div
            [:input {:name "valuefield" :type "text" :auto-focus true}]]
           [:div
            [:button {:on-click #(reset! modal-content nil)} "Cancel"]
            [:input {:type "submit" :value "Ok"}]]]))

(defn alert [text]
  (reset! modal-content
          [:div [:p text]
           [:button {:on-click #(reset! modal-content nil)} "Ok"]]))

(defn set-context-menu [x y body]
  (reset! context-content {:x x :y y :body body}))

(defn clear-context-menu []
  (swap! context-content assoc :body nil))

;; Onboarding popup for first-time users
(defn device-icon [name]
  (let [icon (case name
               "resistor" (rc/inline "icons/resistor.svg")
               "capacitor" (rc/inline "icons/capacitor.svg")
               "inductor" (rc/inline "icons/inductor.svg")
               "diode" (rc/inline "icons/diode.svg")
               "vsource" (rc/inline "icons/vsource.svg")
               "isource" (rc/inline "icons/isource.svg")
               "nmos" (rc/inline "icons/nmos.svg")
               "pmos" (rc/inline "icons/pmos.svg")
               "npn" (rc/inline "icons/npn.svg")
               "pnp" (rc/inline "icons/pnp.svg")
               "port" (rc/inline "icons/port.svg")
               "ground" (rc/inline "icons/ground.svg")
               "supply" (rc/inline "icons/supply.svg")
               "straight" (rc/inline "icons/straight.svg")
               "bend" (rc/inline "icons/bend.svg")
               "sbend" (rc/inline "icons/sbend.svg")
               "taper" (rc/inline "icons/taper.svg")
               "transition" (rc/inline "icons/transition.svg")
               "terminator" (rc/inline "icons/terminator.svg")
               "crossing" (rc/inline "icons/crossing.svg")
               "ring-single" (rc/inline "icons/ring-single.svg")
               "ring-double" (rc/inline "icons/ring-double.svg")
               "spiral" (rc/inline "icons/spiral.svg")
               "splitter-1x2" (rc/inline "icons/splitter-1x2.svg")
               "coupler" (rc/inline "icons/coupler.svg")
               "coupler-ring" (rc/inline "icons/coupler-ring.svg")
               "mmi-1x2" (rc/inline "icons/mmi-1x2.svg")
               "mmi-2x2" (rc/inline "icons/mmi-2x2.svg")
               "mzi-1x2" (rc/inline "icons/mzi-1x2.svg")
               "mzi-2x2" (rc/inline "icons/mzi-2x2.svg")
               "led" (rc/inline "icons/led.svg")
               "photodiode" (rc/inline "icons/photodiode.svg")
               "modulator" (rc/inline "icons/modulator.svg")
               "grating-coupler" (rc/inline "icons/grating-coupler.svg")
               "")]
    [:span.device-icon {:dangerouslySetInnerHTML (r/unsafe-html icon)}]))

(defn shortcut-table [title rows]
  [:div.shortcut-category
   [:h3 title]
   [:table
    [:tbody
     (for [[icon key desc] rows]
       ^{:key desc}
       [:tr
        [:td.icon-cell icon]
        [:td (when (seq key) [:kbd key])]
        [:td.desc desc]])]]])

(defn onboarding-popup []
  [:div.onboarding
   [:button.close-btn
    {:on-click #(reset! modal-content nil)
     :aria-label "Close"}
    "\u00D7"]
   [:h2 "Welcome to Mosaic"]
   [:p.intro "Modern schematic entry and simulation for analog IC design."]

   ;; Components section - full width
   [:div.shortcut-category.components-section
    [:h3 "Components"]
    [:div.components-row
     [:div.component-item
      [device-icon "resistor"]
      [:kbd "R"]
      [:span.label "Resistor"]]
     [:div.component-item
      [device-icon "capacitor"]
      [:kbd "C"]
      [:span.label "Capacitor"]]
     [:div.component-item
      [device-icon "inductor"]
      [:kbd "L"]
      [:span.label "Inductor"]]
     [:div.component-item
      [device-icon "diode"]
      [:kbd "D"]
      [:span.label "Diode"]]
     [:div.component-item
      [device-icon "vsource"]
      [:kbd "V"]
      [:span.label "Vsource"]]
     [:div.component-item
      [device-icon "isource"]
      [:kbd "I"]
      [:span.label "Isource"]]
     [:div.component-item
      [device-icon "nmos"]
      [:kbd "M"]
      [:span.label "MOSFET"]]
     [:div.component-item
      [device-icon "npn"]
      [:kbd "B"]
      [:span.label "BJT"]]
     [:div.component-item
      [chip]
      [:kbd "X"]
      [:span.label "Subcircuit"]]
     [:div.component-item
      [device-icon "port"]
      [:kbd "P"]
      [:span.label "Port"]]]
    [:p.hint "Long-press button or Shift+key for alternatives (PMOS, PNP, etc.)"]]

   ;; Two column layout for tools
   [:div.shortcut-grid
    [shortcut-table "Drawing"
     [[[cursor] "Esc" "Select"]
      [[wire] "W" "Wire"]
      [[eraser] "E" "Eraser"]
      [[move] "Space" "Pan (hold)"]
      [[rotatecw] "S" "Spin CW"]
      [[rotateccw] "Shift+S" "Spin CCW"]
      [[mirror-vertical] "F" "Flip X"]
      [[mirror-horizontal] "Shift+F" "Flip Y"]
      [[delete] "Del" "Delete"]
      [[zoom-in] "Scroll" "Zoom"]]]

    [shortcut-table "Actions"
     [[[undoi] "Ctrl+Z" "Undo"]
      [[redoi] "Ctrl+Shift+Z" "Redo"]
      [[copyi] "Ctrl+C" "Copy"]
      [[cuti] "Ctrl+X" "Cut"]
      [[pastei] "Ctrl+V" "Paste"]
      [[library] "" "Library Manager"]
      [[history] "" "Snapshot History"]
      [[external-link] "" "Pop Out Notebook"]
      [[help] "" "Keyboard Shortcuts"]
      [[sun-icon] "" "Toggle Theme"]
      [[login] "" "Account"]]]]

   [:div.actions
    [:button
     {:on-click (fn []
                  (.open js/window "docs" "docs"))}
     [docs]
     [:span "Documentation"]]
    [:button.primary
     {:on-click (fn []
                  (set-onboarding-shown!)
                  (reset! modal-content nil))}
     "Get Started"]]])

(defn show-onboarding! []
  (reset! modal-content [onboarding-popup]))
