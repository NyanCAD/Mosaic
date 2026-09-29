; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.common
  (:require [reagent.core :as r]
            [react-bootstrap-icons :as icons]
            [clojure.spec.alpha :as s]
            reagent.ratom
            [nyancad.hipflask.util :refer [json->clj]]
            #?@(:web [nyancad.hipflask]
                :vscode [nyancad.mosaic.jsatom]
                :test [])
            clojure.edn
            clojure.set
            clojure.string
            [clojure.pprint :refer [pprint]]
            [clojure.zip :as zip]
            goog.functions
            [shadow.resource :as rc]))

;; Reactive-atom protocol extension. The underlying atom type depends on the
;; platform: PAtom (PouchDB-backed) for the web app, JsAtom (jsonc-backed)
;; for the VSCode webview. The :test build runs under Node without either.
#?(:web (extend-type ^js nyancad.hipflask/PAtom reagent.ratom/IReactiveAtom)
   :vscode (extend-type ^js nyancad.mosaic.jsatom.protocol/JsAtom reagent.ratom/IReactiveAtom)
   :test nil)

(def mac? (and (exists? js/navigator)
              (boolean (re-find #"(?i)Mac|iPhone|iPad|iPod" js/navigator.userAgent))))

(def mod-key (if mac? "⌘" "Ctrl"))

(def mod-bind (if mac? :os :control))

(def grid-size 50)
(def debounce #(goog.functions/debounce % 1000))

(defn dbfield
  "Debounced two-way binding between a text input and a cursor/atom.
   Keystrokes update the input immediately; writes to st are debounced.
   valfn: extract display text from atom state  (st → string)
   changefn: write raw text back into atom      (st, string → nil)"
  [typ _props st valfn changefn]
  (let [int  (r/atom (valfn @st))   ; display text for instant UI feedback
        ext  (r/atom @st)           ; snapshot of st — detects external changes
        dbfn (debounce changefn)]   ; changefn called once per edit, after debounce
    (fn [_typ props st valfn _changefn]
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
  [{:keys [name tooltip type item-type options placeholder default unit] :or {type :input}} cursor on-change extra-el]
  (let [k (keyword name)
        ph (or placeholder "")
        valfn #(or (get % k) default "")
        numvalfn #(let [v (get % k default)] (if (some? v) (str v) ""))
        wrap (fn [f] (fn [st v] (f st v) (when on-change (on-change))))
        coerce-csv (case item-type
                     :number  #(let [n (js/parseFloat %)] (if (js/isNaN n) % n))
                     :integer #(let [n (js/parseInt % 10)] (if (js/isNaN n) % n))
                     str)
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
                           #(clojure.string/join ", " (get % k []))
                           (wrap #(swap! %1 assoc k (mapv coerce-csv (clojure.string/split %2 #"[, ]+"))))]
                :number   [dbfield :input {:id name :type "number" :step "any" :placeholder ph} cursor
                           numvalfn
                           (wrap #(let [n (js/parseFloat %2)]
                                    (swap! %1 assoc k (if (js/isNaN n) %2 n))))]
                :integer  [dbfield :input {:id name :type "number" :step "1" :placeholder ph} cursor
                           numvalfn
                           (wrap #(let [n (js/parseInt %2 10)]
                                    (swap! %1 assoc k (if (js/isNaN n) %2 n))))]
                ;; default: text input
                          [dbfield :input {:id name :type "text" :placeholder ph} cursor
                           valfn (wrap #(swap! %1 assoc k %2))])]
    [:<>
     [:label {:for name :title tooltip} name]
     (if extra-el
       [:div.field-with-extra input extra-el]
       (if unit
         [:div.field-with-unit input [:span.unit-suffix unit]]
         input))]))

;; @tags property editing
(defn- tagged-enum-editor
  "Render a tagged union: a select for the discriminant, then the matching case's children.
   The union's cases live under `:cases` — distinct from a prop's `:variants`
   key, which scopes visibility to a port variant in `visible-props`."
  [{:keys [tag-key cases tooltip]} enum-cursor on-change wrap-leaf path]
  (let [tag-val (get @enum-cursor (keyword tag-key))
        active-case (first (filter #(= (:value %) tag-val) cases))]
    [:<>
     [:label {:title tooltip} tag-key]
     [:select {:value (or tag-val "")
               :on-change #(let [v (.. % -target -value)
                                  selected (first (filter (fn [vr] (= (:value vr) v)) cases))
                                  defaults (into {} (map (fn [{:keys [name default]}]
                                                           [(keyword name) (if (some? default) default "")]))
                                                   (:children selected))]
                             (reset! enum-cursor (if (seq v) (merge {(keyword tag-key) v} defaults) {}))
                             (when on-change (on-change)))}
      [:option {:value ""} "None"]
      (for [{:keys [value label]} cases]
        [:option {:key value :value value} label])]
     (when active-case
       [recursive-editor (:children active-case) enum-cursor on-change wrap-leaf path])]))

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

;; @tags property editing
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
     (for [{:keys [name children type] :as field} fields]
       (let [new-path (conj path (keyword name))]
         [:<> {:key name}
          (cond
            (= type :tagged-enum)
            [tagged-enum-editor field (r/cursor cursor [(keyword name)]) on-change wrap-leaf new-path]
            children
            [list-editor field (r/cursor cursor [(keyword name)]) on-change wrap-leaf new-path]
            :else
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

;; @tags editor flip transform composition, photonic model ports
(defn transform
  "Convert a six-number wire transform into a composable DOMMatrix."
  [matrix]
  (js/DOMMatrixReadOnly. (clj->js matrix)))
(defn transform-vec [obj]
  [(.-a obj) (.-b obj) (.-c obj) (.-d obj) (.-e obj) (.-f obj)])
(defn point [x y] (.fromPoint js/DOMPointReadOnly (clj->js {:x x :y y})))

;; @tags editor flip transform composition
(defn flip-x
  "World-frame horizontal flip: pre-multiply `m` by the vertical-axis reflection
   diag(-1, 1) so the mirror is applied about the screen's vertical axis
   regardless of `m`'s current rotation. `.multiply` post-multiplies, so the
   reflection is the receiver and `m` the argument. Takes and returns a
   DOMMatrix, for use with `transform-selected`."
  [m]
  (.multiply (js/DOMMatrixReadOnly. #js [-1 0 0 1 0 0]) m))

;; @tags editor flip transform composition
(defn flip-y
  "World-frame vertical flip: pre-multiply `m` by the horizontal-axis reflection
   diag(1, -1). See `flip-x`."
  [m]
  (.multiply (js/DOMMatrixReadOnly. #js [1 0 0 -1 0 0]) m))

;; @tags editor flip transform composition
(defn rotate-cw
  "World-frame 90° clockwise rotation: pre-multiply `m` by rotate(90) so the
   on-screen direction is consistent regardless of `m`'s current mirror. A
   local-frame (post-multiply) rotation reverses under a reflection — the flip
   plus rotate bug — which this avoids. Takes and returns a DOMMatrix, for use
   with `transform-selected`."
  [m]
  (.multiply (.rotate (js/DOMMatrixReadOnly.) 90) m))

;; @tags editor flip transform composition
(defn rotate-ccw
  "World-frame 90° counter-clockwise rotation: pre-multiply by rotate(-90).
   See `rotate-cw`."
  [m]
  (.multiply (.rotate (js/DOMMatrixReadOnly.) -90) m))

;; Top-level values and extensions that touch browser APIs at load time.
;; Both :web and :vscode run in a DOM context, so we fall through to the
;; implicit :cljs branch for them. :test runs under Node and gets inert
;; stubs. `do`-wrapping is required because `#?@` splicing doesn't work at
;; file top level. :test must come before :cljs because both features are
;; active in the test build and first match wins.
#?(:test
   (do
     (def I nil)
     (def IV [1 0 0 1 0 0])
     (def url-params nil)
     (def current-workspace nil))
   :cljs
   (do
     (def I (js/DOMMatrixReadOnly.))
     (def IV (transform-vec I))
     (def url-params (js/URLSearchParams. js/window.location.search))
     (def current-workspace (.get url-params "ws"))
     (extend-type js/DOMMatrixReadOnly
       IPrintWithWriter
       (-pr-writer [obj writer _opts]
         (write-all writer
                    "#transform ["
                    (.-a obj) " " (.-b obj) " " (.-c obj) " "
                    (.-d obj) " " (.-e obj) " " (.-f obj)
                    "]")))))

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

;; @tags editor flip transform composition
(defn transform-direction
  "Determine cardinal direction from transformation matrix.
  Returns one of: :left, :right, :top, :bottom based on which screen axis the
  local +x axis (1,0) lands on after the transform. The transformed tip is
  (a·1 + c·0 + e, b·1 + d·0 + f) = (a+e, b+f), computed directly from the
  six-element vector so the function is pure Clojure and runs under the Node
  test shim without a DOMMatrix/DOMPoint. `:left`/`:right`/`:top`/`:bottom` are
  the side of the parent box a subcircuit port attaches to one level up."
  [{tfm :transform}]
  (let [[a b _ _ e f] (or tfm IV)
        x (+ a e)
        y (+ b f)
        abs-x (abs x)
        abs-y (abs y)]
    (if (> abs-x abs-y)
      (if (< x 0) :right :left)
      (if (< y 0) :bottom :top))))


;; `number?` on its own admits ##NaN and ±##Inf, which JSON can't
;; represent and Pydantic rejects as "not a number." Schematic
;; coordinates are always finite — enforce that in the spec so generated
;; samples are JSON-safe.
(s/def ::finite-number (s/and number? #(js/Number.isFinite %)))
(s/def ::x ::finite-number)
(s/def ::y ::finite-number)
;; Wire endpoints are relative to (x, y).
(s/def ::rx ::finite-number)
(s/def ::ry ::finite-number)
(s/def ::name string?)
(s/def ::transform (s/coll-of ::finite-number :count 6))
(s/def ::model (s/nilable string?))

;; Device-level optional fields that Pydantic also models. CouchDB
;; envelope fields (_id, _rev, _deleted) are deliberately NOT specced —
;; s/keys is open, so extra keys pass through, and these are transport
;; concerns the in-memory schematic shouldn't care about.
(s/def ::template (s/nilable string?))

; Device type specs
(def device-types #{"pmos" "nmos" "npn" "pnp" "resistor" "capacitor"
                    "inductor" "vsource" "isource" "diode" "led" "photodiode" "modulator"
                    "ckt" "amp" "die"
                    "straight" "bend" "sbend" "taper" "transition"
                    "terminator" "crossing" "ring-single" "ring-double" "spiral"
                    "splitter-1x2" "coupler" "coupler-ring"
                    "mmi-1x2" "mmi-2x2" "mzi-1x2" "mzi-2x2"
                    "grating-coupler"})

(def schematic-only-types #{"wire" "port" "text" "polyline" "group"})

(s/def ::type (clojure.set/union device-types schematic-only-types))

(s/def ::variant #{"hv" "vh" "d" "ground" "supply" "text" "emitter" "detector"})
;; Port-name keys are keywords in the in-memory representation (round-trip
;; through json->clj keywordizes object keys); net-name values are strings.
(s/def ::nets (s/map-of keyword? string?))
(s/def ::net (s/nilable string?))

(defmulti device-spec :type)
;; Components require a :name — the schematic renders by name and the
;; SPICE netlist keys off it. commit-staged (editor.cljc) assigns one
;; before validating, so pre-commit state is never unnamed.
;;
;; Wires deliberately keep :name as :opt-un. split-wire (editor.cljc)
;; creates fresh gensym-keyed wire segments during merge; those don't
;; get a human-readable name because wires display by their computed
;; net, not by :name. The stored :name on wires is CouchDB housekeeping.
(defmethod device-spec "wire" [_]
  (s/keys :req-un [::rx ::ry ::type ::x ::y]
          :opt-un [::name ::variant ::net]))
(defmethod device-spec :default [_]
  (s/keys :req-un [::type ::x ::y ::name]
          :opt-un [::transform ::model ::nets ::template]))
;; Retag uses the unqualified :type so generation round-trips through
;; device-spec's dispatch (which reads :type, not ::type).
(s/def ::device (s/multi-spec device-spec :type))
(s/def ::schematic (s/map-of string? ::device))

; Model specs for modeldb
(s/def ::tags (s/coll-of string? :kind vector?))
(s/def ::side #{"top" "bottom" "left" "right"})
;; Port "type" field (electric vs photonic). The spec keyword is qualified
;; under :port/ rather than :nyancad.mosaic.common/ so its NAME ("type")
;; can be used as an unqualified map-key validator in ::port-entry without
;; clashing with ::type (the device-type enum) in the same spec registry.
;; s/keys :req-un/:opt-un uses the spec keyword's name portion as the map
;; key, so :port/type validates the :type field of port entries while
;; ::type independently validates the :type field of devices.
(s/def :port/type #{"electric" "photonic"})
(s/def ::aliases (s/coll-of string? :kind vector?))
(s/def ::port-entry (s/keys :req-un [::name ::side]
                             :opt-un [:port/type ::aliases]))
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
(s/def ::variants (s/coll-of ::variant :kind set?))
(s/def ::unit string?)
(s/def ::default any?)
(s/def ::parameter (s/keys :req-un [::name]
                           :opt-un [::tooltip ::variants ::unit ::default]))
(s/def ::props (s/coll-of ::parameter :kind vector?))

(defn visible-props
  "Filter a props vector to those visible for the given port `variant`.

   A parameter with no `:variants` key is always visible; a parameter with a
   `:variants` set is visible only when `variant` is a member. Preserves order."
  [props variant]
  (filterv (fn [p]
             (or (not (:variants p))
                 (contains? (:variants p) variant)))
           props))

(s/def ::symbol (s/nilable string?))
(s/def ::artwork-transform ::transform)
(s/def ::model-def (s/keys :req-un [::name]
                           :opt-un [::type ::tags ::ports ::models ::props ::symbol
                                    ::artwork-transform]))
(s/def ::modeldb (s/map-of string? ::model-def))

;; @tags factory card dragging
(defn model-prop-defaults
  [model-def]
  (when model-def
    (not-empty
      (into {} (for [p (:props model-def) :when (and (:name p) (some? (:default p)))]
                 [(keyword (:name p)) (:default p)])))))

;; @tags property editing
(defn schema->field-type
  "Convert a nyanlib prop (carrying optional :schema from JSON Schema) into a
   field descriptor suitable for recursive-editor / leaf-editor."
  [{:keys [name tooltip schema default] :as prop}]
  (let [base {:name name :tooltip (or tooltip "") :default default}]
    (cond
      (nil? schema) (merge base (dissoc prop :schema))

      (:enum schema)
      (assoc base :type :select
                  :options (mapv #(hash-map :value (str %) :label (str %)) (:enum schema)))

      (= "boolean" (:type schema))
      (assoc base :type :checkbox)

      (= "integer" (:type schema))
      (assoc base :type :integer)

      (= "number" (:type schema))
      (assoc base :type :number)

      (:prefixItems schema)
      (let [types (map #(-> {:name "_" :schema %} schema->field-type :type) (:prefixItems schema))
            item-type (when (apply = types) (first types))]
        (assoc base :type :csv :item-type item-type))

      (and (= "array" (:type schema)) (:items schema))
      (let [item-field (schema->field-type {:name "_" :schema (:items schema)})]
        (assoc base :type :csv :item-type (:type item-field)))

      (:anyOf schema)
      (let [non-null (remove #(= "null" (:type %)) (:anyOf schema))]
        (if (= 1 (count non-null))
          (schema->field-type (assoc prop :schema (first non-null)))
          (merge base (dissoc prop :schema))))

      :else (merge base (dissoc prop :schema)))))

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

;; @tags mosaic port exposure, photonic model ports
(defn ascii-patern
  ([pattern] (ascii-patern "electric" pattern))
  ([port-type pattern]
   (let [last-x (dec (apply max 0 (map count pattern)))
         last-y (dec (count pattern))]
     (for [[y s] (map-indexed vector pattern)
           [x c] (map-indexed vector s)
           :when (not= c " ")]
       {:name (str c)
        :x x
        :y y
        :type port-type
        :side (cond
                (= y 0) :top
                (= y last-y) :bottom
                (= x 0) :left
                (= x last-x) :right
                :else :right)}))))

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

(def ^:private shape-opts
  {"amp"         {:align {:top :start :bottom :start} :min-dims [1 2]}
   "ring-single" {:align {:left :end :right :end}     :min-dims [1 2]}
   "sbend"       {:align {:left :start :right :end}   :min-dims [1 2]}
   "mmi-2x2"     {:min-dims [1 3]}
   "mzi-2x2"     {:min-dims [1 3]}})

(defn port-perimeter
  "Calculate device perimeter [width height] based on ports.
   Ports are [{:name :side :type}]. Optional type string looks up min-dims in shape-opts."
  ([ports] (port-perimeter ports nil))
  ([ports type]
   (let [{:keys [min-dims]} (get shape-opts type)
         by-side (group-ports-by-side ports)
         left-n (count (:left by-side))
         right-n (count (:right by-side))
         top-n (count (:top by-side))
         bottom-n (count (:bottom by-side))
         raw-height (max 1 left-n right-n)
         raw-width (max 1 top-n bottom-n)
         height (if (and (not= (odd? left-n) (odd? right-n))
                         (even? raw-height))
                  (inc raw-height)
                  raw-height)
         base-width (if (and (not= (odd? top-n) (odd? bottom-n))
                             (even? raw-width))
                      (inc raw-width)
                      raw-width)
         [min-w min-h] (or min-dims [0 0])]
     [(max base-width min-w) (max height min-h)])))

(defn spread-ports
  "Spread n ports in size slots. Gap in middle if n < size.
   Optional align: :start packs toward position 1, :end packs toward position size."
  [n size & [align]]
  (cond
    (= n 0) []
    (<= size n) (vec (range 1 (inc n)))
    (= align :start) (vec (range 1 (inc n)))
    (= align :end) (vec (range (- size n -1) (inc size)))
    :else
    (let [mid (quot (inc size) 2)
          half (quot n 2)
          first-half (range 1 (inc half))
          second-half (range (- size half -1) (inc size))]
      (vec (if (odd? n)
             (concat first-half [mid] second-half)
             (concat first-half second-half))))))

(defn port-locations
  "Calculate port positions from [{:name :side :type}].
   Returns flat list of port maps with :x :y added.
   Optional type string looks up per-side alignment in shape-opts."
  ([ports] (port-locations ports nil))
  ([ports type]
   (let [{:keys [align]} (get shape-opts type)
         [width height] (port-perimeter ports type)
         by-side (group-ports-by-side ports)
         left (or (:left by-side) [])
         right (or (:right by-side) [])
         top (or (:top by-side) [])
         bottom (or (:bottom by-side) [])
         left-ys (spread-ports (count left) height (get align :left))
         right-ys (spread-ports (count right) height (get align :right))
         top-xs (spread-ports (count top) width (get align :top))
         bottom-xs (spread-ports (count bottom) width (get align :bottom))
         left-locs (map-indexed (fn [i p] (assoc p :side :left :x 0 :y (nth left-ys i))) left)
         right-locs (map-indexed (fn [i p] (assoc p :side :right :x (inc width) :y (nth right-ys i))) right)
         top-locs (map-indexed (fn [i p] (assoc p :side :top :x (nth top-xs i) :y 0)) top)
         bottom-locs (map-indexed (fn [i p] (assoc p :side :bottom :x (nth bottom-xs i) :y (inc height))) bottom)]
     (concat top-locs bottom-locs left-locs right-locs))))

;; @tags factory metadata annotations, photonic model ports
(defn port-display-name
  "Return the first non-empty display alias without changing connectivity name."
  [{:keys [name aliases]}]
  (or (first (filter #(and (string? %) (not-empty %)) aliases)) name))

; icons
(def zoom-in (r/adapt-react-class icons/ZoomIn))
(def zoom-out (r/adapt-react-class icons/ZoomOut))
(def zoom-fit (r/adapt-react-class icons/ArrowsFullscreen))
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
(def text-annotation-icon (r/adapt-react-class icons/TextLeft))
(def edit (r/adapt-react-class icons/PencilSquare))
(def help (r/adapt-react-class icons/QuestionCircle))
(def external-link (r/adapt-react-class icons/BoxArrowUpRight))
(def expose-ports (r/adapt-react-class icons/NodePlus))
;; @tags mosaic make array
(def grid3x3 (r/adapt-react-class icons/Grid3x3))
(def simulate (r/adapt-react-class icons/PlayCircle))
(def amp-icon (r/adapt-react-class icons/CaretRight))
(def photonic-icon (r/adapt-react-class icons/Lightbulb))
(def graph-icon (r/adapt-react-class icons/GraphUp))
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
(def wand-icon (r/adapt-react-class icons/Robot))
(def gear (r/adapt-react-class icons/Gear))

(defn layout-icon
  "Layout glyph: a device cell with a metal route (metal 1 in from top → via at
  the bend → metal 2 out to the right) overshooting the cell, the via cut clean
  through. Custom inline SVG, drawn in currentColor. Used on the VS Code
  switch-to-Livewire button. viewBox is tightened to the artwork so the
  cell + overhang fill the icon box."
  []
  [:svg {:xmlns "http://www.w3.org/2000/svg" :width "1em" :height "1em"
         :viewBox "4 2 18 18" :fill "currentColor"}
   [:path {:d "M4 4 H20 V20 H4 Z M8.5 8.5 H12.5 V12.5 H8.5 Z"
           :fill-opacity "0.32" :fill-rule "evenodd"}]
   [:rect {:x 8.5 :y 2 :width 4 :height 5.5}]
   [:rect {:x 13.5 :y 8.5 :width 8.5 :height 4}]
   [:path {:d "M7 7 H14 V14 H7 Z M8.5 8.5 H12.5 V12.5 H8.5 Z" :fill-rule "evenodd"}]])

;; @tags mosaic radio groups
(defn radiobuttons
  "Render rows as one named radio group. Each row is [label value tooltip]."
  ([group-name cursor m] (radiobuttons group-name cursor m nil nil nil))
  ([group-name cursor m dblclk ctxclk]
   (radiobuttons group-name cursor m dblclk ctxclk nil))
  ([group-name cursor m dblclk ctxclk on-change]
   [:<>
    (doall (for [[label name disp] m]
             [:<> {:key name}
              [:input {:type "radio"
                       :id name
                       :name group-name
                       :value name
                       :aria-label disp
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

;; @tags mosaic model picker
(defn model-search-rank
  "Relevance rank of query against a display name, smaller is better:
   0 exact name (case-insensitive), 1 name prefix, 2 word-boundary match
   (the query starts right after a `_` or `-`), 3 plain substring, nil no
   match. An empty query ranks 0 — everything matches. cm/filter-models
   ranks search results by [rank, name length, name] — see
   specs/mosaic_model_picker.md."
  [name query]
  (let [lname (clojure.string/lower-case name)
        lquery (clojure.string/lower-case query)
        boundary? #{\_ \-}]
    (cond
      (empty? lquery) 0
      (= lname lquery) 0
      (clojure.string/starts-with? lname lquery) 1
      (loop [i (clojure.string/index-of lname lquery)]
        (when (some? i)
          (if (and (pos? i) (boundary? (nth lname (dec i))))
            true
            (recur (clojure.string/index-of lname lquery (inc i))))))
      2
      (clojure.string/includes? lname lquery) 3
      :else nil)))

;; @tags mosaic model picker
(defn private-cell?
  "True iff the entry's display name starts with `_` — the gdsfactory
   private-cell convention. The name is read like cm/filter-models reads
   it (`:name`, falling back to the id), str-guarded against a nil name.
   A display-only predicate: pickers may hide these entries from their
   lists; resolution never filters on it (#1139, #1182, #1059)."
  [[model-id model]]
  (clojure.string/starts-with? (str (get model :name model-id)) "_"))

;; @tags mosaic model picker
(defn filter-models
  "Filter models by active tags and search text.
   sel-tags: vector of active tags. Plain tags match via set containment on :tags.
   Property tags like \"type:ckt\" match the corresponding model field.
   With search text, matches are ranked best-first: exact name, prefix,
   word-boundary, substring — ties on shorter name, then alphabetically.
   Without search text, iteration order is preserved."
  [models sel-tags search-text]
  (let [plain-tags (set (remove parse-prop-tag sel-tags))
        prop-tags (keep parse-prop-tag sel-tags)
        name-fn (fn [[model-id model]] (get model :name model-id))
        matches (filter (fn [entry]
                          (let [model (second entry)
                                model-tags (set (or (:tags model) []))
                                tags-match (every? model-tags plain-tags)
                                props-match (every? (fn [[k v]]
                                                      (= (str (get model (keyword k))) v))
                                                    prop-tags)]
                            (and tags-match props-match)))
                        models)]
    (if (empty? search-text)
      matches
      (->> matches
           (keep (fn [entry]
                   (let [model-name (name-fn entry)]
                     (when-let [rank (model-search-rank model-name search-text)]
                       [[rank
                         (count model-name)
                         (clojure.string/lower-case model-name)]
                        entry]))))
           (sort-by first)
           (map second)))))

;; @tags mosaic model picker
(defn add-tag!
  "Add tag to selected-atom unless it is already selected.
   The tag-badge click handler, shared between libman's model list and the
   editor's model picker."
  [selected-atom tag]
  (swap! selected-atom (fn [v] (if (some #{tag} v) v (conj v tag)))))

;; @tags mosaic model picker
(defn selected-tag-badges
  "Render every tag in selected-atom as a chip; clicking one removes it.
   Shared between libman's header and the editor's model picker. This is the
   only affordance that reaches all selected tags: the tag tree covers just
   the first two tags of the models it was built from, and row badges exist
   only while rows are visible, so a tag can otherwise filter the list
   invisibly with no way to clear it. Callers own the empty-selection
   fallback."
  [selected-atom]
  [:<>
   (doall (for [tag @selected-atom]
            [:span.tag-badge.active
             {:key tag
              :class (when (parse-prop-tag tag) "prop-tag")
              :on-click #(swap! selected-atom (fn [v] (vec (remove #{tag} v))))}
             tag " ×"]))])

;; @tags mosaic model picker
(defn model-row-content
  "Shared row content for model lists — the libman radio rows and the
   editor's model picker rows: schematic/code icon, display name, and tag
   badges (the type badge plus tags beyond the first two, which form the
   tag tree). on-tag-click, when given, makes badges clickable filters."
  [model model-id on-tag-click]
  (let [schem? (not (has-code-models? model))
        icon (if schem? schemmodel codemodel)
        type-badge (str "type:" (:type model "ckt"))
        extra-tags (drop 2 (:tags model))
        all-badges (cons type-badge extra-tags)]
    [:span [icon] " " (get model :name model-id)
     (for [tag all-badges]
       [:span.tag-badge {:key tag
                         :class [(when (parse-prop-tag tag) "prop-tag")
                                 (when on-tag-click "clickable")]
                         :on-click (when on-tag-click
                                     #(do (.stopPropagation %)
                                          (on-tag-click tag)))}
        tag])]))

;; @tags mosaic model picker, mosaic radio groups, terminology
(defn model-list
  "Render a list of models as radio buttons.
   group-name: unique radio-group name
   selected-atom: atom holding selected model id
   models: seq of [model-id model] pairs
   on-dblclick: optional (fn [model-id] -> event-handler) for double-click
   on-contextmenu: optional (fn [model-id] -> event-handler) for right-click
   on-tag-click: optional (fn [tag] -> nil) called when a tag badge is clicked"
  ([group-name selected-atom models]
   (model-list group-name selected-atom models nil nil nil))
  ([group-name selected-atom models on-dblclick on-contextmenu]
   (model-list group-name selected-atom models on-dblclick on-contextmenu nil))
  ([group-name selected-atom models on-dblclick on-contextmenu on-tag-click]
   (if (seq models)
     [radiobuttons group-name selected-atom
      (doall (for [[model-id model] models]
               [[model-row-content model model-id on-tag-click]
                model-id
                (get model :name model-id)]))
      on-dblclick
      on-contextmenu]
      [:div.empty "No components found"])))

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

;; @tags editor keyboard shortcuts
(defn keyboard-shortcuts [shortcuts e]
  (when-not (or ;; it's a repeat event
             (.-repeat e)
             ;; the user is typing, ignore
             (= "INPUT" (.. e -target -tagName))
             (= "TEXTAREA" (.. e -target -tagName)))
    (let [keys (keyset e)]
      (println keys)
      (when-let [handler (get shortcuts keys)]
        (.preventDefault e)
        (handler)))))

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

;; @tags gsch symbol sidecar
(defn b64encode
  "Base64-encode a string, UTF-8-safe. Plain js/btoa throws on codepoints > 255."
  [s]
  (-> s
      js/encodeURIComponent
      (clojure.string/replace
       #"%([0-9A-F]{2})"
       #(js/String.fromCharCode (str "0x" (nth % 1))))
      js/btoa))

(defn generate-important-template
  "Build a template string from props marked :important. Returns nil if none."
  [props]
  (let [important (filter :important props)]
    (when (seq important)
      (clojure.string/join "\n"
        (cons "{self.name}"
              (map #(str (:name %) ": {self.props." (:name %) "}")
                   important))))))

;; @tags model identity
(defn model-key
  "Convert a bare model ID to a database key with 'models:' prefix.
   Returns nil if input is nil. Asserts that non-nil input is not already prefixed."
  [bare-id]
  (when bare-id
    (assert (not (clojure.string/starts-with? bare-id "models:"))
            (str "model-key expects bare ID, got prefixed: " bare-id))
    (str "models:" bare-id)))

;; @tags model identity
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

;; User's workspace list (fetched on demand)
(defonce user-workspaces (r/atom []))

(defn get-db-name
  "Get the database name for current context (workspace or personal library)."
  []
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

(def overlay-margin
  "Gap in px kept between a floating overlay and the viewport edge, and between
   the selection toolbar and the geometry it anchors to."
  8)

;; @tags mosaic selection toolbar, property editing
(defn- clamp-to-viewport
  "Position of a `size`-px box placed at `pos`, slid to keep `overlay-margin`
   clear of both ends of a `viewport`-px axis."
  [pos size viewport]
  (max overlay-margin (min pos (- viewport size overlay-margin))))

;; @tags mosaic selection toolbar
(defn union-rect
  "Smallest rect containing every rect in `rects`, each `{:x :y :width :height}`.
   Returns nil for an empty sequence."
  [rects]
  (when (seq rects)
    (let [left (apply min (map :x rects))
          top (apply min (map :y rects))
          right (apply max (map #(+ (:x %) (:width %)) rects))
          bottom (apply max (map #(+ (:y %) (:height %)) rects))]
      {:x left :y top :width (- right left) :height (- bottom top)})))

;; @tags mosaic selection toolbar
(defn inset-rect
  "Shrink `rect` by `d` on every side."
  [{:keys [x y width height]} d]
  {:x (+ x d) :y (+ y d)
   :width (- width (* 2 d)) :height (- height (* 2 d))})

;; @tags mosaic selection toolbar
(defn user->screen
  "Scale `:k` and offsets `:dx`/`:dy` mapping SVG user units to viewport px for
   the viewBox `[vx vy vw vh]` drawn into the canvas box `{:left :top :width
   :height}` under `xMidYMid meet` — the tighter axis sets the scale and the
   slack is split evenly. Derived from state rather than read back off the DOM,
   so it is current in the render that changed the viewBox."
  [[vx vy vw vh] {:keys [left top width height]}]
  (let [k (min (/ width vw) (/ height vh))]
    {:k k
     :dx (+ left (/ (- width (* vw k)) 2) (* (- vx) k))
     :dy (+ top (/ (- height (* vh k)) 2) (* (- vy) k))}))

;; @tags mosaic selection toolbar
(defn rect->screen
  "Map a rect from SVG user units to viewport px through a `user->screen`."
  [{:keys [k dx dy]} {:keys [x y width height]}]
  {:x (+ (* x k) dx) :y (+ (* y k) dy)
   :width (* width k) :height (* height k)})

;; @tags mosaic selection toolbar, property editing
(defn- clamp-context-menu!
  "Slide the popup back inside the viewport so it never runs off an edge. Its
   own max-height keeps it shorter than the viewport, so clamping suffices."
  [el x y]
  (when el
    (let [^js rect (.getBoundingClientRect el)]
      (set! (.. el -style -left)
            (str (clamp-to-viewport x (.-width rect) (.-innerWidth js/window)) "px"))
      (set! (.. el -style -top)
            (str (clamp-to-viewport y (.-height rect) (.-innerHeight js/window)) "px")))))

(defn contextmenu []
  (let [{:keys [:x :y :body]} @context-content]
    ;; Key on [x y] so React remounts the popup on each open, firing :ref to
    ;; re-measure and clamp it into the viewport.
    ^{:key [x y]}
    [:div.contextmenu.window
     {:class (if body "visible" "hidden")
      :style {:top y :left x}
      :ref (fn [el] (clamp-context-menu! el x y))}
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

;; @tags validator semantics
(defn alert [text]
  (reset! modal-content
          [:div [:p text]
           [:button {:on-click #(reset! modal-content nil)} "Ok"]])
  nil)

(defn set-context-menu [x y body]
  (reset! context-content {:x x :y y :body body}))

(defn clear-context-menu []
  (swap! context-content assoc :body nil))

;; @tags editor keyboard shortcuts
(defn clear-modal []
  (reset! modal-content nil))

;; Onboarding popup for first-time users
;; @tags mosaic device tray
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
               "port-label" (rc/inline "icons/port-label.svg")
               "ground" (rc/inline "icons/ground.svg")
               "supply" (rc/inline "icons/supply.svg")
               "emitter" (rc/inline "icons/emitter.svg")
               "detector" (rc/inline "icons/detector.svg")
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
               "wave" (rc/inline "icons/wave.svg")
               "bolt" (rc/inline "icons/bolt.svg")
               "")]
    [:span.device-icon {:dangerouslySetInnerHTML (r/unsafe-html icon)}]))

;; @tags editor keyboard shortcuts
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

;; @tags editor keyboard shortcuts, mosaic device tray, component simulation
(defn onboarding-popup []
  [:div.onboarding
   [:button.close-btn
    {:on-click #(reset! modal-content nil)
     :aria-label "Close"}
    "\u00D7"]
   [:h2 "GF+ Schematic Editor"]

   ;; Components section - full width
   [:div.shortcut-category.components-section
    [:h3 "Components"]
    [:div.components-row
     [:div.component-item
      [device-icon "port"]
      [:kbd "P"]
      [:span.label "Port"]]
     [:div.component-item
      [device-icon "ground"]
      [:kbd "G"]
      [:span.label "Ground"]]
     [:div.component-item
      [device-icon "supply"]
      [:kbd "Shift+P"]
      [:span.label "Supply"]]
     [:div.component-item
      [device-icon "vsource"]
      [:kbd "V"]
      [:span.label "Vsource"]]
     [:div.component-item
      [device-icon "isource"]
      [:kbd "I"]
      [:span.label "Isource"]]
     [:div.component-item
      [device-icon "port-label"]
      [:kbd "Shift+T"]
      [:span.label "Port label"]]
     [:div.component-item
      [text-annotation-icon]
      [:kbd "T"]
      [:span.label "Text annotation"]]]
    [:p.hint "More components available in the device tray."]]

   ;; Two column layout for tools
   [:div.shortcut-grid
    [shortcut-table "Drawing"
     [[[cursor] "Esc" "Select"]
      [[wire] "W" "Wire"]
      [[eraser] "E" "Eraser"]
      [[move] "Space" "Pan (hold)"]
      [[rotatecw] "R" "Rotate CW"]
      [[rotateccw] "Shift+R" "Rotate CCW"]
      [[mirror-vertical] "X" "Flip Horizontally"]
      [[mirror-horizontal] "Y" "Flip Vertically"]
      [[delete] "Del / Backspace / D" "Delete"]
      [[zoom-in] "Scroll" "Zoom"]
      [[zoom-fit] "Home" "Zoom to fit"]]]

    [shortcut-table "Actions"
     [[[undoi] (str mod-key "+Z") "Undo"]
      [[redoi] (str mod-key "+Shift+Z") "Redo"]
      [[copyi] (str mod-key "+C") "Copy"]
      [[cuti] (str mod-key "+X") "Cut"]
      [[pastei] (str mod-key "+V") "Paste"]
      [[graph-icon] "" "Simulate"]
      [[layout-icon] "" "Open in Livewire"]
      [[help] "" "Help"]]]]

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
