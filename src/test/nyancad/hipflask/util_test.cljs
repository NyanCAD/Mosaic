; SPDX-FileCopyrightText: 2026 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.hipflask.util-test
  "Contract-driven tests for nyancad.hipflask.util — the small helper
   namespace extracted so non-PouchDB code (e.g. the VSCode extension host)
   can depend on it without pulling in the PouchDB JS bundle."
  (:require [cljs.test :refer [deftest is testing]]
            [nyancad.hipflask.util :as util]))

;; ---------------------------------------------------------------------------
;; json->clj
;; ---------------------------------------------------------------------------
;; Contract: walk a PouchDB response (or any JS value produced by
;; JSON.parse) and produce plain Clojure data. Use `keyword` on object
;; keys — but guard against advanced-compilation key mangling by
;; walking via Object.entries rather than js->clj. Only objects whose
;; constructor === js/Object are treated as maps; host objects (Date,
;; etc.) pass through unchanged.

(deftest json->clj-nil
  (testing "nil is the identity"
    (is (nil? (util/json->clj nil)))))

(deftest json->clj-primitives
  (testing "numbers, strings, booleans pass through unchanged"
    (is (= 42 (util/json->clj 42)))
    (is (= "hello" (util/json->clj "hello")))
    (is (= true (util/json->clj true)))
    (is (= false (util/json->clj false)))))

(deftest json->clj-array-of-primitives
  (testing "JS array of primitives becomes a CLJS vector"
    (let [out (util/json->clj #js [1 2 3])]
      (is (vector? out))
      (is (= [1 2 3] out)))))

(deftest json->clj-plain-object-keywordizes-keys
  (testing "plain object keys become keywords"
    (is (= {:a 1 :b 2} (util/json->clj #js {:a 1 :b 2})))))

(deftest json->clj-single-char-keys
  (testing "single-char keys survive (this is why json->clj exists — \n
            cljs.core/js->clj with :keywordize-keys true can mangle these
            under advanced compilation)"
    ;; Written as strings so the CLJS reader doesn't munge the key names
    (is (= {:a 1 :b 2 :x 3}
           (util/json->clj #js {"a" 1 "b" 2 "x" 3})))))

(deftest json->clj-nested
  (testing "objects inside arrays inside objects recurse fully"
    (is (= {:a {:b [1 2 {:c 3}]}}
           (util/json->clj #js {:a #js {:b #js [1 2 #js {:c 3}]}})))))

(deftest json->clj-non-plain-object-passes-through
  (testing "host objects (constructor !== js/Object) are returned as-is"
    ;; A Date has constructor === js/Date, which fails the js/Object check
    ;; in the `cond`, so it falls through to the `:else` branch. PouchDB
    ;; returns timestamps as plain strings/numbers in practice, but the
    ;; pass-through behavior matters for anything unexpected.
    (let [d (js/Date.)
          out (util/json->clj d)]
      (is (identical? d out)))))

;; ---------------------------------------------------------------------------
;; update-keys
;; ---------------------------------------------------------------------------
;; Contract: apply f to the values at a specific set of keys of m, leaving
;; every other key untouched. Differs from clojure.core/update-keys
;; (renames keys) and from reduce-kv (walks all keys). The 4+-arity form
;; passes extra args through to f, mirroring core/update.

(deftest update-keys-2arity-applies-f-to-named-keys
  (testing "single named key: f receives the current value, result is stored"
    (is (= {:a 2 :b 2} (util/update-keys {:a 1 :b 2} [:a] inc)))))

(deftest update-keys-2arity-leaves-other-keys-alone
  (testing "keys not in the list are not rewritten"
    (is (= {:a 10 :b 2 :c 3}
           (util/update-keys {:a 1 :b 2 :c 3} [:a] #(* % 10))))))

(deftest update-keys-variadic-passes-extra-args
  (testing "4-arg form: (f current extra...) mirrors core/update"
    (is (= {:a 11} (util/update-keys {:a 1} [:a] + 10)))
    (is (= {:a 6} (util/update-keys {:a 1} [:a] + 2 3)))))

(deftest update-keys-missing-key-applies-f-to-nil
  (testing "if the key is absent, f sees nil and the result is written back;
            this matches (get m k) semantics and is why a doseq would not
            substitute — update-keys deliberately installs a value even
            when the key did not previously exist"
    (is (= {:a 1 :b 1} (util/update-keys {:a 1} [:b] (fnil inc 0))))))

(deftest update-keys-multi-key
  (testing "every key in the list gets f applied independently"
    (is (= {:a 2 :b 3 :c 4}
           (util/update-keys {:a 1 :b 2 :c 3} [:a :b :c] inc)))))
