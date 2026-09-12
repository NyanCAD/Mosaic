; SPDX-FileCopyrightText: 2026 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.jsatom.protocol
  "Webview-side core of nyancad.mosaic.jsatom, split out so the edit protocol
   is testable under shadow-cljs's :test build (Node) without js/window or
   js/acquireVsCodeApi.

   Mirrors the nyancad.hipflask.util / jsatom.util split: the postMessage and
   window-listener plumbing stays in jsatom.cljs; the JSON edit generation, the
   host->webview echo application, and the JsAtom deftype live here, driven
   through an injected `post!` fn so tests can supply an in-memory transport.
   The read-only GfpAtom shares the backing-ratom pattern and uses an injected
   request function for whole-entry resolution rather than document edits."
  (:require ["jsonc-parser" :as jsonc]
            [nyancad.mosaic.jsatom.util :refer [doc->state]]
            [nyancad.mosaic.invocation :as invocation]
            [nyancad.hipflask.util :refer [json->clj]]
            [cljs.core.async :refer [chan promise-chan go <! put! close!]]
            [clojure.string :as str]
            [reagent.core :as r]
            reagent.ratom))

(defn value->js
  "Convert a Clojure value to the JS value jsonc/modify expects. Only nil maps to
   js/undefined (delete the key); everything else — including false, 0 and \"\" —
   round-trips through clj->js. (A prior `(or (clj->js v) js/undefined)` collapsed
   boolean false to a delete; see falsy-values-survive in jsatom_test.)"
  [value]
  (if (nil? value) js/undefined (clj->js value)))

