; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.keyboard-shortcuts-test
  "Regression coverage for document-level Mosaic edit shortcuts."
  (:require [cljs.test :refer [deftest is testing]]
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.common :as cm]
            [nyancad.mosaic.editor :as e]
            [nyancad.mosaic.editor.platform-test :as platform]))

;; @tags editor keyboard shortcuts
(deftest destructive-shortcuts-delete-on-keydown
  (doseq [key ["Backspace" "Delete" "d"]]
    (testing (str key " deletes the selection and suppresses its host default")
      (try
        (reset! platform/schematic {"R1" {:type "resistor" :x 0 :y 0}})
        (swap! e/ui assoc ::e/selected #{"R1"})
        (let [default-prevented? (atom false)
              event #js {:key key
                         :repeat false
                         :target #js {:tagName "DIV"}
                         :preventDefault #(reset! default-prevented? true)}]
          (cm/keyboard-shortcuts e/immediate-shortcuts event)
          (is (= {} @platform/schematic))
          (is (= #{} (::e/selected @e/ui)))
          (is (true? @default-prevented?)))
        (finally
          (reset! platform/schematic {})
          (swap! e/ui assoc ::e/selected #{}))))))

;; @tags editor keyboard shortcuts
(deftest destructive-shortcuts-preserve-native-field-editing
  (try
    (reset! platform/schematic {"R1" {:type "resistor" :x 0 :y 0}})
    (swap! e/ui assoc ::e/selected #{"R1"})
    (let [default-prevented? (atom false)
          event #js {:key "Backspace"
                     :repeat false
                     :target #js {:tagName "INPUT"}
                     :preventDefault #(reset! default-prevented? true)}]
      (cm/keyboard-shortcuts e/immediate-shortcuts event)
      (is (contains? @platform/schematic "R1"))
      (is (= #{"R1"} (::e/selected @e/ui)))
      (is (false? @default-prevented?)))
    (finally
      (reset! platform/schematic {})
      (swap! e/ui assoc ::e/selected #{}))))
