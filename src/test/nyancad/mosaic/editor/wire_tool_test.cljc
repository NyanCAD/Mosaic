; SPDX-FileCopyrightText: 2026 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.wire-tool-test
  "Tests for the port-press wire-tool activation (#3529).

   Covers `port-wire-start` (momentary entry + the pointer and tool guards),
   `maybe-restore-tool!` (the one-shot restore), the sites that fire it
   (`add-wire` on-port, `drag-end` non-zero drag, `cancel` 0-arity), and the
   `:on-pointer-down` handler attached to the `circle.port` bulb in `port`.

   Model: one `::prev-tool` slot, shared by every momentary tool (pen-eraser,
   middle-click pan, hold-Space pan, port-press wire). Written on entry, read
   and cleared by `maybe-restore-tool!`, which no-ops on an empty slot — so a
   restore site with no pending entry leaves `::tool` alone instead of writing
   nil, which the `::ui` spec's closed tool set would reject at the validator.

   Sharing one slot means momentary tools do not nest: a pan taken mid-route
   consumes the slot, so the route no longer restores on completion. Pinned
   below as accepted behavior.

   The `circle.nc` handler in `schematic-dots` is gated on `nc?` and reads the
   reactive `point-index` track, so it is covered by manual testing rather than
   a unit test here."
  (:require [cljs.test :refer [deftest is testing]]
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.editor :as e]
            [nyancad.mosaic.editor.platform-test :as pform]))

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
         ::e/tool ::e/cursor
         ::e/prev-tool nil))

