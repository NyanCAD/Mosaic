; SPDX-FileCopyrightText: 2026 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.jsatom-test
  "Round-trip tests for the jsatom webview<->host edit protocol, run against
   Microsoft's real shared text-document model (vscode-languageserver-textdocument)
   instead of Electron. The webview core (nyancad.mosaic.jsatom.protocol) and the
   host validation (nyancad.mosaic.jsatom.bridge/build-edit-ops) are the actual
   production code; only the message transport and the host's apply/forward
   orchestration are re-created here, mirroring extension.cljc/create-atom-channel.

   The document (an LspDoc wrapping a real TextDocument) exercises the genuine
   positionAt / getText(range) / incremental-update algorithm, so the offset math
   that governs whether an edit lands correctly is not faked.

   These tests do not presuppose bugs — they assert the protocol's invariants
   (host stays valid JSON; host and webview converge). Where an assertion fails,
   that is a reproduced corruption, and the failing scenario is the spec for the
   fix."
  (:require [cljs.test :refer [deftest is testing async]]
            ["vscode-languageserver-textdocument" :as lsp]
            ["jsonc-parser" :as jsonc]
            [nyancad.mosaic.jsatom.protocol :as protocol]
            [nyancad.mosaic.jsatom.bridge :as bridge]
            [nyancad.mosaic.jsatom.util :refer [doc->state]]
            [cljs.core.async :refer [go go-loop chan <! >! put! close! timeout]]))

;; ---------------------------------------------------------------------------
;; Host document — real TextDocument behind the IHostDocument protocol
;; ---------------------------------------------------------------------------

