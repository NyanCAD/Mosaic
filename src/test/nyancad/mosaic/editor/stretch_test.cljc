; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.stretch-test
  "A Ctrl-drag that collapses a wire onto one cell deletes the wire. The
   deletion must be a dissoc: a `{id nil}` entry fails the schematic validator,
   which now alerts the user."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.editor :as e]))

(def wire {:type "wire" :x 0 :y 0 :rx 2 :ry 0 :variant "d" :name "W1"})

(deftest collapsing-a-wire-removes-it-without-a-nil-value
  (let [sch {"t:W1" wire}
        out (e/commit-stretch sch {"t:W1" #{:end}} -2 0)]
    (testing "the collapsed wire is gone, not a nil entry"
      (is (not (contains? out "t:W1"))))
    (testing "the result passes the schematic validator"
      (is (s/valid? :nyancad.mosaic.common/schematic out)))))

(deftest stretching-a-wire-keeps-it
  (let [out (e/commit-stretch {"t:W1" wire} {"t:W1" #{:end}} 1 0)]
    (is (= 3 (get-in out ["t:W1" :rx])))
    (is (s/valid? :nyancad.mosaic.common/schematic out))))

(deftest ids-missing-from-the-subset-are-not-fabricated
  (is (= {} (e/commit-stretch {} {"t:W9" #{:end}} 1 0))))
