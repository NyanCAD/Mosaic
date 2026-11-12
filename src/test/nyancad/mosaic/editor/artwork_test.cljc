; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.artwork-test
  "A model may carry an `:artwork-transform` six-number matrix that is applied
   to the built-in symbol artwork only, around the symbol's centre."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.editor :as e]))

(deftest no-matrix-means-no-transform
  (is (nil? (e/centered-artwork-transform 4 nil))))

(deftest mirror-is-applied-about-the-symbol-centre
  (testing "a horizontal mirror of a 4-cell symbol maps x -> 2*center - x"
    (let [t (e/centered-artwork-transform 4 [-1 0 0 1 0 0])
          m (js/DOMMatrixReadOnly. t)
          center (* 2 50)] ; size 4 cells, 50px grid -> center at 100
      (is (= -1 (.-a m)))
      (is (= 1 (.-d m)))
      (is (= (* 2 center) (.-e m)) "translation re-centres the mirrored artwork")
      (is (= 0 (.-f m))))))

(deftest model-def-accepts-a-six-number-matrix
  (is (s/valid? :nyancad.mosaic.common/model-def
                {:name "gc" :artwork-transform [-1 0 0 1 0 0]}))
  (is (not (s/valid? :nyancad.mosaic.common/model-def
                     {:name "gc" :artwork-transform [1 0 0 1]}))))
