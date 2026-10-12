; SPDX-FileCopyrightText: 2026 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.jsatom.util-test
  "doc->state injects :x/:y defaults so Livewire-authored .gsch
   files (which omit those fields) load cleanly into Mosaic. Existing
   :x/:y on Mosaic-authored entries must win over the defaults so
   round-trips stay byte-stable."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [nyancad.mosaic.common :as cm]
            [nyancad.mosaic.jsatom.util :refer [doc->state]]))

(deftest livewire-shaped-entry-validates
  (testing "entry with no :x/:y/:transform gets x=0, y=0, no :transform key, validates"
    (let [doc (js/JSON.stringify
                #js {"R1" #js {"type" "resistor" "name" "R1" "model" "models:r"}})
          {:strs [R1]} (doc->state doc)]
      (is (= 0 (:x R1)))
      (is (= 0 (:y R1)))
      (is (not (contains? R1 :transform)))
      (is (s/valid? ::cm/device R1) (s/explain-str ::cm/device R1)))))

(deftest multiple-unplaced-devices-stack
  (testing "three unplaced entries get distinct :y values 0, 1, 2"
    (let [doc (js/JSON.stringify
                #js {"R1" #js {"type" "resistor" "name" "R1"}
                     "R2" #js {"type" "resistor" "name" "R2"}
                     "R3" #js {"type" "resistor" "name" "R3"}})
          state (doc->state doc)
          ys (set (map :y (vals state)))]
      (is (= #{0 1 2} ys)))))

(deftest mosaic-authored-entry-roundtrips
  (testing "explicit :x/:y/:transform survive normalization"
    (let [doc (js/JSON.stringify
                #js {"R1" #js {"type" "resistor"
                               "name" "R1"
                               "x" 5
                               "y" 7
                               "transform" #js [1 0 0 1 0 0]}})
          {:strs [R1]} (doc->state doc)]
      (is (= 5 (:x R1)))
      (is (= 7 (:y R1)))
      (is (= [1 0 0 1 0 0] (:transform R1))))))

(deftest mixed-entries-stack-without-gaps
  (testing "an authored entry between two unauthored ones doesn't burn a slot"
    (let [doc (js/JSON.stringify
                #js {"A" #js {"type" "resistor" "name" "A"}
                     "B" #js {"type" "resistor" "name" "B" "x" 9 "y" 9}
                     "C" #js {"type" "resistor" "name" "C"}})
          {:strs [A B C]} (doc->state doc)]
      (is (= 9 (:y B)) "authored entry preserved")
      (is (= #{0 1} (set [(:y A) (:y C)]))
          "two unauthored entries get y=0 and y=1, not y=0 and y=2"))))

;; @tags nyancir format
(deftest livewire-via-entry-validates
  (testing "a Livewire route via (layout-only fields, no :x/:y) passes the schematic spec"
    (let [doc (js/JSON.stringify
                #js {"via_1" #js {"type" "via" "name" "via_1"
                                  "irl_x" 52.0 "irl_y" 23.357
                                  "bbox_width" 0.62 "bbox_height" 0.62
                                  "bbox_xmin" -0.31 "bbox_ymin" -0.31}})
          state (doc->state doc)]
      (is (s/valid? ::cm/schematic state) (s/explain-str ::cm/schematic state)))))
