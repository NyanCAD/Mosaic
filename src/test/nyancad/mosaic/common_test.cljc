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
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.common :as cm]))

;; ---------------------------------------------------------------------------
;; Radio groups
;; ---------------------------------------------------------------------------

;; @tags mosaic radio groups
(deftest radiobuttons-expose-group-and-accessible-names
  (let [selected (atom :alpha)
        view (cm/radiobuttons "test-group" selected
                              [["Alpha" :alpha "Choose alpha"]
                               ["Beta" :beta "Choose beta"]])
        inputs (map #(nth % 2) (second view))
        attrs (map second inputs)]
    (testing "every option belongs to the explicit named group"
      (is (= ["test-group" "test-group"] (map :name attrs))))
    (testing "tooltips provide concise accessible names"
      (is (= ["Choose alpha" "Choose beta"] (map :aria-label attrs))))
    (testing "the selected value controls checked state"
      (is (= [true false] (map :checked attrs))))
    (testing "the default handler updates the cursor"
      ((:on-change (second attrs)) nil)
      (is (= :beta @selected)))))

;; @tags mosaic radio groups
(deftest radiobuttons-delegate-custom-change-handler
  (let [selected (atom :alpha)
        changed (atom nil)
        view (cm/radiobuttons "test-group" selected
                              [["Beta" :beta "Choose beta"]]
                              nil nil #(reset! changed %))
        attrs (-> view second first (nth 2) second)]
    ((:on-change attrs) nil)
    (is (= :beta @changed))
    (is (= :alpha @selected) "custom handlers own the state update")))

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

;; Contract (spread-ports alignment): :start packs ports toward position 1,
;; :end packs toward position size. n > size overflow is the same regardless.

(deftest spread-ports-start-alignment
  (testing ":start packs ports toward position 1"
    (is (= [1] (cm/spread-ports 1 3 :start)))
    (is (= [1 2] (cm/spread-ports 2 5 :start)))
    (is (= [1 2 3] (cm/spread-ports 3 3 :start)) "n = size: same as default")))

(deftest spread-ports-end-alignment
  (testing ":end packs ports toward position size"
    (is (= [3] (cm/spread-ports 1 3 :end)))
    (is (= [4 5] (cm/spread-ports 2 5 :end)))
    (is (= [2] (cm/spread-ports 1 2 :end)))
    (is (= [1 2 3] (cm/spread-ports 3 3 :end)) "n = size: same as default")))

(deftest spread-ports-alignment-overflow
  (testing "n > size with alignment: same overflow as default"
    (is (= [1 2 3 4] (cm/spread-ports 4 2 :start)))
    (is (= [1 2 3 4] (cm/spread-ports 4 2 :end)))))

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

(deftest port-perimeter-amp-type
  (testing "amp min-dims [1 2] ensures minimum height"
    (is (= [1 2] (cm/port-perimeter [{:side :left}] "amp"))
        "1 left port: width 1, height bumped from 1 to 2 by min-dims"))
  (testing "amp with many left ports: min-dims don't shrink"
    (is (= [1 6] (cm/port-perimeter (repeat 6 {:side :left}) "amp"))
        "6 left ports: height 6 > min 2, width stays 1")))

(deftest port-perimeter-type-driven-min-dims
  (testing "ring-single min-dims [1 2]"
    (is (= [1 2] (cm/port-perimeter [{:side :left} {:side :right}] "ring-single"))
        "1L+1R: raw [1 1], min-dims bumps height to 2"))
  (testing "sbend min-dims [1 2]"
    (is (= [1 2] (cm/port-perimeter [{:side :left} {:side :right}] "sbend"))))
  (testing "mmi-2x2 min-dims [1 3]"
    (is (= [1 3] (cm/port-perimeter (into [] (concat (repeat 2 {:side :left})
                                                      (repeat 2 {:side :right}))) "mmi-2x2"))
        "2L+2R: raw [1 2], min-dims bumps height to 3"))
  (testing "unknown type: no min-dims, default behavior"
    (is (= [1 1] (cm/port-perimeter [{:side :left} {:side :right}] "resistor")))))

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

(deftest port-locations-amp-type-top-aligned
  (testing "amp type: top ports use :start alignment (1, 2, ...)"
    (let [ports [{:name "A" :side :top :type :electric}
                 {:name "B" :side :top :type :electric}
                 {:name "L" :side :left :type :electric}]
          locs (cm/port-locations ports "amp")
          top (sort-by :x (filter #(= (:side %) :top) locs))]
      (is (= 1 (:x (first top))))
      (is (= 2 (:x (second top)))))))

(deftest port-locations-type-driven-alignment
  (testing "ring-single: left :end, right :end — ports at bottom"
    (let [ports [{:name "o1" :side :left :type "photonic"}
                 {:name "o2" :side :right :type "photonic"}]
          locs (into {} (map (juxt :name (juxt :x :y))) (cm/port-locations ports "ring-single"))]
      (is (= {"o1" [0 2] "o2" [2 2]} locs)
          "both ports at y=2 (bottom of height=2 box)")))
  (testing "sbend: left :start, right :end — staggered ports"
    (let [ports [{:name "o1" :side :left :type "photonic"}
                 {:name "o2" :side :right :type "photonic"}]
          locs (into {} (map (juxt :name (juxt :x :y))) (cm/port-locations ports "sbend"))]
      (is (= {"o1" [0 1] "o2" [2 2]} locs)
          "left at top, right at bottom")))
  (testing "mmi-2x2: default spread with min-dims [1 3]"
    (let [ports [{:name "o1" :side :left :type "photonic"}
                 {:name "o2" :side :right :type "photonic"}
                 {:name "o3" :side :left :type "photonic"}
                 {:name "o4" :side :right :type "photonic"}]
          locs (into {} (map (juxt :name (juxt :x :y))) (cm/port-locations ports "mmi-2x2"))]
      (is (= [0 1] (get locs "o1")))
      (is (= [0 3] (get locs "o3")))
      (is (= "gap at y=2 between left port pairs"
             "gap at y=2 between left port pairs")))))

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
;; model-prop-defaults — extract default prop values from a model definition
;; ---------------------------------------------------------------------------
;;
;; Contract: given a model definition with :props, return a map of
;; {prop-name → default-value} for props that carry a non-nil :default. nil
;; model-def → nil. Empty :props → nil. Props missing :name are skipped. Props
;; without a :default (or :default nil) are omitted entirely — they must not be
;; materialized as empty overrides into the device doc.

(deftest model-prop-defaults-nil-model
  (is (nil? (cm/model-prop-defaults nil))))

(deftest model-prop-defaults-empty-props
  (is (nil? (cm/model-prop-defaults {:props []}))))

(deftest model-prop-defaults-with-named-props
  (is (= {:length "10"}
         (cm/model-prop-defaults {:props [{:name "length" :default "10"}
                                          {:name "width"}]}))))

(deftest model-prop-defaults-skips-nameless
  (is (= {:a "1"}
         (cm/model-prop-defaults {:props [{:name "a" :default "1"}
                                          {:tooltip "orphan"}]}))))

(deftest model-prop-defaults-nil-default-excluded
  (is (nil? (cm/model-prop-defaults {:props [{:name "x" :default nil}]}))))

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

;; ---------------------------------------------------------------------------
;; visible-props — filter a props vector by port variant
;; ---------------------------------------------------------------------------
;;
;; Contract: a parameter without a :variants key is always visible; a parameter
;; with a :variants set is visible only when the given variant string is a
;; member of that set. The function preserves order and returns a vector.

(deftest visible-props-filters-by-variant
  (testing "a prop with no :variants is always visible"
    (is (= [{:name "a"}] (cm/visible-props [{:name "a"}] "emitter")))
    (is (= [{:name "a"}] (cm/visible-props [{:name "a"}] nil))))
  (testing "a prop with :variants shows only when variant is a member"
    (let [emitter-props [{:name "wl" :variants #{"emitter"}}]]
      (is (= emitter-props (cm/visible-props emitter-props "emitter")))
      (is (= [] (cm/visible-props emitter-props "detector")))
      (is (= [] (cm/visible-props emitter-props nil)))))
  (testing "mixed list keeps unscoped and matching-variant props in order"
    (let [props [{:name "name"} {:name "wl" :variants #{"emitter"}}]]
      (is (= props (cm/visible-props props "emitter")))
      (is (= [{:name "name"}] (cm/visible-props props "detector"))))))

;; @tags property editing
(deftest visible-props-keeps-tagged-enum-cases
  (testing "a tagged-enum field's :cases is not mistaken for port-variant scoping"
    (let [tran {:name "tran" :type :tagged-enum :tag-key "type"
                :cases [{:value "sin"} {:value "pulse"}]}]
      (is (= [tran] (cm/visible-props [tran] nil)))
      (is (= [tran] (cm/visible-props [tran] "emitter"))))))

;; ---------------------------------------------------------------------------
;; World-frame flips — flip-x, flip-y
;; ---------------------------------------------------------------------------
;;
;; Contract: `flip-x`/`flip-y` mirror a transform about the screen's
;; vertical/horizontal axis by *pre-multiplying* the reflection, so the flip
;; axis does not "rotate" with the device. A rotation of 90° is the matrix
;; [0 1 -1 0 0 0]; a local-frame (post-multiply) flip of that rotation would
;; give [0 -1 -1 0 0 0], which these tests reject. `flip-x`/`flip-y`/`rotate-cw`
;; operate on DOMMatrix values; `via-mat` threads a `[a b c d e f]` vector
;; through a DOMMatrix and back via `cm/transform-vec` so the assertions read in
;; transform-vector form.

(defn- via-mat [mat-fn v]
  (cm/transform-vec (mat-fn (js/DOMMatrixReadOnly. (clj->js v)))))

;; @tags editor flip transform composition, photonic model ports
(deftest wire-transform-produces-a-composable-dommatrix
  (testing "a wire-format matrix can be multiplied with another DOMMatrix"
    (let [centered (-> (js/DOMMatrixReadOnly.)
                       (.translate 75 75)
                       (.multiply (cm/transform [-1 0 0 1 0 0]))
                       (.translate -75 -75))]
      (is (= [-1 0 0 1 150 0] (cm/transform-vec centered))))))

(deftest flip-x-mirrors-about-vertical-axis
  (testing "identity flips to diag(-1, 1)"
    (is (= [-1 0 0 1 0 0] (via-mat cm/flip-x [1 0 0 1 0 0]))))
  (testing "pre-multiplies (world frame), so rotate-90 then flip-x is world-mirrored"
    (is (= [0 1 1 0 0 0] (via-mat cm/flip-x [0 1 -1 0 0 0])))
    ;; Not the local-frame (post-multiply) result:
    (is (not= [0 -1 -1 0 0 0] (via-mat cm/flip-x [0 1 -1 0 0 0]))))
  (testing "negates the x column including translation"
    (is (= [-1 0 0 1 -10 20] (via-mat cm/flip-x [1 0 0 1 10 20]))))
  (testing "applying the same flip twice is the identity"
    (is (= [0 1 -1 0 0 0]
           (via-mat cm/flip-x (via-mat cm/flip-x [0 1 -1 0 0 0]))))))

(deftest flip-y-mirrors-about-horizontal-axis
  (testing "identity flips to diag(1, -1)"
    (is (= [1 0 0 -1 0 0] (via-mat cm/flip-y [1 0 0 1 0 0]))))
  (testing "pre-multiplies (world frame) for a rotated transform"
    (is (= [0 -1 -1 0 0 0] (via-mat cm/flip-y [0 1 -1 0 0 0]))))
  (testing "negates the y column including translation"
    (is (= [1 0 0 -1 10 -20] (via-mat cm/flip-y [1 0 0 1 10 20]))))
  (testing "applying the same flip twice is the identity"
    (is (= [0 1 -1 0 0 0]
           (via-mat cm/flip-y (via-mat cm/flip-y [0 1 -1 0 0 0]))))))

(defn- round6 [v] (mapv #(/ (js/Math.round (* 1e6 %)) 1e6) v))

(deftest rotate-cw-is-world-frame
  (testing "identity rotates 90° clockwise"
    (is (= [0 1 -1 0 0 0] (round6 (via-mat cm/rotate-cw [1 0 0 1 0 0])))))
  (testing "pre-multiplies (world frame), so it stays clockwise even when the
            transform is flipped — the flip+rotate 'backwards' bug"
    (is (= [0 -1 -1 0 0 0] (round6 (via-mat cm/rotate-cw [-1 0 0 1 0 0]))))
    ;; Not the local-frame (post-multiply) result, which reverses under a mirror:
    (is (not= [0 1 1 0 0 0] (round6 (via-mat cm/rotate-cw [-1 0 0 1 0 0])))))
  (testing "clockwise then counter-clockwise returns to identity"
    (is (= [1 0 0 1 0 0]
           (round6 (via-mat cm/rotate-ccw (via-mat cm/rotate-cw [1 0 0 1 0 0])))))))

(deftest rotate-ccw-is-world-frame
  (testing "identity rotates 90° counter-clockwise"
    (is (= [0 -1 1 0 0 0] (round6 (via-mat cm/rotate-ccw [1 0 0 1 0 0])))))
  (testing "pre-multiplies (world frame) even when flipped"
    (is (= [0 1 1 0 0 0] (round6 (via-mat cm/rotate-ccw [-1 0 0 1 0 0]))))))

;; ---------------------------------------------------------------------------
;; transform-direction — port doc transform → hierarchy attachment side
;; ---------------------------------------------------------------------------
;; Contract: `transform-direction` maps a port doc's :transform to the side of
;; the parent box the port attaches to one level up, by transforming the local
;; +x axis (1,0) and reading which screen axis dominates. identity (the
;; emitter's placement) → :left (port on the device's right, sitting on the
;; left edge of the parent); a horizontal flip [-1 0 0 1 0 0] (the detector's
;; add-time placement) → :right (port mirrored to the device's left, sitting on
;; the right edge of the parent). A nil :transform defaults to IV (identity).
;; This is the mapping the detector fix relies on so its visual port-on-the-left
;; agrees with the hierarchy side it reports.

(deftest transform-direction-maps-port-to-parent-side
  (testing "identity transform → :left"
    (is (= :left (cm/transform-direction {:transform [1 0 0 1 0 0]}))))
  (testing "horizontal flip (add-detector placement) → :right"
    (is (= :right (cm/transform-direction {:transform [-1 0 0 1 0 0]}))))
  (testing "nil transform defaults to IV (identity) → :left"
    (is (= :left (cm/transform-direction {})))))

;; ---------------------------------------------------------------------------
;; alert — returns nil so validator or-short-circuit is falsey on rejection
;; ---------------------------------------------------------------------------
;; Contract (spec "validator function"): cm/alert fires the modal as a side
;; effect and returns nil, so #(or (s/valid? ::spec %) (cm/alert ...)) returns
;; falsey when the spec fails. reset! returns its new value (a truthy Hiccup
;; vector); if alert returned that, the validator would never reject.

(deftest alert-returns-nil
  (testing "alert returns nil even though reset! returns the new value"
    (let [ret (cm/alert "test message")]
      (is (nil? ret) "alert must return nil so validators see a falsey rejection"))
    (is (some? @cm/modal-content) "modal-content should have been set as a side effect")))

;; ---------------------------------------------------------------------------
;; union-rect — bounding rect of a selection
;; ---------------------------------------------------------------------------
;;
;; Contract (spec "mosaic selection toolbar"): reduce `{:x :y :width :height}`
;; rects to the smallest rect containing all of them, nil for nothing. Rects
;; arrive in SVG user units from the tile math in selection-anchor-rect, so
;; coordinates may be negative and are not integers in general.

(deftest union-rect-of-nothing-is-nil
  (testing "empty or nil input has no bounding rect"
    (is (nil? (cm/union-rect [])))
    (is (nil? (cm/union-rect nil)))))

(deftest union-rect-of-one-is-itself
  (testing "a single rect is its own bounding rect"
    (is (= {:x 10 :y 20 :width 30 :height 40}
           (cm/union-rect [{:x 10 :y 20 :width 30 :height 40}])))))

(deftest union-rect-spans-disjoint-rects
  (testing "spans from the left/top-most edge to the right/bottom-most edge"
    ;; left edge 0, top edge 0, right edge 90+10=100, bottom edge 40+10=50
    (is (= {:x 0 :y 0 :width 100 :height 50}
           (cm/union-rect [{:x 0 :y 0 :width 10 :height 10}
                           {:x 90 :y 40 :width 10 :height 10}]))))
  (testing "order of the inputs does not matter"
    (is (= (cm/union-rect [{:x 0 :y 0 :width 10 :height 10}
                           {:x 90 :y 40 :width 10 :height 10}])
           (cm/union-rect [{:x 90 :y 40 :width 10 :height 10}
                           {:x 0 :y 0 :width 10 :height 10}])))))

(deftest union-rect-keeps-the-enclosing-rect
  (testing "a rect fully inside another does not grow the result"
    (is (= {:x 0 :y 0 :width 100 :height 100}
           (cm/union-rect [{:x 0 :y 0 :width 100 :height 100}
                           {:x 25 :y 25 :width 10 :height 10}])))))

(deftest union-rect-handles-negative-coordinates
  (testing "content left of / above the viewport origin still bounds correctly"
    ;; left -50, top -20, right 0+5=5, bottom 0+5=5
    (is (= {:x -50 :y -20 :width 55 :height 25}
           (cm/union-rect [{:x -50 :y -20 :width 10 :height 10}
                           {:x 0 :y 0 :width 5 :height 5}])))))

;; ---------------------------------------------------------------------------
;; inset-rect — tile rect to tetris body rect
;; ---------------------------------------------------------------------------
;;
;; Contract (spec "mosaic selection toolbar"): shrink a rect by the same amount
;; on all four sides. The toolbar insets the selection's tile rect by half a
;; grid cell, in user units, so the bar clears the port circles at any zoom.

(deftest inset-rect-shrinks-on-every-side
  (testing "both edges of each axis move in, so the size drops by twice d"
    (is (= {:x 35 :y 45 :width 30 :height 80}
           (cm/inset-rect {:x 10 :y 20 :width 80 :height 130} 25))))
  (testing "an inset of zero is the identity"
    (is (= {:x 10 :y 20 :width 80 :height 130}
           (cm/inset-rect {:x 10 :y 20 :width 80 :height 130} 0))))
  (testing "half a grid cell is what the toolbar uses"
    ;; a single 4-cell tile at the origin: 200x200 user units -> 150x150 body
    (is (= {:x 25 :y 25 :width 150 :height 150}
           (cm/inset-rect {:x 0 :y 0 :width 200 :height 200}
                          (/ cm/grid-size 2))))))

;; ---------------------------------------------------------------------------
;; user->screen — viewBox to viewport pixels
;; ---------------------------------------------------------------------------
;;
;; Contract (spec "mosaic selection toolbar"): the scale and offset the browser
;; applies for `xMidYMid meet` — the tighter axis sets the scale, the leftover
;; slack on the other axis is split evenly, and the viewBox origin shifts to
;; the canvas box's origin. Derived from ::zoom and the canvas box rather than
;; read back off the DOM, so it is current in the render that changed ::zoom.

(deftest user->screen-matches-the-box-when-aspect-ratios-agree
  (testing "no letterboxing: scale is the plain ratio and the origin lines up"
    ;; 1000x800 viewBox into a 500x400 box at the viewport origin -> k = 0.5
    (is (= {:k 0.5 :dx 0.0 :dy 0.0}
           (cm/user->screen [0 0 1000 800]
                            {:left 0 :top 0 :width 500 :height 400}))))
  (testing "the canvas box offset carries straight into dx/dy"
    (is (= {:k 0.5 :dx 30.0 :dy 70.0}
           (cm/user->screen [0 0 1000 800]
                            {:left 30 :top 70 :width 500 :height 400})))))

(deftest user->screen-pans-with-the-viewbox-origin
  (testing "a viewBox scrolled right moves content left by origin times scale"
    ;; vx = 100 at k = 0.5 -> dx = -50
    (is (= {:k 0.5 :dx -50.0 :dy -25.0}
           (cm/user->screen [100 50 1000 800]
                            {:left 0 :top 0 :width 500 :height 400})))))

(deftest user->screen-letterboxes-the-slack-axis
  (testing "a box wider than the viewBox centres the content horizontally"
    ;; k = min(1000/1000, 400/800) = 0.5; slack = 1000 - 1000·0.5 = 500, halved
    (is (= {:k 0.5 :dx 250.0 :dy 0.0}
           (cm/user->screen [0 0 1000 800]
                            {:left 0 :top 0 :width 1000 :height 400}))))
  (testing "a box taller than the viewBox centres the content vertically"
    ;; k = min(500/1000, 800/800) = 0.5; slack = 800 - 800·0.5 = 400, halved
    (is (= {:k 0.5 :dx 0.0 :dy 200.0}
           (cm/user->screen [0 0 1000 800]
                            {:left 0 :top 0 :width 500 :height 800})))))

;; ---------------------------------------------------------------------------
;; rect->screen — user-unit rect through a user->screen
;; ---------------------------------------------------------------------------
;;
;; Contract (spec "mosaic selection toolbar"): positions scale and translate,
;; sizes only scale. The canvas transform has no rotation, so a rect stays a
;; rect.

(deftest rect->screen-scales-sizes-and-translates-positions
  (testing "position takes the offset, size does not"
    (is (= {:x 120 :y 90 :width 60 :height 80}
           (cm/rect->screen {:k 2 :dx 100 :dy 50}
                            {:x 10 :y 20 :width 30 :height 40}))))
  (testing "an identity transform leaves the rect untouched"
    (is (= {:x 10 :y 20 :width 30 :height 40}
           (cm/rect->screen {:k 1 :dx 0 :dy 0}
                            {:x 10 :y 20 :width 30 :height 40}))))
  (testing "a panned canvas can push the rect off-viewport"
    (is (= {:x -95 :y -40 :width 15 :height 20}
           (cm/rect->screen {:k 0.5 :dx -100 :dy -50}
                            {:x 10 :y 20 :width 30 :height 40})))))

(deftest rect->screen-composes-with-user->screen
  (testing "a tile at the viewBox origin lands at the canvas box origin"
    (let [t (cm/user->screen [0 0 1000 800] {:left 30 :top 70 :width 500 :height 400})]
      (is (= {:x 30.0 :y 70.0 :width 25.0 :height 25.0}
             (cm/rect->screen t {:x 0 :y 0 :width 50 :height 50}))))))
