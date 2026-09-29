; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.port-exposure-test
  (:require [cljs.test :refer [async deftest is testing]]
            [cljs.core.async :refer [go <!]]
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.common :as cm]
            [nyancad.mosaic.editor :as e]
            [nyancad.mosaic.editor.platform-test :as pform]))

(def group-prefix "test$top:")

(def four-port-model
  {"models:test.component"
   {:name "Test component"
    :ports [{:name "left" :side :left :type "photonic"}
            {:name "right" :side :right :type "electric"}
            {:name "top" :side :top :type "electric"}
            {:name "bottom" :side :bottom :type "photonic"}]}})

;; @tags mosaic port exposure
(defn- component
  ([] (component [1 0 0 1 0 0]))
  ([transform]
   {:type "ckt"
    :model "test.component"
    :x 10
    :y 10
    :transform transform
    :name "X1"}))

;; @tags mosaic port exposure
(defn- docs-of-type [docs type]
  (into {} (filter (fn [[_ doc]] (= type (:type doc))) docs)))

;; @tags mosaic port exposure
(deftest exposure-uses-nc-endpoint-occupancy
  (let [device-id (str group-prefix "X1")
        body-wire-id (str group-prefix "W2")
        sch {device-id (component)
             ;; Wire endpoint suppresses the left port.
             (str group-prefix "W1")
             {:type "wire" :x 10 :y 11 :rx -2 :ry 0 :variant "d"}
             ;; Wire body crosses the right port without suppressing it.
             body-wire-id
             {:type "wire" :x 12 :y 10 :rx 0 :ry 2 :variant "d"}
             ;; Text-only marker does not suppress the top port.
             (str group-prefix "label")
             {:type "port" :variant "text" :x 11 :y 10
              :transform cm/IV :name "label"}
             ;; Structural marker suppresses the bottom port.
             (str group-prefix "outer")
             {:type "port" :x 11 :y 12 :transform cm/IV :name "outer"}}
        idx (e/build-point-index sch four-port-model)
        ports (e/exposable-device-ports four-port-model sch idx device-id)
        docs (e/build-port-exposure-docs four-port-model sch idx device-id)
        with-docs (merge sch docs)
        next-idx (e/build-point-index with-docs four-port-model)]
    (is (= #{"right" "top"} (set (map :name ports))))
    (is (= #{[12 11] [11 10]}
           (set (map (juxt :x :y) (vals docs)))))
    (is (= #{[12 11]} (get (e/build-wire-split-index with-docs next-idx)
                           body-wire-id))
        "the normal post-action split phase connects a crossing wire")
    (is (empty? (e/build-port-exposure-docs
                 four-port-model with-docs next-idx device-id))
        "the generated endpoint markers make a repeated invocation a no-op")))

;; @tags mosaic port exposure
(deftest exposure-builds-co-located-directional-markers-with-global-names
  (let [device-id (str group-prefix "X1")
        sch {device-id (component)
             (str group-prefix "P1")
             {:type "port" :x 30 :y 30 :transform cm/IV :name "existing"}
             (str group-prefix "R1")
             {:type "resistor" :x 40 :y 40 :transform cm/IV :name "P2"}}
        idx (e/build-point-index sch four-port-model)
        docs (e/build-port-exposure-docs four-port-model sch idx device-id)]
    (is (= #{(str group-prefix "P3") (str group-prefix "P4")
             (str group-prefix "P5") (str group-prefix "P6")}
           (set (keys docs))))
    (is (= #{"P3" "P4" "P5" "P6"} (set (map :name (vals docs)))))
    (is (= #{[10 11] [12 11] [11 10] [11 12]}
           (set (map (juxt :x :y) (vals docs)))))
    (is (= #{cm/IV [-1 0 0 1 0 0]
             [0 1 -1 0 0 0] [0 -1 1 0 0 0]}
           (set (map :transform (vals docs)))))
    (is (empty? (docs-of-type docs "wire")))))

;; @tags mosaic port exposure
(deftest exposure-is-capability-based-and-excludes-port-documents
  (let [worker-model {"models:test.worker"
                      {:ports [{:name "o1" :side :left :type "photonic"}]}}
        worker-id (str group-prefix "C1")
        worker {worker-id {:type "worker-backed-type"
                           :model "test.worker"
                           :x 0 :y 0 :transform cm/IV :name "C1"}}
        worker-idx (e/build-point-index worker worker-model)
        resistor-id (str group-prefix "R1")
        resistor {resistor-id {:type "resistor" :x 0 :y 0
                               :transform cm/IV :name "R1"}}
        resistor-idx (e/build-point-index resistor {})
        emitter-id (str group-prefix "source")
        emitter {emitter-id {:type "port" :variant "emitter" :x 0 :y 0
                             :transform cm/IV :name "source"}}
        emitter-idx (e/build-point-index emitter {})
        missing-id (str group-prefix "missing")
        missing {missing-id {:type "worker-backed-type" :model "missing"
                             :x 0 :y 0 :transform cm/IV :name "missing"}}
        missing-idx (e/build-point-index missing {})]
    (is (= {:disabled? false}
           (e/port-exposure-state worker-model worker worker-idx worker-id)))
    (is (= {:disabled? false}
           (e/port-exposure-state {} resistor resistor-idx resistor-id)))
    (is (nil? (e/port-exposure-state {} emitter emitter-idx emitter-id)))
    (is (nil? (e/port-exposure-state {} missing missing-idx missing-id)))))

;; @tags mosaic port exposure
(deftest builtin-connection-data-declares-canonical-sides
  (is (= [{:name "A" :x 0 :y 0 :type "electric" :side :top}
          {:name "B" :x 2 :y 0 :type "electric" :side :top}
          {:name "C" :x 1 :y 1 :type "electric" :side :right}
          {:name "D" :x 0 :y 2 :type "electric" :side :bottom}
          {:name "E" :x 2 :y 2 :type "electric" :side :bottom}]
         (vec (cm/ascii-patern ["A B" " C " "D E"])))
      "vertical edges win at corners and an interior port defaults right")
  (is (= 3 (cm/pattern-size (cm/ascii-patern ["A B" " C " "D E"]))))
  (doseq [[type variant expected]
          [["nmos" nil
            [{:name "D" :x 1 :y 0 :type "electric" :side :top}
             {:name "G" :x 0 :y 1 :type "electric" :side :left}
             {:name "B" :x 1 :y 1 :type "electric" :side :right}
             {:name "S" :x 1 :y 2 :type "electric" :side :bottom}]]
           ["npn" nil
            [{:name "C" :x 1 :y 0 :type "electric" :side :top}
             {:name "B" :x 0 :y 1 :type "electric" :side :left}
             {:name "E" :x 1 :y 2 :type "electric" :side :bottom}]]
           ["resistor" nil
            [{:name "P" :x 1 :y 0 :type "electric" :side :top}
             {:name "N" :x 1 :y 2 :type "electric" :side :bottom}]]
           ["led" nil
            [{:name "P" :x 1 :y 0 :type "electric" :side :top}
             {:name "O" :x 2 :y 1 :type "photonic" :side :right}
             {:name "N" :x 1 :y 2 :type "electric" :side :bottom}]]
           ["photodiode" nil
            [{:name "P" :x 1 :y 0 :type "electric" :side :top}
             {:name "O" :x 2 :y 1 :type "photonic" :side :right}
             {:name "N" :x 1 :y 2 :type "electric" :side :bottom}]]
           ["modulator" nil
            [{:name "P" :x 1 :y 0 :type "electric" :side :top}
             {:name "1" :x 0 :y 1 :type "photonic" :side :left}
             {:name "2" :x 2 :y 1 :type "photonic" :side :right}
             {:name "N" :x 1 :y 2 :type "electric" :side :bottom}]]
           ["port" "emitter"
            [{:name "P" :x 2 :y 1 :type "photonic" :side :right}]]
           ["port" "detector"
            [{:name "P" :x 2 :y 1 :type "photonic" :side :right}]]
           ["port" ::e/default
            [{:name "P" :x 0 :y 0 :side :right}]]]]
    (let [conn (get-in e/models [type ::e/conn])]
      (is (= expected (vec (if variant (get conn variant) conn))) type))))

;; @tags mosaic die-component, mosaic port exposure
(deftest die-ports-resolve-as-declared
  ;; cspdk-style die ports: sides are the die edge each port sits on (the
  ;; opposite of the inward GDS orientation), same-side ports clockwise, so
  ;; the declared list index 0 is the bottom-most port of a vertical side.
  (let [models {"models:pk.die"
                {:name "die" :type "die"
                 :ports [{:name "o24" :side "left" :type "photonic"}
                         {:name "o13" :side "left" :type "photonic"}
                         {:name "e31" :side "bottom" :type "electric"}
                         {:name "e1" :side "bottom" :type "electric"}]}}
        dev {:type "die" :model "pk.die" :x 10 :y 10 :transform cm/IV :name "X1"}
        ports (e/device-port-types models dev)
        by-name (into {} (map (juxt :name identity)) ports)]
    (testing "a die renders its declared model sides without type-specific inversion"
      (is (= :left (:side (by-name "o24"))))
      (is (= :left (:side (by-name "o13"))))
      (is (= :bottom (:side (by-name "e31"))))
      (is (= :bottom (:side (by-name "e1")))))
    (testing "declared clockwise order maps to Mosaic's index-0-first slots"
      (let [by-side (group-by :side ports)]
        (is (= ["o24" "o13"] (mapv :name (:left by-side))))
        (is (= ["e31" "e1"] (mapv :name (:bottom by-side))))
        (is (= [11 12] (mapv :y (:left by-side)))
            "the first declared port of a side lands on the top slot (smallest y,
             here 10 device y + 1/2 relative)")))))

;; @tags mosaic die-component, factory resolution
(deftest die-rendering-uses-platform-model-lookup
  (let [lookups (atom [])
        model (atom (get four-port-model "models:test.component"))
        die (assoc (component) :type "die")]
    (with-redefs [pform/model-cursor (fn [_ & path]
                                     (swap! lookups conj path)
                                     model)]
      (is (vector? (e/circuit-sym "die" die)))
      (is (some #{["models:test.component"]} @lookups))
      (is (nil? (e/circuit-shape "die" die))))))

;; @tags mosaic port exposure
(deftest canonical-directions-preserve-legacy-port-map-shapes
  (let [resistor {:type "resistor" :x 0 :y 0 :transform cm/IV}
        legacy-static (first (e/device-port-types {} resistor))
        mosfet-id (str group-prefix "M1")
        mosfet {mosfet-id {:type "nmos" :x 0 :y 0
                           :transform cm/IV :name "M1"}}
        mosfet-idx (e/build-point-index mosfet {})
        bulk-marker (some #(when (= [1 1] ((juxt :x :y) %)) %)
                          (vals (e/build-port-exposure-docs
                                 {} mosfet mosfet-idx mosfet-id)))
        rotated-model {"models:test.left"
                       {:ports [{:name "o1" :side :left :type "photonic"}]}}
        rotated {:type "ckt" :model "test.left" :x 0 :y 0
                 :transform [0 1 -1 0 0 0]}
        rotated-id (str group-prefix "X1")
        rotated-sch {rotated-id rotated}
        rotated-idx (e/build-point-index rotated-sch rotated-model)
        rotated-marker (first (vals (e/build-port-exposure-docs
                                     rotated-model rotated-sch rotated-idx rotated-id)))
        legacy-model (first (e/device-port-types rotated-model rotated))]
    (is (not (contains? legacy-static :side)))
    (is (not (contains? legacy-static :direction)))
    (is (= [-1 0 0 1 0 0] (:transform bulk-marker)))
    (is (= [0 1 -1 0 0 0] (:transform rotated-marker)))
    (is (= :left (:side legacy-model)))
    (is (not (contains? legacy-model :direction)))))

;; @tags mosaic port exposure
(deftest direction-uses-the-linear-transform-and-vertical-ties
  (let [models {"models:test.left"
                {:ports [{:name "o1" :side :left :type "photonic"}]}}
        device-id (str group-prefix "X1")]
    (doseq [[transform expected]
            [[cm/IV cm/IV]
             [[-1 0 0 1 0 0] [-1 0 0 1 0 0]]
             [[0 1 -1 0 0 0] [0 1 -1 0 0 0]]
             [[0 -1 1 0 0 0] [0 -1 1 0 0 0]]
             [[1 0.2 2 1 17 23] cm/IV]
             [[1 1 0 1 17 23] [0 1 -1 0 0 0]]]]
      (let [sch {device-id {:type "ckt" :model "test.left" :x 0 :y 0
                            :transform transform :name "X1"}}
            idx (e/build-point-index sch models)
            marker (first (vals (e/build-port-exposure-docs
                                 models sch idx device-id)))]
        (is (= expected (:transform marker)) (str transform))))))

;; @tags mosaic port exposure
(deftest generated-markers-receive-network-annotations
  (let [device-id (str group-prefix "X1")
        sch {device-id (component)}
        idx (e/build-point-index sch four-port-model)
        docs (e/build-port-exposure-docs four-port-model sch idx device-id)
        with-docs (merge sch docs)
        next-idx (e/build-point-index with-docs four-port-model)
        networks (e/build-wire-networks with-docs next-idx)
        nets (e/build-netlist networks)
        net-types (e/build-net-type-index networks next-idx)
        updates (e/build-net-annotations with-docs nets net-types)
        expected-natures {cm/IV "photonic"
                          [-1 0 0 1 0 0] "electric"
                          [0 1 -1 0 0 0] "electric"
                          [0 -1 1 0 0 0] "photonic"}]
    (doseq [[id doc] docs]
      (is (string? (get-in updates [id :nets :P])))
      (is (= (get expected-natures (:transform doc))
             (get-in updates [id :nature]))))))

;; @tags mosaic port exposure, nyancir format, reactive indices
(defn- annotations-for
  "Run one annotation derivation over `sch`, the way `post-action!` does."
  [sch]
  (let [idx (e/build-point-index sch four-port-model)
        networks (e/build-wire-networks sch idx)]
    (e/build-net-annotations sch
                             (e/build-netlist networks)
                             (e/build-net-type-index networks idx))))

;; @tags mosaic port exposure, nyancir format, reactive indices
(deftest a-disconnected-port-loses-its-nets-annotation
  (let [device-id (str group-prefix "X1")
        sch {device-id (component)}
        idx (e/build-point-index sch four-port-model)
        docs (e/build-port-exposure-docs four-port-model sch idx device-id)
        with-docs (merge sch docs)
        ;; Apply the annotations the way post-action! does: merge-with merge is
        ;; one level deep over document ids, so each update is a shallow merge
        ;; into its doc.
        annotated (merge-with merge with-docs (annotations-for with-docs))
        ;; The device the markers exposed is gone, so no cell carries a :ports
        ;; attribution any more and every marker is free-floating — the state
        ;; the reported P2 was left in.
        orphaned (dissoc annotated device-id)
        updates (annotations-for orphaned)]
    (testing "every marker carried a net while it was still on a device pin"
      (is (= 4 (count docs)))
      (is (every? #(string? (get-in annotated [% :nets :P])) (keys docs))))
    (testing "disconnecting clears :nets instead of leaving a phantom net"
      (doseq [id (keys docs)]
        (is (= {} (:nets (get updates id))) id)))
    (testing ":nets and :nature stay independently diffed"
      ;; An isolated port has no net type, so nature resolves to the photonic
      ;; default. Markers already recorded photonic therefore need no nature
      ;; rewrite and emit the cleared connectivity alone; markers recorded
      ;; electric emit both, and neither field suppresses the other.
      (let [by-nature (group-by #(get-in annotated [% :nature]) (keys docs))]
        (is (seq (get by-nature "photonic")))
        (is (seq (get by-nature "electric")))
        (doseq [id (get by-nature "photonic")]
          (is (= {:nets {}} (get updates id)) id))
        (doseq [id (get by-nature "electric")]
          (is (= {:nets {} :nature "photonic"} (get updates id)) id))))
    (testing "clearing is idempotent — an already-cleared port is not rewritten"
      (let [cleared (merge-with merge orphaned updates)]
        (doseq [id (keys docs)]
          (is (nil? (get (annotations-for cleared) id)) id))))))

;; @tags mosaic port exposure, nyancir format, reactive indices
(deftest removing-the-wire-to-a-port-clears-its-nets
  ;; The repro reported in #4814: a port marker joined to a device pin by a
  ;; wire, then the wire deleted. Unlike a marker sitting directly on a pin
  ;; cell — which is always reachable from that cell's seed — a marker at the
  ;; far end of a wire genuinely leaves the network when the wire goes.
  (let [device-id (str group-prefix "X1")
        port-id (str group-prefix "P1")
        wire-id (str group-prefix "W1")
        ;; The left pin of the device sits at [10 11]; run a wire from it out
        ;; to [8 11] and put the marker on that far endpoint.
        wired {device-id (component)
               wire-id {:type "wire" :x 10 :y 11 :rx -2 :ry 0 :variant "d"}
               port-id {:type "port" :x 8 :y 11 :transform cm/IV :name "P1"}}
        annotated (merge-with merge wired (annotations-for wired))]
    (testing "the marker is annotated while the wire joins it to the pin"
      (is (string? (get-in annotated [port-id :nets :P]))))
    (testing "deleting the wire clears the marker's :nets"
      (let [updates (annotations-for (dissoc annotated wire-id))]
        (is (= {} (:nets (get updates port-id))))))))

;; @tags mosaic port exposure, nyancir format, reactive indices
(deftest a-port-that-never-carried-a-net-produces-no-write
  (let [bare-id (str group-prefix "bare")
        sch {bare-id {:type "port" :x 40 :y 40 :transform cm/IV
                      :name "bare" :nature "photonic"}}]
    (is (nil? (get (annotations-for sch) bare-id))
        "an unwired port with no :nets key must not be touched")))

;; @tags mosaic port exposure
(deftest action-writes-one-batch-supports-undo-redo-and-skips-an-empty-repeat
  (async done
    (let [device-id (str group-prefix "X1")
          wire-id (str group-prefix "W1")
          initial {device-id (component)
                   wire-id {:type "wire" :x 12 :y 10 :rx 0 :ry 2
                            :variant "d" :name "crossing"}}]
      (reset! pform/modeldb four-port-model)
      (reset! pform/schematic initial)
      (reset! e/undotree @(cm/newundotree))
      (cm/newdo e/undotree initial)
      (go
        (try
          (let [docs (<! (e/expose-ports! device-id))]
            (is (= 4 (count docs)))
            (is (= 2 (count (docs-of-type @pform/schematic "wire")))
                "the crossing wire is split by the real post-action lifecycle")
            (is (every? #(string? (get-in % [:nets :P]))
                        (vals (docs-of-type @pform/schematic "port"))))
            (let [undo-after-first (pr-str @e/undotree)]
              (is (nil? (<! (e/expose-ports! device-id))))
              (is (= undo-after-first (pr-str @e/undotree))
                  "an empty repeat adds no undo checkpoint"))
            (<! (e/undo-schematic))
            (is (= initial @pform/schematic))
            (<! (e/redo-schematic))
            (is (= 2 (count (docs-of-type @pform/schematic "wire"))))
            (is (every? #(string? (get-in % [:nets :P]))
                        (vals (docs-of-type @pform/schematic "port")))
                "redo restores the completed split and annotation state"))
          (finally
            (reset! pform/modeldb {})
            (reset! pform/schematic {})
            (done)))))))