(defn- mock-event
  "Minimal JS pointer event for port-wire-start's guards. port-wire-start
   reads (.-isPrimary e) and (.-button e); pen-eraser? reads (.-pointerType
   e) and (.-button e)."
  [& {:keys [is-primary button pointer-type]
      :or {is-primary true button 0 pointer-type "mouse"}}]
  #js {:isPrimary is-primary :button button :pointerType pointer-type})

(defn- mock-up-event
  "Minimal JS pointer event for drag-end: it stop-propagates and compares
   target to currentTarget to decide whether the release was on the canvas
   background."
  []
  (let [el #js {}]
    #js {:target el :currentTarget el :button 0 :isPrimary true
         :stopPropagation (fn [])}))

(defn- resistor
  "A resistor at (x, y) puts port cells at (x+1, y) and (x+1, y+2)."
  [id x y]
  [id {:type "resistor" :x x :y y :transform [1 0 0 1 0 0] :name id}])

;; ---------------------------------------------------------------------------
;; port-wire-start — momentary entry from a port-dot press
;; ---------------------------------------------------------------------------
;;
;; Contract: a primary left-click on a port dot, while the cursor tool is
;; active, saves ::prev-tool and sets ::tool ::wire. It does NOT stop-propagate
;; and does NOT arm the wire — the device/background dispatch does — so those
;; are integration-tested manually.

(deftest port-wire-start-enters-momentary-wire-from-cursor
  (testing "a primary left-click from ::cursor saves ::prev-tool, sets ::wire"
    (try
      (ui-reset)
      (e/port-wire-start (mock-event))
      (is (= ::e/cursor (::e/prev-tool @e/ui)))
      (is (= ::e/wire (::e/tool @e/ui)))
      (finally (ui-reset)))))

(deftest port-wire-start-noop-for-non-primary
  (testing "a non-primary pointer (e.g. a second finger) does not enter the
            wire tool"
    (try
      (ui-reset)
      (e/port-wire-start (mock-event :is-primary false))
      (is (= ::e/cursor (::e/tool @e/ui)))
      (is (nil? (::e/prev-tool @e/ui)))
      (finally (ui-reset)))))

(deftest port-wire-start-noop-for-middle-click
  (testing "a middle-click (button 1) does not enter the wire tool —
            momentary pan stays"
    (try
      (ui-reset)
      (e/port-wire-start (mock-event :button 1))
      (is (= ::e/cursor (::e/tool @e/ui)))
      (is (nil? (::e/prev-tool @e/ui)))
      (finally (ui-reset)))))

(deftest port-wire-start-noop-for-pen-eraser
  (testing "a pen-eraser press (pointerType pen, button 5) does not enter the
            wire tool — the tool is still ::cursor when the dot handler runs,
            so the pen needs its own exclusion"
    (try
      (ui-reset)
      (e/port-wire-start (mock-event :pointer-type "pen" :button 5))
      (is (= ::e/cursor (::e/tool @e/ui)))
      (is (nil? (::e/prev-tool @e/ui)))
      (finally (ui-reset)))))

;; The gesture is cursor-only: every other tool owns a click on a port dot.

(deftest port-wire-start-noop-for-every-non-cursor-tool
  (testing "the dot handler stands down under ::wire (sticky W), ::eraser,
            ::probe, and ::pan — no slot written, tool unchanged"
    (try
      (doseq [tool [::e/wire ::e/eraser ::e/probe ::e/pan]]
        (ui-reset)
        (swap! e/ui assoc ::e/tool tool)
        (e/port-wire-start (mock-event))
        (is (= tool (::e/tool @e/ui))
            (str "tool unchanged under " tool))
        (is (nil? (::e/prev-tool @e/ui))
            (str "no slot written under " tool)))
      (finally (ui-reset)))))

(deftest port-wire-start-noop-preserves-staged-device
  (testing "with the device tool armed, a port press must not enter the wire
            tool: add-wire would overwrite ::staging and the device being
            placed would be silently lost"
    (try
      (ui-reset)
      (let [staged {:type "port" :variant "ground" :x 3 :y 4 :transform [1 0 0 1 0 0]}]
        (swap! e/ui assoc ::e/tool ::e/device ::e/staging staged)
        (e/port-wire-start (mock-event))
        (is (= ::e/device (::e/tool @e/ui)))
        (is (= staged (::e/staging @e/ui)) "staged device survives the press"))
      (finally (ui-reset)))))

;; ---------------------------------------------------------------------------
;; maybe-restore-tool! — the one-shot restore
;; ---------------------------------------------------------------------------

(deftest maybe-restore-restores-and-clears
  (testing "with ::prev-tool set, restores ::tool to it and clears the slot"
    (try
      (ui-reset)
      (swap! e/ui assoc ::e/tool ::e/wire ::e/prev-tool ::e/cursor)
      (e/maybe-restore-tool!)
      (is (= ::e/cursor (::e/tool @e/ui)))
      (is (nil? (::e/prev-tool @e/ui)) "cleared after restore")
      (finally (ui-reset)))))

(deftest maybe-restore-noop-on-empty-slot-keeps-tool-valid
  (testing "a restore site firing with no pending entry leaves ::tool alone —
            writing nil would violate the ::tool spec and trip the ui
            validator"
    (try
      (ui-reset)
      (swap! e/ui assoc ::e/tool ::e/eraser ::e/prev-tool nil)
      (e/maybe-restore-tool!)
      (is (= ::e/eraser (::e/tool @e/ui)) "unchanged, and not nil")
      (finally (ui-reset)))))

(deftest maybe-restore-is-idempotent
  (testing "a second call after restore is a no-op (slot already empty)"
    (try
      (ui-reset)
      (swap! e/ui assoc ::e/tool ::e/wire ::e/prev-tool ::e/cursor)
      (e/maybe-restore-tool!)
      (e/maybe-restore-tool!)
      (is (= ::e/cursor (::e/tool @e/ui)))
      (is (nil? (::e/prev-tool @e/ui)))
      (finally (ui-reset)))))

(deftest escape-during-held-pan-then-release-keeps-tool-valid
  (testing "hold-Space pan, Escape, release Space: Escape consumes the slot, so
            the keyup restore must no-op rather than write ::tool nil"
    (try
      (reset! pform/schematic {})
      (ui-reset)
      (e/momentary-tool! ::e/pan)                 ; Space keydown
      (e/cancel)                             ; Escape
      (e/maybe-restore-tool!)                ; Space keyup
      (is (= ::e/cursor (::e/tool @e/ui)))
      (is (some? (::e/tool @e/ui)) "never nil — the validator would reject it")
      (finally
        (reset! pform/schematic {})
        (ui-reset)))))

(deftest pan-mid-route-consumes-the-restore
  (testing "accepted consequence of one shared slot: a pan taken mid-route
            saves ::wire and its release returns ::wire, emptying the slot — so
            completion no longer restores and the wire tool stays active until
            Escape"
    (try
      (ui-reset)
      (e/port-wire-start (mock-event))       ; ::wire, slot = ::cursor
      (e/momentary-tool! ::e/pan)                 ; middle-click / Space
      (is (= ::e/wire (::e/prev-tool @e/ui)) "pan saves the wire tool")
      (e/maybe-restore-tool!)                ; release
      (is (= ::e/wire (::e/tool @e/ui)) "back to drawing the wire")
      (e/maybe-restore-tool!)                ; route completes
      (is (= ::e/wire (::e/tool @e/ui)) "stays ::wire — one Escape to leave")
      (finally (ui-reset)))))

;; ---------------------------------------------------------------------------
;; The restore sites — add-wire on-port, drag-end non-zero drag
;; ---------------------------------------------------------------------------

(deftest add-wire-on-port-restores-tool
  (testing "a continuation click landing on a device port commits and returns
            to the pre-gesture tool"
    (try
      (reset! pform/schematic (into {} [(resistor "R1" 0 0)]))
      (ui-reset)
      ;; A wire staged from (5,0) ending at (1,0) — R1's P port cell.
      (swap! e/ui assoc ::e/tool ::e/wire ::e/prev-tool ::e/cursor
             ::e/staging {:type "wire" :x 5 :y 0 :rx -4 :ry 0 :variant "d"}
             ::e/dragging ::e/wire)
      (e/add-wire [1 0] false)
      (is (nil? (::e/staging @e/ui)) "route ended")
      (is (nil? (::e/dragging @e/ui)))
      (is (= ::e/cursor (::e/tool @e/ui)) "tool restored at the on-port exit")
      (is (nil? (::e/prev-tool @e/ui)))
      (finally
        (reset! pform/schematic {})
        (ui-reset)))))

(deftest add-wire-continuation-keeps-tool-mid-route
  (testing "a continuation click on an empty cell re-arms the next segment and
            must NOT restore — a multi-click route stays in the wire tool"
    (try
      (reset! pform/schematic {})
      (ui-reset)
      (swap! e/ui assoc ::e/tool ::e/wire ::e/prev-tool ::e/cursor
             ::e/staging {:type "wire" :x 5 :y 0 :rx -4 :ry 0 :variant "d"}
             ::e/dragging ::e/wire)
      (e/add-wire [1 0] false)
      (is (= ::e/wire (::e/tool @e/ui)) "still drawing")
      (is (= ::e/cursor (::e/prev-tool @e/ui)) "restore still pending")
      (finally
        (reset! pform/schematic {})
        (ui-reset)))))

(deftest drag-end-nonzero-drag-restores-tool
  (testing "a press-drag-release from a port commits one segment and returns to
            the pre-gesture tool"
    (try
      (reset! pform/schematic {})
      (ui-reset)
      (swap! e/ui assoc ::e/tool ::e/wire ::e/prev-tool ::e/cursor
             ::e/staging {:type "wire" :x 0 :y 0 :rx 3 :ry 0 :variant "d"}
             ::e/dragging ::e/wire)
      (e/drag-end (mock-up-event))
      (is (nil? (::e/dragging @e/ui)) "route ended")
      (is (= ::e/cursor (::e/tool @e/ui)))
      (is (nil? (::e/prev-tool @e/ui)))
      (finally
        (reset! pform/schematic {})
        (ui-reset)))))

(deftest drag-end-zero-length-click-keeps-tool
  (testing "a zero-length click leaves ::dragging set for the next segment, so
            no restore fires"
    (try
      (reset! pform/schematic {})
      (ui-reset)
      (swap! e/ui assoc ::e/tool ::e/wire ::e/prev-tool ::e/cursor
             ::e/staging {:type "wire" :x 0 :y 0 :rx 0 :ry 0 :variant "d"}
             ::e/dragging ::e/wire)
      (e/drag-end (mock-up-event))
      (is (= ::e/wire (::e/dragging @e/ui)) "still routing")
      (is (= ::e/wire (::e/tool @e/ui)))
      (is (= ::e/cursor (::e/prev-tool @e/ui)) "restore still pending")
      (finally
        (reset! pform/schematic {})
        (ui-reset)))))

;; ---------------------------------------------------------------------------
;; cancel — Escape restores for port-armed wires; sticky stays ::wire
;; ---------------------------------------------------------------------------

(deftest cancel-escape-restores-tool-for-port-armed-wire
  (testing "Escape (cancel 0-arity) on a port-armed wire restores ::tool to
            ::prev-tool and clears it — one Escape returns"
    (try
      (reset! pform/schematic {})
      (ui-reset)
      (swap! e/ui assoc ::e/tool ::e/wire ::e/prev-tool ::e/cursor
             ::e/staging {:type "wire" :x 0 :y 0 :rx 0 :ry 0 :variant "d"}
             ::e/dragging ::e/wire)
      (e/cancel)
      (is (= ::e/cursor (::e/tool @e/ui))
          "restored to the pre-gesture tool, not stayed ::wire")
      (is (nil? (::e/prev-tool @e/ui)))
      (is (nil? (::e/staging @e/ui)))
      (is (nil? (::e/dragging @e/ui)))
      (finally
        (reset! pform/schematic {})
        (ui-reset)))))

(deftest cancel-escape-keeps-wire-tool-for-sticky-wire
  (testing "Escape on a sticky-W wire (empty slot, as W leaves it) keeps ::tool
            ::wire — the existing behavior; a second Escape returns to ::cursor"
    (try
      (reset! pform/schematic {})
      (ui-reset)
      (swap! e/ui assoc ::e/tool ::e/wire ::e/prev-tool nil
             ::e/staging {:type "wire" :x 0 :y 0 :rx 0 :ry 0 :variant "d"}
             ::e/dragging ::e/wire)
      (e/cancel)
      (is (= ::e/wire (::e/tool @e/ui))
          "sticky wire stays ::wire after Escape")
      (finally
        (reset! pform/schematic {})
        (ui-reset)))))

(deftest cancel-1-arity-clears-prev-tool
  (testing "pressing W (cancel 1-arity ::wire) clears the slot so a port-armed
            wire promotes to sticky — completion won't restore to the dropped
            tool"
    (try
      (reset! pform/schematic {})
      (ui-reset)
      (swap! e/ui assoc ::e/tool ::e/wire ::e/prev-tool ::e/cursor
             ::e/staging {:type "wire" :x 0 :y 0 :rx 0 :ry 0 :variant "d"})
      (e/cancel ::e/wire)
      (is (= ::e/wire (::e/tool @e/ui)))
      (is (nil? (::e/prev-tool @e/ui)) "slot cleared → sticky")
      (finally
        (reset! pform/schematic {})
        (ui-reset)))))

;; ---------------------------------------------------------------------------
;; port — :on-pointer-down handler attached to the bulb
;; ---------------------------------------------------------------------------

(deftest port-renderer-attaches-pointer-down
  (testing "the circle.port bulb carries port-wire-start as its
            :on-pointer-down"
    (let [el (e/port 0 0 "photonic")]
      (is (= :circle.port (first el)))
      (is (= e/port-wire-start (get (second el) :on-pointer-down))
          "circle.port presses launch the momentary wire tool"))))
