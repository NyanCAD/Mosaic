; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.connectivity-test
  "Contract-driven tests for the net-annotation refactor.

   Each deftest states what the function SHOULD do (derived from the data
   model, not from running the code), then exercises it. Expected values are
   hand-computed from the algorithm description — never copied back from
   CLJS output.

   Coordinate conventions used across cases:
   - A resistor device doc `{:type \"resistor\" :x x0 :y y0 :transform [1 0 0 1 0 0]}`
     rotates its two built-in ports (P at (1,0), N at (1,2) in template space)
     through `rotate-shape` with size=3, mid=1. At identity transform and
     device origin (x0, y0), the result is P at (x0+1, y0) and N at (x0+1, y0+2);
     body cell is (x0+1, y0+1)."
  (:require [cljs.test :refer [deftest is testing]]
            [nyancad.mosaic.editor :as e]
            [nyancad.mosaic.editor.platform-test :as pform]))

;; ---------------------------------------------------------------------------
;; Helpers for building test schematics
;; ---------------------------------------------------------------------------

(defn- resistor [id x y]
  [id {:type "resistor" :x x :y y :transform [1 0 0 1 0 0] :name id}])

(defn- wire
  "Straight horizontal/vertical wire. No variant (so body cells fill
   interior points between endpoints)."
  [id x y rx ry]
  [id {:type "wire" :x x :y y :rx rx :ry ry}])

(defn- named-wire [id x y rx ry name]
  [id {:type "wire" :x x :y y :rx rx :ry ry :name name}])

(defn- port-doc [id x y name]
  [id {:type "port" :x x :y y :transform [1 0 0 1 0 0] :name name}])

(defn- sch [& entries]
  (into {} entries))

;; ---------------------------------------------------------------------------
;; exrange — exclusive integer range
;; ---------------------------------------------------------------------------
;; Contract: yield integers from `start` toward `start+width`, excluding both
;; endpoints. Sign of `width` picks direction. Zero width yields nothing.

(deftest exrange-positive-width
  (testing "excludes both start and start+width"
    (is (= [1 2] (seq (e/exrange 0 3))))
    (is (= [11 12 13 14] (seq (e/exrange 10 5))))))

(deftest exrange-negative-width
  (testing "steps down by 1, still excluding endpoints"
    (is (= [9 8] (seq (e/exrange 10 -3))))))

(deftest exrange-zero-width
  (testing "empty — start == start+width"
    (is (nil? (seq (e/exrange 5 0))))))

(deftest exrange-width-one
  (testing "width 1 has no integers strictly between"
    (is (nil? (seq (e/exrange 0 1))))))

;; ---------------------------------------------------------------------------
;; wire-locations — [[endpoints] [body-cells]]
;; ---------------------------------------------------------------------------
;; Contract: for a wire at (x,y) with delta (rx,ry):
;;   - endpoints = [(x,y) (x+rx,y+ry)]
;;   - body depends on variant:
;;     "hv"  → horizontal leg then corner then vertical leg
;;     "vh"  → vertical leg then corner then horizontal leg
;;     nil/"d" → interior integer points of the straight segment,
;;               or [] for diagonals (rx and ry both nonzero)

