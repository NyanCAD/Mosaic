; SPDX-FileCopyrightText: 2026 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.parity-test
  "Cross-language schema parity.

   The clojure.spec definitions in common.cljc and the Pydantic models in
   schemas.py describe the same on-the-wire data. After the explicit
   reconciliation commit, the two should agree — this namespace holds the
   test that catches future drift.

   Strategy: generate schematic docs via `clojure.spec.alpha/gen`,
   augment with the serialization-boundary fields (_id, name) that the
   in-memory spec treats as optional but Pydantic requires, then shell to
   a Python subprocess (tests/_parity_oracle.py) to validate with
   Pydantic.

   When the Python side is unavailable (no pydantic, no python3 on PATH,
   or nyancad.schemas fails to import) the tests print a short SKIP
   notice and pass — checkouts that haven't synced the Python env still
   run a clean CLJS suite. CI installs the Python deps, so parity
   actually runs there."
  (:require ["child_process" :as cp]
            ["fs" :as fs]
            ["path" :as path]
            [cljs.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen]
            [nyancad.mosaic.common :as cm]))

;; ---------------------------------------------------------------------------
;; Subprocess plumbing
;; ---------------------------------------------------------------------------

(defn- find-repo-root
  "Walk up from the current working directory to the Mosaic repo root
   (identified by shadow-cljs.edn + package.json co-located)."
  []
  (loop [p (js/process.cwd)]
    (cond
      (or (nil? p) (= p "/")) nil
      (and (.existsSync fs (path/join p "shadow-cljs.edn"))
           (.existsSync fs (path/join p "package.json")))
      p
      :else (recur (path/dirname p)))))

(defn- python-for-nyancad
  "Pick the Python that has nyancad + pydantic installed. Prefer the
   uv-managed venv under python/nyancad/.venv (local dev); fall back to
   python3 on PATH (CI, where nyancad-server[test] installs into the
   system Python)."
  [nyancad-cwd]
  (let [venv-py (path/join nyancad-cwd ".venv/bin/python3")]
    (if (.existsSync fs venv-py) venv-py "python3")))

