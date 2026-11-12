; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.pointer-down-test
  "Middle-click pan must suppress the browser default.

   Browsers start autoscroll from the compatibility mousedown of a middle press
   unless its default was prevented, which leaves an autoscroll indicator
   floating on the canvas. The middle-button pointerdown that starts momentary
   pan therefore calls preventDefault, which also suppresses the compatibility
   mousedown. Other buttons keep their default."
  (:require [cljs.test :refer [deftest is testing]]
            [nyancad.mosaic.editor :as e]
            [nyancad.mosaic.editor.platform-test]))

(defn- ui-reset []
  (swap! e/ui assoc
         ::e/dragging nil
         ::e/staging nil
         ::e/paste-staging nil
         ::e/selected #{}
         ::e/pointer-cache {}
         ::e/tool ::e/cursor))

(defn- press-event
  "JS pointerdown with a counting preventDefault. Returns [event calls-atom]."
  [button]
  (let [calls (atom 0)]
    [#js {:pointerId 1 :clientX 10 :clientY 10 :isPrimary true :button button
          :pointerType "mouse" :shiftKey false
          :stopPropagation (fn [])
          :preventDefault (fn [] (swap! calls inc))}
     calls]))

(defn- with-press-state [f]
  (let [previous-pointerdown @e/last-pointerdown]
    (try
      (ui-reset)
      (reset! e/last-pointerdown {:time 0 :x 0 :y 0})
      (f)
      (finally
        (reset! e/last-pointerdown previous-pointerdown)
        (ui-reset)))))

(deftest middle-press-on-background-prevents-default
  (testing "a middle press on the canvas background cancels the browser default"
    (with-press-state
      (fn []
        (let [[ev calls] (press-event 1)]
          (e/on-pointer-down-bg ev)
          (is (= 1 @calls) "preventDefault called once"))))))

(deftest middle-press-on-element-prevents-default
  (testing "a middle press on a device cancels the browser default"
    (with-press-state
      (fn []
        (let [[ev calls] (press-event 1)]
          (e/on-pointer-down-element "R1" ev)
          (is (= 1 @calls) "preventDefault called once"))))))

(deftest left-press-keeps-default
  (testing "a left press on a device leaves the browser default alone"
    (with-press-state
      (fn []
        (let [[ev calls] (press-event 0)]
          (with-redefs [e/drag-start (fn [_ _])]
            (e/on-pointer-down-element "R1" ev))
          (is (zero? @calls)))))))
