; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.port-exposure-test
  (:require [cljs.test :refer [async deftest is]]
            [cljs.core.async :refer [chan close! go <! timeout]]
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.common :as cm]
            [nyancad.mosaic.editor :as e]
            [nyancad.mosaic.editor.platform-test :as pform]))

(def group-prefix "test$top:")

(def four-port-model
  {"models:test.component"
   {:name "Test component"
    :ports [{:name "left" :side :left :type "photonic"}
            {:name "right" :side :right :type "electric"}
            {:name "top" :side :top :type "electric"}
            {:name "bottom" :side :bottom :type "photonic"}]}})

(def one-left-port-model
  {"models:test.component"
   {:name "Test component"
    :ports [{:name "left" :side :left :type "photonic"}]}})

(defn- component
  ([]
   (component [1 0 0 1 0 0]))
  ([transform]
   {:type "ckt"
    :model "test.component"
    :x 10
    :y 10
    :transform transform
    :name "X1"}))

(defn- with-model [model f]
  (reset! pform/modeldb model)
  (try
    (f)
    (finally
      (reset! pform/modeldb {})
      (reset! pform/schematic {}))))

(defn- docs-of-type [docs type]
  (into {} (filter (fn [[_ doc]] (= type (:type doc))) docs)))

(deftest connection-semantics-use-physical-endpoints
  (with-model
    four-port-model
    (fn []
      (let [device-id (str group-prefix "X1")
            sch {device-id (component)
                 ;; Left source port (10,11): a wire endpoint connects it.
                 (str group-prefix "W9")
                 {:type "wire" :x 10 :y 11 :rx -2 :ry 0 :variant "d"}
                 ;; Right source port (12,11): a wire body crosses it, which
                 ;; must not connect it.
                 (str group-prefix "W10")
                 {:type "wire" :x 11 :y 11 :rx 2 :ry 0 :variant "d"}
                 ;; Top source port (11,10): a text label is non-physical.
                 (str group-prefix "P9")
                 {:type "port" :variant "text" :x 11 :y 10
                  :transform cm/IV :name "label"}
                 ;; Bottom source port (11,12): a plain marker connects it.
                 (str group-prefix "P10")
                 {:type "port" :x 11 :y 12 :transform cm/IV :name "external"}}
            names (set (map :name (e/unconnected-device-ports
                                   sch (e/build-point-index sch) device-id)))
            component-sch
            {device-id (component)
             (str group-prefix "X2")
             (assoc (component) :x 8 :name "X2")}
            component-names
            (set (map :name (e/unconnected-device-ports
                             component-sch
                             (e/build-point-index component-sch)
                             device-id)))]
        (is (= #{"top" "right"} names))
        (is (= #{"top" "right" "bottom"} component-names)
            "another component port connects at the same physical endpoint")))))

(deftest one-click-builds-all-markers-and-straight-wires
  (with-model
    four-port-model
    (fn []
      (let [device-id (str group-prefix "X1")
            sch {device-id (component)
                 (str group-prefix "P1")
                 {:type "port" :x 30 :y 30 :transform cm/IV :name "P1"}
                 (str group-prefix "W1")
                 {:type "wire" :x 30 :y 31 :rx 2 :ry 0 :variant "d" :name "W1"}}
            docs (e/build-port-exposure-docs sch (e/build-point-index sch) device-id)
            ports (docs-of-type docs "port")
            wires (docs-of-type docs "wire")]
        (is (= #{(str group-prefix "P2") (str group-prefix "P3")
                 (str group-prefix "P4") (str group-prefix "P5")}
               (set (keys ports)))
            "all four free ports use collision-free names")
        (is (= #{(str group-prefix "W2") (str group-prefix "W3")
                 (str group-prefix "W4") (str group-prefix "W5")}
               (set (keys wires)))
            "every clear corridor gets one collision-free wire")
        (is (= #{[11 8] [8 11] [14 11] [11 14]}
               (set (map (juxt :x :y) (vals ports))))
            "markers land exactly two cells outward")
        (is (= #{[0 -2] [-2 0] [2 0] [0 2]}
               (set (map (juxt :rx :ry) (vals wires))))
            "wires are straight two-cell segments")
        (is (= #{cm/IV [-1 0 0 1 0 0]
                 [0 1 -1 0 0 0] [0 -1 1 0 0 0]}
               (set (map :transform (vals ports))))
            "markers face outward on every cardinal side")
        (is (every? #(= "d" (:variant %)) (vals wires)))))))

(deftest transforms-control-outward-placement
  (doseq [[model transform expected-ports expected-wires expected-markers]
          [[one-left-port-model [1 0 0 1 0 0]
            #{[8 11]} #{[10 11 -2 0]} #{cm/IV}]
           [one-left-port-model [0 1 -1 0 0 0]
            #{[11 8]} #{[11 10 0 -2]} #{[0 1 -1 0 0 0]}]
           [one-left-port-model [-1 0 0 1 0 0]
            #{[14 11]} #{[12 11 2 0]} #{[-1 0 0 1 0 0]}]]]
    (with-model
      model
      (fn []
        (let [device-id (str group-prefix "X1")
              sch {device-id (component transform)}
              docs (e/build-port-exposure-docs
                    sch (e/build-point-index sch) device-id)
              ports (vals (docs-of-type docs "port"))
              wires (vals (docs-of-type docs "wire"))]
          (is (= expected-ports (set (map (juxt :x :y) ports)))
              (str transform))
          (is (= expected-wires (set (map (juxt :x :y :rx :ry) wires)))
              (str transform))
          (is (= expected-markers (set (map :transform ports)))
              (str transform)))))))

(deftest occupied-corridor-co-locates-marker-without-wire
  (with-model
    one-left-port-model
    (fn []
      (let [device-id (str group-prefix "X1")
            sch {device-id (component)
                 ;; Vertical body occupies the first outward corridor cell
                 ;; [9,11] without ending on the source at [10,11].
                 (str group-prefix "W9")
                 {:type "wire" :x 9 :y 10 :rx 0 :ry 2 :variant "d" :name "blocker"}}
            docs (e/build-port-exposure-docs sch (e/build-point-index sch) device-id)
            ports (docs-of-type docs "port")
            wires (docs-of-type docs "wire")]
        (is (= [10 11] ((juxt :x :y) (first (vals ports))))
            "occupied corridor falls back to source-cell co-location")
        (is (empty? wires) "fallback creates no wire")))))

(deftest toolbar-action-state
  (with-model
    one-left-port-model
    (fn []
      (let [device-id (str group-prefix "X1")
            initial {device-id (component)}]
        (is (= {:disabled? false}
               (e/port-exposure-state initial (e/build-point-index initial) device-id)))
        (is (nil? (e/port-exposure-state
                   {(str group-prefix "R1") {:type "resistor" :model "test.component"}}
                   {}
                   (str group-prefix "R1"))))
        (let [docs (e/build-port-exposure-docs
                    initial (e/build-point-index initial) device-id)]
          (is (= {:disabled? true}
                 (e/port-exposure-state (merge initial docs) (e/build-point-index (merge initial docs)) device-id))))))))

(deftest action-settles-adds-once-and-checkpoints-once
  (async done
    (let [device-id (str group-prefix "X1")
          initial {device-id (component)}
          first-write (chan)
          second-write (chan)
          done-calls (atom 0)
          checkpoints (atom 0)
          original-done? pform/done?
          original-post-action! e/post-action!]
      (reset! pform/modeldb one-left-port-model)
      (reset! pform/schematic initial)
      (set! pform/done? (fn [_] (if (= 1 (swap! done-calls inc))
                                  first-write second-write)))
      (set! e/post-action! (fn [] (swap! checkpoints inc) (go nil)))
      (let [result (e/expose-unconnected-ports! device-id)]
        (go
          (try
            (close! first-write)
            (<! (timeout 0))
            (swap! pform/schematic assoc (str group-prefix "P1")
                   {:type "port" :x 30 :y 30 :transform cm/IV :name "P1"})
            (close! second-write)
            (let [docs (<! result)]
              (is (= #{(str group-prefix "P2") (str group-prefix "W1")}
                     (set (keys docs))))
              (is (= 4 (count @pform/schematic)))
              (is (nil? (<! (e/expose-unconnected-ports! device-id))))
              (is (= 1 @checkpoints)))
            (finally
              (set! pform/done? original-done?)
              (set! e/post-action! original-post-action!)
              (reset! pform/modeldb {})
              (reset! pform/schematic {})
              (done))))))))
