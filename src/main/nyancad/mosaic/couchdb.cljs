;; SPDX-FileCopyrightText: 2024 Pepijn de Vos
;; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.couchdb
  "CouchDB view functions for model search"
  (:require [clojure.string :as str]))

(defn generate-suffixes
  "Generate search suffixes using reductions with reverse tokenization.
  Elegant approach that builds suffixes from right-to-left."
  [s]
  (->> (re-seq #"[a-z]+|[0-9]+|_+" s)
       reverse
       (reductions #(str %2 %1))
       (remove #(str/starts-with? % "_"))))

(defn name-search-view
  "CouchDB view function for name-based model search with word boundary optimization"
  [^js doc]
  (when (str/starts-with? (.-_id doc) "models:")
    (let [name (str/lower-case (or (.-name doc) ""))
          suffixes (generate-suffixes name)]
      (doseq [suffix suffixes]
        (js/emit suffix
                 #js{:_id (.-_id doc)
                     :name (.-name doc)
                     :type (.-type doc)})))))
