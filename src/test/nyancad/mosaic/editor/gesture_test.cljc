; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.gesture-test
  "`gesture-active?` decides when floating panels must stop catching the
   pointer. It is true while a drag, a staged placement or a staged paste is in
   progress, and false otherwise, so panels behave normally between gestures."
  (:require [cljs.test :refer [deftest is testing]]
            [nyancad.mosaic.editor :as e]
            [nyancad.mosaic.editor.platform-test]))

(deftest idle-ui-is-not-a-gesture
  (testing "nothing dragged or staged"
    (is (false? (e/gesture-active? {::e/dragging nil ::e/staging nil ::e/paste-staging nil})))
    (is (false? (e/gesture-active? {})))))

(deftest drag-is-a-gesture
  (testing "every drag kind counts"
    (doseq [d [::e/device ::e/wire ::e/view ::e/box]]
      (is (true? (e/gesture-active? {::e/dragging d})) (str d)))))

(deftest staging-is-a-gesture
  (testing "a staged device or wire counts"
    (is (true? (e/gesture-active? {::e/staging {:type "resistor"}}))))
  (testing "a staged paste counts"
    (is (true? (e/gesture-active? {::e/paste-staging {"R1" {:type "resistor"}}})))))
