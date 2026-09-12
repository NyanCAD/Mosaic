; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.hipflask.util
  (:refer-clojure :exclude [update-keys]))

(def sep ":")

(defn update-keys
  "Apply f to values at specified keys"
  ([m keys f] (into m (map #(vector % (f (get m %)))) keys))
  ([m keys f & args] (into m (map #(vector % (apply f (get m %) args))) keys)))

(defn json->clj
  "Convert JavaScript objects to Clojure data, safe for single-char keys under advanced compilation."
  [data]
  (cond
    (nil? data)
    nil

    (js/Array.isArray data)
    (mapv #(json->clj %) data)

    (= (.-constructor data) js/Object)
    (into {}
          (map (fn [[k v]]
                 [(keyword k)
                  (json->clj v)]))
          (js/Object.entries data))

    :else
    data))

;; @tags factory resolution
(defn clj->json
  "Convert JSON-compatible Clojure data to JS data with deterministic object ordering."
  [data]
  (cond
    (map? data)
    (js/Object.fromEntries
      (into-array
        (map (fn [[k v]] #js [k (clj->json v)])
             (sort-by first
                      (map (fn [[k v]]
                             [(if (keyword? k) (subs (str k) 1) k) v])
                           data)))))

    (sequential? data)
    (into-array (map clj->json data))

    :else data))
