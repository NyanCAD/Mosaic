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

;; @tags factory resolution, mosaic wire tool
(deftest model-less-placement-preserves-schematic-types
  (async done
    (reset! platform/schematic {})
    (reset! platform/modeldb {})
    (reset! editor/undotree @(cm/newundotree))
    (go
      (doseq [dev [{:type "wire" :x 1 :y 2 :rx 3 :ry 0 :variant "d"}
                   {:type "port" :x 5 :y 2 :variant "emitter" :name "LS1"}
                   {:type "text" :x 7 :y 2 :name "Note"}]]
        (<! (editor/commit-staged dev)))
      (is (= #{"wire" "port" "text"} (set (map :type (vals @platform/schematic)))))
      (is (= [3 0] ((juxt :rx :ry) (first (filter #(= "wire" (:type %))
                                                (vals @platform/schematic))))))
      (done))))

;; @tags factory resolution, nyanlib default contract
(deftest simultaneous-lookups-commit-with-separate-undo-checkpoints
  (async done
    (reset! platform/schematic {})
    (reset! platform/modeldb {})
    (reset! editor/undotree @(cm/newundotree))
    (let [reply (promise-chan)
          checkpoints (atom [])
          [first second] (with-redefs [platform/get-model-async (fn [_ & _keys] reply)]
                           [(editor/commit-staged {:type "ckt" :model "pkg.cell" :x 1 :y 2})
                            (editor/commit-staged {:type "ckt" :model "pkg.cell" :x 4 :y 5})])]
      (add-watch editor/undotree :serial-placement
                 (fn [_ _ _ _] (swap! checkpoints conj (count @platform/schematic))))
      (put! reply {:type "straight"})
      (go
        (<! first)
        (<! second)
        (is (= [1 2] @checkpoints))
        (is (= #{[1 2] [4 5]} (set (map (juxt :x :y) (vals @platform/schematic)))))
        (remove-watch editor/undotree :serial-placement)
        (done)))))

;; @tags factory resolution
(deftest cancellation-preserves-an-active-placement-transaction
  (async done
    (reset! platform/schematic {})
    (reset! platform/modeldb {model-key {:type "straight"}})
    (reset! editor/undotree @(cm/newundotree))
    (let [gate (promise-chan)
          inserted (promise-chan)
          previous-write platform/settled-write
          checkpoints (atom [])]
      (set! platform/settled-write gate)
      (add-watch platform/schematic :first-insertion (fn [_ _ _ _] (put! inserted true)))
      (add-watch editor/undotree :cancel-placement
                 (fn [_ _ _ _] (swap! checkpoints conj (count @platform/schematic))))
      (let [first (editor/commit-staged {:type "ckt" :model "pkg.cell" :x 1 :y 2})]
        (go
          (<! inserted)
          (editor/cancel)
          (let [second (editor/commit-staged {:type "ckt" :model "pkg.cell" :x 4 :y 5})]
            (<! (timeout 0))
            (is (= 1 (count @platform/schematic)))
            (put! gate true)
            (<! first)
            (<! second)
            (is (= [1 2] @checkpoints)))
          (set! platform/settled-write previous-write)
          (remove-watch platform/schematic :first-insertion)
          (remove-watch editor/undotree :cancel-placement)
          (done))))))

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

;; @tags factory resolution, nyanlib default contract
(deftest placement-awaits-resolution-and-keeps-click-intent
  (async done
    (reset! platform/schematic {})
    (reset! platform/modeldb {})
    (reset! editor/undotree @(cm/newundotree))
    (let [reply (promise-chan)
          checkpoints (atom 0)
          lookup (atom nil)
          completion (with-redefs [platform/get-model-async (fn [_ & keys] (reset! lookup keys) reply)]
                       (editor/commit-staged {:type "ckt" :model "pkg.cell" :x 3.2 :y 8.8
                                              :props {:length 23}}))]
      (add-watch editor/undotree :placement (fn [_ _ _ _] (swap! checkpoints inc)))
      (is (empty? @platform/schematic))
      (is (= [model-key :parameterizations "{\"length\":23}"] @lookup))
      (swap! editor/ui assoc ::editor/mouse [100 200])
      (put! reply {:type "straight" :props [{:name "length" :default 17}
                                            {:name "width" :default 0.5}]})
      (go
        (<! completion)
        (let [device (first (vals @platform/schematic))]
          (is (= 1 (count @platform/schematic)))
          (is (= [3 9] [(:x device) (:y device)]))
          (is (= "straight" (:type device)))
          (is (= {:length 23 :width 0.5} (:props device)))
          (is (= 1 @checkpoints)))
        (remove-watch editor/undotree :placement)
        (done)))))

;; @tags factory resolution
(deftest escape-cancels-pending-placement
  (async done
    (reset! platform/schematic {})
    (let [reply (promise-chan)
          completion (with-redefs [platform/get-model-async (fn [_ & _keys] reply)]
                       (editor/commit-staged {:type "ckt" :model "pkg.cell" :x 2 :y 3}))]
      (editor/cancel)
      (put! reply {:type "straight"})
      (go
        (<! completion)
        (is (empty? @platform/schematic))
        (done)))))
