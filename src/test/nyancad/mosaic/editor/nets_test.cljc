; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.nets-test
  "A port marker's :nets annotation must be cleared when the marker leaves
   every network, instead of keeping a phantom net name."
  (:require [cljs.test :refer [deftest is]]
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.common :as cm]
            [nyancad.mosaic.editor :as e]
            [nyancad.mosaic.editor.platform-test :as pform]))

(def group-prefix "test$top:")

(def one-left-port-model
  {"models:test.component"
   {:name "Test component"
    :ports [{:name "left" :side :left :type "photonic"}]}})

(defn- component []
  {:type "ckt" :model "test.component" :x 10 :y 10
   :transform [1 0 0 1 0 0] :name "X1"})

(defn- with-model [model f]
  (reset! pform/modeldb model)
  (try
    (f)
    (finally
      (reset! pform/modeldb {})
      (reset! pform/schematic {}))))

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
    one-left-port-model
    (fn []
      (let [device-id (str group-prefix "X1")
            port-id (str group-prefix "P1")
            ;; The device's left pin sits at [10 11]; the marker is on it.
            sch {device-id (component)
                 port-id {:type "port" :x 10 :y 11 :transform cm/IV :name "P1"}}
            ;; Apply the annotations the way post-action! does: merge-with merge
            ;; is one level deep over document ids.
            annotated (merge-with merge sch (annotations-for sch))
            ;; The device is gone, so no cell carries a :ports attribution any
            ;; more and the marker is free-floating.
            orphaned (dissoc annotated device-id)
            updates (annotations-for orphaned)]
        (is (string? (get-in annotated [port-id :nets :P]))
            "the marker carried a net while it was on a device pin")
        (is (= {} (:nets (get updates port-id)))
            "it clears :nets instead of leaving a phantom net")
        (let [cleared (merge-with merge orphaned updates)]
          (is (nil? (get (annotations-for cleared) port-id))
              "it is not rewritten once already cleared"))))))

(deftest removing-the-wire-to-a-port-clears-its-nets
  ;; A port marker joined to a device pin by a wire, then the wire deleted.
  ;; Unlike a marker sitting directly on a pin cell, a marker at the far end of
  ;; a wire genuinely leaves the network when the wire goes.
  (with-model
    one-left-port-model
    (fn []
      (let [device-id (str group-prefix "X1")
            port-id (str group-prefix "P1")
            wire-id (str group-prefix "W1")
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
