; SPDX-FileCopyrightText: 2026 Pepijn de Vos
; SPDX-License-Identifier: MPL-2.0
(ns nyancad.mosaic.gfpatom-test
  (:require [cljs.test :refer [deftest is async]]
            [cljs.core.async :refer [go <! put! promise-chan timeout poll!]]
            [reagent.core :as r]
            [nyancad.mosaic.jsatom.protocol :as ja]
            [nyancad.mosaic.editor :as editor]
            [nyancad.mosaic.editor.platform-test :as platform]
            [nyancad.mosaic.invocation :as invocation]
            [nyancad.mosaic.common :as cm]))

;; @tags factory resolution
(deftest parameterizations-share-documents-not-resolution-slots
  (async done
    (let [key-a "{\"outputs\":2}"
          key-b "{\"outputs\":4}"
          path-a ["models:pkg.cell" :parameterizations key-a]
          path-b ["models:pkg.cell" :parameterizations key-b]
          replies (atom {})
          source (ja/make-gfp-atom
                   "{\"models:pkg.cell\":{\"generated\":true,\"name\":\"cell\",\"type\":\"ckt\",\"symbol\":\"default.svg\"}}"
                   (r/atom {})
                   (fn [path]
                     (let [reply (promise-chan)]
                       (swap! replies assoc path reply)
                       reply)))
          a (ja/get-async source path-a)
          b (ja/get-async source path-b)
          default (ja/get-async source "models:pkg.cell")
          cursor (ja/gfp-cursor source (conj path-a :ports))]
      (is (identical? a (ja/get-async source path-a)))
      (is (nil? @cursor))
      (go
        (<! (timeout 0))
        (is (= 3 (count @replies)))
        (let [document {:generated true :name "cell" :type "ckt" :symbol "default.svg"
                        :parameterizations {key-a {:ports [] :symbol nil}}}
              update {:session "A" :revision 1 :replace false :resolved true
                      :parameterization key-a :entries {"models:pkg.cell" document}}]
          (put! (get @replies path-a) update)
          (let [result (<! a)]
            (is (= "cell" (:name result)))
            (is (= [] (:ports result)))
            (is (nil? (:symbol result))))
          (is (= [] @cursor))
          (is (nil? (poll! b)))
          (is (nil? (poll! default)))
          (put! (get @replies path-b)
                (-> update
                    (assoc :revision 2 :parameterization key-b)
                    (assoc-in [:entries "models:pkg.cell" :parameterizations key-b]
                              {:ports [{:name "o4"}] :symbol "four.svg"})))
          (is (= [{:name "o4"}] (:ports (<! b))))
          (is (= [] @cursor))
          (is (= 2 (count (:parameterizations (get @source "models:pkg.cell")))))
          (ja/receive-entries! source
                              (assoc update :revision 3 :resolved false :parameterization nil
                                     :entries {"models:pkg.cell" (dissoc document :parameterizations)}))
          (is (nil? @cursor))
          (is (not (identical? a (ja/get-async source path-a)))))
        (done)))))