(defn edit-message
  "Compute the jsonc text edits needed to set `path` to `value` in `doc-str`.
   Returns {:ops <js array of edits, each with :oldcontent set to the substring
   it overwrites> :doc' <resulting document string>}. Pure: no posting, no
   atom mutation."
  [doc-str path value]
  (let [ops (jsonc/modify doc-str
                          (clj->js path)
                          (value->js value)
                          #js{:formattingOptions #js{:tabSize 2 :insertSpaces true}})]
    (doseq [^js up ops]
      (set! (.-oldcontent up)
            (subs doc-str (.-offset up) (+ (.-offset up) (.-length up)))))
    {:ops ops :doc' (jsonc/applyEdits doc-str ops)}))

(defn changed-paths
  "Given the swap key-selector `x` and the resulting `data` map, return the
   seq of [path value] pairs to emit as edit messages. Mirrors the set/map/
   coll/scalar dispatch in the original pouch-swap!."
  [x data]
  (let [entry (fn [k] [[k] (get data k)])]
    (cond
      (set? x)  (map entry x)
      (map? x)  (map entry (keys x))
      (coll? x) [[x (get-in data x)]]
      :else     [[[x] (get data x)]])))

(defn apply-echo
  "Apply a host->webview \"update\" message to the webview's current document
   string and version. Returns {:doc :version :cache} when the incoming version
   is strictly greater than the current one (the version guard), else nil."
  [doc version ^js incoming]
  (let [incoming-version (.-version incoming)]
    (when (> incoming-version version)
      (let [doc' (jsonc/applyEdits doc (.-update incoming))]
        {:doc doc' :version incoming-version :cache (doc->state doc')}))))

;; @tags validator semantics
(defn- pouch-swap!
  "Apply f to the current cache subset (keyed by x), validate, and post edits.
   Returns a channel that carries the new cache value on success or the
   ex-info on validator rejection; the exception never propagates
   synchronously to the caller. The validator's cm/alert side effect is the
   user-facing rejection signal; the channel value is for chaining via done?."
  [^js ja f x & args]
  (let [ch (chan 1)]
    (try
      (let [old @(.-cache ja)
            data (apply f old x args)]
        (when-let [v (.-validator ja)]
          (when-not (v data)
            (throw (ex-info "Validator rejected reference state" {:val data}))))
        (reset! (.-cache ja) data)
        (let [grp (.-group ja)
              post (.-post ja)]
          (doseq [[path value] (changed-paths x data)]
            (swap! (.-version ja) inc)
            (let [{:keys [ops doc']} (edit-message @(.-document ja) path value)]
              (post #js{:type "update" :group grp :update ops})
              (reset! (.-document ja) doc'))))
        (put! ch data))
      (catch :default e
        (put! ch e))
      (finally
        (close! ch)))
    ch))

(deftype JsAtom [group document version cache post ^:mutable validator ^:mutable done?]
  IAtom

  IDeref
  (-deref [_this] @cache)

  ISwap
  (-swap! [_a _f]        (throw (js/Error "JsAtom assumes first argument is a key")))
  (-swap! [a f x]        (set! (.-done? a) (pouch-swap! a f x)))
  (-swap! [a f x y]      (set! (.-done? a) (pouch-swap! a f x y)))
  (-swap! [a f x y more] (set! (.-done? a) (apply pouch-swap! a f x y more)))

  IWatchable
  (-notify-watches [_this old new] (-notify-watches cache old new))
  (-add-watch [this key f]         (-add-watch cache key (fn [key _ old new] (f key this old new))))
  (-remove-watch [_this key]       (-remove-watch cache key)))

;; @tags factory resolution
(defn make-json-atom
  "Build a JsAtom plus its host-message receiver.

   `post!` is called with a #js update message for each webview->host edit.
   `init-version` seeds the version counter from the host document's VSCode
   version so the self-echo guard stays in lockstep even when the document was
   opened at a version > 1 (defaults to 1 for a freshly opened document).

   Returns {:atom <JsAtom> :receive! (fn [^js msg])}, where `receive!` should be
   invoked for every host->webview message; it filters by group, applies
   accepted \"update\" echoes, and adopts authoritative \"resync\" snapshots the
   host sends when it had to reject an edit.

   The initial `reset! cache (doc->state doc)` runs before the validator is
   set (set-validator! is called by the platform after this returns). When
   set-validator! is subsequently called, it re-validates the current cache
   and clears it if the validator rejects — so an invalid initial document
   surfaces as an alert and an empty cache, not as invalid data sitting in
   the cache.

   The `receive!` paths re-check the validator on every incoming message
   (by then the validator is set). On rejection, the incoming value is
   dropped and the cache keeps its previous state; the validator has
   already alerted."
  ([group doc cache post!] (make-json-atom group doc cache post! 1))
  ([group doc cache post! init-version]
   (reset! cache (doc->state doc))
   (let [document (atom doc)
         version  (atom init-version)
         ja       (JsAtom. group document version cache post! nil nil)
         accept!  (fn [new-doc new-ver new-cache]
                    (if-let [v (.-validator ja)]
                      (when (v new-cache)
                        (reset! document new-doc)
                        (reset! version  new-ver)
                        (reset! cache    new-cache))
                      (do
                        (reset! document new-doc)
                        (reset! version  new-ver)
                        (reset! cache    new-cache))))
         receive! (fn [^js msg]
                     (when (= (.-group msg) group)
                       (case (.-type msg)
                         "update"
                         (when-let [res (apply-echo @document @version msg)]
                           (accept! (:doc res) (:version res) (:cache res)))

                         "resync"
                         (let [text (.-text msg)
                               new-cache (doc->state text)]
                           (accept! text (.-version msg) new-cache))

                         nil)))]
     {:atom ja :receive! receive!})))

;; @tags factory resolution
(deftype GfpAtom [cache requests ordering request]
  IAtom
  IDeref
  ;; @tags factory resolution
  (-deref [_] @cache)
  IWatchable
  ;; @tags factory resolution
  (-notify-watches [_ old new] (-notify-watches cache old new))
  ;; @tags factory resolution
  (-add-watch [this key f] (-add-watch cache key (fn [key _ old new] (f key this old new))))
  ;; @tags factory resolution
  (-remove-watch [_ key] (-remove-watch cache key)))

;; @tags factory resolution
(defn- delivered [value]
  (let [ch (promise-chan)]
    (put! ch value)
    ch))

;; @tags factory resolution
(defn- valid-entries? [{:keys [session revision entries replace resolved parameterization]}]
  (and (string? session) (seq session)
       (int? revision) (<= 0 revision)
       (boolean? replace) (boolean? resolved)
       (or (nil? parameterization) (string? parameterization))
       (map? entries)
       (every? (fn [[model-key entry]]
                 (and (string? model-key) (or (nil? entry) (map? entry))))
               entries)))

;; @tags factory resolution
(defn decode-entries
  "Decode a host FactoryEntries value, returning ex-info on malformed data.
   String entry keys and explicit nil deletions survive the JS boundary."
  [^js value]
  (try
    (let [entries (.-entries value)
          decoded {:session (.-session value)
                   :revision (.-revision value)
                   :replace (.-replace value)
                    :resolved (.-resolved value)
                   :entries (when (and (some? entries) (= "object" (goog/typeOf entries))
                                       (not (array? entries)))
                              (into {} (map (fn [pair]
                                               [(aget pair 0)
                                                (invocation/normalize-entry (json->clj (aget pair 1)))]))
                                     (js/Object.entries entries)))}
          decoded (cond-> decoded
                    (some? (.-parameterization value))
                    (assoc :parameterization (.-parameterization value)))]
      (if (valid-entries? decoded)
        decoded
        (ex-info "Malformed factory entries" {})))
    (catch :default e
      (ex-info "Malformed factory entries" {} e))))

;; @tags factory resolution
(defn receive-entries!
  "Apply whole entries from a reply or watcher push. Revisions order overlapping
   snapshots/replies; null entries remove keys. Only resolved pushes settle lookups.
   Malformed updates return ex-info without changing cache or ordering state."
  ([gfp update] (receive-entries! gfp update false))
  ([^js gfp {:keys [session revision entries replace resolved parameterization] :as payload} subscription?]
   (if-not (valid-entries? payload)
     (ex-info "Malformed factory entries" {})
     (let [cache (.-cache gfp)
           requests (.-requests gfp)
           ordering (.-ordering gfp)
           restart? (and (:session @ordering) (not= session (:session @ordering)))]
       ;; Only a subscription snapshot establishes a replacement server session.
       ;; Old outstanding RPC replies/snapshots cannot switch the cache back.
       (when (or (not restart?) (and subscription? replace))
         (when restart?
           (doseq [ch (vals @requests)] (put! ch (ex-info "Factory server restarted" {})))
           (reset! requests {})
           (reset! ordering {:floor -1 :versions {}}))
         (swap! ordering assoc :session session)
          (let [previous @cache
                {:keys [floor versions]} @ordering
               changes (if replace
                          (merge (zipmap (concat (keys @cache)
                                                 (map #(if (vector? %) (first %) %) (keys @requests)))
                                         (repeat nil)) entries)
                         entries)
               accepted (into {} (filter (fn [[model-key _]]
                                          (>= revision (max floor (get versions model-key -1))))) changes)]
           (swap! ordering update :versions merge (zipmap (keys accepted) (repeat revision)))
           (when replace (swap! ordering update :floor max revision))
           (swap! cache
                  (fn [values]
                    (reduce-kv (fn [values model-key entry]
                                 (if entry
                                   (assoc values model-key (assoc entry :gfp/session session :gfp/revision revision))
                                   (dissoc values model-key)))
                               values accepted)))
            (doseq [[request-key pending] @requests
                    :when (vector? request-key)
                    :let [[model-key _ params-key] request-key]
                    :when (contains? accepted model-key)]
              (let [entry (get accepted model-key)
                    normalized (invocation/normalized-path entry request-key)
                    normalized-key (nth normalized 2 nil)
                    previous-entry (get previous model-key)
                    previous-path (invocation/normalized-path previous-entry request-key)
                    completed (if parameterization
                                (invocation/normalized-path entry [model-key :parameterizations parameterization])
                                [model-key])
                    available? (or (= 1 (count normalized))
                                   (some? (invocation/override-key entry normalized-key)))]
                (cond
                  (nil? entry)
                  (put! pending (ex-info "Factory is unavailable" {:key model-key}))

                  (and resolved available?
                       (or (= parameterization params-key) (= normalized completed)))
                  (let [value (invocation/lookup @cache request-key)
                        identity (if (= 1 (count normalized)) model-key normalized)
                        completed-channel (delivered value)]
                    (put! pending value)
                    (when-let [alias (get @requests identity)] (put! alias value))
                    (swap! requests assoc request-key completed-channel identity completed-channel))

                  (and (not available?)
                       (or (some? (invocation/override-key previous-entry (nth previous-path 2 nil)))
                           (and (not resolved) (nil? parameterization) (not replace))))
                  (do
                    (put! pending (ex-info "Factory invocation invalidated" {:key request-key}))
                    (swap! requests dissoc request-key)))))
            (doseq [[model-key entry] accepted]
              (when (or (and resolved (nil? parameterization)) (nil? entry))
               (let [value (or (get @cache model-key) (ex-info "Factory is unavailable" {:key model-key}))]
                 (when-let [pending (get @requests model-key)] (put! pending value))
                 (swap! requests assoc model-key (delivered value)))))))))))

;; @tags factory resolution
(defn- local-entry? [model-key entry]
  (or (nil? model-key) (str/ends-with? model-key ".gsch")
      (and entry (not (:generated entry)))))

;; @tags factory resolution
(defn get-async
  "Return one shared result channel. Persisted entries are presentation, not proof
   of session resolution. Authored entries and ordinary atoms stay local."
  [source request-key]
  (let [path (if (vector? request-key) request-key [request-key])
        model-key (first path)
        path (invocation/normalized-path (get @source model-key) path)
        request-key (if (= 1 (count path)) model-key path)]
   (if (instance? GfpAtom source)
    (let [^js gfp source
          ;; Requests are ordinary atoms, never reactive render dependencies.
          requests (.-requests gfp)
          entry (get @(.-cache gfp) model-key)]
      (if (local-entry? model-key entry)
        (delivered (or entry (ex-info "Model is unavailable" {:key model-key})))
         (or (get @requests request-key)
            (let [ch (promise-chan)]
               (swap! requests assoc request-key ch)
              (go
                (try
                   (let [result (<! ((.-request gfp) request-key))]
                     (when (identical? ch (get @requests request-key))
                      (if (instance? js/Error result)
                        (put! ch result)
                        (if (and (valid-entries? result) (contains? (:entries result) model-key))
                          (do
                            (receive-entries! gfp result)
                             (when (identical? ch (get @requests request-key))
                              (put! ch (ex-info "Resolution superseded by a newer catalogue" {:key model-key}))))
                          (put! ch (ex-info "Factory resolution failed" {:key model-key}))))))
                  (catch :default e
                     (when (identical? ch (get @requests request-key)) (put! ch e)))))
              ch))))
     (delivered (or (invocation/lookup @source path)
                    (ex-info "Model is unavailable" {:key model-key}))))))

;; @tags factory resolution
(defn gfp-cursor
  "A synchronous reactive cache cursor which registers only nonlocal demand.
   Local reads do not allocate result channels or missing-model errors."
  [source path]
  (if (instance? GfpAtom source)
    (do
      (binding [reagent.ratom/*ratom-context* nil]
        (let [model-key (first path)
              entry (get @(.-cache ^js source) model-key)]
          (when-not (local-entry? model-key entry)
             (get-async source (if (= :parameterizations (second path))
                                 (subvec path 0 3) model-key)))))
       (if (= :parameterizations (second path))
         (r/cursor (fn [path] (invocation/lookup @(.-cache ^js source) path)) path)
         (r/cursor (.-cache ^js source) path)))
     (if (= :parameterizations (second path))
       (r/cursor (fn [path] (invocation/lookup @source path)) path)
       (r/cursor source path))))

;; @tags factory resolution
(defn make-gfp-atom [doc cache request!]
  (reset! cache (update-vals (doc->state doc) invocation/normalize-entry))
  (GfpAtom. cache (atom {}) (atom {:floor -1 :versions {}}) request!))
