; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.paste-test
  "Contract-driven tests for the Mosaic clipboard.

   `doc-center` computes the visual center of a single device doc — the
   point that the `device` renderer uses as its transform-origin
   (:x + size/2, :y + size/2). `clipboard-centroid` averages `doc-center`
   over a group and is the anchor `paste` uses so the group's visual
   centroid rides under the cursor.

   Also covers the cancel/keyup guards against the two reported PR bugs:
   - Esc then re-select must not jump the instance to the Esc cursor
     (cancel resets ::delta and cleans orphan ::selected).
   - Releasing Cmd before V during Cmd+V must not spawn a voltage source
     (the placement keyup shortcuts no-op while a drag or paste-staging
     is active).

   Each deftest states what the function SHOULD do, with expected values
   hand-computed from the contract — not copied from CLJS output."
  (:require [cljs.test :refer [deftest is testing]]
            [nyancad.mosaic.editor :as e]
            [nyancad.mosaic.editor.platform-test :as pform]))

;; ---------------------------------------------------------------------------
;; doc-center — electrical builtins (::bg [w h])
;; ---------------------------------------------------------------------------
;;
;; Contract: devices with a 2-element ::bg vector center on their tetris rect,
;; which is drawn one tile in from :x/:y. Center = [:x + 1 + w/2, :y + 1 + h/2].
;; This matches add-device's placement math.

(deftest doc-center-twoport-at-origin
  (testing "a resistor (twoport-bg [1 1]) at (0,0) centers on (1.5, 1.5)"
    (is (= [1.5 1.5] (e/doc-center {:type "resistor" :x 0 :y 0})))))

(deftest doc-center-twoport-offset
  (testing "a resistor at (5,7) centers on (6.5, 8.5)"
    (is (= [6.5 8.5] (e/doc-center {:type "resistor" :x 5 :y 7})))))

(deftest doc-center-mosfet-at-origin
  (testing "an nmos (active-bg [1 1]) at (0,0) centers on (1.5, 1.5)"
    (is (= [1.5 1.5] (e/doc-center {:type "nmos" :x 0 :y 0})))))

;; ---------------------------------------------------------------------------
;; doc-center — wires
;; ---------------------------------------------------------------------------
;;
;; Contract: a wire's segment runs from (:x+0.5, :y+0.5) to (:x+rx+0.5,
;; :y+ry+0.5), so center (midpoint) = [:x + rx/2 + 0.5, :y + ry/2 + 0.5].

(deftest doc-center-wire-midpoint
  (testing "a wire from (2,3) with rx=4, ry=0 centers on (4.5, 3.5)"
    (is (= [4.5 3.5] (e/doc-center {:type "wire" :x 2 :y 3 :rx 4 :ry 0})))))

(deftest doc-center-zero-length-wire
  (testing "a zero-length wire at (0,0) centers on its tile (0.5, 0.5)"
    (is (= [0.5 0.5] (e/doc-center {:type "wire" :x 0 :y 0 :rx 0 :ry 0})))))

;; ---------------------------------------------------------------------------
;; doc-center — ports/text (no ::bg, no :model, has ::conn)
;; ---------------------------------------------------------------------------
;;
;; Contract: port/text/group devices have no 2-element ::bg and no :model.
;; The visual center is the origin tile center = [:x + 0.5, :y + 0.5].

(deftest doc-center-port
  (testing "a default port (::bg []) at (3,4) centers on (3.5, 4.5)"
    (is (= [3.5 4.5] (e/doc-center {:type "port" :x 3 :y 4})))))

(deftest doc-center-text
  (testing "a text element at (2,5) centers on (2.5, 5.5)"
    (is (= [2.5 5.5] (e/doc-center {:type "text" :x 2 :y 5})))))

;; ---------------------------------------------------------------------------
;; doc-center — photonic builtins (no ::bg, no :model, no ::conn)
;; ---------------------------------------------------------------------------
;;
;; Contract: photonic builtins (straight, bend, ring-single, etc.) have no
;; ::bg and no :model. photonic-sym computes size from [1 1] fallback →
;; size 3. Center = [:x + 1.5, :y + 1.5].

