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
;; build-wire-networks — cell-seeded BFS
;; ---------------------------------------------------------------------------
;; Contract: partition device-port cells into connected components, where
;; wire-like docs (wires, port-docs) are edges between cells. Every cell
;; carrying a device-port attribution is a seed, so every port gets a net —
;; including dangling single-port cells (their own singleton component).
;; Dangling wires with no attached ports are absent from the output (they
;; have no netlist consumer).
;;
;; Each component collects:
;;   :wires         — set of wire ids traversed
;;   :attributions  — vector of [dev-id port-name] for device ports attached
;;   :port-names    — names collected from port-doc edges
;;   :wire-names    — names collected from named wire edges
;;   :points        — set of cells the component touches
;; Plus :wire->component {wire-id → component-index}. Component numbering
;; is deterministic: port-cell seeds sorted by [x y].

(defn- build-networks [schem]
  (let [pt-idx (e/build-point-index schem)]
    (e/build-wire-networks schem pt-idx)))

(deftest wire-networks-single-wire-between-two-devices
  (testing "R1--W1--R2: R1.N and R2.P share one component via W1"
    (let [schem (sch (resistor "R1" 0 0)
                     (wire "W1" 1 2 0 2)
                     (resistor "R2" 0 4))
          {:keys [components wire->component]} (build-networks schem)
          chain (first (filter #(contains? (:wires %) "W1") components))]
      (is (= #{"W1"} (:wires chain)))
      (is (= #{["R1" :N] ["R2" :P]} (set (:attributions chain))))
      (is (= #{[1 2] [1 4]} (:points chain)))
      (is (contains? wire->component "W1")))))

(deftest wire-networks-disjoint-groups-stay-separate
  (testing "two independent R-W-R chains produce two separate wire-bearing components"
    ;; Core connectivity property: nothing connects the two groups, so the
    ;; nets stay disjoint.
    (let [schem (sch (resistor "R1" 0 0)    (wire "W1" 1 2 0 2)  (resistor "R2" 0 4)
                     (resistor "R3" 10 10) (wire "W2" 11 12 0 2) (resistor "R4" 10 14))
          {:keys [components]} (build-networks schem)
          wire-comps (filter #(seq (:wires %)) components)]
      (is (= 2 (count wire-comps)))
      (is (= #{#{"W1"} #{"W2"}} (set (map :wires wire-comps)))))))

(deftest wire-networks-dangling-wire-absent
  (testing "a wire with no device ports at either endpoint produces no component"
    ;; Dangling wires have no netlist consumer; they're edges with nothing
    ;; to connect, so the cell-seeded BFS simply never reaches them.
    (let [schem (sch (wire "W1" 0 0 2 0))
          {:keys [components wire->component]} (build-networks schem)]
      (is (empty? components))
      (is (empty? wire->component)))))

(deftest wire-networks-wire-chain
  (testing "W1--W2--W3 sharing endpoints absorb into one component"
    (let [schem (sch (resistor "R1" 0 0)
                     (wire "W1" 1 2 0 2)
                     (wire "W2" 1 4 0 2)
                     (wire "W3" 1 6 0 2)
                     (resistor "R2" 0 8))
          {:keys [components]} (build-networks schem)
          chain (first (filter #(seq (:wires %)) components))]
      (is (= #{"W1" "W2" "W3"} (:wires chain)))
      (is (= #{["R1" :N] ["R2" :P]} (set (:attributions chain)))))))

(deftest wire-networks-port-doc-on-wire
  (testing "a port doc on a wire connecting a device contributes its name"
    (let [schem (sch (resistor "R1" 0 0)             ; N at (1,2)
                     (wire "W1" 1 2 0 2)              ; (1,2)→(1,4)
                     (port-doc "P1" 1 4 "GND"))       ; at the far end
          {:keys [components]} (build-networks schem)
          chain (first (filter #(seq (:wires %)) components))]
      (is (= ["GND"] (:port-names chain))))))

(deftest wire-networks-named-wire-tracks-name
  (testing "a named wire's name shows up in :wire-names of its component"
    (let [schem (sch (resistor "R1" 0 0)                    ; N at (1,2)
                     (named-wire "W1" 1 2 0 2 "my_net")      ; ends (1,2)→(1,4)
                     (resistor "R2" 0 4))                    ; P at (1,4)
          {:keys [components]} (build-networks schem)
          chain (first (filter #(seq (:wires %)) components))]
      (is (= ["my_net"] (:wire-names chain))))))

(deftest wire-networks-component-order-deterministic
  (testing "regardless of insertion order, components come out sorted by port-cell seed [x y]"
    (let [s1 (sch (resistor "Ra" 10 10) (resistor "Rb" 0 0))
          s2 (sch (resistor "Rb" 0 0) (resistor "Ra" 10 10))
          netlist1 (e/build-netlist (build-networks s1))
          netlist2 (e/build-netlist (build-networks s2))]
      ;; netN numbering derives from component order. Same netN assignments
      ;; regardless of how devices were added to the schematic map.
      (is (= (:devices netlist1) (:devices netlist2))))))

(deftest wire-networks-direct-device-to-device
  ;; Contract: a net is a maximal connected set of device ports. Two devices
  ;; sharing a port cell share a net even with no intermediate wire —
  ;; handled natively now that port cells seed the BFS.
  (testing "two devices with co-located ports and no wire → share a component"
    (let [schem (sch (resistor "R1" 0 0)  ; ports (1,0) (1,2)
                     (resistor "R2" 0 2)) ; ports (1,2) (1,4) — shares (1,2)
          {:keys [components]} (build-networks schem)
          shared (first (filter #(= 2 (count (:attributions %))) components))]
      (is (= #{["R1" :N] ["R2" :P]}
             (set (:attributions shared))))
      (is (empty? (:wires shared))
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
  (testing "anonymous components get distinct generated netN names"
    (let [schem (sch (resistor "R1" 0 0)
                     (wire "W1" 1 2 0 2)
                     (resistor "R2" 0 4)
                     (resistor "R3" 10 10)
                     (wire "W2" 11 12 0 2)
                     (resistor "R4" 10 14))
          {:keys [wires]} (e/build-netlist (build-networks schem))]
      (is (not= (get wires "W1") (get wires "W2"))
          "disjoint wires land in different nets")
      (is (every? #(re-matches #"net\d+" %) (vals wires))
          "generated names follow the netN pattern"))))

(deftest netlist-disconnected-device-each-pin-its-own-net
  (testing "a disconnected device's ports each become their own singleton net"
    ;; Every port is a first-class seed, so disconnected pins get generated
    ;; netN entries rather than being dropped. This matches old Python's
    ;; behaviour where every port had a dummy wire.
    (let [schem (sch (resistor "R1" 0 0))
          {:keys [devices]} (e/build-netlist (build-networks schem))
          r1-nets (get devices "R1")]
      (is (= 2 (count r1-nets)) "both ports present")
      (is (not= (:P r1-nets) (:N r1-nets)) "P and N are on different floating nets"))))

;; ---------------------------------------------------------------------------
;; build-net-type-index — propagate port types across components
;; ---------------------------------------------------------------------------
;; Contract: a doc's type is the type of the first typed port at any cell in
;; its component, or nil if none. All wires AND port docs in a component share
;; the resolved type.

(deftest wire-type-photonic-propagates
  (testing "a photonic port at one end of a wire → wire is photonic"
    ;; Use an led (photonic output at (2,1)) so we get a "photonic" endpoint.
    ;; led at (0, 0): P (1, 0) electric, O (2, 1) photonic, N (1, 2) electric.
    (let [schem (sch ["LED1" {:type "led" :x 0 :y 0 :transform [1 0 0 1 0 0] :name "LED1"}]
                     (wire "W1" 2 1 3 0))  ; endpoint at (2, 1) photonic, other at (5, 1)
          pt-idx (e/build-point-index schem)
          networks (e/build-wire-networks schem pt-idx)
          net-types (e/build-net-type-index networks pt-idx)]
      (is (= "photonic" (get net-types "W1"))))))

(deftest wire-type-untyped-is-nil
  (testing "a wire with no typed endpoint stays nil"
    (let [schem (sch (wire "W1" 0 0 2 0))
          pt-idx (e/build-point-index schem)
          networks (e/build-wire-networks schem pt-idx)
          net-types (e/build-net-type-index networks pt-idx)]
      (is (nil? (get net-types "W1"))))))

;; ---------------------------------------------------------------------------
;; build-net-annotations — partial update map keyed by doc-id
;; ---------------------------------------------------------------------------
;; Contract: return {doc-id {:nets …} | {:net …}} containing ONLY docs whose
;; annotation differs from their current value. Every attributable device
;; gets :nets with an entry for EVERY port — a disconnected pin gets a
;; floating netN rather than being absent.

(defn- annotations [schem]
  (let [pt-idx   (e/build-point-index schem)
        networks (e/build-wire-networks schem pt-idx)
        nets     (e/build-netlist networks)
        net-types (e/build-net-type-index networks pt-idx)]
    (e/build-net-annotations schem nets net-types)))

(deftest annotations-fresh-schematic
  (testing "a fresh schematic with no :nets gets a full update"
    (let [schem (sch (resistor "R1" 0 0)
                     (named-wire "W1" 1 2 0 2 "middle")
                     (resistor "R2" 0 4))
          a (annotations schem)]
      (testing "connected ports get the named net; disconnected ports get generated netN"
        (is (= "middle" (get-in a ["R1" :nets :N])))
        (is (= "middle" (get-in a ["R2" :nets :P])))
        (is (string? (get-in a ["R1" :nets :P]))
            "R1.P is disconnected — gets its own generated net")
        (is (string? (get-in a ["R2" :nets :N]))
            "R2.N is disconnected — gets its own generated net"))
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

(deftest annotations-disconnected-device-gets-floating-nets
  (testing "a disconnected device gets :nets entries with floating netN for each port"
    ;; Matches old Python's behaviour: every port has a net, dangling or not.
    (let [schem (sch (resistor "R1" 0 0))
          a (annotations schem)]
      (is (contains? a "R1"))
      (is (= 2 (count (get-in a ["R1" :nets]))))
      (is (every? string? (vals (get-in a ["R1" :nets])))))))

(deftest annotations-stale-nets-replaced-with-fresh
  (testing "stale :nets on a disconnected device get replaced with fresh floating nets"
    (let [schem (sch ["R1" {:type "resistor" :x 0 :y 0 :transform [1 0 0 1 0 0]
                            :name "R1" :nets {:P "old" :N "old"}}])
          a (annotations schem)]
      (is (contains? a "R1"))
      (let [new-nets (get-in a ["R1" :nets])]
        (is (not= {:P "old" :N "old"} new-nets)
            "stale values replaced")
        (is (= 2 (count new-nets))
            "both ports still have nets")))))

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

;; ---------------------------------------------------------------------------
;; split-wire — the mutation that rewrites a wire into its pieces
;; ---------------------------------------------------------------------------
;; Contract: split a wire at the given cells into consecutive segments.
;;   - The first surviving segment reuses the original id (so its :name and
;;     document identity carry over); the rest get ids DERIVED from the wire
;;     id + segment start cell, never a fresh gensym — so two clients splitting
;;     the same wire while syncing produce identical documents that converge
;;     under replication instead of duplicating.
;;   - No original wire is ever left behind at full length.

(defn- split-once
  "Reset the shared schematic to `schem`, split `wirename` at its crossing
   points, and return the resulting schematic map."
  [schem wirename]
  (reset! pform/schematic schem)
  (let [pt-idx (e/build-point-index schem)
        coords (get (e/build-wire-split-index schem pt-idx) wirename)]
    (e/split-wire wirename coords pt-idx)
    @pform/schematic))

(deftest split-wire-reuses-original-and-adds-deterministic-tail
  (testing "W1 crossing R1.N at (1,2) splits into original id + one derived id"
    ;; R1 at (0,0): N at (1,2). W1 from (0,2)→(3,2) passes through (1,2).
    (let [schem (sch (resistor "R1" 0 0)
                     (wire "W1" 0 2 3 0))
          result (split-once schem "W1")
          wires  (into {} (filter (fn [[_ v]] (= "wire" (:type v))) result))]
      (is (= 2 (count wires)) "exactly two segments, no duplicates")
      (is (contains? wires "W1") "original id reused for the first segment")
      (testing "first segment is (0,2)→(1,2)"
        (is (= [0 2 1 0] ((juxt :x :y :rx :ry) (get wires "W1")))))
      (testing "tail segment (1,2)→(3,2) has an id derived from the wire + start cell"
        (let [tail (first (dissoc wires "W1"))]
          (is (= "W1-split-1-2" (key tail)))
          (is (= [1 2 2 0] ((juxt :x :y :rx :ry) (val tail)))))))))

(deftest split-wire-is-deterministic-across-runs
  (testing "splitting the same wire twice yields identical ids (convergent under sync)"
    ;; Two independent clients both see W1 crossing R1.N and split it. The
    ;; resulting document ids MUST match so replication merges them instead of
    ;; accumulating duplicate tail wires. A gensym-based id would differ here.
    (let [schem (sch (resistor "R1" 0 0)
                     (resistor "R2" 0 4)  ; N at (1,6) — a second crossing
                     (wire "W1" 0 2 5 0)) ; passes (1,2)=R1.N and ... only (1,2)
          run1 (split-once schem "W1")
          run2 (split-once schem "W1")
          wire-ids (fn [m] (set (for [[k v] m :when (= "wire" (:type v))] k)))]
      (is (= (wire-ids run1) (wire-ids run2))
          "segment ids are identical across independent splits")
      (is (contains? (wire-ids run1) "W1")
          "original id is among them"))))

(deftest split-wire-no-leftover-full-wire
  (testing "the full-length original never survives alongside the pieces"
    (let [schem (sch (resistor "R1" 0 0)
                     (wire "W1" 0 2 3 0))
          result (split-once schem "W1")]
      ;; The rewritten W1 must be a piece (rx 1), not the original span (rx 3).
      (is (= 1 (:rx (get result "W1")))
          "W1 was shortened to its first segment, not left at full length")
      (is (every? #(<= (abs (:rx %)) 2)
                  (for [[_ v] result :when (= "wire" (:type v))] v))
          "no surviving wire spans the whole original length"))))

;; ---------------------------------------------------------------------------
;; Photonic model-defined ports
;; ---------------------------------------------------------------------------
;; Contract: when a photonic builtin has model ports in @modeldb,
;; device-port-types returns model-named ports (e.g. "o1", "o2") and
;; device-locations uses model-derived positions.

(defn- photonic-dev
  "A photonic builtin with a model reference."
  [id type model-id x y]
  [id {:type type :model model-id :x x :y y
       :transform [1 0 0 1 0 0] :name id}])

(deftest photonic-model-ports-override-port-names
  (testing "straight with model ports uses model port names, not builtin '1'/'2'"
    (reset! pform/modeldb
            {"models:test.straight"
             {:name "Straight"
              :ports [{:name "o1" :side :left :type "photonic"}
                      {:name "o2" :side :right :type "photonic"}]}})
    (try
      (let [dev {:type "straight" :model "test.straight"
                 :x 0 :y 0 :transform [1 0 0 1 0 0] :name "S1"}
            port-types (e/device-port-types dev)
            names (set (map :name port-types))]
        (is (= #{"o1" "o2"} names)
            "port names come from model, not builtin"))
      (finally
        (reset! pform/modeldb {})))))

(deftest photonic-without-model-uses-default-box
  (testing "straight without :model field gets no connection points"
    (reset! pform/modeldb {})
    (let [dev {:type "straight" :x 0 :y 0
               :transform [1 0 0 1 0 0] :name "S1"}
          port-types (e/device-port-types dev)]
      (is (empty? port-types)
          "no model → no ports (circuit path with empty modeldb)"))))

(deftest photonic-model-ports-location-alignment
  (testing "device-locations and device-port-types agree for photonic with model ports"
    (reset! pform/modeldb
            {"models:test.straight"
             {:name "Straight"
              :ports [{:name "o1" :side :left :type "photonic"}
                      {:name "o2" :side :right :type "photonic"}]}})
    (try
      (let [dev {:type "straight" :model "test.straight"
                 :x 5 :y 5 :transform [1 0 0 1 0 0] :name "S1"}
            [loc-endpoints _] (e/device-locations dev)
            port-types (e/device-port-types dev)
            loc-cells (set loc-endpoints)
            port-cells (set (map (juxt :x :y) port-types))]
        (is (= loc-cells port-cells)
            "location endpoints and port-type positions must match"))
      (finally
        (reset! pform/modeldb {})))))

(deftest photonic-model-ports-rotation-alignment
  (testing "photonic with model ports under 90° rotation: :ids and :ports align"
    (reset! pform/modeldb
            {"models:test.straight"
             {:name "Straight"
              :ports [{:name "o1" :side :left :type "photonic"}
                      {:name "o2" :side :right :type "photonic"}]}})
    (try
      (let [schem (sch (photonic-dev "S1" "straight" "test.straight" 5 5))
            schem (update schem "S1" assoc :transform [0 1 -1 0 0 0])
            idx (e/build-point-index schem)
            id-cells (set (for [[pt {:keys [ids]}] idx
                                :when (contains? ids "S1")]
                            pt))
            port-cells (set (for [[pt {:keys [ports]}] idx
                                  :when (some #(= "S1" (first %)) ports)]
                              pt))]
        (is (= id-cells port-cells)
            "cells where S1 appears in :ids must match cells where it appears in :ports"))
      (finally
        (reset! pform/modeldb {})))))

;; ---------------------------------------------------------------------------
;; Type-driven port alignment for photonic devices
;; ---------------------------------------------------------------------------

(deftest photonic-ring-single-ports-at-bottom
  (testing "ring-single with model ports: both ports at bottom of [1,2] box"
    (reset! pform/modeldb
            {"models:test.ring"
             {:name "RingSingle"
              :ports [{:name "o1" :side :left :type "photonic"}
                      {:name "o2" :side :right :type "photonic"}]}})
    (try
      (let [dev {:type "ring-single" :model "test.ring"
                 :x 0 :y 0 :transform [1 0 0 1 0 0] :name "RS1"}
            port-types (e/device-port-types dev)
            locs (into {} (map (juxt :name (juxt :x :y))) port-types)]
        (is (= [0 2] (get locs "o1")) "left port at bottom (y=2)")
        (is (= [2 2] (get locs "o2")) "right port at bottom (y=2)"))
      (finally
        (reset! pform/modeldb {})))))

(deftest photonic-sbend-ports-staggered
  (testing "sbend with model ports: left at top, right at bottom"
    (reset! pform/modeldb
            {"models:test.sbend"
             {:name "SBend"
              :ports [{:name "o1" :side :left :type "photonic"}
                      {:name "o2" :side :right :type "photonic"}]}})
    (try
      (let [dev {:type "sbend" :model "test.sbend"
                 :x 0 :y 0 :transform [1 0 0 1 0 0] :name "SB1"}
            port-types (e/device-port-types dev)
            locs (into {} (map (juxt :name (juxt :x :y))) port-types)]
        (is (= [0 1] (get locs "o1")) "left port at top (y=1)")
        (is (= [2 2] (get locs "o2")) "right port at bottom (y=2)"))
      (finally
        (reset! pform/modeldb {})))))

(deftest photonic-bend-ports-left-top
  (testing "bend with model ports: o1 left, o2 top — markers at the canonical artwork endpoints"
    (reset! pform/modeldb
            {"models:test.bend"
             {:name "Bend"
              :ports [{:name "o1" :side :left :type "photonic"}
                      {:name "o2" :side :top :type "photonic"}]}})
    (try
      (let [dev {:type "bend" :model "test.bend"
                 :x 0 :y 0 :transform [1 0 0 1 0 0] :name "B1"}
            port-types (e/device-port-types dev)
            locs (into {} (map (juxt :name (juxt :x :y))) port-types)]
        (is (= [0 1] (get locs "o1")) "left port at mid-left (0,1)")
        (is (= [1 0] (get locs "o2")) "top port at mid-top (1,0)"))
      (finally
        (reset! pform/modeldb {})))))

(deftest photonic-mmi-2x2-port-gap
  (testing "mmi-2x2 with model ports: gap preserved between port pairs"
    (reset! pform/modeldb
            {"models:test.mmi"
             {:name "MMI2x2"
              :ports [{:name "o1" :side :left :type "photonic"}
                      {:name "o2" :side :left :type "photonic"}
                      {:name "o3" :side :right :type "photonic"}
                      {:name "o4" :side :right :type "photonic"}]}})
    (try
      (let [dev {:type "mmi-2x2" :model "test.mmi"
                 :x 0 :y 0 :transform [1 0 0 1 0 0] :name "M1"}
            port-types (e/device-port-types dev)
            locs (into {} (map (juxt :name (juxt :x :y))) port-types)]
        (is (= [0 1] (get locs "o1")) "first left at y=1")
        (is (= [0 3] (get locs "o2")) "second left at y=3 (gap at y=2)")
        (is (= [2 1] (get locs "o3")) "first right at y=1")
        (is (= [2 3] (get locs "o4")) "second right at y=3"))
      (finally
        (reset! pform/modeldb {})))))

(deftest photonic-rotation-alignment-ring-single
  (testing "ring-single under 90° rotation: :ids and :ports align"
    (reset! pform/modeldb
            {"models:test.ring"
             {:name "RingSingle"
              :ports [{:name "o1" :side :left :type "photonic"}
                      {:name "o2" :side :right :type "photonic"}]}})
    (try
      (let [schem (sch (photonic-dev "RS1" "ring-single" "test.ring" 5 5))
            schem (update schem "RS1" assoc :transform [0 1 -1 0 0 0])
            idx (e/build-point-index schem)
            id-cells (set (for [[pt {:keys [ids]}] idx
                                :when (contains? ids "RS1")]
                            pt))
            port-cells (set (for [[pt {:keys [ports]}] idx
                                  :when (some #(= "RS1" (first %)) ports)]
                              pt))]
        (is (= id-cells port-cells)
            "cells where RS1 appears in :ids must match cells where it appears in :ports"))
      (finally
        (reset! pform/modeldb {})))))

;; ---------------------------------------------------------------------------
;; Port nets annotation — port docs carry {:nets {:P net}}
;; ---------------------------------------------------------------------------
;; Contract: build-netlist returns a :ports {port-doc-id net-name} map, and
;; build-net-annotations writes {:nets {:P net}} onto each port doc so the
;; port carries explicit cross-editor connectivity (consumed by Livewire's
;; derive-and-glue and the compile-time nets fallback).

(deftest netlist-port-doc-gets-ports-entry
  (testing "build-netlist maps a port doc to its component's net name"
    (let [schem (sch (resistor "R1" 0 0)        ; N at (1,2)
                     (port-doc "in" 1 2 "in"))  ; same cell, named "in"
          {:keys [ports]} (e/build-netlist (build-networks schem))]
      (is (= "in" (get ports "in"))))))

(deftest annotations-port-doc-gets-nets-P
  (testing "a port doc carries {:nets {:P net}}"
    (let [schem (sch (resistor "R1" 0 0)
                     (port-doc "in" 1 2 "in"))
          a (annotations schem)]
      (is (= {:P "in"} (get-in a ["in" :nets]))))))

(deftest annotations-two-ports-one-network-same-net
  (testing "two port docs on one network resolve to the same net"
    (let [schem (sch (resistor "R1" 0 0)        ; N at (1,2)
                     (wire "W1" 1 2 0 2)         ; (1,2)→(1,4)
                     (port-doc "in" 1 2 "in")
                     (port-doc "out" 1 4 "out"))
          {:keys [ports]} (e/build-netlist (build-networks schem))]
      (is (= (get ports "in") (get ports "out"))
          "both port docs share the component's single net"))))

;; ---------------------------------------------------------------------------
;; Port nature — port docs take the nature of their net via the unified index
;; ---------------------------------------------------------------------------
;; Contract: build-net-type-index resolves a port doc to its net's type (nil
;; when untyped/isolated). build-net-annotations writes that onto the port doc
;; as :nature, defaulting nil to "photonic" (the nyanlib indexer's fallback).

(defn- net-types [schem]
  (let [pt-idx   (e/build-point-index schem)
        networks (e/build-wire-networks schem pt-idx)]
    (e/build-net-type-index networks pt-idx)))

(deftest port-nature-photonic
  (testing "a port doc on a photonic net resolves photonic"
    ;; led O port is photonic at (2,1); a port doc sharing that point is on
    ;; the same network.
    (let [schem (sch ["LED1" {:type "led" :x 0 :y 0 :transform [1 0 0 1 0 0] :name "LED1"}]
                     (port-doc "opt" 2 1 "opt"))]
      (is (= "photonic" (get (net-types schem) "opt")))
      (is (= "photonic" (get-in (annotations schem) ["opt" :nature]))))))

(deftest port-nature-electric
  (testing "a port doc on an electric net resolves electric"
    ;; led P port is electric at (1,0).
    (let [schem (sch ["LED1" {:type "led" :x 0 :y 0 :transform [1 0 0 1 0 0] :name "LED1"}]
                     (port-doc "pwr" 1 0 "pwr"))]
      (is (= "electric" (get (net-types schem) "pwr")))
      (is (= "electric" (get-in (annotations schem) ["pwr" :nature]))))))

(deftest port-nature-isolated-defaults-photonic
  (testing "an isolated port doc has nil net type but is annotated photonic"
    (let [schem (sch (port-doc "lonely" 5 5 "lonely"))]
      (is (nil? (get (net-types schem) "lonely"))
          "the index leaves untyped nets nil")
      (is (= "photonic" (get-in (annotations schem) ["lonely" :nature]))
          "the annotation defaults nil to photonic"))))

;; ---------------------------------------------------------------------------
;; schematic-airwires — top-level ratsnest layer
;; ---------------------------------------------------------------------------
;; Contract: render a dashed-red arc for (a) polylines whose endpoints sit on
;; different nets and (b) port docs whose :attached_port pin sits on a
;; different net. Silent only when both pins carry equal non-nil nets.

(defn- count-airwires
  "Count :g.airwire elements anywhere in a hiccup tree."
  [hiccup]
  (->> (tree-seq #(or (seq? %) (vector? %)) seq hiccup)
       (filter #(and (vector? %) (= :g.airwire (first %))))
       count))

(deftest airwires-polyline-mismatch-renders
  (testing "a polyline whose endpoints are on different nets draws one airwire"
    (reset! pform/schematic
            (sch ["R1" {:type "resistor" :x 0 :y 0 :transform [1 0 0 1 0 0]
                        :name "R1" :nets {:P "n1"}}]
                 ["R2" {:type "resistor" :x 5 :y 5 :transform [1 0 0 1 0 0]
                        :name "R2" :nets {:P "n2"}}]
                 ["poly_1" {:type "polyline"
                            :terminals {:start  {:component_id "R1" :port_name "P"}
                                        :finish {:component_id "R2" :port_name "P"}}}]))
    (try
      (is (= 1 (count-airwires (e/schematic-airwires))))
      (finally (reset! pform/schematic {})))))

(deftest airwires-polyline-match-silent
  (testing "matching endpoint nets → no airwire"
    (reset! pform/schematic
            (sch ["R1" {:type "resistor" :x 0 :y 0 :transform [1 0 0 1 0 0]
                        :name "R1" :nets {:P "n1"}}]
                 ["R2" {:type "resistor" :x 5 :y 5 :transform [1 0 0 1 0 0]
                        :name "R2" :nets {:P "n1"}}]
                 ["poly_1" {:type "polyline"
                            :terminals {:start  {:component_id "R1" :port_name "P"}
                                        :finish {:component_id "R2" :port_name "P"}}}]))
    (try
      (is (= 0 (count-airwires (e/schematic-airwires))))
      (finally (reset! pform/schematic {})))))

(deftest airwires-port-attached-mismatch-renders
  (testing "a port marker attached to a pin on a different net draws one airwire"
    (reset! pform/schematic
            (sch ["R1" {:type "resistor" :x 0 :y 0 :transform [1 0 0 1 0 0]
                        :name "R1" :nets {:P "n1"}}]
                 ["in" {:type "port" :x 5 :y 5 :transform [1 0 0 1 0 0]
                        :name "in" :nets {:P "n2"}
                        :attached_port {:component_id "R1" :port_name "P"}}]))
    (try
      (is (= 1 (count-airwires (e/schematic-airwires))))
      (finally (reset! pform/schematic {})))))

(deftest airwires-port-attached-match-silent
  (testing "a port marker whose net matches its attached pin → no airwire"
    (reset! pform/schematic
            (sch ["R1" {:type "resistor" :x 0 :y 0 :transform [1 0 0 1 0 0]
                        :name "R1" :nets {:P "n1"}}]
                 ["in" {:type "port" :x 5 :y 5 :transform [1 0 0 1 0 0]
                        :name "in" :nets {:P "n1"}
                        :attached_port {:component_id "R1" :port_name "P"}}]))
    (try
      (is (= 0 (count-airwires (e/schematic-airwires))))
      (finally (reset! pform/schematic {})))))
