; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.make-array-test
  "Spec-derived tests for Mosaic's Make array action
   (specs/mosaic_make_array.md).

   The contract under test has two seams:

   - `array-able?` is the pure eligibility predicate: a non-empty model that
     is not a `.gsch` subcircuit, is not already the array factory FQN, and
     whose resolved model definition carries a non-empty `:name`. Tested with
     the explicit `[model model-def]` arity, mirroring `editable-model?`.
   - `make-array!` performs the in-place transform: one whole-document
     replacement on `schematic` (so one undo checkpoint) with the array type,
     model, and merged props, preserving name, pose, and other existing
     fields. It relies on the platform-test stub's `done?` channel, so it is
     `async` here.

   Visual eligibility (which reason title a disabled button shows) and the
   button's position in the action row remain DOM/CSS behavior; only the
   enable/disable predicate and the written document are covered."
  (:require [cljs.test :refer [async deftest is testing]]
            [cljs.core.async :refer [go <!]]
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.common :as cm]
            [nyancad.mosaic.editor :as e]
            [nyancad.mosaic.editor.platform-test :as pform]))

(def ^:private array-fqn
  "gdsfactory.components.containers.array_component.array")

(def ^:private ring-model-def
  {:name "ring_single"
   :type "ckt"
   :props [{:name "component" :default "pad"}
           {:name "columns" :default 1}
           {:name "rows" :default 1}
           {:name "spacing" :default [10 10]}]})

(def ^:private models
  {"models:test.ring" ring-model-def
   "models:test.nameless" {:type "ckt" :props []}
   (str "models:" array-fqn)
   {:name "array"
    :type "ckt"
    :props [{:name "component" :default "pad"}
            {:name "columns" :default 1}
            {:name "rows" :default 1}
            {:name "spacing" :default [10 10]}]}
   "models:schematics/sub.gsch" {:name "sub" :type "ckt"}})

(def ^:private device
  {:type "ckt"
   :model "test.ring"
   :x 10
   :y 20
   :transform [1 0 0 1 5 7]
   :name "X1"
   :variant "v1"
   :bbox_x 1
   :bbox_y 2
   :ports [{:name "o1"}]})

(def ^:private transformed
  {:type "ckt"
   :model array-fqn
   :props {:component "ring_single" :columns 1 :rows 1 :spacing [10 10]}
   :x 10
   :y 20
   :transform [1 0 0 1 5 7]
   :name "X1"
   :variant "v1"
   :bbox_x 1
   :bbox_y 2
   :ports [{:name "o1"}]})

;; @tags mosaic make array
(defn- model-def-for [model]
  (when model (get models (str "models:" model))))

;; @tags mosaic make array
(deftest array-fqn-is-the-builtin-array
  (is (= "gdsfactory.components.containers.array_component.array"
         e/array-fqn)))

;; @tags mosaic make array
(deftest eligible-when-model-resolves-to-a-named-definition
  (testing "a PDK model whose definition has a :name is eligible"
    (is (true? (e/array-able? "test.ring" (model-def-for "test.ring")))))
  (testing "name presence, not a PDK tag heuristic, decides eligibility"
    (is (true? (e/array-able?
                "gdsfactory.components.bends.bend_euler.bend_euler"
                {:name "bend_euler" :type "ckt"})))))

;; @tags mosaic make array
(deftest ineligible-reasons
  (testing "no model"
    (is (false? (e/array-able? nil ring-model-def)))
    (is (false? (e/array-able? "" ring-model-def))))
  (testing "missing or nameless model definition"
    (is (false? (e/array-able? "test.ring" nil)))
    (is (false? (e/array-able? "test.ring" {:type "ckt"})))
    (is (false? (e/array-able? "test.ring" {:name ""}))))
  (testing "a .gsch subcircuit reference"
    (is (false? (e/array-able? "schematics/sub.gsch"
                               (model-def-for "schematics/sub.gsch"))))
    (is (false? (e/array-able? "sub.gsch" {:name "sub"}))))
  (testing "already the array factory"
    (is (false? (e/array-able? array-fqn
                               (model-def-for array-fqn))))))

;; @tags mosaic make array
(deftest make-array-writes-the-array-document
  (async done
    (let [key "test$top:X1"
          initial {key device}]
      (reset! pform/modeldb models)
      (reset! pform/schematic initial)
      (reset! e/undotree @(cm/newundotree))
      (cm/newdo e/undotree initial)
      (go
        (try
          (<! (e/make-array! key))
          (is (= transformed (get @pform/schematic key))
              "type, model and merged props are written; name/pose/other fields kept")
          (<! (e/undo-schematic))
          (is (= initial @pform/schematic)
              "the transform is exactly one undoable checkpoint")
          (catch :default err
            (is false (str "make-array! did not complete: " err)))
          (finally
            (reset! pform/modeldb {})
            (reset! pform/schematic {})
            (done)))))))

;; @tags mosaic make array
(deftest make-array-without-array-defaults-writes-component-only
  (async done
    (let [key "test$top:X1"
          initial {key device}]
      (reset! pform/modeldb (dissoc models (str "models:" array-fqn)))
      (reset! pform/schematic initial)
      (reset! e/undotree @(cm/newundotree))
      (cm/newdo e/undotree initial)
      (go
        (try
          (<! (e/make-array! key))
          (is (= {:component "ring_single"}
                 (get-in @pform/schematic [key :props]))
              "no array model-def defaults leaves the factory's Python defaults in charge")
          (catch :default err
            (is false (str "make-array! did not complete: " err)))
          (finally
            (reset! pform/modeldb {})
            (reset! pform/schematic {})
            (done)))))))
