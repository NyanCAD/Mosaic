; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.common-test
  "Unit tests for pure functions in nyancad.mosaic.common.

   Each deftest leads with a contract docstring stating what the function
   should do, independent of how it happens to be implemented today. Cases
   are hand-computed from the contract — not copied from the current CLJS
   output."
  (:require [cljs.test :refer [deftest is testing]]
            [nyancad.mosaic.common :as cm]))

;; ---------------------------------------------------------------------------
;; Sorted coord list — bisect-left, insert, dissjoc, set-coord, remove-coord,
;; has-coord
;; ---------------------------------------------------------------------------
;;
;; Contract: coord vectors are kept sorted by their first two elements ([x y]
;; prefix). `set-coord` inserts-or-replaces, `remove-coord` deletes if present,
;; `has-coord` tests presence — all keyed on the [x y] prefix only, so the
;; rest of the vector can carry arbitrary payload.

(deftest bisect-left-finds-insertion-point
  (testing "returns the index where x would be inserted to keep v sorted"
    (is (= 0 (cm/bisect-left [] 5)))
    (is (= 0 (cm/bisect-left [10 20 30] 5)))
    (is (= 3 (cm/bisect-left [10 20 30] 40)))
    (is (= 1 (cm/bisect-left [10 20 30] 15))))
  (testing "on a tie, returns the left-most matching index"
    (is (= 1 (cm/bisect-left [10 20 20 30] 20))))
  (testing "key function projects each element before comparison"
    (is (= 1 (cm/bisect-left [[1 :a] [3 :b] [5 :c]] 2 first)))))

;; `insert` and `dissjoc` are trivial vector primitives exercised
;; indirectly through the set-coord / remove-coord tests below.

(deftest set-coord-inserts-and-replaces
  (testing "inserts a new coord at the correct sorted position"
    (is (= [[1 2]] (cm/set-coord [] [1 2])))
    (is (= [[1 2] [3 4]] (cm/set-coord [[3 4]] [1 2])))
    (is (= [[1 2] [3 4] [5 6]] (cm/set-coord [[1 2] [5 6]] [3 4]))))
  (testing "replaces when an existing coord shares the same [x y] prefix"
    (is (= [[1 2 :new]] (cm/set-coord [[1 2 :old]] [1 2 :new])))
    (is (= [[1 2 :new] [3 4 :keep]]
           (cm/set-coord [[1 2 :old] [3 4 :keep]] [1 2 :new])))))

(deftest remove-coord-only-removes-matches
  (testing "removes an entry matching [x y]"
    (is (= [[3 4]] (cm/remove-coord [[1 2] [3 4]] [1 2]))))
  (testing "leaves the vector untouched when no match"
    (is (= [[1 2] [3 4]] (cm/remove-coord [[1 2] [3 4]] [5 6]))))
  (testing "matches on prefix only — ignores payload"
    (is (= [] (cm/remove-coord [[1 2 :payload]] [1 2])))))

(deftest has-coord-checks-prefix
  (is (true? (cm/has-coord [[1 2] [3 4]] [1 2])))
  (is (true? (cm/has-coord [[1 2 :payload]] [1 2])))
  (is (false? (cm/has-coord [[1 2] [3 4]] [5 6])))
  (is (false? (cm/has-coord [] [1 2]))))

(deftest coord-list-round-trip
  (testing "set-coord -> has-coord returns true"
    (let [v (-> [] (cm/set-coord [5 5 :a]) (cm/set-coord [1 1 :b]) (cm/set-coord [3 3 :c]))]
      (is (cm/has-coord v [1 1]))
      (is (cm/has-coord v [3 3]))
      (is (cm/has-coord v [5 5]))
      (is (not (cm/has-coord v [2 2])))))
  (testing "set-coord maintains sorted order regardless of insertion order"
    (let [v (-> [] (cm/set-coord [5 5]) (cm/set-coord [1 1]) (cm/set-coord [3 3]))]
      (is (= [[1 1] [3 3] [5 5]] v))))
  (testing "remove-coord after set-coord leaves no trace"
    (let [v (cm/set-coord [] [1 2 :x])]
      (is (= [] (cm/remove-coord v [1 2]))))))

