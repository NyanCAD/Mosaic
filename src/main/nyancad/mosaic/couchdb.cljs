;; SPDX-FileCopyrightText: 2024 Pepijn de Vos
;; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.couchdb
  "CouchDB view functions for model search"
  (:require [clojure.string :as str]))

(defn name-search-view
  "CouchDB view function for name-based model search"
  [^js doc]
  (when (str/starts-with? (.-_id doc) "models:")
    (let [name (str/lower-case (or (.-name doc) ""))]
      (doseq [i (range (count name))]
        (js/emit (subs name i)
                 #js{:_id (.-_id doc)
                     :name (.-name doc)
                     :type (.-type doc)})))))