; SPDX-FileCopyrightText: 2024 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.couchdb-test
  "Contract-driven tests for the CouchDB search view helpers."
  (:require [cljs.test :refer [deftest is testing]]
            [goog.object :as gobj]
            [nyancad.mosaic.couchdb :as couchdb]))

;; ---------------------------------------------------------------------------
;; generate-suffixes
;; ---------------------------------------------------------------------------
;; Contract: tokenize the input into runs of lowercase letters, digits, or
;; underscores. Build right-to-left cumulative suffixes so a CouchDB prefix
;; search matches at any word boundary. Drop any suffix beginning with an
;; underscore — users don't search for "_foo".
;;
;; The goal: a user typing any word-boundary prefix of a model name should
;; land on it. For "nmos_3p3" a user might type "nmos", "3p3", or "p3".

(deftest generate-suffixes-single-token
  (testing "a string with one token has one suffix (itself)"
    (is (= ["hello"] (vec (couchdb/generate-suffixes "hello"))))))

(deftest generate-suffixes-empty
  (testing "empty string → no suffixes"
    (is (= [] (vec (couchdb/generate-suffixes ""))))))

(deftest generate-suffixes-letter-digit-split
  (testing "letters and digits form separate tokens"
    ;; "opamp2" → tokens ["opamp" "2"]; reversed ["2" "opamp"];
    ;; reductions: "2", "opamp2". Both usable as search anchors.
    (is (= ["2" "opamp2"] (vec (couchdb/generate-suffixes "opamp2"))))))

(deftest generate-suffixes-underscore-split-and-filtered
  (testing "underscore tokens create boundaries but leading-underscore suffixes drop"
    ;; "nmos_3p3" → tokens ["nmos" "_" "3" "p" "3"]; reversed the other way;
    ;; reductions yield "3", "p3", "3p3", "_3p3", "nmos_3p3".
    ;; Filter out "_3p3" — user wouldn't start a search with "_".
    (is (= ["3" "p3" "3p3" "nmos_3p3"]
           (vec (couchdb/generate-suffixes "nmos_3p3"))))))

(deftest generate-suffixes-every-result-matches-original-as-suffix
  (testing "every emitted suffix is literally a suffix of the input string"
    (doseq [s ["foo" "foo_bar" "abc123def" "nmos_3p3" "a_b_c"]]
      (doseq [suffix (couchdb/generate-suffixes s)]
        (is (clojure.string/ends-with? s suffix)
            (str s " does not end with " suffix))))))

(deftest generate-suffixes-drops-leading-underscore-anywhere-it-happens
  (testing "a single-char leading underscore suffix is dropped"
    ;; "a_b" → tokens ["a" "_" "b"]; reductions: "b", "_b", "a_b".
    ;; Drop "_b".
    (is (= ["b" "a_b"] (vec (couchdb/generate-suffixes "a_b"))))))

;; ---------------------------------------------------------------------------
;; name-search-view
;; ---------------------------------------------------------------------------
;; Contract: a CouchDB map function. For a doc whose _id starts with
;; "models:", emit one (key, value) pair per suffix of the lower-cased
;; :name. The value is a JS object `{_id, name, type}`. Non-model docs
;; (anything else) emit nothing.
;;
;; We stub out `js/emit` by rebinding it to a function that pushes onto a
;; local vector, so we can inspect what was emitted.

(defn- with-captured-emits
  "Install a fake `emit` on the global object so name-search-view's
   call to `js/emit` lands in our capture vector. Runs `f`, returns the
   captured vector, restores any previous `emit` binding."
  [f]
  (let [emitted (atom [])
        prev (gobj/get js/global "emit")]
    (gobj/set js/global "emit" (fn [k v] (swap! emitted conj [k v])))
    (try (f) @emitted
         (finally (gobj/set js/global "emit" prev)))))

(deftest name-search-view-skips-non-model-docs
  (testing "a doc whose _id is not under models: emits nothing"
    (let [emits (with-captured-emits
                  #(couchdb/name-search-view
                     #js {:_id "schem:R1" :name "R1" :type "resistor"}))]
      (is (empty? emits)))))

(deftest name-search-view-emits-one-per-suffix
  (testing "a model doc emits every suffix of its lower-cased name as a key"
    (let [emits (with-captured-emits
                  #(couchdb/name-search-view
                     #js {:_id "models:nmos-ihp" :name "NMOS_3p3" :type "nmos"}))
          keys (set (map first emits))]
      (testing "keys come from (generate-suffixes (lower-case name))"
        ;; "nmos_3p3" → ("3" "p3" "3p3" "nmos_3p3")
        (is (= #{"3" "p3" "3p3" "nmos_3p3"} keys)))
      (testing "values carry the _id, name, and type as a JS object"
        (let [[_ ^js v] (first emits)]
          (is (= "models:nmos-ihp" (.-_id v)))
          (is (= "NMOS_3p3" (.-name v)))
          (is (= "nmos" (.-type v))))))))

(deftest name-search-view-handles-missing-name
  (testing "a model doc with no name emits nothing (empty string → empty suffixes)"
    (let [emits (with-captured-emits
                  #(couchdb/name-search-view
                     #js {:_id "models:abc"}))]
      (is (empty? emits)))))

(deftest name-search-view-lowercases-name-for-search
  (testing "search keys are lower-cased so case doesn't matter"
    (let [emits (with-captured-emits
                  #(couchdb/name-search-view
                     #js {:_id "models:x" :name "FooBar"}))
          keys (set (map first emits))]
      ;; "foobar" → single token, only suffix is "foobar"
      (is (contains? keys "foobar"))
      (is (not (contains? keys "FooBar"))))))
