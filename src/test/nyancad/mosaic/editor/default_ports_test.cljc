; SPDX-FileCopyrightText: 2026 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.default-ports-test
  "A photonic symbol placed without a model (or with a model that lists no
   ports) keeps the shape and port leads its artwork was drawn for. Without
   defaults it collapsed to a 1x1 box and lost its connection points."
  (:require [cljs.test :refer [deftest is testing]]
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.common :as cm]
            [nyancad.mosaic.editor :as e]
            [nyancad.mosaic.editor.platform-test :as pform]))

;; Port cells each symbol's artwork draws its leads at (see the comment above
;; each *-elements def in editor.cljc).
(def art-port-cells
  {"straight"        #{[0 1] [2 1]}
   "taper"           #{[0 1] [2 1]}
   "transition"      #{[0 1] [2 1]}
   "spiral"          #{[0 1] [2 1]}
   "bend"            #{[0 1] [1 0]}
   "sbend"           #{[0 1] [2 2]}
   "terminator"      #{[0 1]}
   "grating-coupler" #{[0 1]}
   "crossing"        #{[0 1] [2 1] [1 0] [1 2]}
   "ring-single"     #{[0 2] [2 2]}
   "ring-double"     #{[0 1] [2 1] [0 2] [2 2]}
   "coupler"         #{[0 1] [2 1] [0 2] [2 2]}
   "coupler-ring"    #{[0 1] [3 1] [1 0] [2 0]}
   "splitter-1x2"    #{[0 2] [2 1] [2 3]}
   "mmi-1x2"         #{[0 2] [2 1] [2 3]}
   "mzi-1x2"         #{[0 2] [2 1] [2 3]}
   "mmi-2x2"         #{[0 1] [2 1] [0 3] [2 3]}
   "mzi-2x2"         #{[0 1] [2 1] [0 3] [2 3]}})

(defn- bare [t] {:type t :x 0 :y 0 :transform cm/IV})

(deftest every-photonic-symbol-has-default-ports
  (is (= (set (keys art-port-cells)) (set (keys cm/default-ports)))))

(deftest model-less-symbols-keep-their-artwork-size
  (doseq [t (keys art-port-cells)]
    (let [[w h] (e/device-dims (bare t))
          art-size (::e/size (::e/sym (get e/models t)))]
      (is (= art-size (+ 2 (max w h))) (str t " footprint matches its artwork")))))

(deftest model-less-symbols-expose-their-art-ports
  (doseq [[t cells] art-port-cells]
    (is (= cells (set (map (juxt :x :y) (e/device-port-types (bare t)))))
        (str t " ports sit where the artwork draws them"))
    (is (= cells (set (first (e/device-locations (bare t)))))
        (str t " connection index matches"))))

(deftest a-model-with-no-ports-counts-as-no-model
  (reset! pform/modeldb {"models:m.bend" {:name "bend" :type "bend" :ports []}})
  (try
    (is (= [1 1] (e/device-dims {:type "bend" :model "m.bend"})))
    (is (= (get art-port-cells "bend")
           (set (map (juxt :x :y)
                     (e/device-port-types (assoc (bare "bend") :model "m.bend"))))))
    (finally (reset! pform/modeldb {}))))

(deftest model-ports-take-precedence-over-defaults
  (testing "a model with more ports than fit stretches the symbol"
    (reset! pform/modeldb
            {"models:m.wide"
             {:name "wide" :type "straight"
              :ports (vec (concat (for [i (range 4)] {:name (str "l" i) :side :left :type "photonic"})
                                  (for [i (range 4)] {:name (str "r" i) :side :right :type "photonic"})))}})
    (try
      (is (= [1 4] (e/device-dims {:type "straight" :model "m.wide"})))
      (is (= 8 (count (e/device-port-types (assoc (bare "straight") :model "m.wide")))))
      (finally (reset! pform/modeldb {})))))

(deftest non-photonic-model-less-devices-are-unchanged
  (testing "a bare ckt is still a 1x1 box with no ports"
    (is (= [1 1] (e/device-dims (bare "ckt"))))
    (is (empty? (e/device-port-types (bare "ckt"))))))
