# SPDX-FileCopyrightText: 2022 Pepijn De Vos <pepijndevos@gmail.com>
# SPDX-FileCopyrightText: 2022 NyanCAD Mosaic
#
# SPDX-License-Identifier: GPL-3.0-only

(ns nyancad.mosaic.common
  (:require [reagent.core :as r]
            [react-bootstrap-icons :as icons]
            [clojure.spec.alpha :as s]
            reagent.ratom
            nyancad.hipflask
            clojure.edn
            clojure.set
            clojure.string
            [clojure.zip :as zip]
            goog.functions))

; allow taking a cursor of a pouch atom
(extend-type ^js nyancad.hipflask/PAtom reagent.ratom/IReactiveAtom)

(def grid-size 50)
(def debounce #(goog.functions/debounce % 1000))

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
    (println v xy idx val)
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
(s/def ::cell string?)
(s/def ::coord (s/tuple number? number?))
(s/def ::wires (s/and set? (s/coll-of ::coord)))

(defmulti cell-type :cell)
(defmethod cell-type "wire" [_]
  (s/keys :req-un [::rx ::ry ::cell ::x ::y]))
(defmethod cell-type :default [_]
  (s/keys :req-un [::cell ::transform ::x ::y]))
(s/def ::device (s/multi-spec cell-type ::cell))
(s/def ::schematic (s/map-of string? ::device))

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
(def notebook (r/adapt-react-class icons/Book))
(def simulate (r/adapt-react-class icons/Play))
(def library (r/adapt-react-class icons/Collection))
(def probe (r/adapt-react-class icons/Search))

(defn radiobuttons
([cursor m] (radiobuttons cursor m nil))
([cursor m dblclk]
 [:<>
  (doall (for [[label active-label name disp] m]
           [:<> {:key name}
            [:input {:type "radio"
                     :id name
                     :value name
                     :checked (= name @cursor)
                     :on-change #(reset! cursor name)}]
            [:label {:for name
                     :title disp
                     :on-double-click (when dblclk (dblclk name))}
             (if (= name @cursor) active-label label)]]))]))

(defn renamable [cursor]
  (r/with-let [active (r/atom false)
               to (atom nil)
               edit #(reset! active true)]
    (if (or @active (empty? @cursor))
      [:form {:on-submit (fn [^js e]
                           (js/console.log e)
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
      [:span {:on-click #(if (= (.-detail %) 1)
                           (reset! to (js/window.setTimeout edit 1000))
                           (js/window.clearTimeout @to))}
       @cursor])))

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

(def default-sync "https://c6be5bcc-59a8-492d-91fd-59acc17fef02-bluemix.cloudantnosqldb.appdomain.cloud/schematics")