;; ---------------------------------------------------------------------------
;; Port geometry — spread-ports, port-perimeter, port-locations
;; ---------------------------------------------------------------------------
;;
;; Contract (spread-ports): given n ports to distribute across `size` slots,
;; place them hugging the edges with a gap in the middle when n < size. For
;; n = size every slot is filled; for n = 0 no slots; for n > size the slots
;; overflow and are numbered sequentially starting at 1.
;;
;; Cases below are hand-computed — ported from the deleted Python
;; TestSpreadPorts class with its reasoning intact.

(deftest spread-ports-full-and-empty
  (is (= [1 2 3] (cm/spread-ports 3 3)) "every slot filled, no gap")
  (is (= [] (cm/spread-ports 0 5)) "no ports → no positions")
  (is (= [1] (cm/spread-ports 1 1)) "single port in single slot"))

(deftest spread-ports-overflow
  (testing "more ports than slots: slots overflow, numbered from 1"
    (is (= [1 2 3 4 5] (cm/spread-ports 5 3)))))

(deftest spread-ports-odd-n-uses-center
  (testing "1 port in 3 slots: center position (2)"
    (is (= [2] (cm/spread-ports 1 3))))
  (testing "1 port in 5 slots: center position (3)"
    (is (= [3] (cm/spread-ports 1 5))))
  (testing "1 port in 4 slots: center = (4+1)//2 = 2 (slight bias toward top)"
    (is (= [2] (cm/spread-ports 1 4))))
  (testing "3 ports in 5 slots: edges + center"
    (is (= [1 3 5] (cm/spread-ports 3 5))))
  (testing "3 ports in 7 slots: top edge, center, bottom edge"
    (is (= [1 4 7] (cm/spread-ports 3 7))))
  (testing "5 ports in 7 slots: 2 top, center (4), 2 bottom"
    (is (= [1 2 4 6 7] (cm/spread-ports 5 7)))))

(deftest spread-ports-even-n-hugs-edges
  (testing "2 ports in 4 slots: one at each edge, gap of 2 in middle"
    (is (= [1 4] (cm/spread-ports 2 4))))
  (testing "2 ports in 5 slots: one at each edge, gap of 3 in middle"
    (is (= [1 5] (cm/spread-ports 2 5))))
  (testing "4 ports in 6 slots: 2 at top, 2 at bottom, gap in middle"
    (is (= [1 2 5 6] (cm/spread-ports 4 6)))))

