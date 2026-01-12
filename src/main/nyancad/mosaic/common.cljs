; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.common
  (:require [reagent.core :as r]
            [react-bootstrap-icons :as icons]
            [clojure.spec.alpha :as s]
            reagent.ratom
            nyancad.hipflask
            clojure.edn
            clojure.set
            clojure.string
            [clojure.pprint :refer [pprint]]
            [clojure.zip :as zip]
            goog.functions
            [shadow.resource :as rc]))

; allow taking a cursor of a pouch atom
(extend-type ^js nyancad.hipflask/PAtom reagent.ratom/IReactiveAtom)

(def grid-size 50)
(def debounce #(goog.functions/debounce % 1000))

(defn dbfield [typ props st valfn changefn]
  (let [int (r/atom @st)
        ext (r/atom @st)
        dbfn (debounce changefn)]
    (fn [typ props st valfn changefn]
      (when (not= @ext @st)
        (reset! int @st)
        (reset! ext @st))
      [typ (assoc props
                  :value (valfn @int)
                  :on-change (fn [e]
                               (let [val (.. e -target -value)]
                                 (dbfn st val)
                                 (changefn int val))))])))

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
                    "inductor" "vsource" "isource" "diode" "ckt"})

(def schematic-only-types #{"wire" "port" "text"})

(s/def ::type (clojure.set/union device-types schematic-only-types))

(defmulti device-spec :type)
(defmethod device-spec "wire" [_]
  (s/keys :req-un [::rx ::ry ::type ::x ::y]))
(defmethod device-spec :default [_]
  (s/keys :req-un [::type ::transform ::x ::y]
          :opt-un [::model]))
(s/def ::device (s/multi-spec device-spec ::type))
(s/def ::schematic (s/map-of string? ::device))

; Model specs for modeldb
(s/def ::category (s/coll-of string? :kind vector?))
(s/def ::port-list (s/coll-of string? :kind vector?))
(s/def ::ports (s/keys :opt-un [::top ::bottom ::left ::right]))
(s/def ::top ::port-list)
(s/def ::bottom ::port-list)
(s/def ::left ::port-list)
(s/def ::right ::port-list)
(s/def ::code string?)
(s/def ::use-x boolean?)
(s/def ::template (s/keys :opt-un [::name ::code ::use-x]))
(s/def ::template-list (s/coll-of ::template :kind vector?))
(s/def ::templates (s/map-of keyword? ::template-list))

; Parameter specs for unified props system
(s/def ::tooltip string?)
(s/def ::parameter (s/keys :req-un [::name]
                           :opt-un [::tooltip]))
(s/def ::props (s/coll-of ::parameter :kind vector?))

(s/def ::model-def (s/keys :req-un [::name]
                           :opt-un [::type ::category ::ports ::templates ::props]))
(s/def ::modeldb (s/map-of string? ::model-def))

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

(defn ascii-patern [pattern]
  (for [[y s] (map-indexed vector pattern)
        [x c] (map-indexed vector s)
        :when (not= c " ")]
    [x y c]))

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

(defn pattern-size [pattern]
  (let [size (inc (apply max (mapcat (partial take 2) pattern)))]
    (if (js/isFinite size)
      size
      1)))

(defn port-perimeter [ports]
  (let [height (max 1 (count (:left ports)) (count (:right ports)))
        width (max 1 (count (:top ports)) (count (:bottom ports)))]
    [width height]))

(defn port-locations [ports]
  (let [[width height] (port-perimeter ports)
        top (:top ports)
        bottom (:bottom ports)
        left (:left ports)
        right (:right ports)
        top-locs (map-indexed (fn [i n] [(inc i) 0 n]) top)
        bottom-locs (map-indexed (fn [i n] [(inc i) (inc height) n]) bottom)
        left-locs (map-indexed (fn [i n] [0 (inc i) n]) left)
        right-locs (map-indexed (fn [i n] [(inc width) (inc i) n]) right)]
    [top-locs bottom-locs left-locs right-locs]))

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
        (conj-when :os (.-metaKey e))
        )))

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
      clojure.string/trim
      (clojure.string/replace " " "\u00a0")))

(defn format [s state]
  (-> s
      (clojure.string/replace
       #"(?<=[^{]|^)\{([^{}:]+)(?::(?:\.(\d+))?([ef])?)?\}(?=[^}]|$)"
       (fn [[_ code precision type]]
         (try
           (let [key-names (map name (keys state))
                 values (map clj->js (vals state))
                 func (apply js/Function (concat key-names [(str "return (" code ")")]))
                 result (nyancad.hipflask/json->clj (apply func values))
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

(defn str-to-hex [s]
  (apply str (map #(str (.toString (.charCodeAt % 0) 16)) s)))

(defn get-sync-url []
  (when-let [username (get-current-user)]
    (str couchdb-url "userdb-" (str-to-hex username))))

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
      [label]
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
      [[save] "" "Save Snapshot"]
      [[external-link] "" "Pop Out Notebook"]
      [[help] "" "Keyboard Shortcuts"]
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
