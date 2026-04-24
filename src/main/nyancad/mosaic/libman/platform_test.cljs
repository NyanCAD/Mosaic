; SPDX-FileCopyrightText: 2026 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.libman.platform-test
  "Test platform stub for libman.cljc.

   Provides bare reagent atoms and no-op functions for the symbols
   libman.cljc :refers from the real platform modules — without pulling in
   PouchDB, the browser DOM, or the VSCode API. Selected by the `:test`
   reader feature via shadow-cljs build configuration.

   Tests targeting libman helpers just call the pure fns directly; this
   module exists only so libman's ns form resolves under :test."
  (:require [reagent.core :as r]))

(defonce modeldb (r/atom {}))
(defonce syncactive (r/atom false))
(defonce preview-url (r/atom nil))

;; UI hooks — no-ops in tests.
(defn get-preview [_ _ _ _] nil)
(defn search-remote-models [_ _] nil)
(defn remote-models-section [_ _] nil)
(defn edit-url [_] nil)
(defn import-ports [_ _] nil)
(defn workspace-selector [] nil)
(defn init-extra! [_ _ _] nil)
