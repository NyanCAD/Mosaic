; SPDX-FileCopyrightText: 2026 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.tray-test
  "Tests for the device tray inventory and picker-first placement.

   Covers `tray-groups` well-formedness (registered types, unique ids), the
   `ideal-placement?` split (passives, sources, ports and text place directly;
   semiconductors and subcircuits open the picker), `picker-type-match?` (ckt
   catch-all, amp as a ckt variant), `arm-device!` (ideal stages directly,
   picker-first opens the picker, confirming arms with the bare model id, and
   \"Place without model\" stages with none), and `picker-key-down!` (arrow
   wrapping, Enter confirm, Escape cancel, empty result set).

   The split-button flyout interaction and picker row markup are visual/DOM
   behavior and are covered by manual testing."
  (:require [cljs.test :refer [deftest is testing]]
            [reagent.core :as r]
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.common :as cm]
            [nyancad.mosaic.editor :as e]
            [nyancad.mosaic.editor.platform-test :as pform]))

(defn- ui-reset []
  (swap! e/ui assoc
         ::e/staging nil
         ::e/tool ::e/cursor
         ::e/mouse [0 0]
         ::e/selected #{}
         ::e/dragging nil
         ::e/paste-staging nil))

(deftest tray-groups-well-formed-test
  (let [valid-types (into cm/device-types cm/schematic-only-types)
        ids (map :id e/tray-groups)]
    (is (apply distinct? ids) "group ids are unique")
    (doseq [{:keys [id variants]} e/tray-groups]
      (is (keyword? id))
      (is (seq variants) (str id " has variants"))
      (doseq [{:keys [title type icon]} variants]
        (is (and (string? title) (seq title)) (str type " has a title"))
        (is (contains? valid-types type) (str "unregistered type: " type))
        (is (vector? icon) (str type " has hiccup icon"))))))

(defn- domain-groups [dom] (e/visible-tray-groups e/tray-groups dom))