(deftest wire-locations-straight-horizontal
  (let [[endpoints body] (e/wire-locations {:x 0 :y 0 :rx 3 :ry 0})]
    (is (= [[0 0] [3 0]] endpoints))
    (testing "body = interior integer points, both ends excluded"
      (is (= #{[1 0] [2 0]} (set body))))))

(deftest wire-locations-straight-vertical
  (let [[_ body] (e/wire-locations {:x 0 :y 0 :rx 0 :ry 3})]
    (is (= #{[0 1] [0 2]} (set body)))))

(deftest wire-locations-diagonal
  (testing "a diagonal wire has no body cells"
    (let [[_ body] (e/wire-locations {:x 0 :y 0 :rx 3 :ry 3})]
      (is (empty? body)))))

(deftest wire-locations-hv-has-corner
  (testing "hv: horizontal first (at y=start-y), corner at (x+rx, y), then vertical"
    (let [[_ body] (e/wire-locations {:x 0 :y 0 :rx 2 :ry 2 :variant "hv"})]
      (is (contains? (set body) [2 0]) "corner at (2, 0)"))))

(deftest wire-locations-vh-has-corner
  (testing "vh: vertical first (at x=start-x), corner at (x, y+ry), then horizontal"
    (let [[_ body] (e/wire-locations {:x 0 :y 0 :rx 2 :ry 2 :variant "vh"})]
      (is (contains? (set body) [0 2]) "corner at (0, 2)"))))

;; ---------------------------------------------------------------------------
;; wire-corner — corner cell of an elbow wire, nil otherwise
;; ---------------------------------------------------------------------------

(deftest wire-corner-hv
  (testing "hv corner is at (x+rx, y) — end of the horizontal leg"
    (is (= [3 0] (e/wire-corner {:x 0 :y 0 :rx 3 :ry 2 :variant "hv"})))))

(deftest wire-corner-vh
  (testing "vh corner is at (x, y+ry) — end of the vertical leg"
    (is (= [0 2] (e/wire-corner {:x 0 :y 0 :rx 3 :ry 2 :variant "vh"})))))

(deftest wire-corner-straight-is-nil
  (is (nil? (e/wire-corner {:x 0 :y 0 :rx 3 :ry 0})))
  (is (nil? (e/wire-corner {:x 0 :y 0 :rx 3 :ry 3 :variant "d"}))))

;; ---------------------------------------------------------------------------
;; build-point-index — unified per-point fold
;; ---------------------------------------------------------------------------
;; Contract: for every cell touched by any device, record
;;   :ids       — #{dev-ids with a connection endpoint at this cell}
;;   :body-ids  — #{dev-ids with body/path at this cell}
;;   :ports     — [[dev-id :port-name] …] for attributable devices only
;;                (port names are keywords to round-trip cleanly through
;;                jsatom/json->clj, which keywordizes object keys)
;;   :types     — #{port types present, e.g. "electric" "photonic"}
;;   :<type>-count — count per type
;; Cells untouched by any device are absent from the map.

(deftest point-index-empty-schematic
  (is (= {} (e/build-point-index {}))))

(deftest point-index-single-resistor
  (testing "resistor at origin (0,0): P at (1,0), N at (1,2), body at (1,1)"
    (let [idx (e/build-point-index (sch (resistor "R1" 0 0)))]
      (testing ":ids at both port cells"
        (is (= #{"R1"} (get-in idx [[1 0] :ids])))
        (is (= #{"R1"} (get-in idx [[1 2] :ids]))))
      (testing ":body-ids at the body cell, not the port cells"
        (is (= #{"R1"} (get-in idx [[1 1] :body-ids])))
        (is (nil? (get-in idx [[1 0] :body-ids]))))
      (testing ":ports attributions for each named port (keyword-keyed)"
        (is (= [["R1" :P]] (get-in idx [[1 0] :ports])))
        (is (= [["R1" :N]] (get-in idx [[1 2] :ports]))))
      (testing ":types and :electric-count"
        (is (= #{"electric"} (get-in idx [[1 0] :types])))
        (is (= 1 (get-in idx [[1 0] :electric-count])))))))

(deftest point-index-two-devices-sharing-a-cell
  (testing "R1 at (0,0) N-port lands at (1,2); R2 at (0,2) P-port lands at (1,2)"
    ;; R2 at (0, 2): P at (1, 2), N at (1, 4), body at (1, 3).
    ;; So (1, 2) is R1's N AND R2's P.
    (let [idx (e/build-point-index (sch (resistor "R1" 0 0)
                                        (resistor "R2" 0 2)))
          cell (get idx [1 2])]
      (is (= #{"R1" "R2"} (:ids cell))
          "both IDs present at the shared cell")
      (is (= 2 (count (:ports cell)))
          "both attributions present (vector preserves duplicates)")
      (is (= #{["R1" :N] ["R2" :P]} (set (:ports cell)))
          "attributions name the right ports on the right devices")
      (is (= 2 (:electric-count cell))
          "counts sum across devices"))))

(deftest point-index-wire-only
  (testing "a wire's endpoints go into :ids; its path cells go into :body-ids; no :ports anywhere"
    (let [idx (e/build-point-index (sch (wire "W1" 0 0 3 0)))]
      (is (= #{"W1"} (get-in idx [[0 0] :ids])))
      (is (= #{"W1"} (get-in idx [[3 0] :ids])))
      (is (= #{"W1"} (get-in idx [[1 0] :body-ids])))
      (is (= #{"W1"} (get-in idx [[2 0] :body-ids])))
      (is (every? #(nil? (:ports %)) (vals idx))
          "wires have no :ports — attributable? is false"))))

(deftest point-index-port-doc-no-attribution
  (testing "port doc lands in :ids (wire-like) but contributes no :ports (not attributable)"
    (let [idx (e/build-point-index (sch (port-doc "P1" 5 5 "GND")))
          cell (get idx [5 5])]
      (is (contains? (:ids cell) "P1"))
      (is (nil? (:ports cell))))))

(deftest point-index-rotated-asymmetric-subcircuit-alignment
  ;; Contract: for any attributable device, every cell where the device
  ;; appears in :ids must also carry the device's port attribution in :ports,
  ;; and vice versa. The two sets must be identical — otherwise net
  ;; attribution is silently lost when a wire meets the device at an :ids
  ;; cell that has no :ports entry.
  ;;
  ;; Pre-fix, `circuit-locations` used `(+ 2 (max w h))` from port-perimeter
  ;; while `device-port-types` fell back to pattern-size. For subcircuits
  ;; that don't fill every side (e.g. op-amp: 2 left + 1 right, perimeter
  ;; [1 3] but max port coord + 1 = 4), those sizes diverge. Identity
  ;; transform hid the bug because the centering cancels; any non-identity
  ;; rotation exposed it.
  (testing "op-amp under 90° rotation: :ids and :ports land at the same cells"
    (reset! pform/modeldb
            {"models:opamp"
             {:name "OpAmp"
              :ports [{:name "in+" :side :left :type "electric"}
                      {:name "in-" :side :left :type "electric"}
                      {:name "out" :side :right :type "electric"}]}})
    (try
      (let [schem (sch ["U1" {:type "ckt" :model "opamp"
                              :x 5 :y 5 :transform [0 1 -1 0 0 0]
                              :name "U1"}])
            idx (e/build-point-index schem)
            id-cells (set (for [[pt {:keys [ids]}] idx
                                :when (contains? ids "U1")]
                            pt))
            port-cells (set (for [[pt {:keys [ports]}] idx
                                  :when (some #(= "U1" (first %)) ports)]
                              pt))]
        (is (= id-cells port-cells)
            "cells where U1 appears in :ids must match cells where it appears in :ports"))
      (finally
        (reset! pform/modeldb {})))))

;; ---------------------------------------------------------------------------
;; build-wire-networks — shared BFS primitive
;; ---------------------------------------------------------------------------
;; Contract: partition the graph where nodes are wires + port-docs and edges
;; are shared cells. For each component, collect:
;;   :wires         — set of wire ids
;;   :attributions  — vector of [dev-id port-name] for device ports attached
;;   :port-names    — names collected from port-doc nodes
;;   :wire-names    — names collected from named wire nodes
;;   :points        — set of cells the component touches
;; Plus :wire->component {wire-id → component-index} for reverse lookup.
;; Component numbering is deterministic: seeds sorted by [x y].

(defn- build-networks [schem]
  (let [pt-idx (e/build-point-index schem)]
    (e/build-wire-networks schem pt-idx)))

(deftest wire-networks-single-wire-between-two-devices
  (testing "R1--W1--R2: one component with attributions for both device ports"
    ;; R1 at (0, 0): ports at (1, 0) and (1, 2).
    ;; R2 at (0, 2): ports at (1, 2) and (1, 4).
    ;; Put a 0-length wire W1 at (1, 2) so it touches both device ports
    ;; without any path. Wire endpoints are both (1, 2).
    ;; A wire with rx=ry=0 is degenerate; use a straight wire with length 2
    ;; running R1.N (1,2) to another point. To keep the attribution map
    ;; clean, place R2 so its P is at (1, 2) via direct stacking:
    (let [schem (sch (resistor "R1" 0 0)  ; ports (1,0) (1,2)
                     (wire "W1" 1 2 0 2)   ; endpoints (1,2) (1,4)
                     (resistor "R2" 0 4))  ; ports (1,4) (1,6)
          {:keys [components wire->component]} (build-networks schem)]
      (is (= 1 (count components)) "one wire → one component")
      (let [c (first components)]
        (testing "wires and points"
          (is (= #{"W1"} (:wires c)))
          (is (contains? (:points c) [1 2]))
          (is (contains? (:points c) [1 4])))
        (testing "attributions: both device ports touching the wire's endpoints"
          (is (= #{["R1" :N] ["R2" :P]} (set (:attributions c))))))
      (is (= {"W1" 0} wire->component)))))

(deftest wire-networks-disjoint-wires
  (testing "two wires with no shared cell → two components"
    (let [schem (sch (wire "W1" 0 0 2 0)
                     (wire "W2" 10 10 2 0))
          {:keys [components]} (build-networks schem)]
      (is (= 2 (count components))))))

(deftest wire-networks-wire-chain
  (testing "W1--W2--W3 sharing endpoints form one component"
    ;; W1 (0,0)-(2,0), W2 (2,0)-(4,0), W3 (4,0)-(6,0)
    (let [schem (sch (wire "W1" 0 0 2 0)
                     (wire "W2" 2 0 2 0)
                     (wire "W3" 4 0 2 0))
          {:keys [components]} (build-networks schem)]
      (is (= 1 (count components)))
      (is (= #{"W1" "W2" "W3"} (:wires (first components)))))))

(deftest wire-networks-port-doc-on-wire
  (testing "a port doc touching a wire contributes its name via :port-names"
    (let [schem (sch (wire "W1" 0 0 2 0)
                     (port-doc "P1" 2 0 "GND"))
          {:keys [components]} (build-networks schem)]
      (is (= 1 (count components)))
      (is (= ["GND"] (:port-names (first components)))))))

(deftest wire-networks-named-wire-tracks-name
  (let [schem (sch (named-wire "W1" 0 0 2 0 "my_net"))
        {:keys [components]} (build-networks schem)]
    (is (= ["my_net"] (:wire-names (first components))))))

(deftest wire-networks-component-order-deterministic
  (testing "regardless of insertion order, components come out sorted by seed [x y]"
    (let [s1 (sch (wire "Wa" 10 10 1 0) (wire "Wb" 0 0 1 0))
          s2 (sch (wire "Wb" 0 0 1 0) (wire "Wa" 10 10 1 0))
          n1 (build-networks s1)
          n2 (build-networks s2)]
      ;; Seed order is by [x y], so Wb (at 0,0) seeds component 0 in both.
      (is (= (:wire->component n1) (:wire->component n2))))))

(deftest wire-networks-direct-device-to-device
  ;; Contract: a net is a maximal connected set of device ports. The
  ;; existence of a wire between them is a drawing affordance, not a logical
  ;; requirement. Two devices sharing a port cell share a net even with no
  ;; intermediate wire — this is handled by `cover-bare-junctions` after
  ;; the main BFS.
  (testing "two devices with co-located ports and no wire → share a singleton net"
    (let [schem (sch (resistor "R1" 0 0)  ; N at (1, 2)
                     (resistor "R2" 0 2)) ; P at (1, 2)
          {:keys [components]} (build-networks schem)]
      (is (= 1 (count components))
          "one component tying R1.N and R2.P together")
      (is (= #{["R1" :N] ["R2" :P]}
             (set (:attributions (first components)))))
      (is (empty? (:wires (first components)))
          "no wires — the component covers a single cell"))))

;; ---------------------------------------------------------------------------
;; build-netlist — {:devices {id {port net}} :wires {id net}}
;; ---------------------------------------------------------------------------
;; Contract: for each component, pick one net name with priority
;;   first port-doc name > first wire name > generated "netN"
;; Wires in the component get the same net via :wires. Devices in no
;; component do not appear; same for wires.

(deftest netlist-port-doc-name-wins
  (testing "a port doc's name beats a wire's name for the shared component"
    (let [schem (sch (resistor "R1" 0 0)
                     (named-wire "W1" 1 2 0 2 "wire_name")
                     (port-doc "P1" 1 4 "VDD")
                     (resistor "R2" 0 4))
          networks (build-networks schem)
          {:keys [devices wires]} (e/build-netlist networks)]
      (is (= "VDD" (get-in devices ["R1" :N])))
      (is (= "VDD" (get-in devices ["R2" :P])))
      (is (= "VDD" (get wires "W1"))))))

(deftest netlist-wire-name-beats-generated
  (testing "with no port doc, a named wire provides the net name"
    (let [schem (sch (resistor "R1" 0 0)
                     (named-wire "W1" 1 2 0 2 "mid")
                     (resistor "R2" 0 4))
          {:keys [devices wires]} (e/build-netlist (build-networks schem))]
      (is (= "mid" (get-in devices ["R1" :N])))
      (is (= "mid" (get wires "W1"))))))

(deftest netlist-anonymous-components-generate-netN
  (testing "with no names, components get generated netN names in seed order"
    (let [schem (sch (resistor "R1" 0 0)
                     (wire "W1" 1 2 0 2)
                     (resistor "R2" 0 4)
                     (resistor "R3" 10 10)
                     (wire "W2" 11 12 0 2)
                     (resistor "R4" 10 14))
          {:keys [wires]} (e/build-netlist (build-networks schem))]
      (testing "two components, two distinct net numbers"
        (is (= #{"net0" "net1"} (set (vals wires)))))
      (testing "W1 (lower [x y]) is net0, W2 is net1"
        (is (= "net0" (get wires "W1")))
        (is (= "net1" (get wires "W2")))))))

(deftest netlist-disconnected-devices-absent
  (testing "a device that touches no wire or port-doc doesn't appear in :devices"
    (let [schem (sch (resistor "R1" 0 0))  ; no wire touching
          {:keys [devices]} (e/build-netlist (build-networks schem))]
      (is (nil? (get devices "R1"))))))

;; ---------------------------------------------------------------------------
;; build-wire-type-index — propagate port types across components
;; ---------------------------------------------------------------------------
;; Contract: a wire's type is the type of the first typed port at any cell
;; in its component, or nil if none. All wires in a component share the
;; resolved type.

(deftest wire-type-photonic-propagates
  (testing "a photonic port at one end of a wire → wire is photonic"
    ;; Use an led (photonic output at (2,1)) so we get a "photonic" endpoint.
    ;; led at (0, 0): P (1, 0) electric, O (2, 1) photonic, N (1, 2) electric.
    (let [schem (sch ["LED1" {:type "led" :x 0 :y 0 :transform [1 0 0 1 0 0] :name "LED1"}]
                     (wire "W1" 2 1 3 0))  ; endpoint at (2, 1) photonic, other at (5, 1)
          pt-idx (e/build-point-index schem)
          networks (e/build-wire-networks schem pt-idx)
          wire-types (e/build-wire-type-index networks pt-idx)]
      (is (= "photonic" (get wire-types "W1"))))))

(deftest wire-type-untyped-is-nil
  (testing "a wire with no typed endpoint stays nil"
    (let [schem (sch (wire "W1" 0 0 2 0))
          pt-idx (e/build-point-index schem)
          networks (e/build-wire-networks schem pt-idx)
          wire-types (e/build-wire-type-index networks pt-idx)]
      (is (nil? (get wire-types "W1"))))))

;; ---------------------------------------------------------------------------
;; build-net-annotations — partial update map keyed by doc-id
;; ---------------------------------------------------------------------------
;; Contract: return {doc-id {:nets …} | {:net …}} containing ONLY docs whose
;; annotation differs from their current value. Missing :nets is equivalent
;; to {} — no spurious entry for a disconnected device that never had nets.

(defn- annotations [schem]
  (let [pt-idx   (e/build-point-index schem)
        networks (e/build-wire-networks schem pt-idx)
        nets     (e/build-netlist networks)]
    (e/build-net-annotations schem nets)))

(deftest annotations-fresh-schematic
  (testing "a fresh schematic with no :nets gets a full update"
    (let [schem (sch (resistor "R1" 0 0)
                     (named-wire "W1" 1 2 0 2 "middle")
                     (resistor "R2" 0 4))
          a (annotations schem)]
      (is (= {:N "middle"} (get-in a ["R1" :nets])))
      (is (= {:P "middle"} (get-in a ["R2" :nets])))
      (is (= "middle" (get-in a ["W1" :net]))))))

(deftest annotations-idempotent
  (testing "applying annotations then re-running produces an empty delta"
    (let [schem (sch (resistor "R1" 0 0)
                     (named-wire "W1" 1 2 0 2 "n")
                     (resistor "R2" 0 4))
          a1 (annotations schem)
          applied (reduce-kv (fn [s id upd] (update s id merge upd)) schem a1)
          a2 (annotations applied)]
      (is (= {} a2) "no further changes on the second pass"))))

(deftest annotations-nil-vs-empty-nets
  (testing "a disconnected device with no :nets produces NO entry (not {:nets {}})"
    (let [schem (sch (resistor "R1" 0 0))  ; disconnected
          a (annotations schem)]
      (is (not (contains? a "R1"))))))

(deftest annotations-stale-nets-get-cleared
  (testing "a disconnected device carrying stale :nets → cleared to {}"
    (let [schem (sch ["R1" {:type "resistor" :x 0 :y 0 :transform [1 0 0 1 0 0]
                            :name "R1" :nets {:P "old" :N "old"}}])
          a (annotations schem)]
      (is (= {:nets {}} (get a "R1"))))))

;; ---------------------------------------------------------------------------
;; build-wire-split-index — split wires where they cross another endpoint
;; ---------------------------------------------------------------------------
;; Contract: for any cell where a wire's path (:body-ids) crosses an
;; endpoint (:ids) of any doc, record the cell as a split point for that
;; wire. A wire not crossing anything has no entry.

(deftest wire-split-detects-crossing
  (testing "wire passing through a device's port cell → split recorded"
    ;; Place R1 at (0, 0): port N at (1, 2).
    ;; Run a horizontal wire W1 from (0, 2) to (3, 2): it passes through
    ;; (1, 2) — the body cell (interior) overlaps R1's port endpoint.
    (let [schem (sch (resistor "R1" 0 0)
                     (wire "W1" 0 2 3 0))
          pt-idx (e/build-point-index schem)
          splits (e/build-wire-split-index schem pt-idx)]
      (is (contains? (get splits "W1") [1 2])
          "split point recorded at (1, 2) where W1's path meets R1.N"))))

(deftest wire-split-ignores-non-crossing
  (testing "a wire not crossing any endpoint yields no split"
    (let [schem (sch (wire "W1" 0 0 3 0))
          pt-idx (e/build-point-index schem)
          splits (e/build-wire-split-index schem pt-idx)]
      (is (empty? splits)))))

(deftest wire-split-endpoint-match-does-not-count
  (testing "a wire landing exactly on a port cell at its OWN endpoint → not split"
    ;; W1 from (0, 0) to (1, 0): endpoint (1, 0) is R1.P, but that cell is
    ;; in W1's :ids (endpoint), not :body-ids (path). Contract says the
    ;; crossing must be in the path, not at the endpoint.
    (let [schem (sch (resistor "R1" 0 0)  ; P at (1, 0)
                     (wire "W1" 0 0 1 0)) ; endpoint coincides with R1.P
          pt-idx (e/build-point-index schem)
          splits (e/build-wire-split-index schem pt-idx)]
      (is (empty? splits)))))
