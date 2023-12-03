; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.jsatom
  (:require ["jsonc-parser" :as jsonc]))

(defonce vscode (js/acquireVsCodeApi))

(defn update-keyset
  "Apply f to every value in keys"
  ([m keys f] (into m (map #(vector % (f (get m %)))) keys))
  ([m keys f & args] (into m (map #(vector % (apply f (get m %) args))) keys)))

(defn- pouch-swap! [document cache f x & args]
  (letfn [(keyset [key] ; the set of keys to update
            (cond
              (set? key) key ; update-keyset
              (map? key) (keys key) ; into
              (coll? key) #{(first key)} ; update-in
              :default #{key}))] ; update/assoc/dissoc/etc.
    ; build a map with only the keys to update
  ))

(deftype JsAtom [^:mutable document cache]
  IAtom

  IDeref
  (-deref [_this] @cache)

  ISwap
  (-swap! [_a _f]         (throw (js/Error "Pouch atom assumes first argument is a key")))
  (-swap! [_a f x]        (pouch-swap! document cache f x))
  (-swap! [_a f x y]      (pouch-swap! document cache f x y))
  (-swap! [_a f x y more] (apply pouch-swap! document cache f x y more))

  IWatchable
  (-notify-watches [_this old new] (-notify-watches cache old new))
  (-add-watch [this key f]         (-add-watch cache key (fn [key _ old new] (f key this old new))))
  (-remove-watch [_this key]       (-remove-watch cache key)))

(defn json-atom
  ([doc] (json-atom doc (atom {})))
  ([doc cache]
   (reset! cache (js->clj (jsonc/parse doc)))
   (let [ja (JsAtom. doc cache)]
     (.addEventListener js/window "message"
       (fn [event]
         (.log js/console (.-data event))
         (set! (.-document ja)
               (jsonc/applyEdits (.-document ja) (.-data event)))
         (.log js/console (.-document ja))))
     ja)))