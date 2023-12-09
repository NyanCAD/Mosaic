; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.jsatom
  (:require ["jsonc-parser" :as jsonc]
            reagent.ratom
            goog.functions))

(def debounce #(goog.functions/debounce % 100))

(defonce vscode (js/acquireVsCodeApi))

(defn- pouch-swap! [ja f x & args]
  (let [data (apply swap! (.-cache ja) f x args)
        msg (fn [path value]
              ;; (.log js/console (clj->js path) (clj->js value))
              (swap! (.-version ja) inc)
              (let [update (jsonc/modify @(.-document ja)
                                         (clj->js path)
                                         (or (clj->js value) js/undefined)
                                         #js{})]
                (.postMessage vscode #js{:type "update" :update update})
                (swap! (.-document ja) jsonc/applyEdits update)))]
    (cond
      (set? x) (doseq [k x] (msg [k] (get data k))) ; update-keyset
      (map? x) (doseq [k (keys x)] (msg [k] (get data k))) ; into
      (coll? x) (msg x (get-in data x)) ; update-in
      :else (msg [x] (get data x))))) ; update/assoc/dissoc/etc.

(deftype JsAtom [document version cache]
  IAtom

  IDeref
  (-deref [_this] @cache)

  ISwap
  (-swap! [_a _f]         (throw (js/Error "Pouch atom assumes first argument is a key")))
  (-swap! [a f x]        (pouch-swap! a f x))
  (-swap! [a f x y]      (pouch-swap! a f x y))
  (-swap! [a f x y more] (apply pouch-swap! a f x y more))

  IWatchable
  (-notify-watches [_this old new] (-notify-watches cache old new))
  (-add-watch [this key f]         (-add-watch cache key (fn [key _ old new] (f key this old new))))
  (-remove-watch [_this key]       (-remove-watch cache key)))

; allow taking a cursor of a pouch atom
(extend-type ^js JsAtom reagent.ratom/IReactiveAtom)

(defn json-atom
  ([doc] (json-atom doc (atom {})))
  ([doc cache]
   (reset! cache (js->clj (jsonc/parse doc) :keywordize-keys true))
   (let [ja (JsAtom. (atom doc) (atom 0) cache)]
     (.addEventListener js/window "message"
       (fn [event]
         (when (and (= (.. event -data -type) "update")
                    (> (.. event -data -version) @(.-version ja)))
           (.log js/console "reset!!!")
           (swap! (.-document ja) jsonc/applyEdits (.. event -data -update))
           (reset! (.-version ja) (.. event -data -version))
           (reset! cache (js->clj (jsonc/parse @(.-document ja)) :keywordize-keys true)))))
     ja)))