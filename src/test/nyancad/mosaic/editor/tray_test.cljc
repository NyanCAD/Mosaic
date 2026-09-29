; SPDX-FileCopyrightText: 2026 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.tray-test
  "Tests for the placement-category device tray and picker-first model
   picker (specs/mosaic_device_tray.md, specs/mosaic_model_picker.md).

   Covers `tray-groups` well-formedness (registered types, valid domains,
   unique ids), exact Optical/Electrical inventories and defaults, stable
   filler counts, the `ideal-placement?` exclusion set, `picker-type-match?`,
   `staged-type`, `arm-device!`, and
   `picker-key-down!` (arrow wrapping, Enter confirm, Escape cancel, empty
   result set).

   Split-tray child identity is covered through its generated Hiccup keys.
   Flyout interaction, domain rocker rendering, and picker row markup remain
   visual/DOM behavior covered by manual testing — see the manual test plan in
   PR.md."
  (:require [cljs.test :refer [deftest is testing]]
            [reagent.core :as r]
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.common :as cm]
            [nyancad.mosaic.editor :as e]
            [nyancad.mosaic.editor.platform-test :as pform]))

;; @tags mosaic device tray
(defn- ui-reset []
  (swap! e/ui assoc
         ::e/staging nil
         ::e/tool ::e/cursor
         ::e/mouse [0 0]
         ::e/domain ::e/optical
         ::e/selected #{}
         ::e/dragging nil
         ::e/paste-staging nil))

