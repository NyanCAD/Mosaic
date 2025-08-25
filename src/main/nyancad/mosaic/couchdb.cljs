;; SPDX-FileCopyrightText: 2024 Pepijn de Vos
;; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.couchdb
  "CouchDB view functions for model search")

(defn model-view
  "Dummy CouchDB view function for testing compilation"
  [^js doc]
  (js/log "Processing document:" (.-_id doc))
  (js/emit (.-name doc) (.-_id doc)))