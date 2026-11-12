; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.selection-toolbar-test
  "Contract-driven tests for the selection-toolbar gate and anchor.

   `::selected` is a flat set of ids covering devices, wires and text labels,
   because all three are selected through the same drag-start path. The toolbar
   anchors to device symbols only, and its single-device actions need an exact
   count, so `selected-devices` is what decides both. Expected values are derived
   from the data model (`:type` on the schematic doc), not from running the code.

   `device-tile-size` mirrors doc-center's branches to give each device a
   square tile in grid cells; `selection-anchor-rect` unions those tiles into
   the anchor rect in SVG user units. The screen mapping on top of this lives
   in `nyancad.mosaic.common-test` (union-rect, inset-rect, user->screen,
   rect->screen); the bar's placement against that rect is CSS."
  (:require [cljs.test :refer [deftest is testing]]
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.editor :as e]))

(def ^:private sch
  "One of each selectable kind. Ports are devices: they carry props and a symbol,
   unlike wires and text."
  {"R1"     {:type "resistor" :x 0 :y 0}
   "C1"     {:type "capacitor" :x 5 :y 0}
   "port-1" {:type "port" :x 9 :y 0}
   "wire-1" {:type "wire" :x 2 :y 2 :rx 3 :ry 0}
   "text-1" {:type "text" :x 0 :y 9}})

(deftest selected-devices-keeps-devices
  (testing "device ids pass through"
    (is (= #{"R1"} (e/selected-devices sch #{"R1"})))
    (is (= #{"R1" "C1"} (e/selected-devices sch #{"R1" "C1"}))))
  (testing "ports are devices — they have a symbol and props"
    (is (= #{"port-1"} (e/selected-devices sch #{"port-1"})))))

(deftest selected-devices-drops-wires-and-text
  (testing "a wire-only selection yields no devices, so no toolbar"
    (is (= #{} (e/selected-devices sch #{"wire-1"}))))
  (testing "a text-only selection yields no devices"
    (is (= #{} (e/selected-devices sch #{"text-1"}))))
  (testing "wires and text together still yield nothing"
    (is (= #{} (e/selected-devices sch #{"wire-1" "text-1"})))))

(deftest selected-devices-filters-mixed-selections
  (testing "a mixed selection keeps only the devices"
    (is (= #{"R1"} (e/selected-devices sch #{"R1" "wire-1" "text-1"})))
    (is (= #{"R1" "C1" "port-1"}
           (e/selected-devices sch #{"R1" "C1" "port-1" "wire-1" "text-1"}))))
  (testing "wires in a selection do not count as devices"
    ;; A box-select that caught a wire alongside one device is still a
    ;; single-device selection.
    (is (= 1 (count (e/selected-devices sch #{"R1" "wire-1"}))))
    (is (= 2 (count (e/selected-devices sch #{"R1" "C1" "wire-1"}))))))

(deftest selected-devices-ignores-unknown-ids
  (testing "an empty selection yields nothing"
    (is (= #{} (e/selected-devices sch #{}))))
  (testing "ids absent from the schematic are dropped rather than anchored to"
    ;; A stale selection (device deleted, selection not yet cleared) must not
    ;; keep the toolbar alive pointing at geometry that is gone.
    (is (= #{} (e/selected-devices sch #{"deleted-1"})))
    (is (= #{"R1"} (e/selected-devices sch #{"R1" "deleted-1"}))))
  (testing "a doc with no :type is not treated as a device"
    (is (= #{} (e/selected-devices {"odd-1" {:x 0 :y 0}} #{"odd-1"})))))

;; ---------------------------------------------------------------------------
;; device-tile-size — a device's square tile in grid cells
;; ---------------------------------------------------------------------------
;;
;; Contract (selection toolbar): mirrors doc-center's branches —
;; ::bg [w h] → (+ 2 (max w h)); :model → (+ 2 (max (device-dims dev)));
;; photonic builtin without a model → 3; anything else → 1. The else branch is
;; what plain ports render at (port-sym, size 1), which device-dims' [1 1]
;; fallthrough would misreport as 3.

(deftest device-tile-size-per-device-kind
  (testing "electrical builtins (::bg [1 1]) get the standard 3-tile"
    (is (= 3 (e/device-tile-size {:type "resistor" :x 0 :y 0})))
    (is (= 3 (e/device-tile-size {:type "capacitor" :x 0 :y 0}))))
  (testing "a photonic builtin without a model gets the [1 1] tile"
    (is (= 3 (e/device-tile-size {:type "straight" :x 0 :y 0}))))
  (testing "a subcircuit whose model is not loaded falls back to [1 1]"
    ;; The loaded-model path is device-dims' port-perimeter contract, covered
    ;; in nyancad.mosaic.editor.paste-test; the platform-test modeldb is empty.
    (is (= 3 (e/device-tile-size {:type "ckt" :model "absent" :x 0 :y 0}))))
  (testing "a plain port renders at size 1, not the [1 1] fallthrough's 3"
    (is (= 1 (e/device-tile-size {:type "port" :x 0 :y 0})))))

;; ---------------------------------------------------------------------------
;; selection-anchor-rect — the toolbar's anchor in SVG user units
;; ---------------------------------------------------------------------------
;;
;; Contract (selection toolbar): union of [x·grid, y·grid,
;; tile·grid, tile·grid] over the selected device ids, nil for nothing. The
;; input is device ids only — dropping wires and text is selected-devices'
;; job. grid-size is 50, so one tile is 50 user units.

(deftest selection-anchor-rect-covers-tiles
  (testing "a single device yields its own tile rect"
    ;; resistor at (0,0), tile 3 → 150x150 at the origin
    (is (= {:x 0 :y 0 :width 150 :height 150}
           (e/selection-anchor-rect sch #{"R1"}))))
  (testing "a plain port anchors to its 1-tile, not a 3-tile"
    ;; port at (9,0), tile 1 → 50x50 at (450, 0)
    (is (= {:x 450 :y 0 :width 50 :height 50}
           (e/selection-anchor-rect sch #{"port-1"}))))
  (testing "multiple devices union to the smallest enclosing rect"
    ;; R1 (0,0,150,150) ∪ C1 (250,0,150,150) → right edge 400
    (is (= {:x 0 :y 0 :width 400 :height 150}
           (e/selection-anchor-rect sch #{"R1" "C1"})))
    ;; adding the port extends the right edge to 450+50 = 500
    (is (= {:x 0 :y 0 :width 500 :height 150}
           (e/selection-anchor-rect sch #{"R1" "C1" "port-1"}))))
  (testing "an empty selection has no anchor"
    (is (nil? (e/selection-anchor-rect sch #{})))))