;; @tags factory resolution
(deftest parameterization-keys-survive-injection-and-rpc-decoding
  (let [params-key "{\"cross_section\":\"pdk/strip\"}"
        document {:generated true :name "cell" :type "ckt"
                  :parameterizations {params-key {:ports []}}}
        payload #js {:session "A" :revision 1 :replace false :resolved true
                     :parameterization params-key
                     :entries (clj->js {"models:pkg.cell" document})}
        cache (r/atom {})
        source (ja/make-gfp-atom
                 (js/JSON.stringify (.-entries payload)) cache (fn [_] (promise-chan)))]
    (is (= #{params-key} (set (keys (:parameterizations (get @source "models:pkg.cell"))))))
    (is (= #{params-key}
           (set (keys (get-in (ja/decode-entries payload)
                              [:entries "models:pkg.cell" :parameterizations])))))))

(def model-key "models:pkg.cell")
(def skeleton {:generated true :name "cell" :type "ckt"})

;; @tags factory resolution, nyanlib default contract
(deftest normalized-invocations-use-only-known-runtime-defaults
  (let [baseline (assoc skeleton :type "die" :metadata-version 7
                        :props [{:name "npads" :default 8}
                                {:name "optional" :default nil}
                                {:name "enabled" :default false}
                                {:name "unknown"}])
        path (invocation/lookup-path model-key {:npads 8 :optional nil :enabled false :unknown nil})]
    (is (= [model-key :parameterizations "{\"unknown\":null}"]
           (invocation/normalized-path baseline path)))
    (is (= path (invocation/normalized-path (dissoc baseline :metadata-version) path)))
    (is (= [model-key] (invocation/normalized-path baseline
                                                  (invocation/lookup-path model-key {:npads 8}))))
    (is (= "die" (:type (invocation/lookup {model-key baseline} path))))
    (is (= [] (:ports (invocation/lookup
                       {model-key (assoc baseline :parameterizations {"{\"npads\":10.0}" {:ports []}})}
                       (invocation/lookup-path model-key {:npads 10 :enabled false})))))))

;; @tags factory resolution
(deftest baseline-push-does-not-retire-pending-variant-and-empty-override-settles
  (async done
    (let [replies (atom {})
          baseline (assoc skeleton :type "die" :metadata-version 7
                          :props [{:name "npads" :default 8}])
          source (ja/make-gfp-atom (js/JSON.stringify (clj->js {model-key skeleton})) (r/atom {})
                                  (fn [path]
                                    (let [reply (promise-chan)] (swap! replies assoc path reply) reply)))
          path (invocation/lookup-path model-key {:npads 10})
          pending (ja/get-async source path)]
      (ja/receive-entries! source {:session "A" :revision 1 :replace false :resolved true
                                  :entries {model-key baseline}})
      (is (nil? (poll! pending)))
      (is (identical? pending (ja/get-async source path)))
      (is (= "die" @(ja/gfp-cursor source (conj path :type))))
      (go
        (<! (timeout 0))
        (put! (get @replies path)
              {:session "A" :revision 2 :replace false :resolved true :parameterization "{\"npads\":10}"
               :entries {model-key (assoc baseline :parameterizations {"{\"npads\":10.0}" {}})}})
        (is (= "die" (:type (<! pending))))
        (is (= "die" (:type (<! (ja/get-async source path)))))
        (is (= 1 (count @replies)))
        (done)))))

;; @tags factory resolution, nyanlib default contract
(deftest pre-baseline-default-and-normalized-replies-satisfy-future-demand
  (async done
    (go
      (doseq [[props variant] [[{:npads 8 :enabled false} nil]
                             [{:npads 10 :enabled false} "{\"npads\":10.0}"]]]
        (let [calls (atom [])
              reply (promise-chan)
              source (ja/make-gfp-atom (js/JSON.stringify (clj->js {model-key skeleton})) (r/atom {})
                                      (fn [path] (swap! calls conj path) reply))
              path (invocation/lookup-path model-key props)
              pending (ja/get-async source path)
              baseline (cond-> (assoc skeleton :type "die" :metadata-version 7
                                              :props [{:name "npads" :default 8} {:name "enabled" :default false}])
                         variant (assoc :parameterizations {variant {}}))]
          (put! reply {:session "A" :revision 1 :replace false :resolved true
                       :parameterization (nth path 2) :entries {model-key baseline}})
          (is (= "die" (:type (<! pending))))
          (is (= "die" (:type (<! (ja/get-async source path)))))
          (is (= "die" (:type (<! (ja/get-async source (if variant
                                                       (invocation/lookup-path model-key {:npads 10})
                                                       model-key))))))
          (<! (timeout 0))
          (is (= [path] @calls))))
      (done))))

;; @tags factory resolution, nyanlib default contract
(deftest collapsing-default-reply-settles-an-already-pending-default-reader
  (async done
    (let [reply (promise-chan)
          path (invocation/lookup-path model-key {:npads 8})
          source (ja/make-gfp-atom (js/JSON.stringify (clj->js {model-key skeleton})) (r/atom {})
                                  (fn [requested] (if (= path requested) reply (promise-chan))))
          explicit (ja/get-async source path)
          default (ja/get-async source model-key)]
      (put! reply {:session "A" :revision 1 :replace false :resolved true
                   :parameterization (nth path 2)
                   :entries {model-key (assoc skeleton :type "die" :metadata-version 7
                                             :props [{:name "npads" :default 8}])}})
      (go
        (is (= "die" (:type (<! explicit))))
        (is (= "die" (:type (<! default))))
        (is (= "die" (:type (<! (ja/get-async source model-key)))))
        (done)))))

;; @tags factory resolution, photonic model ports
(deftest parameter-edit-keeps-die-dispatch-through-pending-resolution
  (let [baseline (assoc skeleton :type "die" :metadata-version 7
                        :props [{:name "npads" :default 8}]
                        :parameterizations {"{\"npads\":10}" {}})
        source (ja/make-gfp-atom (js/JSON.stringify (clj->js {model-key baseline})) (r/atom {})
                                (fn [_] (promise-chan)))
        dev {:model "pkg.cell" :type "die" :props {:npads 10}}]
    (with-redefs [platform/modeldb source
                  platform/model-cursor (fn [source & path] (ja/gfp-cursor source (vec path)))]
      (doseq [device [dev (assoc-in dev [:props :npads] 12)]]
        (is (= "die" (:type (nth (editor/get-model ::editor/sym device "X1" device) 2))))))
    ;; Invalidation keeps last-known Dschematic presentation, even for old files
    ;; whose stored type predates the first successful resolution.
    (with-redefs [platform/model-cursor
                  (fn [_ & _] (r/atom (assoc skeleton :type "die" :has-dschematic true)))]
      (let [old-device (assoc dev :type "ckt")]
        (is (= "die" (:type (nth (editor/get-model ::editor/sym old-device "X1" old-device) 2))))))
    ;; A cold skeleton does not override the saved instance's authoritative type.
    (with-redefs [platform/model-cursor (fn [_ & _] (r/atom skeleton))]
      (is (= "die" (:type (nth (editor/get-model ::editor/sym dev "X1" dev) 2)))))))

;; @tags factory resolution, mosaic port exposure
(deftest snapshot-geometry-selects-the-instances-parameterization
  (let [key "{\"outputs\":4}"
        ports (mapv (fn [n] {:name (str "o" n) :side "right" :type "photonic"}) (range 4))
        snapshot {model-key (assoc skeleton :ports [{:name "default" :side "left"}]
                                  :parameterizations {key {:ports ports}})}
        dev {:model "pkg.cell" :type "ckt" :props {:outputs 4} :x 0 :y 0}]
    (is (= [model-key] (invocation/lookup-path model-key {})))
    (is (= [model-key :parameterizations "{\"outputs\":null}"]
           (invocation/lookup-path model-key {:outputs nil})))
    (is (= ["models:local.gsch"] (invocation/lookup-path "models:local.gsch" {:outputs 4})))
    (is (= #{"o0" "o1" "o2" "o3"}
           (set (map :name (editor/device-port-types snapshot dev)))))
    (is (= 4 (count (first (editor/circuit-locations snapshot dev)))))
    (is (= #{"default"}
           (set (map :name (editor/device-port-types snapshot (assoc dev :props {}))))))))

;; @tags factory resolution
(deftest platform-lookups-use-variadic-paths
  (async done
    (let [source (r/atom {model-key {:ports [{:name "o1"}] :enabled false}})
          cursor (platform/model-cursor source model-key :ports 0 :name)]
      (is (= "o1" @cursor))
      (swap! source assoc-in [model-key :ports 0 :name] "o2")
      (is (= "o2" @cursor))
      (go
        (is (= "o2" (<! (platform/get-model-async source model-key :ports 0 :name))))
        (is (false? (<! (platform/get-model-async source model-key :enabled))))
        (is (nil? (<! (platform/get-model-async source model-key :missing))))
        (is (instance? js/Error (<! (platform/get-model-async source "missing" :ports))))
        (is (= @source (<! (platform/get-model-async source))))
        (done)))))

;; @tags factory resolution
(defn harness []
  (let [calls (atom [])
        reply (promise-chan)
        cache (r/atom {})
        gfp (ja/make-gfp-atom (js/JSON.stringify (clj->js {model-key skeleton})) cache
                             (fn [requested-key] (swap! calls conj requested-key) reply))]
    {:gfp gfp :cache cache :calls calls :reply reply}))

;; @tags factory resolution
(defn update-entry [revision entry]
  {:session "A" :revision revision :replace false :resolved true :entries {model-key entry}})

;; @tags factory resolution
(deftest empty-model-identities-do-not-register-demand
  (let [calls (atom [])]
    (with-redefs [platform/model-cursor
                  (fn [_ & path]
                    (swap! calls conj path)
                    (r/atom skeleton))]
      (is (nil? (editor/model-entry nil)))
      (is (nil? (editor/model-entry "")))
      (is (empty? @calls))
      (is (= skeleton (editor/model-entry "pkg.cell")))
      (is (= [[model-key]] @calls)))))

;; @tags factory resolution
(deftest local-cursors-do-not-create-async-results
  (let [authored {:generated false :name "Authored"}
        entries {model-key authored "models:local.gsch" skeleton}
        cache (r/atom {})
        gfp (ja/make-gfp-atom (js/JSON.stringify (clj->js entries)) cache
                              (fn [_] (throw (js/Error. "unexpected RPC"))))]
    ;; The asynchronous accessor allocates even for local data. Cursor reads
    ;; bypass it entirely, including absent .gsch entries and whole-cache reads.
    (with-redefs [ja/get-async (fn [& _] (throw (js/Error. "unexpected async result")))]
      (is (= "Authored" @(ja/gfp-cursor gfp [model-key :name])))
      (is (= "cell" @(ja/gfp-cursor gfp ["models:local.gsch" :name])))
      (is (nil? @(ja/gfp-cursor gfp ["models:missing.gsch"])))
      (is (= {model-key (assoc authored :x 0 :y 0)
              "models:local.gsch" (assoc skeleton :x 0 :y 1)}
             @(ja/gfp-cursor gfp [])))
      (swap! cache assoc-in [model-key :name] "Renamed")
      (is (= "Renamed" @(ja/gfp-cursor gfp [model-key :name]))))))

;; @tags factory resolution
(deftest removal-reply-clears-last-known-entry
  (async done
    (let [{:keys [gfp reply]} (harness)
          pending (ja/get-async gfp model-key)]
      (put! reply (update-entry 1 nil))
      (go
        (is (instance? js/Error (<! pending)))
        (is (not (contains? @gfp model-key)))
        (is (instance? js/Error (<! (ja/get-async gfp model-key))))
        (done)))))

;; @tags factory resolution
(deftest malformed-catalogues-preserve-cache-and-ordering
  (let [{:keys [gfp]} (harness)
        valid (update-entry 3 (assoc skeleton :type "straight"))]
    (is (= valid (ja/decode-entries (clj->js valid))))
    (is (= (update-entry 4 nil) (ja/decode-entries (clj->js (update-entry 4 nil)))))
    (ja/receive-entries! gfp valid)
    (doseq [malformed [nil {} (assoc valid :revision nil)
                       (assoc valid :revision "4") (assoc valid :session nil)
                       (assoc valid :entries nil) (assoc valid :entries [])
                       (assoc valid :entries {model-key false})]]
      (is (instance? js/Error (ja/decode-entries (clj->js malformed))))
      (is (instance? js/Error (ja/receive-entries! gfp malformed)))
      (is (= "straight" (:type (get @gfp model-key)))))
    (ja/receive-entries! gfp (update-entry 2 skeleton))
    (is (= "straight" (:type (get @gfp model-key))))
    (ja/receive-entries! gfp (update-entry 4 (assoc skeleton :type "coupler")))
    (is (= "coupler" (:type (get @gfp model-key))))))

;; @tags factory resolution
(deftest malformed-resolution-settles-as-an-error
  (async done
    (let [{:keys [gfp reply]} (harness)
          pending (ja/get-async gfp model-key)
          before @gfp]
      (put! reply (ja/decode-entries nil))
      (go
        (is (instance? js/Error (<! pending)))
        (is (= before @gfp))
        (is (identical? pending (ja/get-async gfp model-key)))
        (done)))))

;; @tags factory resolution
(deftest cursor-and-channel-share-resolution
  (async done
    (let [{:keys [gfp cache calls reply]} (harness)
          cursor (ja/gfp-cursor gfp [model-key :type])
          result (ja/get-async gfp model-key)
          notifications (atom 0)]
      (is (= "ckt" @cursor))
      (is (not (satisfies? ISwap gfp)))
      (is (identical? result (ja/get-async gfp model-key)))
      (add-watch cache :test (fn [_ _ _ _] (swap! notifications inc)))
      (go
        (<! (timeout 0))
        (is (= [model-key] @calls))
        (put! reply (update-entry 1 (assoc skeleton :type "straight" :ports [])))
        (let [entry (<! result)]
          (is (= "straight" (:type entry)))
          (is (= [] (:ports entry)))
          (is (= entry (<! result)))
          (is (= "straight" @cursor))
          (is (= 1 @notifications)))
        (done)))))

;; @tags factory resolution
(deftest watcher-push-wins-over-older-request-and-snapshot
  (async done
    (let [{:keys [gfp reply]} (harness)
          pending (ja/get-async gfp model-key)]
      (ja/receive-entries! gfp (update-entry 3 (assoc skeleton :ports [{:name "new"}])))
      (put! reply (update-entry 2 (assoc skeleton :ports [{:name "old"}])))
      (ja/receive-entries! gfp {:session "A" :revision 1 :replace true :resolved false :entries {model-key skeleton}})
      (go
        (is (= [{:name "new"}] (:ports (<! pending))))
        (<! (timeout 0))
        (is (= [{:name "new"}] (:ports (get @gfp model-key))))
        (ja/receive-entries! gfp {:session "A" :revision 4 :replace true :resolved false :entries {}})
        (ja/receive-entries! gfp (update-entry 2 skeleton))
        (is (not (contains? @gfp model-key)))
        (is (instance? js/Error (<! (ja/get-async gfp model-key))))
        (done)))))

;; @tags factory resolution
(deftest snapshot-fences-uncached-and-pending-keys
  (async done
    (let [{:keys [gfp reply]} (harness)
          missing "models:pkg.removed"
          pending (ja/get-async gfp missing)]
      (ja/receive-entries! gfp {:session "A" :revision 10 :replace true :resolved false :entries {model-key skeleton}})
      (put! reply (assoc (update-entry 9 skeleton) :entries {missing skeleton}))
      (ja/receive-entries! gfp (assoc (update-entry 8 skeleton) :entries {"models:pkg.unseen" skeleton}))
      (go
        (is (instance? js/Error (<! pending)))
        (<! (timeout 0))
        (is (= #{model-key} (set (keys @gfp))))
        (done)))))

;; @tags factory resolution
(deftest subscription-snapshot-resets-restarted-server-ordering
  (async done
    (let [{:keys [gfp reply]} (harness)]
      (ja/receive-entries! gfp (update-entry 100 (assoc skeleton :type "straight")))
      (let [previous (ja/get-async gfp model-key)
            pending (ja/get-async gfp "models:pkg.pending")]
        (ja/receive-entries! gfp {:session "B" :revision 0 :replace true :resolved false
                                 :entries {model-key skeleton}} true)
        ;; An old catch-up response cannot switch the session back to A.
        (ja/receive-entries! gfp {:session "A" :revision 101 :replace true :resolved false :entries {}})
        (let [current (ja/get-async gfp model-key)]
          (is (not (identical? previous current)))
          (put! reply (assoc (update-entry 1 (assoc skeleton :type "coupler")) :session "B"))
          (go
            (is (instance? js/Error (<! pending)))
            (is (= "coupler" (:type (<! current))))
            (is (= "B" (:gfp/session (get @gfp model-key))))
            (done)))))))

;; @tags factory resolution
(deftest authored-and-web-models-stay-local
  (async done
    (let [cache (r/atom {})
          authored {:generated false :type "amp"}
          gfp (ja/make-gfp-atom (js/JSON.stringify (clj->js {model-key authored})) cache
                               (fn [_] (throw (js/Error. "unexpected RPC"))))
          web (r/atom {model-key authored})]
      (go
        (is (= "amp" (:type (<! (ja/get-async gfp model-key)))))
        (is (= authored (<! (ja/get-async web model-key))))
        (is (= "amp" @(ja/gfp-cursor web [model-key :type])))
        (done)))))

;; @tags factory resolution
(deftest failed-lookups-stay-stable-until-a-watcher-result
  (async done
    (let [{:keys [gfp calls reply]} (harness)
          pending (ja/get-async gfp model-key)]
      (put! reply (ex-info "worker failed" {}))
      (go
        (is (instance? js/Error (<! pending)))
        (ja/gfp-cursor gfp [model-key :ports])
        (is (identical? pending (ja/get-async gfp model-key)))
        (is (= [model-key] @calls))
        (is (= "ckt" (:type (get @gfp model-key))))
        (ja/receive-entries! gfp (update-entry 2 (assoc skeleton :ports [])))
        (is (= [] (:ports (<! (ja/get-async gfp model-key)))))
        (done)))))

;; ---------------------------------------------------------------------------
;; Placement readiness gate (specs/factory_resolution.md § Placement)
;; ---------------------------------------------------------------------------

;; @tags factory resolution, mosaic model picker
(defn- pick-factory!
  "Arm a factory-backed device through the public tray/model-picker entry point,
   as confirming `model-id` in the library picker does, at the last canvas mouse
   position."
  [model-id]
  (reset! cm/modal-content nil)
  (editor/arm-device! {:type "ckt" :picker-type ::editor/any} [3 4])
  ((nth @cm/modal-content 2) model-id))

;; @tags factory resolution
(defn- canvas-click []
  #js {:button 0 :stopPropagation (fn [])})

;; @tags factory resolution, factory card dragging
(defn- drop-event
  "A drop event carrying `fqn` in the sidebar's `text/x-gfp-factory` payload.
   Coordinates come from `cm/viewbox-coord`, which the drop tests redef."
  [fqn]
  #js {:preventDefault (fn [])
       :stopPropagation (fn [])
       :dataTransfer #js {:getData (fn [_] (js/JSON.stringify #js {:factory fqn}))}})

;; @tags factory resolution
(defn- placement-fixture!
  "Reset the placement surfaces for one test; `modeldb` defaults to empty."
  ([] (placement-fixture! {}))
  ([modeldb]
   (reset! platform/schematic {})
   (reset! platform/modeldb modeldb)
   (reset! editor/undotree @(cm/newundotree))
   (swap! editor/ui assoc
          ::editor/staging nil ::editor/arm-result nil ::editor/committing false
          ::editor/dragging nil ::editor/tool ::editor/cursor)))

;; @tags factory resolution
(defn- await-ready!
  "Yield until the armed placement leaves its busy state."
  []
  (go (loop [n 0]
        (when (and (editor/placement-busy?) (< n 512))
          (<! (timeout 0))
          (recur (inc n))))))

;; @tags factory resolution
(defn- await-undo!
  "Yield until the undo tree satisfies `pred` — the placement's undo checkpoint."
  [pred]
  (go (loop [n 0]
        (when (and (not (pred)) (< n 512))
          (<! (timeout 0))
          (recur (inc n))))))

;; @tags factory resolution, mosaic wire tool
(deftest model-less-placement-preserves-schematic-types
  (async done
    (placement-fixture!)
    (go
      (doseq [dev [{:type "wire" :x 1 :y 2 :rx 3 :ry 0 :variant "d"}
                   {:type "port" :x 5 :y 2 :variant "emitter" :name "LS1"}
                   {:type "text" :x 7 :y 2 :name "Note"}]]
        (<! (editor/commit-staged dev)))
      (is (= #{"wire" "port" "text"} (set (map :type (vals @platform/schematic)))))
      (is (= [3 0] ((juxt :rx :ry) (first (filter #(= "wire" (:type %))
                                                (vals @platform/schematic))))))
      (done))))

;; @tags factory resolution, nyanlib default contract, mosaic model picker
(deftest factory-placement-lifecycle
  ;; Arming a factory resolves the selected invocation and hides the ghost until
  ;; it lands; a pre-readiness click is rejected and never replayed; once
  ;; resolved a fresh click places it with resolved defaults, snapped
  ;; coordinates, an allocated name, one undo checkpoint, and no resolution
  ;; metadata in the saved device.
  (async done
    (placement-fixture!)
    (let [reply (promise-chan)]
      (with-redefs [platform/get-model-async (fn [_ & _] reply)]
        (swap! editor/ui assoc ::editor/mouse [5.5 8.5])
        (pick-factory! "models:pkg.cell")
        (is (= "pkg.cell" (:model (::editor/staging @editor/ui))))
        (is (nil? (editor/tool-elements)) "the ghost is hidden until resolved")
        (is (editor/placement-busy?) "the canvas shows a busy cursor until resolved")
        (is (empty? @platform/schematic) "arming stages, it does not insert")
        (editor/drag-end (canvas-click))
        (is (empty? @platform/schematic) "a pre-readiness click inserts nothing"))
      (put! reply {:generated false :name "cell" :type "straight" :symbol "straight.svg"
                   :props [{:name "length" :default 17}
                           {:name "width" :default 0.5}]})
      (go
        (<! (await-ready!))
        (is (not (editor/placement-busy?)) "resolution clears the busy state")
        (is (some? (editor/tool-elements)) "the preview appears once resolved")
        (is (empty? @platform/schematic)
            "resolution alone inserts nothing; the rejected click is not replayed")
        (editor/drag-end (canvas-click))
        (<! (await-undo! #(= @platform/schematic (cm/undo-state @editor/undotree))))
        (let [[id device] (first @platform/schematic)
              serialized (:doc' (ja/edit-message "{}" [id] device))]
          (is (= 1 (count @platform/schematic)) "the fresh click places exactly once")
          (is (= [4 7] [(:x device) (:y device)]) "coordinates snap to the grid")
          (is (= "straight" (:type device)))
          (is (= {:length 17 :width 0.5} (:props device)) "resolved defaults merge")
          (is (string? (:name device)) "a name is allocated at insertion")
          (is (= @platform/schematic (cm/undo-state @editor/undotree))
              "the undo checkpoint holds the placed device")
          (is (not (re-find #"straight\.svg|generated" serialized))
              "the saved device serializes no resolved-model metadata"))
        (done)))))

;; @tags factory resolution, nyanlib default contract
(deftest warm-invocation-is-immediately-placeable
  ;; An already-resolved invocation delivers synchronously through `take!`: the
  ;; arm never enters the busy state, so the very next click places it.
  (async done
    (placement-fixture!)
    (let [reply (promise-chan)]
      (put! reply {:type "straight" :props [{:name "length" :default 17}]})
      (with-redefs [platform/get-model-async (fn [_ & _] reply)]
        (swap! editor/ui assoc ::editor/mouse [5.5 8.5])
        (pick-factory! "models:pkg.cell")
        (is (not (editor/placement-busy?)) "a warm arm is ready synchronously")
        (is (some? (editor/tool-elements)) "a warm arm previews at once"))
      (go
        (editor/drag-end (canvas-click))
        (<! (await-undo! #(= @platform/schematic (cm/undo-state @editor/undotree))))
        (let [device (first (vals @platform/schematic))]
          (is (= [4 7] [(:x device) (:y device)]))
          (is (= {:length 17} (:props device))))
        (done)))))

;; @tags factory resolution, nyanlib default contract
(deftest explicit-props-parameterize-the-invocation
  ;; Arming resolves the invocation selected by the explicit props; insertion
  ;; keeps the explicit value and fills the rest from resolved defaults.
  (async done
    (placement-fixture!)
    (let [reply (promise-chan)
          lookup (atom nil)]
      (with-redefs [platform/get-model-async (fn [_ & keys] (reset! lookup keys) reply)]
        (editor/add-device "ckt" [5.5 8.5] :model "pkg.cell" :props {:length 23})
        (is (= [model-key :parameterizations "{\"length\":23}"] @lookup)
            "arming resolves the invocation selected by the supplied props")
        (is (empty? @platform/schematic)))
      (put! reply {:type "straight" :props [{:name "length" :default 17}
                                            {:name "width" :default 0.5}]})
      (go
        (<! (await-ready!))
        (editor/drag-end (canvas-click))
        (<! (await-undo! #(= @platform/schematic (cm/undo-state @editor/undotree))))
        (let [device (first (vals @platform/schematic))]
          (is (= 1 (count @platform/schematic)))
          (is (= {:length 23 :width 0.5} (:props device))
              "explicit props win; resolved defaults fill the rest"))
        (done)))))

;; @tags factory resolution
(deftest retired-arm-reply-neither-readies-nor-alerts
  ;; Re-arming retires the previous arm's result cell: a late success or error
  ;; from it can neither ready nor alert for the newer selection, which places
  ;; its own model.
  (async done
    (go
      (doseq [[kind stale] {:success {:type "stale" :props [{:name "length" :default 1}]}
                            :error (ex-info "worker failed" {})}]
        (placement-fixture!)
        (let [stale-reply (promise-chan)
              live-reply (promise-chan)
              replies (atom [stale-reply live-reply])]
          (with-redefs [platform/get-model-async
                        (fn [_ & _] (let [r (first @replies)] (swap! replies rest) r))]
            (swap! editor/ui assoc ::editor/mouse [5.5 8.5])
            (pick-factory! "models:pkg.cell")
            (pick-factory! "models:pkg.other")
            (put! stale-reply stale))
          ;; let the dispatched stale callback run
          (<! (timeout 0))
          (is (editor/placement-busy?) (str kind " reply leaves the live arm pending"))
          (is (not (re-find #"Cannot place component" (str @cm/modal-content)))
              (str kind " reply raises no alert"))
          (put! live-reply {:type "coupler" :props [{:name "radius" :default 9}]})
          (<! (await-ready!))
          (editor/drag-end (canvas-click))
          (<! (await-undo! #(= @platform/schematic (cm/undo-state @editor/undotree))))
          (let [device (first (vals @platform/schematic))]
            (is (= 1 (count @platform/schematic)) (str kind " reply inserts nothing"))
            (is (= "coupler" (:type device)) (str kind " reply: the live selection places"))
            (is (= {:radius 9} (:props device))))))
      (done))))

;; @tags factory resolution
(deftest cancel-before-resolution-leaves-nothing-staged
  (async done
    (placement-fixture!)
    (let [reply (promise-chan)]
      (with-redefs [platform/get-model-async (fn [_ & _] reply)]
        (pick-factory! "models:pkg.cell")
        (editor/cancel))
      (put! reply {:type "straight"})
      (go
        (<! (await-ready!))
        (is (empty? @platform/schematic)
            "cancelling before resolution leaves nothing staged or inserted")
        (is (not (editor/placement-busy?)))
        (is (nil? (editor/tool-elements)) "cancel clears the staged ghost")
        (done)))))

;; @tags factory resolution
(deftest resolution-failure-alerts-and-leaves-a-fresh-arm-placeable
  ;; A failed resolution alerts and clears the pending placement without arming;
  ;; a later, resolvable arm still previews and places on a fresh click.
  (async done
    (placement-fixture!)
    (let [reply (promise-chan)]
      (with-redefs [platform/get-model-async (fn [_ & _] reply)]
        (swap! editor/ui assoc ::editor/mouse [5.5 8.5])
        (pick-factory! "models:pkg.cell")
        (put! reply (ex-info "worker failed" {:key model-key})))
      (go
        (<! (await-ready!))
        (is (empty? @platform/schematic) "a resolution failure inserts nothing")
        (is (re-find #"Cannot place component" (str @cm/modal-content))
            "resolution failure alerts")
        (is (not (editor/placement-busy?)) "failure leaves no stuck busy state")
        (is (nil? (editor/tool-elements)) "failure clears the pending placement")
        (reset! platform/modeldb
                {model-key {:generated false :name "cell" :type "straight"
                            :props [{:name "length" :default 5}]}})
        (pick-factory! "models:pkg.cell")
        (<! (await-ready!))
        (editor/drag-end (canvas-click))
        (<! (await-undo! #(= @platform/schematic (cm/undo-state @editor/undotree))))
        (let [device (first (vals @platform/schematic))]
          (is (= 1 (count @platform/schematic)))
          (is (= "straight" (:type device)) "a fresh arm is not poisoned by the failure")
          (is (= {:length 5} (:props device))))
        (done)))))

;; @tags factory resolution
(deftest unready-factory-commit-does-not-insert
  ;; `commit-staged` for a factory device with no armed, resolved result cell
  ;; completes without inserting and starts no resolution itself.
  (async done
    (placement-fixture!)
    (go
      (<! (editor/commit-staged {:type "ckt" :model "pkg.cell" :x 1 :y 2}))
      (is (empty? @platform/schematic)
          "a factory commit outside the armed, resolved state inserts nothing")
      (done))))

;; @tags factory resolution, mosaic model picker
(deftest insertion-transaction-holds-its-gate-through-the-undo-checkpoint
  ;; An accepted insertion holds a busy admission gate through its post-action
  ;; and undo checkpoint. Intervening clicks are rejected without replay, and
  ;; Escape or re-arming cannot release the active transaction's gate; its
  ;; checkpoint still lands exactly once and the re-armed device places fresh.
  (async done
    (placement-fixture!
     {model-key {:generated false :name "cell" :type "straight"}
      "models:pkg.other" {:generated false :name "other" :type "coupler"}})
    (let [gate (promise-chan)
          previous platform/settled-write
          checkpoints (atom 0)]
      (set! platform/settled-write gate)
      (add-watch editor/undotree :busy-gate (fn [_ _ _ _] (swap! checkpoints inc)))
      (swap! editor/ui assoc ::editor/mouse [5.5 8.5])
      (pick-factory! "models:pkg.cell")
      (go
        (<! (await-ready!))
        (editor/drag-end (canvas-click))
        (is (= 1 (count @platform/schematic)) "the accepted click inserts once")
        (is (editor/placement-busy?) "the gate is held through settlement")
        (editor/drag-end (canvas-click))
        (is (= 1 (count @platform/schematic))
            "a click during settlement is rejected, not queued")
        (editor/cancel)
        (is (= 1 (count @platform/schematic)) "cancel does not release the transaction")
        (is (editor/placement-busy?) "the gate survives cancel")
        (pick-factory! "models:pkg.other")
        (is (editor/placement-busy?) "re-arming cannot release the settling gate")
        (editor/drag-end (canvas-click))
        (is (= 1 (count @platform/schematic)) "a re-armed click is rejected while settling")
        (put! gate true)
        (<! (await-undo! #(= @platform/schematic (cm/undo-state @editor/undotree))))
        (is (= 1 @checkpoints) "exactly one undo checkpoint for the one insertion")
        (is (= @platform/schematic (cm/undo-state @editor/undotree)))
        ;; the re-armed device is still armed and places on a fresh click
        (<! (await-ready!))
        (editor/drag-end (canvas-click))
        (<! (await-undo! #(and (= 2 (count @platform/schematic))
                               (= @platform/schematic (cm/undo-state @editor/undotree)))))
        (is (= #{"straight" "coupler"} (set (map :type (vals @platform/schematic)))))
        (set! platform/settled-write previous)
        (remove-watch editor/undotree :busy-gate)
        (done)))))

;; @tags factory resolution
(deftest synchronous-insertion-failure-releases-the-gate
  ;; A throw in the synchronous insert prep must still release the admission
  ;; gate, or every later placement is stranded.
  (async done
    (placement-fixture!
     {model-key {:generated false :name "cell" :type "straight"
                 :props [{:name "length" :default 17}]}})
    (swap! editor/ui assoc ::editor/mouse [5.5 8.5])
    (pick-factory! "models:pkg.cell")
    (go
      (<! (await-ready!))
      (with-redefs [cm/model-prop-defaults (fn [_] (throw (js/Error. "prep failed")))]
        (try (editor/drag-end (canvas-click))
             (catch :default _ nil)))
      (is (empty? @platform/schematic) "a failed prep inserts nothing")
      (is (not (editor/placement-busy?)) "the synchronous failure releases the gate")
      (editor/drag-end (canvas-click))
      (<! (await-undo! #(= @platform/schematic (cm/undo-state @editor/undotree))))
      (is (= 1 (count @platform/schematic)) "a fresh placement succeeds after the failed one")
      (done))))

;; @tags factory resolution, factory card dragging, mosaic device tray
(deftest external-drop-arms-and-requires-a-fresh-click
  ;; A cold or warm factory drop arms through the readiness gate and never
  ;; inserts on drop: cold waits for resolution, warm is ready at once, and a
  ;; single fresh click places it.
  (async done
    (go
      (doseq [warm? [false true]]
        (placement-fixture!)
        (let [reply (promise-chan)]
          (when warm?
            (put! reply {:type "straight" :props [{:name "length" :default 17}]}))
          (with-redefs [cm/viewbox-coord (fn [_] [3 4])
                        platform/get-model-async (fn [_ & _] reply)]
            (editor/on-drop (drop-event "pkg.cell")))
          (is (empty? @platform/schematic) "a drop never inserts on drop")
          (is (= warm? (not (editor/placement-busy?)))
              "a cold drop is busy until it resolves; a warm drop is ready")
          (when-not warm?
            (put! reply {:type "straight" :props [{:name "length" :default 17}]}))
          (<! (await-ready!))
          (is (empty? @platform/schematic) "resolution alone does not insert on drop")
          (editor/drag-end (canvas-click))
          (<! (await-undo! #(= @platform/schematic (cm/undo-state @editor/undotree))))
          (let [device (first (vals @platform/schematic))]
            (is (= 1 (count @platform/schematic)))
            (is (= "straight" (:type device)))
            (is (= {:length 17} (:props device))))))
      (done))))

;; @tags factory resolution, factory card dragging
(deftest drop-during-a-busy-placement-is-rejected
  ;; A drop is a placement action: while an arm resolves or an accepted
  ;; insertion settles it leaves the pending selection untouched and starts no
  ;; new arm.
  (async done
    (go
      (doseq [settling? [false true]]
        (placement-fixture!
         (if settling? {model-key {:generated false :name "cell" :type "straight"}} {}))
        (let [reply (promise-chan)
              gate (promise-chan)
              previous platform/settled-write]
          (set! platform/settled-write gate)
          (with-redefs [platform/get-model-async (fn [_ & _] reply)]
            (swap! editor/ui assoc ::editor/mouse [5.5 8.5])
            (pick-factory! "models:pkg.cell"))
          (when settling?
            (put! reply {:type "straight"})
            (<! (await-ready!))
            (editor/drag-end (canvas-click))
            (is (= 1 (count @platform/schematic))))
          (let [cell (::editor/arm-result @editor/ui)
                staged (::editor/staging @editor/ui)]
            (with-redefs [cm/viewbox-coord (fn [_] [9 9])]
              (editor/on-drop (drop-event "pkg.other")))
            (is (identical? cell (::editor/arm-result @editor/ui))
                "a busy drop does not retire the active arm")
            (is (= staged (::editor/staging @editor/ui))
                "a busy drop leaves the staged selection untouched"))
          (put! gate true)
          (put! reply {:type "straight"})
          (<! (await-ready!))
          (is (= (if settling? 1 0) (count @platform/schematic))
              "a busy drop inserts nothing")
          (set! platform/settled-write previous)))
      (done))))
