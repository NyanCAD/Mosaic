; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.jsatom
  "Reactive atom backed by a VSCode text document buffer.
   Uses jsonc-parser to apply edits and sync state with the extension host.
   Top-level keys are strings (matching PouchDB format), inner values use keyword keys.
   Each JsAtom has a group field for message routing in multi-atom webviews."
  (:require ["jsonc-parser" :as jsonc]
            [nyancad.hipflask.util :refer [json->clj]]
            [cljs.core.async :refer [go]]
            reagent.ratom))

(defonce vscode (js/acquireVsCodeApi))

(defn- doc->state
  "Parse JSON document string to Clojure state.
   Top-level keys remain strings, inner values use keyword keys."
  [doc-str]
  (let [parsed (jsonc/parse doc-str)]
    (if parsed
      (into {}
            (map (fn [entry]
                   [(aget entry 0) (json->clj (aget entry 1))]))
            (js/Object.entries parsed))
      {})))

(defn- pouch-swap! [^js ja f x & args]
  (let [old @(.-cache ja)
        data (apply f old x args)]
    (when-let [v (.-validator ja)]
      (when-not (v data)
        (throw (ex-info "Validator rejected reference state" {:val data}))))
    (reset! (.-cache ja) data)
    (let [grp (.-group ja)
          msg (fn [path value]
                (swap! (.-version ja) inc)
                (let [update (jsonc/modify @(.-document ja)
                                           (clj->js path)
                                           (or (clj->js value) js/undefined)
                                           #js{})]
                  (doseq [up update]
                    (set! (.-oldcontent ^js up)
                          (subs @(.-document ja)
                                (.-offset up)
                                (+ (.-offset up) (.-length up)))))
                  (.postMessage vscode #js{:type "update" :group grp :update update})
                  (swap! (.-document ja) jsonc/applyEdits update)))]
      (cond
        (set? x) (doseq [k x] (msg [k] (get data k)))
        (map? x) (doseq [k (keys x)] (msg [k] (get data k)))
        (coll? x) (msg x (get-in data x))
        :else (msg [x] (get data x))))))

(deftype JsAtom [group document version cache ^:mutable validator]
  IAtom

  IDeref
  (-deref [_this] @cache)

  ISwap
  (-swap! [_a _f]         (throw (js/Error "JsAtom assumes first argument is a key")))
  (-swap! [a f x]        (pouch-swap! a f x))
  (-swap! [a f x y]      (pouch-swap! a f x y))
  (-swap! [a f x y more] (apply pouch-swap! a f x y more))

  IWatchable
  (-notify-watches [_this old new] (-notify-watches cache old new))
  (-add-watch [this key f]         (-add-watch cache key (fn [key _ old new] (f key this old new))))
  (-remove-watch [_this key]       (-remove-watch cache key)))

(defn json-atom
  "Create a JsAtom from a JSON document string, tagged with a group name.
   Listens for update messages from the VSCode extension host,
   filtering by group to support multiple atoms in one webview."
  ([group doc] (json-atom group doc (atom {})))
  ([group doc cache]
   (reset! cache (doc->state doc))
   (let [ja (JsAtom. group (atom doc) (atom 1) cache nil)]
     (.addEventListener js/window "message"
       (fn [^js event]
         (when (and (= (.. event -data -type) "update")
                    (= (.. event -data -group) group)
                    (> (.. event -data -version) @(.-version ja)))
           (swap! (.-document ja) jsonc/applyEdits (.. event -data -update))
           (reset! (.-version ja) (.. event -data -version))
           (reset! cache (doc->state @(.-document ja))))))
     ja)))

(defn done?
  "Returns a channel that completes immediately.
   JsAtom edits are synchronous, so there is never anything to wait for.
   This matches the hipflask/done? API so editor code can use done? uniformly."
  [_ja]
  (go nil))
