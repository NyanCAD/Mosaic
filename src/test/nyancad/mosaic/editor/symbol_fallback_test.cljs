; SPDX-License-Identifier: MPL-2.0
(ns nyancad.mosaic.editor.symbol-fallback-test
  (:require [cljs.test :refer [deftest is testing]]
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.common :as cm]
            [nyancad.mosaic.editor :as editor]
            [nyancad.mosaic.editor.platform-test :as platform]))

;; @tags factory resolution
(defn- render-component [renderer entry model-definition symbol-url]
  (with-redefs [editor/model-entry (fn [& _] model-definition)
                platform/resolve-symbol-url (fn [& _] (atom symbol-url))
                editor/counter-rotate (fn [& _] "matrix(1, 0, 0, 1, 0, 0)")]
    (renderer "U1" (merge {:type "ckt" :name "U1" :model "cells.Filter"
                          :transform cm/IV :x 0 :y 0} entry))))

;; @tags factory resolution
(defn- model-labels [rendered]
  (->> (tree-seq coll? seq rendered)
       (filter #(and (vector? %) (= :text.model-name (first %))))
       (mapv last)))

;; @tags factory resolution
(deftest components-without-artwork-show-their-known-name
  (doseq [renderer [editor/circuit-sym-box editor/circuit-sym-amplifier]]
    (testing "resolved display name is preferred"
      (is (= ["Optical filter"]
             (model-labels (render-component renderer {} {:name "Optical filter"} nil)))))
    (testing "known identity stays visible before metadata resolves"
      (is (= ["cells.Filter"] (model-labels (render-component renderer {} nil nil)))))
    (testing "blank names fall back to the identity"
      (doseq [name ["" "  \t"]]
        (is (= ["cells.Filter"] (model-labels (render-component renderer {} {:name name} nil))))))
    (testing "a component with no known identity still has the placeholder"
      (is (= ["?"] (model-labels (render-component renderer {:model nil} nil nil)))))))

;; @tags factory resolution
(deftest assigned-artwork-keeps-its-existing-rendering-path
  (let [entry {:name "Optical filter" :symbol "symbols/filter.svg"}
        rendered (render-component editor/circuit-sym-box {} entry "data:image/svg+xml;base64,PHN2Zy8+")
        images (filter #(and (vector? %) (= :image (first %))) (tree-seq coll? seq rendered))]
    (is (empty? (model-labels rendered)))
    (is (= ["data:image/svg+xml;base64,PHN2Zy8+"] (mapv #(get-in % [1 :href]) images))))
  (testing "unavailable artwork shows the name instead"
    (is (= ["Optical filter"]
           (model-labels (render-component editor/circuit-sym-box {}
                                           {:name "Optical filter" :symbol "symbols/filter.svg"} nil))))))