(deftest doc-center-photonic-builtin-straight
  (testing "a straight (photonic builtin, no model) at (0,0) centers on (1.5, 1.5)"
    (is (= [1.5 1.5] (e/doc-center {:type "straight" :x 0 :y 0})))))

(deftest doc-center-photonic-builtin-ring-offset
  (testing "a ring-single at (10,20) centers on (11.5, 21.5)"
    (is (= [11.5 21.5] (e/doc-center {:type "ring-single" :x 10 :y 20})))))

;; ---------------------------------------------------------------------------
;; doc-center — subcircuit/photonic with :model
;; ---------------------------------------------------------------------------
;;
;; Contract: devices with a :model read ports from @modeldb via device-dims,
;; computing [w h] from port-perimeter. Center = [:x + 1 + w/2, :y + 1 + h/2]
;; (the tetris rect center, same as electrical builtins). When the model isn't
;; loaded (no ports), falls back to [1 1] → center (1.5, 1.5). These tests use
;; the platform-test stub where @modeldb is empty, so the fallback path is
;; exercised.

(deftest doc-center-subcircuit-no-model-loaded
  (testing "a subcircuit with :model not in @modeldb falls back to [1 1], center (1.5, 1.5)"
    (is (= [1.5 1.5] (e/doc-center {:type "mzi-1x2" :x 0 :y 0 :model "some-uuid"})))))

;; ---------------------------------------------------------------------------
;; device-dims
;; ---------------------------------------------------------------------------
;;
;; Contract: returns [width height] of a device's tetris rect (before the
;; 2-tile padding). Electrical builtins read ::bg from the static models map;
;; devices with a :model read ports from @modeldb via port-perimeter;
;; everything else falls back to [1 1]. In the test stub @modeldb is empty,
;; so the :model branch exercises the fallback.

(deftest device-dims-electrical-builtin
  (testing "a resistor (twoport-bg [1 1]) returns [1 1]"
    (is (= [1 1] (e/device-dims {:type "resistor"})))))

(deftest device-dims-mosfet
  (testing "an nmos (active-bg [1 1]) returns [1 1]"
    (is (= [1 1] (e/device-dims {:type "nmos"})))))

(deftest device-dims-photonic-builtin-no-model
  (testing "a straight (photonic builtin, no model, no ::bg) falls back to [1 1]"
    (is (= [1 1] (e/device-dims {:type "straight"})))))

(deftest device-dims-subcircuit-model-not-loaded
  (testing "a subcircuit with :model not in @modeldb falls back to [1 1]"
    (is (= [1 1] (e/device-dims {:type "mzi-1x2" :model "some-uuid"})))))

(deftest device-dims-port
  (testing "a default port (::bg []) falls back to [1 1]"
    (is (= [1 1] (e/device-dims {:type "port"})))))

;; ---------------------------------------------------------------------------
;; clipboard-centroid
;; ---------------------------------------------------------------------------
;;
;; Contract: mean of doc-center over the docs. Returns [0 0] for empty
;; (paste no-op guard).

(deftest clipboard-centroid-empty-is-origin
  (testing "an empty clipboard returns [0 0] so paste no-ops rather than dividing by zero"
    (is (= [0 0] (e/clipboard-centroid [])))))

(deftest clipboard-centroid-single-doc-is-its-visual-center
  (testing "a single doc's centroid is its doc-center"
    (is (= [1.5 1.5] (e/clipboard-centroid [{:type "resistor" :x 0 :y 0}])))))

(deftest clipboard-centroid-two-docs-is-the-midpoint-of-centers
  (testing "two resistors at (0,0) and (10,20) — centers (1.5,1.5) and (11.5,21.5) — mean (6.5,11.5)"
    (is (= [6.5 11.5] (e/clipboard-centroid [{:type "resistor" :x 0 :y 0}
                                             {:type "resistor" :x 10 :y 20}])))))

