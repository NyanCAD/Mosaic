; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.viewport-zoom-test
  "The schematic editor's zoom-to-fit viewBox: content padding, minimum size,
   and the aspect-ratio correction that stops preserveAspectRatio from
   letterboxing the canvas. Expected values are hand-computed from
   specs/mosaic_viewport_zoom.md."
  (:require [cljs.test :refer [deftest is testing]]
            [nyancad.mosaic.editor :as editor]))

;; @tags mosaic viewport zoom
(defn- approx= [a b]
  (< (js/Math.abs (- a b)) 1e-9))

;; @tags mosaic viewport zoom
(deftest fit-viewbox-matches-the-container-aspect
  (testing "a wide container expands the viewBox horizontally about the content"
    (let [[x y w h] (editor/fit-viewbox 0 0 1000 500 1000 400)]
      (is (approx= 2.5 (/ w h)))
      (is (approx= -250 x))
      (is (approx= -50 y))
      (is (approx= 1500 w))
      (is (approx= 600 h))))
  (testing "a tall container expands it vertically"
    (let [[x y w h] (editor/fit-viewbox 0 0 1000 500 400 1000)]
      (is (approx= 0.4 (/ w h)))
      (is (approx= -100 x))
      (is (approx= -1250 y))
      (is (approx= 1200 w))
      (is (approx= 3000 h))))
  (testing "content padding is 10% per side, with a one-grid-size minimum"
    (is (= [-4 -4 50 50] (editor/fit-viewbox 0 0 40 40 60 60)))
    (is (= [-100 -50 1200 600] (editor/fit-viewbox 0 0 1000 500 1200 600)))))
