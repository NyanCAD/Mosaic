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

(defn- annotations-for
  "Run one annotation derivation over `sch`, the way `post-action!` does."
  [sch]
  (let [idx (e/build-point-index sch)
        networks (e/build-wire-networks sch idx)]
    (e/build-net-annotations sch
                             (e/build-netlist networks)
                             (e/build-net-type-index networks idx))))

(deftest a-disconnected-port-loses-its-nets-annotation
  (with-model
    four-port-model
    (fn []
      (let [device-id (str group-prefix "X1")
            sch {device-id (component)}
            idx (e/build-point-index sch)
            docs (e/build-port-exposure-docs sch idx device-id)
            markers (docs-of-type docs "port")
            with-docs (merge sch docs)
            ;; Apply the annotations the way post-action! does: merge-with merge
            ;; is one level deep over document ids, so each update is a shallow
            ;; merge into its doc.
            annotated (merge-with merge with-docs (annotations-for with-docs))
            ;; The device the markers exposed is gone, so no cell carries a
            ;; :ports attribution any more and every marker is free-floating.
            orphaned (dissoc annotated device-id)
            updates (annotations-for orphaned)]
        (is (= 4 (count markers)))
        (is (every? #(string? (get-in annotated [% :nets :P])) (keys markers))
            "every marker carried a net while it was still on a device pin")
        (doseq [id (keys markers)]
          (is (= {} (:nets (get updates id)))
              (str id " clears :nets instead of leaving a phantom net")))
        (let [cleared (merge-with merge orphaned updates)]
          (doseq [id (keys markers)]
            (is (nil? (get (annotations-for cleared) id))
                (str id " is not rewritten once already cleared"))))))))

(deftest removing-the-wire-to-a-port-clears-its-nets
  ;; A port marker joined to a device pin by a wire, then the wire deleted.
  ;; Unlike a marker sitting directly on a pin cell, a marker at the far end of
  ;; a wire genuinely leaves the network when the wire goes.
  (with-model
    four-port-model
    (fn []
      (let [device-id (str group-prefix "X1")
            port-id (str group-prefix "P1")
            wire-id (str group-prefix "W1")
            ;; The left pin of the device sits at [10 11]; run a wire from it out
            ;; to [8 11] and put the marker on that far endpoint.
            wired {device-id (component)
                   wire-id {:type "wire" :x 10 :y 11 :rx -2 :ry 0 :variant "d"}
                   port-id {:type "port" :x 8 :y 11 :transform cm/IV :name "P1"}}
            annotated (merge-with merge wired (annotations-for wired))]
        (is (string? (get-in annotated [port-id :nets :P]))
            "the marker is annotated while the wire joins it to the pin")
        (is (= {} (:nets (get (annotations-for (dissoc annotated wire-id)) port-id)))
            "deleting the wire clears the marker's :nets")))))

(deftest a-port-that-never-carried-a-net-produces-no-write
  (let [bare-id (str group-prefix "bare")
        sch {bare-id {:type "port" :x 40 :y 40 :transform cm/IV
                      :name "bare" :nature "photonic"}}]
    (is (nil? (get (annotations-for sch) bare-id))
        "an unwired port with no :nets key must not be touched")))

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