(deftest clipboard-centroid-mixed-device-and-wire
  (testing "resistor (0,0) + zero-length wire (2,3) — centers (1.5,1.5) and (2.5,3.5) — mean (2.0,2.5)"
    (is (= [2.0 2.5] (e/clipboard-centroid [{:type "resistor" :x 0 :y 0}
                                            {:type "wire" :x 2 :y 3 :rx 0 :ry 0}])))))

(deftest clipboard-centroid-mixed-electrical-and-photonic
  (testing "resistor (0,0) + straight (4,0) — centers (1.5,1.5) and (5.5,1.5) — mean (3.5,1.5)"
    (is (= [3.5 1.5] (e/clipboard-centroid [{:type "resistor" :x 0 :y 0}
                                            {:type "straight" :x 4 :y 0}])))))

(deftest clipboard-centroid-handles-negative-coordinates
  (testing "resistors at (-3,-6) and (3,6) — centers (-1.5,-4.5) and (4.5,7.5) — mean (1.5,1.5)"
    (is (= [1.5 1.5] (e/clipboard-centroid [{:type "resistor" :x -3 :y -6}
                                            {:type "resistor" :x 3 :y 6}])))))

;; ---------------------------------------------------------------------------
;; cancel — resets ::delta and cleans orphan ::selected after a paste
;; ---------------------------------------------------------------------------
;;
;; Contract: cancel (Escape / tool-switch) resets ::delta to zero and filters
;; ::selected against @schematic, so a cancelled paste can't leak its pointer
;; offset onto the next selection. Without the ::delta reset, a click on any
;; instance after Esc flows straight to drag-end → end-ui, which applies the
;; stale (cursor-at-Esc − centroid) delta to the newly selected instance —
;; the reported "jumps to where Esc was pressed" bug.

(defn- ui-reset []
  (swap! e/ui assoc
         ::e/dragging nil
         ::e/staging nil
         ::e/stretch nil
         ::e/paste-staging nil
         ::e/selected #{}
         ::e/delta {:x 0 :y 0 :rx 0 :ry 0}
         ::e/mouse-start [0 0]
         ::e/mouse [0 0]
         ::e/tool ::e/cursor))

(deftest cancel-resets-delta-after-paste
  (testing "::delta is reset to zero so the stale paste offset can't leak
            onto the next selection (jump-on-reselect)"
    (try
      (reset! pform/schematic {})
      (swap! e/ui assoc
             ::e/dragging ::e/device
             ::e/tool ::e/cursor
             ::e/paste-staging {"R9" {:type "resistor" :x 0 :y 0}}
             ::e/selected #{"R9"}            ; orphan: never inserted
             ::e/delta {:x 5 :y 7 :rx 0 :ry 0}
             ::e/mouse-start [1.5 1.5])
      (e/cancel)
      (is (= {:x 0 :y 0 :rx 0 :ry 0} (::e/delta @e/ui)))
      (is (nil? (::e/dragging @e/ui)))
      (is (nil? (::e/paste-staging @e/ui)))
      (is (= ::e/cursor (::e/tool @e/ui)))
      (finally (ui-reset)))))

(deftest cancel-drops-orphan-selected-after-paste
  (testing "::selected is filtered against @schematic, dropping pasted ids
            that were never inserted"
    (try
      (reset! pform/schematic {})          ; nothing in schematic
      (swap! e/ui assoc
             ::e/dragging ::e/device
             ::e/paste-staging {"R9" {:type "resistor" :x 0 :y 0}}
             ::e/selected #{"R9"}           ; orphan — not in @schematic
             ::e/delta {:x 5 :y 7 :rx 0 :ry 0})
      (e/cancel)
      (is (empty? (::e/selected @e/ui))
          "orphan pasted id dropped because it's absent from @schematic")
      (finally (ui-reset)))))