(deftest tray-inventory-test
  (testing "every item belongs to exactly one known domain"
    (is (every? #{::e/electrical ::e/photonic} (map :domain e/tray-groups))))
  (testing "electrical groups appear in the toolbar order"
    (is (= [:ports :resistor :inductor :capacitor :diode :sources :transistors :subcircuits]
           (map :id (domain-groups ::e/electrical)))))
  (testing "single-variant items are flat buttons, multi-variant items are flyouts"
    (is (= [5 1 1 1 1 2 4 2]
           (map (comp count :variants) (domain-groups ::e/electrical)))))
  (testing "every placeable electronic type is reachable from the electrical tray"
    (is (= #{"port" "text" "resistor" "inductor" "capacitor" "diode" "vsource" "isource"
             "nmos" "pmos" "npn" "pnp" "ckt" "amp"}
           (set (for [g (domain-groups ::e/electrical) v (:variants g)] (:type v)))))))

(deftest photonic-inventory-test
  (testing "photonic groups, in order"
    (is (= [:photonic-ports :waveguides :couplers :interferometers
            :opto-electronic :interfaces :library]
           (map :id (domain-groups ::e/photonic)))))
  (testing "every photonic symbol type is reachable from the photonic tray"
    (let [photonic-types #{"straight" "bend" "sbend" "taper" "transition"
                           "terminator" "crossing" "ring-single" "ring-double" "spiral"
                           "splitter-1x2" "coupler" "coupler-ring"
                           "mmi-1x2" "mmi-2x2" "mzi-1x2" "mzi-2x2"
                           "grating-coupler" "led" "photodiode" "modulator"}
          tray-types (set (for [g (domain-groups ::e/photonic) v (:variants g)] (:type v)))]
      (is (every? tray-types photonic-types)
          (str "missing: " (remove tray-types photonic-types)))))
  (testing "the library button lists the whole library"
    (let [lib (first (:variants (first (filter #(= :library (:id %)) e/tray-groups))))]
      (is (= ::e/any (:picker-type lib))))))

(deftest tray-slot-deficit-test
  (testing "the narrower domain is padded to the widest one"
    (is (= 8 (count (domain-groups ::e/electrical))))
    (is (= 7 (count (domain-groups ::e/photonic))))
    (is (= 0 (e/tray-slot-deficit e/tray-groups ::e/electrical)))
    (is (= 1 (e/tray-slot-deficit e/tray-groups ::e/photonic))))
  (testing "a domain with more items than another is the one with no padding"
    (let [groups [{:id :a :domain ::e/electrical}
                  {:id :b :domain ::e/electrical}
                  {:id :c :domain ::e/electrical}
                  {:id :d :domain ::e/photonic}]]
      (is (= 0 (e/tray-slot-deficit groups ::e/electrical)))
      (is (= 2 (e/tray-slot-deficit groups ::e/photonic))))))

(deftest visible-tray-groups-follows-the-domain-test
  (try
    (swap! e/ui assoc ::e/domain ::e/photonic)
    (is (= (map :id (domain-groups ::e/photonic))
           (map :id (e/visible-tray-groups))))
    (swap! e/ui assoc ::e/domain ::e/electrical)
    (is (= (map :id (domain-groups ::e/electrical))
           (map :id (e/visible-tray-groups))))
    (finally (swap! e/ui assoc ::e/domain ::e/electrical))))

(deftest ideal-placement-test
  (testing "passives, sources, ports and text stage ideal directly (no picker)"
    (doseq [t ["resistor" "capacitor" "inductor" "vsource" "isource" "text"]]
      (is (true? (e/ideal-placement? t nil)) t))
    (doseq [v [nil "ground" "supply" "text"]]
      (is (true? (e/ideal-placement? "port" v)) (str "port/" v))))
  (testing "semiconductors and subcircuits are picker-first"
    (doseq [t ["diode" "nmos" "pmos" "npn" "pnp" "ckt" "amp"]]
      (is (false? (e/ideal-placement? t nil)) t))))

(deftest picker-type-match-test
  (is (true? (e/picker-type-match? "nmos" "nmos")))
  (is (false? (e/picker-type-match? "nmos" "pmos")))
  (testing "ckt matches the catch-all type, including untyped entries"
    (is (true? (e/picker-type-match? "ckt" "ckt")))
    (is (true? (e/picker-type-match? "ckt" nil)))
    (is (false? (e/picker-type-match? "ckt" "nmos"))))
  (testing "amp is a ckt variant: amp and ckt entries together"
    (is (true? (e/picker-type-match? "amp" "amp")))
    (is (true? (e/picker-type-match? "amp" "ckt")))
    (is (true? (e/picker-type-match? "amp" nil)))
    (is (false? (e/picker-type-match? "amp" "nmos")))))

(deftest picker-narrowing-test
  (let [modeldb {"models:pk.nfet_a" {:name "nfet_a" :type "nmos" :tags ["fets" "nfet"]}
                 "models:pk.nfet_b" {:name "nfet_b" :type "nmos" :tags ["fets" "nfet"]}
                 "models:pk.pfet" {:name "pfet" :type "pmos" :tags ["fets" "pfet"]}
                 "models:pk.cell_x" {:name "cell_x" :type "ckt" :tags ["cells"]}
                 "models:pk.amp_y" {:name "amp_y" :type "amp" :tags ["cells"]}}
        of-type (fn [t] (into {} (filter (fn [[_ m]] (e/picker-type-match? t (:type m))) modeldb)))]
    (is (= #{"models:pk.nfet_a" "models:pk.nfet_b"}
           (set (keys (of-type "nmos")))))
    (is (= #{"models:pk.cell_x" "models:pk.amp_y"}
           (set (keys (of-type "amp"))))
        "amp's picker includes ckt entries")
    (is (= #{"models:pk.cell_x"}
           (set (keys (of-type "ckt")))))
    (testing "search query narrows within the type filter"
      (is (= #{"models:pk.nfet_a"}
             (set (keys (cm/filter-models (of-type "nmos") [] "nfet_a"))))))
    (testing "tags narrow within the type filter"
      (is (= #{"models:pk.nfet_a" "models:pk.nfet_b"}
             (set (keys (cm/filter-models (of-type "nmos") ["fets"] ""))))))))

(deftest any-picker-and-staged-type-test
  (testing "::any matches every entry, typed or not"
    (is (true? (e/picker-type-match? ::e/any "bend")))
    (is (true? (e/picker-type-match? ::e/any nil))))
  (testing "a type-scoped picker still filters"
    (is (false? (e/picker-type-match? "bend" "straight"))))
  (testing "staged-type prefers the model's own known type"
    (reset! pform/modeldb {"models:a.bend" {:name "bend" :type "bend"}
                           "models:a.untyped" {:name "u"}
                           "models:a.alien" {:name "x" :type "nonsense"}})
    (try
      (is (= "bend" (e/staged-type "ckt" "models:a.bend")))
      (is (= "ckt" (e/staged-type "ckt" "models:a.untyped")))
      (is (= "ckt" (e/staged-type "ckt" "models:a.alien")))
      (finally (reset! pform/modeldb {})))))

(deftest library-button-stages-the-picked-models-type-test
  (ui-reset)
  (reset! cm/modal-content nil)
  (reset! pform/modeldb {"models:a.bend" {:name "bend" :type "bend"}})
  (try
    (e/arm-device! "ckt" nil [3 4] ::e/any)
    (let [[_ device-type on-select] @cm/modal-content]
      (is (= ::e/any device-type))
      (on-select "models:a.bend")
      (is (= "bend" (:type @e/staging)) "stages as a bend, not a generic ckt box")
      (is (= "a.bend" (:model @e/staging))))
    (finally
      (reset! cm/modal-content nil)
      (reset! pform/modeldb {}))))

(deftest arm-device-test
  (ui-reset)
  (reset! cm/modal-content nil)
  (testing "ideal types stage immediately without opening the picker"
    (e/arm-device! "resistor" nil [3 4])
    (is (= "resistor" (:type @e/staging)))
    (is (nil? (:model @e/staging)))
    (is (nil? @cm/modal-content))
    (is (= ::e/device @e/tool)))
  (testing "picker-first types open the picker instead of staging"
    (ui-reset)
    (e/arm-device! "nmos" nil [3 4])
    (is (nil? @e/staging))
    (is (some? @cm/modal-content)))
  (testing "confirming arms the staged device with the bare model id"
    (swap! e/ui assoc ::e/mouse [7 8])
    (let [[_ device-type on-select] @cm/modal-content]
      (is (= "nmos" device-type))
      (on-select "models:pdk.cells.nfet_a")
      (is (= "nmos" (:type @e/staging)))
      (is (= "pdk.cells.nfet_a" (:model @e/staging)) "model stored as bare id")
      (is (= ::e/device @e/tool))))
  (testing "\"Place without model\" stages the device with no model"
    (ui-reset)
    (swap! e/ui assoc ::e/mouse [7 8])
    (e/arm-device! "nmos" nil [3 4])
    (let [[_ _ _ on-skip] @cm/modal-content]
      (is (fn? on-skip))
      (on-skip)
      (is (= "nmos" (:type @e/staging)))
      (is (not (seq (:model @e/staging))))
      (is (= ::e/device @e/tool))))
  (testing "Escape or Cancel stages nothing"
    (ui-reset)
    (e/arm-device! "nmos" nil [3 4])
    (reset! cm/modal-content nil)
    (is (nil? @e/staging)))
  (reset! cm/modal-content nil))

(deftest picker-key-down-test
  (let [filtered [["models:a" {:name "a"}] ["models:b" {:name "b"}] ["models:c" {:name "c"}]]
        highlight (r/atom 0)
        confirmed (atom nil)
        confirm! #(reset! confirmed %)
        key (fn [k] #js {:key k :preventDefault (fn [])})]
    (testing "arrows move the highlight, wrapping at both ends"
      (e/picker-key-down! (key "ArrowUp") filtered highlight confirm!)
      (is (= 2 @highlight) "ArrowUp wraps to the last row")
      (e/picker-key-down! (key "ArrowDown") filtered highlight confirm!)
      (is (= 0 @highlight) "ArrowDown wraps to the first row")
      (e/picker-key-down! (key "ArrowDown") filtered highlight confirm!)
      (is (= 1 @highlight)))
    (testing "Enter confirms the highlighted row's model id"
      (e/picker-key-down! (key "Enter") filtered highlight confirm!)
      (is (= "models:b" @confirmed)))
    (testing "Enter clamps a highlight left dangling by a shrunken list"
      (reset! highlight 5)
      (reset! confirmed nil)
      (e/picker-key-down! (key "Enter") filtered highlight confirm!)
      (is (= "models:c" @confirmed)))
    (testing "Escape closes the modal without confirming"
      (reset! cm/modal-content [:something])
      (reset! confirmed nil)
      (e/picker-key-down! (key "Escape") filtered highlight confirm!)
      (is (nil? @cm/modal-content))
      (is (nil? @confirmed)))
    (testing "empty result set: arrows and Enter are no-ops"
      (reset! highlight 0)
      (reset! confirmed nil)
      (e/picker-key-down! (key "ArrowDown") [] highlight confirm!)
      (e/picker-key-down! (key "Enter") [] highlight confirm!)
      (is (= 0 @highlight))
      (is (nil? @confirmed)))))

(deftest add-tag-test
  (let [sel (r/atom [])]
    (cm/add-tag! sel "devices")
    (cm/add-tag! sel "type:ckt")
    (is (= ["devices" "type:ckt"] @sel))
    (testing "re-adding a selected tag is a no-op"
      (cm/add-tag! sel "devices")
      (is (= ["devices" "type:ckt"] @sel)))))

(deftest selected-tag-badges-test
  (let [sel (r/atom ["devices" "type:ckt"])
        chips #(vec (second (cm/selected-tag-badges sel)))
        click! (fn [chip] ((:on-click (second chip))))]
    (is (= 2 (count (chips))))
    (testing "a chip shows its tag and a removal affordance"
      (is (= ["devices" " ×"] (vec (drop 2 (first (chips)))))))
    (testing "property tags keep their prop-tag styling"
      (is (= [nil "prop-tag"] (mapv #(:class (second %)) (chips)))))
    (testing "clicking a chip removes only that tag"
      (click! (second (chips)))
      (is (= ["devices"] @sel))
      (click! (first (chips)))
      (is (= [] @sel)))))

(def ^:private picker-db
  {"models:pk.nfet_a" {:name "nfet_a" :type "nmos"
                       :tags ["fets" "nfet" "pk"]}
   "models:pk.nfet_b" {:name "nfet_b" :type "nmos"
                       :tags ["fets" "enhancement" "pk"]}
   "models:pk.pfet" {:name "pfet" :type "pmos" :tags ["pfets" "pk"]}
   "models:pk.cell_x" {:name "cell_x" :type "ckt" :tags ["cells" "pk"]}})

(deftest matching-tags-test
  (let [nfets (into {} (filter (fn [[_ m]] (= "nmos" (:type m))) picker-db))]
    (testing "a tag matching at least one entry on its own is kept"
      (is (= ["fets" "nfet"] (e/matching-tags nfets ["fets" "nfet"]))))
    (testing "a tag matching nothing is dropped, and order is preserved"
      (is (= ["nfet" "pk"] (e/matching-tags nfets ["nfet" "pfets" "pk"]))))
    (testing "property tags are tested the same way"
      (is (= ["type:nmos"] (e/matching-tags nfets ["type:nmos" "type:pmos"]))))
    (testing "tags that each match but do not intersect are left standing"
      (is (= ["nfet" "enhancement"] (e/matching-tags nfets ["nfet" "enhancement"])))
      (is (empty? (cm/filter-models nfets ["nfet" "enhancement"] ""))
          "the empty intersection is the user's own filter, not stale state"))
    (testing "pruning is idempotent"
      (let [once (e/matching-tags nfets ["nfet" "pfets" "pk"])]
        (is (= once (e/matching-tags nfets once)))))))

(deftest picker-tag-persistence-test
  (reset! cm/modal-content nil)
  (reset! pform/modeldb picker-db)
  (testing "opening clears the search text and keeps tags that still apply"
    (reset! e/model-popup-filter "nfe")
    (reset! e/model-popup-category ["fets" "pk"])
    (e/open-model-picker! "nmos" (fn [_]))
    (is (= "" @e/model-popup-filter))
    (is (= ["fets" "pk"] @e/model-popup-category)))
  (testing "reopening for another device type drops only what cannot match"
    (e/open-model-picker! "pmos" (fn [_]))
    (is (= ["pk"] @e/model-popup-category)
        "pk is on the pfet too; fets cannot be"))
  (testing "a type: tag carried in from another picker cannot strand the list"
    (reset! e/model-popup-category ["type:ckt"])
    (e/open-model-picker! "pmos" (fn [_]))
    (is (= [] @e/model-popup-category))
    (is (seq (cm/filter-models (e/picker-models "pmos") @e/model-popup-category ""))))
  (testing "an empty intersection the user built survives the open"
    (reset! e/model-popup-category ["nfet" "enhancement"])
    (e/open-model-picker! "nmos" (fn [_]))
    (is (= ["nfet" "enhancement"] @e/model-popup-category))
    (is (empty? (cm/filter-models (e/picker-models "nmos") @e/model-popup-category ""))
        "and the chips are the way out of it"))
  (reset! e/model-popup-category [])
  (reset! pform/modeldb {})
  (reset! cm/modal-content nil))

;; ---------------------------------------------------------------------------
;; visible-picker-models / hide-private-cells
;; ---------------------------------------------------------------------------
;;
;; Contract (specs/mosaic_model_picker.md): the picker's result list applies
;; cm/filter-models (tags + search, ranked when searching) and then the
;; hide-private-cells toggle — which drops `_`-prefixed display names from the
;; list only, never from resolution. A query that itself starts with `_`
;; bypasses the toggle: it is an explicit ask for a private cell.

;; @tags mosaic model picker
(def ^:private picker-db-with-privates
  {"models:pk.EAM" {:name "EAM" :type "ckt"}
   "models:pk._EAM" {:name "_EAM" :type "ckt"}
   "models:pk._terminator" {:name "_terminator" :type "ckt"}
   "models:pk.stream_EAM" {:name "stream_EAM" :type "ckt"}})

;; @tags mosaic model picker
(deftest visible-picker-models-hides-private-cells-test
  (let [names (fn [visible] (map (fn [[_ m]] (:name m)) visible))]
    (testing "toggle on: _-prefixed names drop from the list, publics stay"
      (is (= ["EAM" "stream_EAM"]
             (names (e/visible-picker-models picker-db-with-privates [] "" true)))))
    (testing "toggle off: every entry is listed"
      (is (= ["EAM" "_EAM" "_terminator" "stream_EAM"]
             (names (e/visible-picker-models picker-db-with-privates [] "" false)))))
    (testing "search and tags apply before the private filter"
      (is (= ["EAM" "_EAM" "stream_EAM"]
             (names (e/visible-picker-models picker-db-with-privates [] "eam" false)))))
    (testing "an explicit _-prefixed query bypasses the toggle"
      (is (= ["_EAM" "_terminator" "stream_EAM"]
             (names (e/visible-picker-models picker-db-with-privates [] "_" true)))))
    (testing "a _-query does not hide public matches either"
      (is (contains? (set (names (e/visible-picker-models picker-db-with-privates [] "_EAM" true)))
                     "stream_EAM")))))

;; @tags mosaic model picker
(deftest visible-picker-models-does-not-hide-from-the-modeldb-test
  (reset! pform/modeldb picker-db-with-privates)
  (testing "the picker's type set still contains the private entry —
            hiding is list-level, not modeldb-level"
    (is (contains? (e/picker-models "ckt") "models:pk._EAM")))
  (reset! pform/modeldb {}))

;; @tags mosaic model picker
(deftest hide-private-cells-toggle-test
  (testing "the toggle defaults on and flips both ways"
    (is (true? @e/hide-private-cells))
    (e/set-hide-private-cells! false)
    (is (false? @e/hide-private-cells))
    (e/set-hide-private-cells! true)
    (is (true? @e/hide-private-cells))))

;; @tags mosaic model picker
(deftest hide-private-cells-denied-storage-test
  (testing "webviews whose storage access is denied boot and toggle anyway"
    ;; The Playwright smoke harness boots Mosaic on an opaque origin: the
    ;; localStorage property exists, but reading or writing it throws
    ;; SecurityError (the CI failure on this PR). exists? guards absence —
    ;; the Node test build — not denial, so the guards below must survive a
    ;; throw on every storage touch.
    (.defineProperty js/Object js/globalThis "localStorage"
                     #js {:configurable true
                          :get (fn []
                                 (throw (js/Error. "Failed to read the 'localStorage' property from 'Window': Access is denied for this document.")))})
    (try
      (testing "the boot default is unchanged when storage reads as denied"
        (is (true? (e/initial-hide-private-cells))))
      (testing "toggling works and persisting does not throw"
        (is (nil? (e/set-hide-private-cells! false)))
        (is (false? @e/hide-private-cells))
        (is (nil? (e/set-hide-private-cells! true)))
        (is (true? @e/hide-private-cells)))
      (finally
        (js-delete js/globalThis "localStorage")))))