;; @tags mosaic device tray
(deftest tray-groups-well-formed-test
  (let [valid-types (into cm/device-types cm/schematic-only-types)
        ids (map :id e/tray-groups)]
    (is (apply distinct? ids) "group ids are unique")
    (doseq [{:keys [id domain variants]} e/tray-groups]
      (is (keyword? id))
      (is (contains? #{::e/optical ::e/electrical} domain)
          (str id " has a valid domain"))
      (is (seq variants) (str id " has variants"))
      (doseq [{:keys [title type icon picker-type]} variants]
        (is (and (string? title) (seq title)) (str type " has a title"))
        (is (contains? valid-types type) (str "unregistered type: " type))
        (is (contains? #{nil ::e/any} picker-type)
            (str type " overrides the picker filter only with ::any"))
        (is (vector? icon) (str type " has hiccup icon"))))))

;; @tags mosaic device tray
(deftest visible-tray-groups-test
  (is (= [::e/optical ::e/electrical]
         (map :id e/tray-domains)))
  (testing "Optical exposes Components, the Port flyout, Port label, and Text annotation"
    (is (= [:components :ports :port-label :text-annotation]
           (map :id (e/visible-tray-groups e/tray-groups ::e/optical))))
    (is (= [1 3 1 1]
           (map (comp count :variants)
                (e/visible-tray-groups e/tray-groups ::e/optical)))))
  (testing "Electrical exposes the device picker and three primitive groups"
    (is (= [:electrical-components :terminals :sources :passives]
           (map :id (e/visible-tray-groups e/tray-groups ::e/electrical))))
    (is (= [1 3 2 3]
           (map (comp count :variants)
                (e/visible-tray-groups e/tray-groups ::e/electrical))))))

;; @tags mosaic device tray
(deftest tray-child-keys-follow-group-ids-test
  (letfn [(split-keys [dom]
            (swap! e/ui assoc ::e/domain dom)
            (->> (nth (nth (e/device-tray) 2) 1)
                 (filter #(and (vector? %) (= e/split-tray (first %))))
                 (mapv #(-> % meta :key))))]
    (is (= [:ports] (split-keys ::e/optical)))
    (is (= [:terminals :sources :passives]
           (split-keys ::e/electrical)))))

;; @tags mosaic device tray
(deftest tray-slot-deficit-test
  (testing "items + filler is four in every category, so the bar's right edge
            and rocker never move when the category changes"
    (is (apply = (for [{:keys [id]} e/tray-domains]
                   (+ (count (e/visible-tray-groups e/tray-groups id))
                      (e/tray-slot-deficit e/tray-groups id))))))
  (testing "the widest category needs no filler, and none gets negative filler"
    (let [deficits (map #(e/tray-slot-deficit e/tray-groups (:id %)) e/tray-domains)]
      (is (= 0 (apply min deficits)))
      (is (every? nat-int? deficits))))
  (is (= 0 (e/tray-slot-deficit e/tray-groups ::e/optical)))
  (is (= 0 (e/tray-slot-deficit e/tray-groups ::e/electrical))))

;; @tags mosaic device tray
(deftest representative-first-variant-test
  (testing "each grouped inventory starts with its agreed visible action"
    (let [first-of (fn [id] (->> e/tray-groups
                                 (filter #(= id (:id %)))
                                 first
                                 :variants
                                 first
                                 ((juxt :type :variant))))]
      (is (= ["port" nil] (first-of :ports)))
      (is (= ["port" "text"] (first-of :port-label)))
      (is (= ["text" nil] (first-of :text-annotation)))
      (is (= ["ckt" nil] (first-of :components)))
      (is (= ["ckt" nil] (first-of :electrical-components)))
      (is (= ["port" nil] (first-of :terminals)))
      (is (= ["vsource" nil] (first-of :sources)))
      (is (= ["resistor" nil] (first-of :passives))))))

;; @tags mosaic device tray
(deftest placement-category-coverage-test
  (testing "Both Components actions use the unfiltered picker"
    (is (= [::e/any ::e/any]
           (into [] (comp (mapcat :variants) (keep :picker-type)) e/tray-groups)))
    (is (= ::e/any
           (->> (e/visible-tray-groups e/tray-groups ::e/optical)
                (filter #(= :components (:id %)))
                first :variants first :picker-type)))
    (is (= ::e/any
           (->> (e/visible-tray-groups e/tray-groups ::e/electrical)
                (filter #(= :electrical-components (:id %)))
                first :variants first :picker-type))))
  (testing "Electrical primitive groups contain model-free elements and terminals"
    (is (= #{["port" nil] ["port" "ground"] ["port" "supply"]
             ["vsource" nil] ["isource" nil]
             ["resistor" nil] ["inductor" nil] ["capacitor" nil]}
           (into #{}
                 (comp (remove #(= :electrical-components (:id %)))
                       (mapcat :variants)
                       (map (juxt :type :variant)))
                 (e/visible-tray-groups e/tray-groups ::e/electrical)))))
  (testing "Optical Port contains the ordinary port, Laser, and Detector"
    (is (= [["port" nil] ["port" "emitter"] ["port" "detector"]]
           (->> (e/visible-tray-groups e/tray-groups ::e/optical)
                (filter #(= :ports (:id %)))
                first :variants
                (mapv (juxt :type :variant)))))
    (is (= ["Add port [p]" "Add laser" "Add detector"]
           (->> (e/visible-tray-groups e/tray-groups ::e/optical)
                (filter #(= :ports (:id %)))
                first :variants
                (map :title)))))
  (testing "Optical keeps Port label and Text annotation as separate actions"
    (let [groups (into {} (map (juxt :id identity))
                       (e/visible-tray-groups e/tray-groups ::e/optical))]
      (is (re-find #"port label \[shift\+t\]"
                   (-> groups :port-label :variants first :title)))
      (is (re-find #"name and expose"
                   (-> groups :port-label :variants first :title)))
      (is (= "Add text annotation [t]"
             (-> groups :text-annotation :variants first :title))))))

(deftest ideal-placement-test
  (testing "exclusion set stages ideal directly (no picker)"
    (doseq [t ["resistor" "capacitor" "inductor" "vsource" "isource" "text"]]
      (is (true? (e/ideal-placement? t nil)) t))
    (doseq [v [nil "ground" "supply" "text" "emitter" "detector"]]
      (is (true? (e/ideal-placement? "port" v)) (str "port/" v))))
  (testing "everything else is picker-first"
    (doseq [t ["diode" "nmos" "pmos" "npn" "pnp" "ckt" "amp"
               "straight" "bend" "sbend" "taper" "transition" "terminator"
               "crossing" "ring-single" "ring-double" "spiral" "splitter-1x2"
               "coupler" "mmi-1x2" "mmi-2x2" "mzi-1x2" "mzi-2x2"
               "grating-coupler" "led" "photodiode" "modulator"]]
      (is (false? (e/ideal-placement? t nil)) t))))

(deftest picker-type-match-test
  (is (true? (e/picker-type-match? "bend" "bend")))
  (is (false? (e/picker-type-match? "bend" "straight")))
  (testing "ckt matches the catch-all type, including untyped entries"
    (is (true? (e/picker-type-match? "ckt" "ckt")))
    (is (true? (e/picker-type-match? "ckt" nil)))
    (is (false? (e/picker-type-match? "ckt" "bend"))))
  (testing "amp lists amps only: ckt and amp are separate buttons"
    (is (true? (e/picker-type-match? "amp" "amp")))
    (is (false? (e/picker-type-match? "amp" "ckt")))
    (is (false? (e/picker-type-match? "amp" nil))
        "an untyped entry is a ckt, so it is not in amp's picker")
    (is (false? (e/picker-type-match? "amp" "bend"))))
  (testing "::any is the whole library, untyped entries included"
    (doseq [t ["ckt" "amp" "bend" "nmos" nil]]
      (is (true? (e/picker-type-match? ::e/any t)) (str "::any matches " t)))))

(deftest picker-narrowing-test
  (let [modeldb {"models:pk.bend_euler" {:name "bend_euler" :type "bend" :tags ["waveguides" "bend"]}
                 "models:pk.bend_circular" {:name "bend_circular" :type "bend" :tags ["waveguides" "bend"]}
                 "models:pk.straight" {:name "straight" :type "straight" :tags ["waveguides" "straight"]}
                 "models:pk.cell_x" {:name "cell_x" :type "ckt" :tags ["cells"]}
                 "models:pk.amp_y" {:name "amp_y" :type "amp" :tags ["cells"]}}
        of-type (fn [t] (into {} (filter (fn [[_ m]] (e/picker-type-match? t (:type m))) modeldb)))]
    (is (= #{"models:pk.bend_euler" "models:pk.bend_circular"}
           (set (keys (of-type "bend")))))
    (is (= #{"models:pk.amp_y"}
           (set (keys (of-type "amp"))))
        "amp's picker is amps only")
    (is (= #{"models:pk.cell_x"}
           (set (keys (of-type "ckt")))))
    (is (= (set (keys modeldb))
           (set (keys (of-type ::e/any))))
        "the library button's picker lists every entry")
    (testing "search query narrows within the type filter"
      (is (= #{"models:pk.bend_euler"}
             (set (keys (cm/filter-models (of-type "bend") [] "euler"))))))
    (testing "tags narrow within the type filter"
      (is (= #{"models:pk.bend_euler" "models:pk.bend_circular"}
             (set (keys (cm/filter-models (of-type "bend") ["waveguides"] ""))))))))

(deftest staged-type-test
  (reset! pform/modeldb
          {"models:pk.bend_euler" {:name "bend_euler" :type "bend"}
           "models:pk.die" {:name "die" :type "die"}
           "models:pk.cell_x" {:name "cell_x"}
           "models:pk.weird" {:name "weird" :type "not-a-device-type"}})
  (testing "a model picked from the unfiltered library stages as its own type"
    (is (= "bend" (e/staged-type "ckt" "models:pk.bend_euler"))))
  (testing "a die-typed entry stages as a die, not the button's ckt"
    (is (= "die" (e/staged-type "ckt" "models:pk.die"))))
  (testing "an untyped entry falls back to the button's type"
    (is (= "ckt" (e/staged-type "ckt" "models:pk.cell_x"))))
  (testing "so does a type this build does not know, and a missing entry"
    (is (= "ckt" (e/staged-type "ckt" "models:pk.weird")))
    (is (= "ckt" (e/staged-type "ckt" "models:pk.absent"))))
  (reset! pform/modeldb {}))

(deftest arm-device-test
  (ui-reset)
  (reset! cm/modal-content nil)
  (testing "ideal types stage immediately without opening the picker"
    (e/arm-device! {:type "resistor"} [3 4])
    (is (= "resistor" (:type @e/staging)))
    (is (nil? (:model @e/staging)))
    (is (nil? @cm/modal-content))
    (is (= ::e/device @e/tool)))
  (testing "port variants dispatch to their dedicated placement fns"
    (ui-reset)
    ;; emitter: no baked transform, so it runs under the :test build where
    ;; cm/I is an inert nil stub. The transform-baking variants (ground,
    ;; supply, label, detector) are covered by manual testing — see PR.md.
    (e/arm-device! {:type "port" :variant "emitter"} [1 1])
    (is (= "port" (:type @e/staging)))
    (is (= "emitter" (:variant @e/staging)))
    (is (nil? @cm/modal-content)))
  (testing "picker-first types open the picker instead of staging"
    (ui-reset)
    (e/arm-device! {:type "bend"} [3 4])
    (is (nil? @e/staging))
    (is (some? @cm/modal-content)))
  (testing "confirming arms the staged device with the bare model id"
    (swap! e/ui assoc ::e/mouse [7 8])
    (let [[_ device-type on-select] @cm/modal-content]
      (is (= "bend" device-type))
      (on-select "models:cspdk.si220.cells.bend_euler")
      (is (= "bend" (:type @e/staging)))
      (is (= "cspdk.si220.cells.bend_euler" (:model @e/staging))
          "model stored as bare id")
      (is (= [6 7] [(:x @e/staging) (:y @e/staging)])
          "armed device centers on the cursor via device-dims ([1 1] fallback)")
      (is (= ::e/device @e/tool))))
  (testing "the library button opens an unfiltered picker and stages the picked
            entry's own type, not its own ckt"
    (ui-reset)
    (reset! cm/modal-content nil)
    (reset! pform/modeldb {"models:pk.bend_euler" {:name "bend_euler" :type "bend"}
                           "models:pk.cell_x" {:name "cell_x" :type "ckt"}})
    (e/arm-device! {:type "ckt" :picker-type ::e/any} [3 4])
    (let [[_ device-type on-select] @cm/modal-content]
      (is (= ::e/any device-type) "picker is opened for the whole library")
      (on-select "models:pk.bend_euler")
      (is (= "bend" (:type @e/staging)))
      (is (= "pk.bend_euler" (:model @e/staging))))
    (testing "and a ckt entry picked there still stages as a ckt"
      (ui-reset)
      (reset! cm/modal-content nil)
      (e/arm-device! {:type "ckt" :picker-type ::e/any} [3 4])
      ((nth @cm/modal-content 2) "models:pk.cell_x")
      (is (= "ckt" (:type @e/staging))))
    (reset! pform/modeldb {}))
  (reset! cm/modal-content nil))

;; ---------------------------------------------------------------------------
;; staging-origin
;; ---------------------------------------------------------------------------
;;
;; Contract: the unrounded top-left [:x :y] that puts a device's visual center
;; under a grid point. `draw-background` insets the tetris rect one tile, so a
;; [w h] device's center sits at [:x + 1 + w/2, :y + 1 + h/2] — invert that.
;; Devices with no tetris rect (nil dims: plain ports, text) render one tile at
;; the origin, so their center is [:x + 0.5, :y + 0.5].
;;
;; `add-device` and `drag-staged-device` share this so the ghost cannot jump
;; between arming and the first pointer move; only `add-device` rounds.

(deftest staging-origin-square-device-test
  (testing "a 1x1 tetris device (resistor) centers at cursor - 1.5 on both axes"
    (is (= [8.5 8.5] (e/staging-origin {:type "resistor"} [10 10])))))

(deftest staging-origin-tall-device-test
  (reset! pform/modeldb
          {"models:pk.tall" {:name "tall" :type "mmi-1x2"
                             :ports [{:name "o1" :side "left" :type "photonic"}
                                     {:name "o2" :side "left" :type "photonic"}
                                     {:name "o3" :side "left" :type "photonic"}]}})
  (testing "a 1x3 device offsets by half its height, not its full height"
    ;; The pre-consolidation drag path subtracted the full w/h and padded 0.5,
    ;; which put this at [8.5 6.5] — a whole tile above the cursor.
    (is (= [8.5 7.5] (e/staging-origin {:type "mmi-1x2" :model "pk.tall"} [10 10]))))
  (reset! pform/modeldb {}))

(deftest staging-origin-no-tetris-rect-test
  (testing "text and plain ports put their single tile under the cursor"
    (is (= [9.5 9.5] (e/staging-origin {:type "text"} [10 10])))
    (is (= [9.5 9.5] (e/staging-origin {:type "port"} [10 10])))))

(deftest add-device-centering-test
  (ui-reset)
  (reset! pform/modeldb
          {"models:pk.bend" {:name "bend" :type "bend"
                             :ports [{:name "o1" :side "left" :type "photonic"}
                                     {:name "o2" :side "top" :type "photonic"}]}
           "models:pk.tall" {:name "tall" :type "mmi-1x2"
                             :ports [{:name "o1" :side "left" :type "photonic"}
                                     {:name "o2" :side "left" :type "photonic"}
                                     {:name "o3" :side "left" :type "photonic"}]}})
  (testing "a model-backed photonic device centers on the cursor like a ::bg builtin"
    (e/add-device "resistor" [10 10])
    (is (= [9 9] [(:x @e/staging) (:y @e/staging)]) "resistor centers via ::bg")
    (e/add-device "bend" [10 10] :model "pk.bend")
    (is (= [9 9] [(:x @e/staging) (:y @e/staging)])
        "bend with a model centers via the modeldb port perimeter"))
  (testing "a device taller than one tile offsets by half its height"
    (e/add-device "mmi-1x2" [10 10] :model "pk.tall")
    (is (= [9 8] [(:x @e/staging) (:y @e/staging)])
        "1x3 device: round(10 - 1/2 - 1) = 9, round(10 - 3/2 - 1) = 8"))
  (testing "model-less text keeps its tile under the cursor"
    (e/add-device "text" [10 10])
    (is (= [10 10] [(:x @e/staging) (:y @e/staging)])))
  (reset! pform/modeldb {}))

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
    (cm/add-tag! sel "waveguides")
    (cm/add-tag! sel "type:ckt")
    (is (= ["waveguides" "type:ckt"] @sel))
    (testing "re-adding a selected tag is a no-op"
      (cm/add-tag! sel "waveguides")
      (is (= ["waveguides" "type:ckt"] @sel)))))

(deftest selected-tag-badges-test
  (let [sel (r/atom ["waveguides" "type:ckt"])
        chips #(vec (second (cm/selected-tag-badges sel)))
        click! (fn [chip] ((:on-click (second chip))))]
    (is (= 2 (count (chips))))
    (testing "a chip shows its tag and a removal affordance"
      (is (= ["waveguides" " ×"] (vec (drop 2 (first (chips)))))))
    (testing "property tags keep their prop-tag styling"
      (is (= [nil "prop-tag"] (mapv #(:class (second %)) (chips)))))
    (testing "clicking a chip removes only that tag"
      (click! (second (chips)))
      (is (= ["waveguides"] @sel))
      (click! (first (chips)))
      (is (= [] @sel)))))

(def ^:private picker-db
  {"models:pk.bend_euler" {:name "bend_euler" :type "bend"
                           :tags ["waveguides" "bend" "pk"]}
   "models:pk.bend_circular" {:name "bend_circular" :type "bend"
                              :tags ["waveguides" "circular" "pk"]}
   "models:pk.nfet" {:name "nfet" :type "nmos" :tags ["devices" "fet" "pk"]}
   "models:pk.cell_x" {:name "cell_x" :type "ckt" :tags ["cells" "pk"]}})

(deftest matching-tags-test
  (let [bends (into {} (filter (fn [[_ m]] (= "bend" (:type m))) picker-db))]
    (testing "a tag matching at least one entry on its own is kept"
      (is (= ["waveguides" "bend"] (e/matching-tags bends ["waveguides" "bend"]))))
    (testing "a tag matching nothing is dropped, and order is preserved"
      (is (= ["bend" "pk"] (e/matching-tags bends ["bend" "devices" "pk"]))))
    (testing "property tags are tested the same way"
      (is (= ["type:bend"] (e/matching-tags bends ["type:bend" "type:nmos"]))))
    (testing "tags that each match but do not intersect are left standing"
      (is (= ["bend" "circular"] (e/matching-tags bends ["bend" "circular"])))
      (is (empty? (cm/filter-models bends ["bend" "circular"] ""))
          "the empty intersection is the user's own filter, not stale state"))
    (testing "pruning is idempotent"
      (let [once (e/matching-tags bends ["bend" "devices" "pk"])]
        (is (= once (e/matching-tags bends once)))))))

(deftest picker-tag-persistence-test
  (reset! cm/modal-content nil)
  (reset! pform/modeldb picker-db)
  (testing "opening clears the search text and keeps tags that still apply"
    (reset! e/model-popup-filter "eul")
    (reset! e/model-popup-category ["waveguides" "pk"])
    (e/open-model-picker! "bend" (fn [_]))
    (is (= "" @e/model-popup-filter))
    (is (= ["waveguides" "pk"] @e/model-popup-category)))
  (testing "reopening for another device type drops only what cannot match"
    (e/open-model-picker! "nmos" (fn [_]))
    (is (= ["pk"] @e/model-popup-category)
        "pk is on the nfet too; waveguides cannot be"))
  (testing "a type: tag carried in from another picker cannot strand the list"
    (reset! e/model-popup-category ["type:ckt"])
    (e/open-model-picker! "nmos" (fn [_]))
    (is (= [] @e/model-popup-category))
    (is (seq (cm/filter-models (e/picker-models "nmos") @e/model-popup-category ""))))
  (testing "an empty intersection the user built survives the open"
    (reset! e/model-popup-category ["bend" "circular"])
    (e/open-model-picker! "bend" (fn [_]))
    (is (= ["bend" "circular"] @e/model-popup-category))
    (is (empty? (cm/filter-models (e/picker-models "bend") @e/model-popup-category ""))
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
(deftest visible-picker-models-resolution-unaffected-test
  (reset! pform/modeldb picker-db-with-privates)
  (testing "a private cell stays a stageable, typed model"
    (is (= "ckt" (e/staged-type "ckt" "models:pk._EAM"))))
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