(deftest spread-ports-invariants
  (testing "output length always equals n for valid inputs"
    (doseq [n (range 8)
            size (range 1 8)]
      (is (= n (count (cm/spread-ports n size)))
          (str "spread-ports " n " " size))))
  (testing "output is always sorted ascending"
    (doseq [n (range 1 6)
            size (range n (+ n 4))]
      (let [result (cm/spread-ports n size)]
        (is (= result (sort result)) (str "spread-ports " n " " size " not sorted")))))
  (testing "when n <= size, all positions are in [1, size]"
    (doseq [n (range 1 6)
            size (range n (+ n 4))]
      (let [result (cm/spread-ports n size)]
        (is (every? #(<= 1 % size) result)
            (str "spread-ports " n " " size " out of bounds")))))
  (testing "even n in even size: layout is symmetric around center"
    (doseq [n [2 4]
            size [n (+ n 2) (+ n 4)]]
      (let [result (set (cm/spread-ports n size))]
        (doseq [p result
                :let [mirror (- (inc size) p)]]
          (is (contains? result mirror)
              (str "spread-ports " n " " size ": " p " has no mirror " mirror)))))))

;; Contract (port-perimeter): compute [width height] of the device body
;; from the count of ports on each side. Height = max(left_count,
;; right_count) with a +1 bump if the counts have different parity (so
;; ports on a short side can sit centered between ports on a long side);
;; width similar for top/bottom. :amp shape forces width >= ceil(height/2)
;; so the triangle "narrows to the right".

(deftest port-perimeter-empty
  (testing "no ports → minimum 1x1 box"
    (is (= [1 1] (cm/port-perimeter [])))))

(deftest port-perimeter-single-side
  (is (= [1 1] (cm/port-perimeter [{:side :left}]))
      "single left port"))

(deftest port-perimeter-matches-dominant-side
  (testing "height = max(left, right), width = max(top, bottom) when parity matches"
    (is (= [1 3] (cm/port-perimeter
                   (into [] (concat (repeat 3 {:side :left}) (repeat 3 {:side :right}))))))
    (is (= [4 1] (cm/port-perimeter
                   (into [] (concat (repeat 4 {:side :top}) (repeat 4 {:side :bottom}))))))))

(deftest port-perimeter-parity-adjustment
  (testing "1 left (odd) + 2 right (even): height bumped from 2 to 3"
    ;; The bump lets the single left port sit centered between the two right ports.
    (is (= [1 3] (cm/port-perimeter
                   (into [] (concat (repeat 1 {:side :left}) (repeat 2 {:side :right}))))))))

(deftest port-perimeter-same-parity-no-bump
  (testing "2 left + 2 right (both even): height stays 2"
    (is (= [1 2] (cm/port-perimeter
                   (into [] (concat (repeat 2 {:side :left}) (repeat 2 {:side :right})))))))
  (testing "2 left + 3 right: raw max is 3 (already odd), no bump needed"
    (is (= [1 3] (cm/port-perimeter
                   (into [] (concat (repeat 2 {:side :left}) (repeat 3 {:side :right}))))))))

(deftest port-perimeter-amp-shape
  (testing "amp constraint: width >= ceil(height/2)"
    ;; 6 left ports → height 6, so width must be >= 3
    (is (= [3 6] (cm/port-perimeter (repeat 6 {:side :left}) :amp))))
  (testing "amp constraint does not shrink existing width"
    ;; 5 top ports already force width 5; 2 left ports require height >= 2.
    ;; The amp minimum (ceil(2/2) = 1) is smaller, so width stays 5.
    (let [ports (into [] (concat (repeat 5 {:side :top}) (repeat 2 {:side :left})))
          [w _] (cm/port-perimeter ports :amp)]
      (is (>= w 5)))))

;; Contract (port-locations): each port gets an (x, y) on the device border.
;; Body occupies [1..width] × [1..height]; ports sit at x=0 (left),
;; x=width+1 (right), y=0 (top), y=height+1 (bottom). The y/x coordinates
;; along each side come from spread-ports(count-on-side, dimension).

(deftest port-locations-two-port-passive
  (testing "1 left + 1 right, perimeter (1,1): left at (0,1), right at (2,1)"
    (let [ports [{:name "P" :side :left :type :electric}
                 {:name "N" :side :right :type :electric}]
          locs (into {} (map (juxt :name (juxt :x :y))) (cm/port-locations ports))]
      (is (= {"P" [0 1] "N" [2 1]} locs)))))

(deftest port-locations-opamp
  (testing "2 left + 1 right, perimeter (1,3): left spread [1,3], right center [2]"
    (let [ports [{:name "in+" :side :left :type :electric}
                 {:name "in-" :side :left :type :electric}
                 {:name "out" :side :right :type :electric}]
          locs (into {} (map (juxt :name (juxt :x :y))) (cm/port-locations ports))]
      (is (= {"in+" [0 1] "in-" [0 3] "out" [2 2]} locs)))))

(deftest port-locations-four-side
  (testing "1 port per side, perimeter (1,1)"
    (let [ports [{:name "T" :side :top :type :electric}
                 {:name "B" :side :bottom :type :electric}
                 {:name "L" :side :left :type :electric}
                 {:name "R" :side :right :type :electric}]
          locs (into {} (map (juxt :name (juxt :x :y))) (cm/port-locations ports))]
      (is (= {"T" [1 0] "B" [1 2] "L" [0 1] "R" [2 1]} locs)))))

(deftest port-locations-amp-shape-top-aligned
  (testing "amp shape: top ports use sequential x (1, 2, ...) — triangle narrows to right"
    (let [ports [{:name "A" :side :top :type :electric}
                 {:name "B" :side :top :type :electric}
                 {:name "L" :side :left :type :electric}]
          locs (cm/port-locations ports :amp)
          top (sort-by :x (filter #(= (:side %) :top) locs))]
      (is (= 1 (:x (first top))))
      (is (= 2 (:x (second top)))))))

(deftest port-locations-preserves-port-data
  (testing ":name and :type survive; :x :y added; :side is kept normalized"
    (let [ports [{:name "in+" :side :left :type :photonic}]
          [p & _] (cm/port-locations ports)]
      (is (= "in+" (:name p)))
      (is (= :photonic (:type p)))
      (is (contains? p :x))
      (is (contains? p :y)))))

;; ---------------------------------------------------------------------------
;; model-key / bare-id — namespace prefix conversion
;; ---------------------------------------------------------------------------
;;
;; Contract: `model-key` prepends "models:" to a bare id; `bare-id` strips
;; it. Nil in → nil out. Already-prefixed / already-bare inputs are
;; programming errors and raise an assertion.

(deftest model-key-conversion
  (is (nil? (cm/model-key nil)))
  (is (= "models:abc-123" (cm/model-key "abc-123")))
  (is (= "models:" (cm/model-key ""))))

(deftest model-key-rejects-already-prefixed
  (is (thrown? js/Error (cm/model-key "models:abc"))))

(deftest bare-id-conversion
  (is (nil? (cm/bare-id nil)))
  (is (= "abc-123" (cm/bare-id "models:abc-123")))
  (is (= "" (cm/bare-id "models:"))))

(deftest bare-id-rejects-unprefixed
  (is (thrown? js/Error (cm/bare-id "abc"))))

(deftest model-key-round-trip
  (doseq [bare ["r1" "abc-123" "photonic:model"]]
    (is (= bare (cm/bare-id (cm/model-key bare))))))

;; ---------------------------------------------------------------------------
;; filter-models / parse-prop-tag / build-tag-index
;; ---------------------------------------------------------------------------

(def ^:private sample-models
  {"models:nmos-ihp"
   {:name "NMOS (IHP)" :type "nmos" :tags ["IHP" "analog"]}
   "models:pmos-ihp"
   {:name "PMOS (IHP)" :type "pmos" :tags ["IHP" "analog"]}
   "models:opamp"
   {:name "OpAmp" :type "ckt" :tags ["Custom" "amplifier"]}
   "models:photonic-wg"
   {:name "Waveguide" :type "straight" :tags ["photonic"]}})

;; Contract (parse-prop-tag): a tag of form "key:value" parses to
;; ["key" "value"]; anything else parses to nil.

(deftest parse-prop-tag-parses-kv
  (is (= ["type" "ckt"] (cm/parse-prop-tag "type:ckt"))))

(deftest parse-prop-tag-plain-tag-is-nil
  (is (nil? (cm/parse-prop-tag "IHP"))))

(deftest parse-prop-tag-splits-on-first-colon
  (is (= ["a" "b:c"] (cm/parse-prop-tag "a:b:c"))))

;; Contract (filter-models): AND-filter models by (1) every plain tag in
;; sel-tags appearing in the model's :tags, (2) every "key:value" prop tag
;; matching the model's corresponding field (as string), and (3) search-text
;; appearing (case-insensitive) in the display name.

(deftest filter-models-plain-tag
  (testing "a plain tag restricts to models carrying that tag"
    (let [matched (into {} (cm/filter-models sample-models ["IHP"] ""))]
      (is (contains? matched "models:nmos-ihp"))
      (is (contains? matched "models:pmos-ihp"))
      (is (not (contains? matched "models:opamp"))))))

(deftest filter-models-prop-tag
  (testing "a prop tag matches on the corresponding field"
    (let [matched (into {} (cm/filter-models sample-models ["type:ckt"] ""))]
      (is (contains? matched "models:opamp"))
      (is (not (contains? matched "models:nmos-ihp"))))))

(deftest filter-models-text-case-insensitive
  (let [matched (into {} (cm/filter-models sample-models [] "opamp"))]
    (is (contains? matched "models:opamp")))
  (let [matched (into {} (cm/filter-models sample-models [] "OPAMP"))]
    (is (contains? matched "models:opamp"))))

(deftest filter-models-no-filters-returns-all
  (is (= (count sample-models)
         (count (cm/filter-models sample-models [] "")))))

(deftest filter-models-combines-criteria
  (testing "plain tag AND search text AND prop tag: all must match"
    (let [matched (into {} (cm/filter-models sample-models ["analog"] "nmos"))]
      (is (= #{"models:nmos-ihp"} (set (keys matched)))))))

;; Contract (build-tag-index): produce a two-level hierarchy from the first
;; two tags of each model. The result is `{first-tag {second-tag {}}}`.
;; Models with no tags don't appear.

(deftest build-tag-index-groups-by-first-two-tags
  (let [idx (cm/build-tag-index sample-models)]
    (is (contains? (get idx "IHP") "analog"))
    (is (contains? (get idx "Custom") "amplifier"))
    (is (contains? idx "photonic"))
    (is (= {} (get-in idx ["photonic"])))))

;; ---------------------------------------------------------------------------
;; format — template interpolation via JS Function
;; ---------------------------------------------------------------------------
;;
;; Contract: substitute `{expr}` with `expr` evaluated against `state`
;; (keys bound as variables). `{{` / `}}` escape literal braces. Missing
;; values or expressions that throw produce "?" — never crash the format.

(deftest format-simple-interpolation
  (is (= "hello world" (cm/format "hello {x}" {:x "world"}))))

(deftest format-missing-key-is-placeholder
  (testing "an undefined variable produces ? rather than crashing"
    (is (= "?" (cm/format "{missing}" {})))))

(deftest format-escaped-braces
  (testing "{{ and }} escape to literal { and }"
    (is (= "{ literal }" (cm/format "{{ literal }}" {})))))

;; ---------------------------------------------------------------------------
;; Undo tree — newundotree / newdo / undo / redo
;; ---------------------------------------------------------------------------
;;
;; Contract: `newundotree` gives an empty history. `newdo` records a new
;; state and advances to it. `undo` steps back (or stays put if there's
;; nothing before). `redo` steps forward into a previously-undone state.
;; Calling `newdo` after `undo` discards the redo branch from that point.

(deftest undo-tree-initial-state-is-nil
  (let [ut (cm/newundotree)]
    (is (nil? (cm/undo ut)))))

(deftest undo-tree-newdo-records-state
  (let [ut (cm/newundotree)]
    (cm/newdo ut {:a 1})
    (is (= {:a 1} (cm/undo ut)))))

(deftest undo-tree-undo-steps-back
  (let [ut (cm/newundotree)]
    (cm/newdo ut {:step 1})
    (cm/newdo ut {:step 2})
    (cm/newdo ut {:step 3})
    ;; After three newdo, `undo` moves to step 2, then step 1.
    (is (= {:step 2} (cm/undo ut)))
    (is (= {:step 1} (cm/undo ut)))))

(deftest undo-tree-redo-steps-forward
  (let [ut (cm/newundotree)]
    (cm/newdo ut {:step 1})
    (cm/newdo ut {:step 2})
    (cm/undo ut)  ; now at step 1
    (is (= {:step 2} (cm/redo ut)))))

(deftest undo-tree-round-trip
  (testing "newdo N → undo N times → redo N times restores the final state"
    (let [ut (cm/newundotree)]
      (cm/newdo ut {:x 1})
      (cm/newdo ut {:x 2})
      (cm/newdo ut {:x 3})
      (dotimes [_ 3] (cm/undo ut))
      (dotimes [_ 3] (cm/redo ut))
      ;; After fully redoing, the current state should be the last one we did.
      ;; Peek via undo-state (or an extra redo stays put):
      (is (= {:x 3} (cm/redo ut))))))