(defn- oracle-probe
  "Best-effort: confirm the oracle is callable and its deps are
   installed. Returns nil on success, a diagnostic string on failure."
  [nyancad-cwd]
  (try
    (let [py (python-for-nyancad nyancad-cwd)
          ^js res (cp/spawnSync py
                                #js ["-c" "import pydantic, nyancad.schemas"]
                                #js {:cwd nyancad-cwd})]
      (when-not (zero? (.-status res))
        (str "python probe exit " (.-status res)
             " — " (.toString (.-stderr res)))))
    (catch :default e
      (str "probe threw: " (.-message e)))))

(defn- run-oracle
  "Spawn the oracle synchronously. json-lines is a seq of already-
   stringified JSON requests; returns a vector of parsed verdicts in
   order. Throws if the subprocess exits non-zero."
  [json-lines]
  (let [root (find-repo-root)
        cwd (path/join root "python/nyancad")
        py (python-for-nyancad cwd)
        input (str (clojure.string/join "\n" json-lines) "\n")
        ^js res (cp/spawnSync py
                              #js ["-m" "tests._parity_oracle"]
                              #js {:cwd cwd :input input})]
    (when-not (zero? (.-status res))
      (throw (ex-info "oracle exited non-zero"
                      {:stdout (.toString (.-stdout res))
                       :stderr (.toString (.-stderr res))
                       :status (.-status res)})))
    (->> (clojure.string/split (.toString (.-stdout res)) #"\n")
         (remove clojure.string/blank?)
         (mapv (fn [line]
                 (js->clj (js/JSON.parse line) :keywordize-keys true))))))

(defn- skip-or-run
  "Probe the oracle once. If unavailable, println a short SKIP notice
   and return ::skipped so the deftest body can bail cleanly."
  []
  (let [root (find-repo-root)
        cwd (when root (path/join root "python/nyancad"))]
    (if-let [reason (and cwd (oracle-probe cwd))]
      (do (println "parity-test SKIP (oracle unavailable):" reason)
          ::skipped)
      ::ok)))

;; ---------------------------------------------------------------------------
;; Serialization-boundary helpers
;; ---------------------------------------------------------------------------

(defn- augment-for-pydantic
  "Spec treats :_id and :name as optional on in-memory devices (pre-commit
   state can lack them). Pydantic requires them at the API/DB boundary.
   Stamp both in deterministically before shipping the doc to Pydantic."
  [dev i]
  (-> dev
      (assoc :_id (str "top:D" i))
      (update :name (fnil identity (str "D" i)))))

(defn- kind-of
  "Oracle uses 'kind' to pick a Pydantic adapter. wire → Wire, anything
   else → Component."
  [dev]
  (if (= "wire" (:type dev)) "wire" "component"))

(defn- ->json-line [dev i]
  (js/JSON.stringify
    (clj->js {:kind (kind-of dev)
              :doc (augment-for-pydantic dev i)})))

(defn- ->model-json-line
  "Same idea as ->json-line but for ::model-def. The oracle routes
   kind:\"model\" to TypeAdapter(ModelMetadata). Pydantic requires _id
   with a 'models:' prefix; spec treats it as envelope."
  [m i]
  (js/JSON.stringify
    (clj->js {:kind "model"
              :doc (assoc m :_id (str "models:M" i))})))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest hand-crafted-device-passes-both-sides
  (testing "a hand-rolled resistor and wire are accepted by both sides
            (sanity check for the plumbing — independent of generators)"
    (when (= ::ok (skip-or-run))
      ;; Components require :name per the spec; wires do not (split-wire
      ;; creates unnamed wire segments during merge, so :name stays opt).
      (let [resistor {:type "resistor" :name "R1" :x 1 :y 2 :transform [1 0 0 1 0 0]}
            wire     {:type "wire" :x 0 :y 0 :rx 3 :ry 0}]
        (is (s/valid? ::cm/device resistor))
        (is (s/valid? ::cm/device wire))
        (let [[v1 v2] (run-oracle [(->json-line resistor 0)
                                   (->json-line wire 1)])]
          (is (true? (:ok v1)) (str "resistor rejected: " (:err v1)))
          (is (true? (:ok v2)) (str "wire rejected: " (:err v2))))))))

(deftest spec-gen-devices-pass-pydantic
  (testing "property: every device generated from (s/gen ::cm/device),
            augmented with _id/name, is accepted by Pydantic.

            If this ever fails, the spec and Pydantic have drifted —
            likely because a type was added to device-types in
            common.cljc without updating ComponentType in schemas.py,
            or because a new optional field was added to one side that
            the other doesn't accept."
    (when (= ::ok (skip-or-run))
      (let [samples (gen/sample (s/gen ::cm/device) 30)]
        (doseq [d samples]
          (is (s/valid? ::cm/device d)
              (str "generator produced spec-invalid device: " d)))
        (let [verdicts (run-oracle
                        (map-indexed (fn [i d] (->json-line d i)) samples))]
          (is (= (count samples) (count verdicts))
              (str "expected " (count samples) " verdicts, got " (count verdicts)))
          (doseq [[i v] (map-indexed vector verdicts)]
            (is (:ok v)
                (str "sample " i " rejected by Pydantic: " (:err v)
                     "\n  doc: " (nth samples i)))))))))

(deftest spec-gen-models-pass-pydantic
  (testing "property: every ::model-def generated by s/gen, augmented with
            a models:-prefixed :_id, is accepted by Pydantic's
            ModelMetadata TypeAdapter.

            This is the model-level mirror of spec-gen-devices-pass-pydantic.
            Catches drift on tags, ports, model entries, props (parameter
            defs), and the has_models/symbol envelope. Historically where
            real schema drift has happened (has_models, tags rename)."
    (when (= ::ok (skip-or-run))
      (let [samples (gen/sample (s/gen ::cm/model-def) 20)]
        (doseq [m samples]
          (is (s/valid? ::cm/model-def m)
              (str "generator produced spec-invalid model: " m)))
        (let [verdicts (run-oracle
                        (map-indexed (fn [i m] (->model-json-line m i)) samples))]
          (is (= (count samples) (count verdicts))
              (str "expected " (count samples) " verdicts, got " (count verdicts)))
          (doseq [[i v] (map-indexed vector verdicts)]
            (is (:ok v)
                (str "sample " i " rejected by Pydantic: " (:err v)
                     "\n  doc: " (nth samples i)))))))))

(deftest pydantic-still-rejects-required-field-drop
  (testing "locked-down sanity: a component doc with no _id/name is
            rejected by Pydantic. Confirms the oracle still discriminates
            — if someone loosens Pydantic without updating this test,
            the false-positive risk is caught."
    (when (= ::ok (skip-or-run))
      (let [bad {:kind "component"
                 :doc {:type "resistor" :x 0 :y 0
                       :transform [1 0 0 1 0 0]}}
            [verdict] (run-oracle [(js/JSON.stringify (clj->js bad))])]
        (is (false? (:ok verdict))
            "Pydantic should reject a component missing _id/name")
        (is (string? (:err verdict)))))))
