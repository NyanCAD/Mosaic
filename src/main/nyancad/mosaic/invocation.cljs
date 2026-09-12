; SPDX-FileCopyrightText: 2026 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.invocation
  (:require [clojure.string :as str]
            [nyancad.hipflask.util :refer [clj->json json->clj]]))

;; @tags factory resolution
(defn- key-params [params-key]
  (try
    (let [params (json->clj (js/JSON.parse params-key))]
      (when (map? params) params))
    (catch :default _ nil)))

;; @tags factory resolution, nyanlib default contract
(defn normalized-path
  "Remove only known evaluated defaults, retaining unknown and explicit nil args."
  [entry [model-key field params-key & nested :as path]]
  (if (and (= field :parameterizations) (>= (or (:metadata-version entry) 0) 7)
           (some? (key-params params-key)))
    (let [params (key-params params-key)
          defaults (into {} (keep (fn [prop]
                                   (when (contains? prop :default)
                                     [(keyword (:name prop)) (:default prop)]))) (:props entry))
          supplied (into {} (remove (fn [[k v]] (and (contains? defaults k) (= v (get defaults k))))) params)]
      (into (if (seq supplied)
              [model-key :parameterizations (js/JSON.stringify (clj->json supplied))]
              [model-key]) nested))
    path))

;; @tags factory resolution
(defn override-key
  "Find a stored key by JSON value, including Rust/JS number spelling differences."
  [entry params-key]
  (when params-key
    (let [variants (:parameterizations entry)]
      (if (contains? variants params-key)
        params-key
        (when-let [params (key-params params-key)]
          (some (fn [key]
                  (when (= params (key-params key)) key))
                (keys variants)))))))

;; @tags factory resolution
(defn lookup-path
  "Address a factory invocation; authored schematics and empty params stay local."
  [model-key params]
  (if (and (seq model-key) (seq params) (not (str/ends-with? model-key ".gsch")))
    [model-key :parameterizations (js/JSON.stringify (clj->json params))]
    [model-key]))

;; @tags factory resolution
(defn lookup
  "Project a factory document or its shallow-merged invocation from a snapshot."
  [snapshot [model-key :as path]]
  (let [entry (get snapshot model-key)
        [_ & fields] (normalized-path entry path)]
    (if (= :parameterizations (first fields))
      (let [[_ params-key & nested] fields
            effective (when entry (merge entry (get-in entry [:parameterizations (override-key entry params-key)])))]
        (if (seq nested) (get-in effective nested) effective))
      (if (seq fields) (get-in entry fields) entry))))

;; @tags factory resolution
(defn normalize-entry
  "Keep encoded invocation keys as strings after document keyword conversion."
  [entry]
  (cond-> entry
    (:parameterizations entry)
    (update :parameterizations
            (fn [variants]
              (into {} (map (fn [[k v]] [(if (keyword? k) (subs (str k) 1) k) v])) variants)))))
