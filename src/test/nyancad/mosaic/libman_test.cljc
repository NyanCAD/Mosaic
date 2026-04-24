; SPDX-FileCopyrightText: 2026 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.libman-test
  "Contract-driven tests for the pure helpers in libman.cljc.

   Everything else in libman is either a Reagent component or a handler
   that mutates a module-level atom — out of scope for unit tests. The two
   pure helpers below drive the tag-filter/category UI."
  (:require [cljs.test :refer [deftest is testing]]
            [nyancad.mosaic.libman :as libman]))

;; ---------------------------------------------------------------------------
;; plain-tags
;; ---------------------------------------------------------------------------
;; Contract: the tag vector in libman's state mixes plain tags (single
;; strings like "IHP" or "analog") with property tags (colon-separated
;; key:value like "type:ckt"). plain-tags drops the property-tags so
;; downstream code — tag hierarchy rendering, AND-filtering by category —
;; can operate on the unambiguous ones only.

(deftest plain-tags-empty-input
  (testing "empty vector in, empty vector out"
    (is (= [] (libman/plain-tags [])))))

(deftest plain-tags-keeps-plain
  (testing "tags without a colon pass through unchanged"
    (is (= ["IHP" "analog"] (libman/plain-tags ["IHP" "analog"])))))

(deftest plain-tags-drops-prop-tags
  (testing "property tags (key:value form) are filtered out"
    (is (= ["IHP" "analog"]
           (libman/plain-tags ["IHP" "type:ckt" "analog"])))))

(deftest plain-tags-preserves-order
  (testing "surviving plain tags keep their original order"
    (is (= ["a" "b" "c"]
           (libman/plain-tags ["a" "x:1" "b" "y:2" "c"])))))

;; ---------------------------------------------------------------------------
;; prop-tag-matches?  (private — reach via var deref)
;; ---------------------------------------------------------------------------
;; Contract: given a tag from the selected-category vector and a model
;; document, return true iff the doc "satisfies" the tag. Plain tags
;; (no colon) always match — they only constrain via the modeldb's own
;; tag list, not the doc's fields. Property tags "k:v" match iff the doc
;; has field k whose stringified value equals v. Doc lookup uses
;; (keyword k) — so "type:ckt" looks at (:type doc).

(def ^:private prop-tag-matches? @#'libman/prop-tag-matches?)

(deftest prop-tag-plain-always-matches
  (testing "a plain tag matches every doc regardless of fields"
    (is (prop-tag-matches? "IHP" {}))
    (is (prop-tag-matches? "analog" {:type "resistor" :name "R1"}))))

(deftest prop-tag-matches-when-field-equals-value
  (testing "k:v tag matches when (str (get doc (keyword k))) = v"
    (is (prop-tag-matches? "type:ckt" {:type "ckt"}))
    (is (prop-tag-matches? "variant:hv" {:variant "hv" :other "x"}))))

(deftest prop-tag-rejects-mismatch
  (testing "k:v tag does not match when the field value differs"
    (is (not (prop-tag-matches? "type:ckt" {:type "resistor"})))))

(deftest prop-tag-missing-field-stringifies-to-empty-string
  (testing "if the doc has no field k, (str nil) = \"\" — so a missing
            field never matches a non-empty value, and 'type:' with an
            empty value string matches every doc that lacks :type"
    (is (not (prop-tag-matches? "type:ckt" {})))
    (is (not (prop-tag-matches? "type:ckt" {:name "foo"})))
    (is (prop-tag-matches? "type:" {}))))

(deftest prop-tag-non-string-fields-stringified
  (testing "non-string field values are compared via (str v), so numbers
            and keywords round-trip through their print representation"
    (is (prop-tag-matches? "n:42" {:n 42}))
    (is (prop-tag-matches? "k::foo" {:k :foo}))))