(deftype LspDoc [^js doc]
  bridge/IHostDocument
  (-position-at    [_ offset]    (.positionAt doc offset))
  (-make-range     [_ start end] #js{:start start :end end})
  (-get-text-range [_ range]     (.getText doc range)))

(defn- host-text [h] (.getText ^js @(:host h)))
(defn- host-version [h] (.-version ^js @(:host h)))

(defn- valid-json?
  "Strict JSON validity (not the lenient jsonc parse) — catches corruption
   that jsonc would silently tolerate."
  [s]
  (try (js/JSON.parse s) true (catch :default _ false)))

(defn- converged?
  "Host document parses to the same state the webview believes it holds."
  [h]
  (= (doc->state (host-text h)) @(:cache h)))

;; ---------------------------------------------------------------------------
;; Harness — webview core + in-memory bus + real host document
;; ---------------------------------------------------------------------------

(defn- make-harness
  "Wire a JsAtom (via the real protocol/make-json-atom) to a real TextDocument
   through an in-memory message bus. `init-version` seeds the host document's
   VSCode version (the webview counter always starts at 1, as in production)."
  ([doc] (make-harness doc 1))
  ([doc init-version]
   (let [bus   (atom [])
         host  (atom (.create lsp/TextDocument "mem://schematic" "json" init-version doc))
         cache (atom {})
         post! (fn [m] (swap! bus conj {:dir :host :msg m}))
         {ja :atom receive! :receive!}
         ;; seed the webview version from the host document version, as the
         ;; extension injects it in production (fixes open-at-version>1 drift)
         (protocol/make-json-atom "schematic" doc cache post! init-version)]
     {:bus bus :host host :cache cache :ja ja :receive! receive!})))

(defn- host-apply!
  "Mirror of extension.cljc/create-atom-channel's edit-queue + change-forward:
   validate via the shared bridge, apply to the real TextDocument (descending by
   offset so original-relative offsets stay valid), then enqueue the host->webview
   echo tagged with the new document version. Returns :applied or :rejected."
  [h ^js msg]
  (let [^js doc @(:host h)
        ops     (bridge/build-edit-ops (LspDoc. doc) (.-update msg))]
    (if (= bridge/rejected ops)
      ;; mirror extension.cljc: on reject, send an authoritative resync snapshot
      (do (swap! (:bus h) conj
                 {:dir :webview
                  :msg #js{:type "resync" :group (.-group msg)
                           :version (.-version doc) :text (.getText doc)}})
          :rejected)
      (let [sorted  (sort-by (fn [op] (.offsetAt doc (.-start ^js (:range op)))) > ops)
            changes (into-array (map (fn [op] #js{:range (:range op) :text (:content op)}) sorted))
            new-ver (inc (.-version doc))]
        (reset! (:host h) (.update lsp/TextDocument doc changes new-ver))
        (swap! (:bus h) conj
               {:dir :webview
                :msg #js{:type "update" :group (.-group msg)
                         :version new-ver :update (.-update msg)}})
        :applied))))

(defn- external-edit!
  "Simulate an edit from outside the webview (user typing, another editor): replace
   [offset, offset+old-len) with `text` on the host, bump version, forward the echo.
   `offset`/`old-len` are against the CURRENT host document."
  [h offset old-len text]
  (let [^js doc @(:host h)
        start   (.positionAt doc offset)
        end     (.positionAt doc (+ offset old-len))
        new-ver (inc (.-version doc))]
    (reset! (:host h)
            (.update lsp/TextDocument doc
                     (into-array [#js{:range #js{:start start :end end} :text text}])
                     new-ver))
    (swap! (:bus h) conj
           {:dir :webview
            :msg #js{:type "update" :group "schematic" :version new-ver
                     :update #js[#js{:offset offset :length old-len :content text}]}})))

(defn- deliver-one! [h item]
  (case (:dir item)
    :host    (host-apply! h (:msg item))
    :webview ((:receive! h) (:msg item))))

(defn- drain!
  "Deliver every queued message FIFO until the bus is empty (host applies enqueue
   echoes, which then get delivered too). Guarded against runaway loops."
  [h]
  (loop [n 0]
    (cond
      (empty? @(:bus h)) n
      (> n 100000)       (throw (ex-info "drain! runaway" {}))
      :else (let [item (first @(:bus h))]
              (swap! (:bus h) (fn [v] (subvec v 1)))
              (deliver-one! h item)
              (recur (inc n))))))

(defn- pop-idx!
  "Remove and deliver the item at index i in the bus (for reorder scenarios)."
  [h i]
  (let [item (nth @(:bus h) i)]
    (swap! (:bus h) (fn [v] (into (subvec v 0 i) (subvec v (inc i)))))
    (deliver-one! h item)
    item))

;; A device with explicit x/y so doc->state's x/y backfill can't mask a diff.
(defn- doc1 []
  (js/JSON.stringify
    #js{"R1" #js{"type" "resistor" "name" "R1" "x" 5 "y" 7}}))

;; ---------------------------------------------------------------------------
;; 1. Correctness invariants — single writer, edits fully drained
;; ---------------------------------------------------------------------------

(deftest write-string-roundtrips
  (let [h (make-harness (doc1))]
    (swap! (:ja h) assoc-in ["R1" :name] "R99")
    (drain! h)
    (is (valid-json? (host-text h)))
    (is (converged? h))
    (is (= "R99" (get-in (doc->state (host-text h)) ["R1" :name])))))

(deftest write-number-roundtrips
  (let [h (make-harness (doc1))]
    (swap! (:ja h) assoc-in ["R1" :x] 42)
    (drain! h)
    (is (converged? h))
    (is (= 42 (get-in (doc->state (host-text h)) ["R1" :x])))))

(deftest write-nested-map-roundtrips
  (let [h (make-harness (doc1))]
    (swap! (:ja h) assoc-in ["R1" :props] {:resistance "10k"})
    (drain! h)
    (is (valid-json? (host-text h)))
    (is (converged? h))
    (is (= "10k" (get-in (doc->state (host-text h)) ["R1" :props :resistance])))))

(deftest write-new-top-level-key
  (let [h (make-harness (doc1))]
    (swap! (:ja h) assoc "C1" {:type "capacitor" :name "C1" :x 1 :y 2})
    (drain! h)
    (is (valid-json? (host-text h)))
    (is (converged? h))
    (is (contains? (doc->state (host-text h)) "C1"))))

(deftest nil-value-deletes-key
  (testing "assoc-in a nil value removes the key (intended delete semantics)"
    (let [h (make-harness (doc1))]
      (swap! (:ja h) assoc-in ["R1" :name] nil)
      (drain! h)
      (is (valid-json? (host-text h)))
      (is (not (contains? (get (doc->state (host-text h)) "R1") :name))))))

(deftest dissoc-top-level-key-deletes
  (let [h (make-harness (doc1))]
    (swap! (:ja h) assoc "C1" {:type "capacitor" :name "C1" :x 1 :y 2})
    (drain! h)
    (swap! (:ja h) dissoc "C1")
    (drain! h)
    (is (valid-json? (host-text h)))
    (is (not (contains? (doc->state (host-text h)) "C1")))))

;; Falsy values: probe whether false / 0 / "" survive or get deleted.
;; (Avoid :x/:y, which doc->state backfills and would mask a lost key.)
(deftest write-false-survives
  (let [h (make-harness (doc1))]
    (swap! (:ja h) assoc-in ["R1" :mirror] false)
    (drain! h)
    (is (valid-json? (host-text h)))
    (is (contains? (get (doc->state (host-text h)) "R1") :mirror)
        "writing false must not delete the key")
    (is (= false (get-in (doc->state (host-text h)) ["R1" :mirror])))
    (is (converged? h))))

(deftest write-zero-survives
  (let [h (make-harness (doc1))]
    (swap! (:ja h) assoc-in ["R1" :rotation] 0)
    (drain! h)
    (is (contains? (get (doc->state (host-text h)) "R1") :rotation)
        "writing 0 must not delete the key")
    (is (= 0 (get-in (doc->state (host-text h)) ["R1" :rotation])))
    (is (converged? h))))

(deftest write-empty-string-survives
  (let [h (make-harness (doc1))]
    (swap! (:ja h) assoc-in ["R1" :label] "")
    (drain! h)
    (is (contains? (get (doc->state (host-text h)) "R1") :label)
        "writing \"\" must not delete the key")
    (is (= "" (get-in (doc->state (host-text h)) ["R1" :label])))
    (is (converged? h))))

;; ---------------------------------------------------------------------------
;; 2. Batched-edit stacking
;; ---------------------------------------------------------------------------

(deftest multi-key-swap-stacks
  (testing "a single swap! merge touching many keys stacks all edits"
    (let [h (make-harness (doc1))]
      (swap! (:ja h) merge {"C1" {:type "capacitor" :name "C1" :x 1 :y 1}
                            "L1" {:type "inductor"  :name "L1" :x 2 :y 2}
                            "D1" {:type "diode"     :name "D1" :x 3 :y 3}})
      (drain! h)
      (is (valid-json? (host-text h)))
      (is (converged? h))
      (let [state (doc->state (host-text h))]
        (is (every? #(contains? state %) ["R1" "C1" "L1" "D1"]))))))

(deftest successive-swaps-accumulate
  (testing "a sequence of independent swaps all land, in order"
    (let [h (make-harness (doc1))]
      (doseq [[k v] [["C1" {:type "capacitor" :name "C1" :x 1 :y 1}]
                     ["L1" {:type "inductor"  :name "L1" :x 2 :y 2}]
                     ["D1" {:type "diode"     :name "D1" :x 3 :y 3}]]]
        (swap! (:ja h) assoc k v)
        (drain! h))
      (is (valid-json? (host-text h)))
      (is (converged? h))
      (is (= 4 (count (doc->state (host-text h))))))))

(deftest interleaved-batches-stack
  (testing "several batched swaps queued before any delivery still stack"
    (let [h (make-harness (doc1))]
      (swap! (:ja h) assoc-in ["R1" :x] 10)
      (swap! (:ja h) assoc "C1" {:type "capacitor" :name "C1" :x 1 :y 1})
      (swap! (:ja h) assoc-in ["R1" :name] "RENAMED")
      (drain! h)
      (is (valid-json? (host-text h)))
      (is (converged? h))
      (let [state (doc->state (host-text h))]
        (is (= 10 (get-in state ["R1" :x])))
        (is (= "RENAMED" (get-in state ["R1" :name])))
        (is (contains? state "C1"))))))

;; ---------------------------------------------------------------------------
;; 3. Concurrency / conflict scenarios (deterministic ordering)
;; ---------------------------------------------------------------------------

(deftest disjoint-external-edit-converges
  (testing "webview edits R1 while an external editor adds a disjoint key; in-order delivery converges"
    (let [h (make-harness (doc1))]
      ;; webview edit -> enqueues a :host msg
      (swap! (:ja h) assoc-in ["R1" :name] "R2")
      ;; deliver it so the host doc is up to date, then an external edit lands
      (drain! h)
      ;; external edit inserts a new key at the end of the object
      (let [text  (host-text h)
            ins-at (.lastIndexOf text "}")
            frag  ",\n  \"NOTE1\": {\"type\": \"note\", \"x\": 0, \"y\": 0}"]
        (external-edit! h ins-at 0 frag))
      (drain! h)
      (is (valid-json? (host-text h)))
      (is (converged? h)))))

(deftest rejected-batch-resyncs
  (testing "an external edit mutates the field the webview is editing; the webview's stale edit is rejected and the webview resyncs to host truth"
    (let [h (make-harness (doc1))]
      ;; External editor changes R1's name VALUE (different length -> also shifts
      ;; later offsets), queuing its echo. The webview does not know yet.
      (let [text (host-text h)
            off  (.indexOf text "\"R1\"" (.indexOf text "\"name\""))]
        (external-edit! h off 4 "\"EXTERNAL\""))
      ;; Webview edits the same field based on its now-stale document.
      (swap! (:ja h) assoc-in ["R1" :name] "NEW")
      (drain! h)
      (is (valid-json? (host-text h)) "document must remain valid JSON")
      (is (converged? h)
          "after the reject-triggered resync, webview must match host truth")
      (is (= "EXTERNAL" (get-in (doc->state (host-text h)) ["R1" :name]))
          "host truth (the external edit) wins the conflict"))))

(deftest initial-version-drift-self-echo
  (testing "host document opened at version > 1: the webview seeds its counter from the host version, so its self-echo is still suppressed"
    (let [h (make-harness (doc1) 5)]                        ; host starts at version 5, webview seeded to 5
      (swap! (:ja h) assoc-in ["R1" :x] 10)
      (drain! h)
      (is (valid-json? (host-text h)))
      (is (converged? h)
          "self-echo must not be re-applied on top of the optimistic local edit")
      (is (= 10 (get-in (doc->state (host-text h)) ["R1" :x]))))))

(deftest external-edits-in-order-stack
  (testing "two disjoint external edits, echoes delivered in order (as postMessage guarantees), both land"
    (let [h (make-harness (doc1))]
      (let [text   (host-text h)
            ins-at (.lastIndexOf text "}")]
        (external-edit! h ins-at 0 ",\"E1\":{\"type\":\"note\",\"x\":0,\"y\":0}"))
      (let [text   (host-text h)
            ins-at (.lastIndexOf text "}")]
        (external-edit! h ins-at 0 ",\"E2\":{\"type\":\"note\",\"x\":1,\"y\":1}"))
      (drain! h)
      (is (valid-json? (host-text h)))
      (is (converged? h))
      (let [state (doc->state (host-text h))]
        (is (contains? state "E1"))
        (is (contains? state "E2"))))))

;; ---------------------------------------------------------------------------
;; 4. Data races — seeded, deterministic interleaving of concurrent writers
;; ---------------------------------------------------------------------------

(defn- make-rng
  "Tiny seeded LCG so any failing interleaving is reproducible from its seed."
  [seed]
  (let [s (atom (bit-and seed 0x7fffffff))]
    (fn [] (let [x (bit-and (+ (* @s 1103515245) 12345) 0x7fffffff)]
             (reset! s x)
             (/ x 0x7fffffff)))))

(defn- rng-int [rng n] (min (dec n) (int (* (rng) n))))

(defn- run-schedule!
  "Cooperatively interleave concurrent writer 'threads' and async message
   delivery, picking the next move with `rng`. Each writer is a vector of 0-arg
   thunks (a swap! or external edit); a move is either 'advance a writer' or
   'deliver a queued bus message'. Runs until all writers are exhausted and the
   bus is empty — modelling multiple async threads racing on one document."
  [h rng writers]
  (let [ws (atom (vec writers))]
    (loop [guard 0]
      (let [pend  (keep-indexed (fn [i ts] (when (seq ts) i)) @ws)
            n-msg (count @(:bus h))
            moves (into (mapv (fn [i] [:writer i]) pend)
                        (mapv (fn [j] [:msg j]) (range n-msg)))]
        (when (and (seq moves) (< guard 100000))
          (let [[kind arg] (nth moves (rng-int rng (count moves)))]
            (case kind
              :writer (let [thunk (first (nth @ws arg))]
                        (swap! ws update arg subvec 1)
                        (thunk))
              :msg    (pop-idx! h arg))
            (recur (inc guard))))))))

(deftest fuzz-concurrent-writers-preserve-structure
  (testing "many seeded interleavings of concurrent writers keep the doc valid and lose no device"
    (let [base-keys #{"R1" "C1" "L1"}]
      (doseq [seed (range 1 61)]
        (let [rng (make-rng seed)
              h   (make-harness
                    (js/JSON.stringify
                      #js{"R1" #js{"type" "resistor" "name" "R1" "x" 5 "y" 7}
                          "C1" #js{"type" "capacitor" "name" "C1" "x" 1 "y" 1}
                          "L1" #js{"type" "inductor" "name" "L1" "x" 2 "y" 2}}))
              ;; two webview writers editing disjoint fields on distinct devices
              writers [[(fn [] (swap! (:ja h) assoc-in ["R1" :name] "R1a"))
                        (fn [] (swap! (:ja h) assoc-in ["R1" :name] "R1b"))]
                       [(fn [] (swap! (:ja h) assoc-in ["C1" :name] "C1a"))
                        (fn [] (swap! (:ja h) assoc-in ["L1" :name] "L1a"))]]]
          (run-schedule! h rng writers)
          (drain! h)
          (is (valid-json? (host-text h))
              (str "seed " seed ": host must stay valid JSON"))
          (is (= base-keys (set (keys (doc->state (host-text h)))))
              (str "seed " seed ": no device may be silently dropped")))))))

;; ---------------------------------------------------------------------------
;; 5. Genuine async threads (core.async) — two writers over channels on one doc
;; ---------------------------------------------------------------------------

(deftest async-concurrent-writers
  (testing "two core.async writer threads swap! disjoint keys while a host go-loop applies edits"
    (async done
      (let [doc   (js/JSON.stringify
                    #js{"R1" #js{"type" "resistor" "name" "R1" "x" 5 "y" 7}
                        "C1" #js{"type" "capacitor" "name" "C1" "x" 1 "y" 1}})
            host  (atom (.create lsp/TextDocument "mem://s" "json" 1 doc))
            cache (atom {})
            to-host (chan 64)
            done-ch (chan 64)
            post!  (fn [m] (put! to-host m))
            {ja :atom receive! :receive!} (protocol/make-json-atom "schematic" doc cache post!)
            h      {:host host :cache cache :ja ja}
            expected 2]                                    ; two single-key swaps -> two host msgs -> two echoes
        ;; host thread: validate + apply + echo, yielding a tick each time
        (go-loop []
          (when-let [^js msg (<! to-host)]
            (<! (timeout 0))
            (let [^js d @host
                  ops   (bridge/build-edit-ops (LspDoc. d) (.-update msg))]
              (when-not (= bridge/rejected ops)
                (let [sorted  (sort-by (fn [op] (.offsetAt d (.-start ^js (:range op)))) > ops)
                      changes (into-array (map (fn [op] #js{:range (:range op) :text (:content op)}) sorted))
                      new-ver (inc (.-version d))]
                  (reset! host (.update lsp/TextDocument d changes new-ver))
                  (receive! #js{:type "update" :group "schematic"
                                :version new-ver :update (.-update msg)}))))
            (put! done-ch :ok)
            (recur)))
        ;; two concurrent writer threads
        (go (<! (timeout 0)) (swap! ja assoc-in ["R1" :name] "R1x"))
        (go (<! (timeout 0)) (swap! ja assoc-in ["C1" :name] "C1x"))
        ;; wait for both edits to be applied, then assert
        (go
          (dotimes [_ expected] (<! done-ch))
          (<! (timeout 0))
          (is (valid-json? (host-text h)))
          (is (converged? h) "disjoint concurrent writers must converge")
          (is (= "R1x" (get-in (doc->state (host-text h)) ["R1" :name])))
          (is (= "C1x" (get-in (doc->state (host-text h)) ["C1" :name])))
          (close! to-host)
          (done))))))