(deftest cancel-keeps-selected-that-still-exist
  (testing "::selected ids still present in @schematic survive cancel"
    (try
      (reset! pform/schematic {"R1" {:type "resistor" :x 0 :y 0}})
      (swap! e/ui assoc
             ::e/dragging ::e/device
             ::e/paste-staging {"R9" {:type "resistor" :x 0 :y 0}}
             ::e/selected #{"R1" "R9"}      ; R1 real, R9 orphan
             ::e/delta {:x 5 :y 7 :rx 0 :ry 0})
      (e/cancel)
      (is (= #{"R1"} (::e/selected @e/ui))
          "real selection kept, orphan dropped")
      (finally
        (reset! pform/schematic {})
        (ui-reset)))))

(deftest cancel-1-arity-resets-delta-and-cleans-selected
  (testing "tool-switch cancel (e.g. press W) also resets ::delta and
            cleans ::selected against @schematic"
    (try
      (reset! pform/schematic {"R1" {:type "resistor" :x 0 :y 0}})
      (swap! e/ui assoc
             ::e/dragging ::e/device
             ::e/paste-staging {"R9" {:type "resistor" :x 0 :y 0}}
             ::e/selected #{"R1" "R9"}
             ::e/delta {:x 5 :y 7 :rx 0 :ry 0})
      (e/cancel ::e/wire)
      (is (= {:x 0 :y 0 :rx 0 :ry 0} (::e/delta @e/ui)))
      (is (= ::e/wire (::e/tool @e/ui)))
      (is (= #{"R1"} (::e/selected @e/ui)))
      (finally
        (reset! pform/schematic {})
        (ui-reset)))))

;; ---------------------------------------------------------------------------
;; V keyup — vsource shortcut suppressed during drag / paste-staging
;; ---------------------------------------------------------------------------
;;
;; Contract: the device-placement keyup shortcuts (V → voltage source, etc.)
;; no-op while ::dragging or ::paste-staging is active. Without this, a
;; Cmd+V paste (keydown) followed by releasing Cmd before V leaves V's keyup
;; with no modifier, which matches the plain #{:v} binding and spawns a
;; voltage source mid-paste.

(deftest vsource-keyup-suppressed-during-paste-staging
  (testing "the #{:v} keyup shortcut no-ops while ::paste-staging is active"
    (try
      (swap! e/ui assoc ::e/mouse [3 4]
             ::e/dragging nil
             ::e/paste-staging {"R9" {:type "resistor" :x 0 :y 0}}
             ::e/staging nil
             ::e/tool ::e/cursor)
      (let [vsource-fn (get e/shortcuts #{:v})]
        (is (fn? vsource-fn))
        (vsource-fn)                        ; should no-op
        (is (nil? (::e/staging @e/ui))
            "no vsource staged while paste-staging is active")
        (is (= ::e/cursor (::e/tool @e/ui))
            "tool not switched to device"))
      ;; once paste-staging clears, the same keyup places the vsource
      (swap! e/ui assoc ::e/paste-staging nil ::e/staging nil ::e/tool ::e/cursor)
      (let [vsource-fn (get e/shortcuts #{:v})]
        (vsource-fn)
        (is (= "vsource" (:type (::e/staging @e/ui)))
            "vsource staged once no drag/paste is active")
        (is (= ::e/device (::e/tool @e/ui))
            "tool switched to device when placement actually fires"))
      (finally (ui-reset)))))

(deftest vsource-keyup-suppressed-during-drag
  (testing "the #{:v} keyup shortcut also no-ops while ::dragging is active
            (covers non-paste drags, e.g. a move-drag in progress)"
    (try
      (swap! e/ui assoc ::e/mouse [3 4]
             ::e/dragging ::e/device
             ::e/paste-staging nil
             ::e/staging nil
             ::e/tool ::e/cursor)
      (let [vsource-fn (get e/shortcuts #{:v})]
        (vsource-fn)
        (is (nil? (::e/staging @e/ui))
            "no vsource staged while a drag is active")
        (is (= ::e/cursor (::e/tool @e/ui))))
      (finally (ui-reset)))))