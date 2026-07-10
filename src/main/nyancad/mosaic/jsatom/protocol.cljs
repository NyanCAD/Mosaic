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
   through an injected `post!` fn so tests can supply an in-memory transport."
  (:require ["jsonc-parser" :as jsonc]
            [nyancad.mosaic.jsatom.util :refer [doc->state]]
            reagent.ratom))

(defn value->js
  "Convert a Clojure value to the JS value jsonc/modify expects, where
   js/undefined means \"delete this key\".

   Only nil deletes. The historical `(or (clj->js value) js/undefined)` also
   collapsed boolean `false` to js/undefined (0 and \"\" happen to survive,
   since they are truthy under ClojureScript's `or`), silently deleting a key
   whenever a field was set to false — see write-false-survives in
   jsatom_test. clj->js of false/0/\"\" round-trips correctly through jsonc."
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
  (cond
    (set? x)  (map (fn [k] [[k] (get data k)]) x)
    (map? x)  (map (fn [k] [[k] (get data k)]) (keys x))
    (coll? x) [[x (get-in data x)]]
    :else     [[[x] (get data x)]]))

(defn apply-echo
  "Apply a host->webview \"update\" message to the webview's {:doc :version}.
   Only applies when the incoming version is strictly greater than the current
   one (the version guard). Returns
   {:applied? bool :doc <string> :version <n> :cache <state>}."
  [{:keys [doc version]} ^js incoming]
  (let [incoming-version (.-version incoming)]
    (if (> incoming-version version)
      (let [doc' (jsonc/applyEdits doc (.-update incoming))]
        {:applied? true :doc doc' :version incoming-version :cache (doc->state doc')})
      {:applied? false :doc doc :version version})))

(defn- pouch-swap! [^js ja f x & args]
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
          (reset! (.-document ja) doc'))))))

(deftype JsAtom [group document version cache post ^:mutable validator]
  IAtom

  IDeref
  (-deref [_this] @cache)

  ISwap
  (-swap! [_a _f]        (throw (js/Error "JsAtom assumes first argument is a key")))
  (-swap! [a f x]        (pouch-swap! a f x))
  (-swap! [a f x y]      (pouch-swap! a f x y))
  (-swap! [a f x y more] (apply pouch-swap! a f x y more))

  IWatchable
  (-notify-watches [_this old new] (-notify-watches cache old new))
  (-add-watch [this key f]         (-add-watch cache key (fn [key _ old new] (f key this old new))))
  (-remove-watch [_this key]       (-remove-watch cache key)))

(defn make-json-atom
  "Build a JsAtom plus its host-message receiver.

   `post!` is called with a #js update message for each webview->host edit.
   `init-version` seeds the version counter from the host document's VSCode
   version so the self-echo guard stays in lockstep even when the document was
   opened at a version > 1 (defaults to 1 for a freshly opened document).

   Returns {:atom <JsAtom> :receive! (fn [^js msg])}, where `receive!` should be
   invoked for every host->webview message; it filters by group, applies
   accepted \"update\" echoes, and adopts authoritative \"resync\" snapshots the
   host sends when it had to reject an edit."
  ([group doc cache post!] (make-json-atom group doc cache post! 1))
  ([group doc cache post! init-version]
   (reset! cache (doc->state doc))
   (let [document (atom doc)
         version  (atom init-version)
         ja       (JsAtom. group document version cache post! nil)
         receive! (fn [^js msg]
                    (when (= (.-group msg) group)
                      (case (.-type msg)
                        "update"
                        (let [{:keys [applied?] :as result}
                              (apply-echo {:doc @document :version @version} msg)]
                          (when applied?
                            (reset! document (:doc result))
                            (reset! version  (:version result))
                            (reset! cache    (:cache result))))

                        "resync"
                        (let [text (.-text msg)]
                          (reset! document text)
                          (reset! version  (.-version msg))
                          (reset! cache    (doc->state text)))

                        nil)))]
     {:atom ja :receive! receive!})))
