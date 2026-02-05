; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.jsatom
  "Reactive atom backed by a VSCode text document buffer.
   Uses jsonc-parser to apply edits and sync state with the extension host.
   Top-level keys are strings (matching PouchDB format), inner values use keyword keys."
  (:require ["jsonc-parser" :as jsonc]
            [nyancad.mosaic.common :refer [json->clj]]
            reagent.ratom
            goog.functions))

(def debounce #(goog.functions/debounce % 100))

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

(defn- pouch-swap! [ja f x & args]
  (let [data (apply swap! (.-cache ja) f x args)
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
                (.postMessage vscode #js{:type "update" :update update})
                (swap! (.-document ja) jsonc/applyEdits update)))]
    (cond
      (set? x) (doseq [k x] (msg [k] (get data k)))
      (map? x) (doseq [k (keys x)] (msg [k] (get data k)))
      (coll? x) (msg x (get-in data x))
      :else (msg [x] (get data x)))))

(deftype JsAtom [document version cache]
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

(extend-type ^js JsAtom reagent.ratom/IReactiveAtom)

(defn json-atom
  "Create a JsAtom from a JSON document string.
   Listens for update messages from the VSCode extension host."
  ([doc] (json-atom doc (atom {})))
  ([doc cache]
   (reset! cache (doc->state doc))
   (let [ja (JsAtom. (atom doc) (atom 1) cache)]
     (.addEventListener js/window "message"
       (fn [^js event]
         (when (and (= (.. event -data -type) "update")
                    (> (.. event -data -version) @(.-version ja)))
           (if-let [full-doc (.. event -data -fullDocument)]
             ;; Full document replacement (from external edits, undo/redo)
             (do
               (reset! (.-document ja) full-doc)
               (reset! (.-version ja) (.. event -data -version))
               (reset! cache (doc->state full-doc)))
             ;; Incremental edits from extension host
             (do
               (swap! (.-document ja) jsonc/applyEdits (.. event -data -update))
               (reset! (.-version ja) (.. event -data -version))
               (reset! cache (doc->state @(.-document ja))))))))
     ja)))
