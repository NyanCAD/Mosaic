; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.symbol-file
  "Symbol maker file identity, shared by the extension host, the schematic
   webview, and the symbol maker. It has no Reagent or DOM dependency so the
   Node extension host can load it."
  (:require [clojure.string :as str]))

;; @tags mosaic symbol maker
(def metadata-id
  "Element id of the <metadata> block that marks a symbol maker file."
  "gfp-symbol-maker")

;; @tags mosaic symbol maker
(def metadata-open
  "Exact opening tag of a symbol maker metadata block."
  (str "<metadata id=\"" metadata-id "\">"))

;; @tags mosaic symbol maker, gsch symbol sidecar
(defn owned-svg?
  "True when `svg` is a symbol maker file, i.e. carries its metadata block."
  [svg]
  (and (string? svg) (str/includes? svg metadata-open)))

;; @tags mosaic symbol maker, gsch symbol sidecar
(defn invalidate-symbol-paths
  "Mark cached symbol URLs stored under any of `paths` stale.
   `cache` maps a symbol path to {:revision r :url ratom}. A matched entry keeps
   its URL but loses its revision, so the next lookup re-reads the file while
   the previous image stays visible. Unmatched entries are returned unchanged."
  [cache paths]
  (reduce (fn [acc path]
            (cond-> acc
              (contains? acc path) (assoc-in [path :revision] ::invalidated)))
          cache
          paths))

;; @tags mosaic symbol maker, gsch symbol sidecar
(defn apply-symbol-change
  "Update the symbol URL cache for a file event on `paths`. A deleted file is
   evicted so the canvas falls back to its box; a created or changed file is
   invalidated so it re-reads while the old image stays visible."
  [cache paths deleted?]
  (if deleted?
    (apply dissoc cache paths)
    (invalidate-symbol-paths cache paths)))

;; @tags mosaic symbol maker
(defn- drive-letter-variants
  "A Windows absolute path in both drive-letter cases; VS Code lower-cases the
   drive letter while Python keeps it upper-case. Other paths pass through."
  [path]
  (if-let [[_ drive tail] (re-find #"^([A-Za-z]):(.*)$" path)]
    [(str (str/lower-case drive) ":" tail) (str (str/upper-case drive) ":" tail)]
    [path]))

;; @tags mosaic symbol maker, gsch symbol sidecar
(defn symbol-path-forms
  "Every spelling a schematic may have cached a symbol under: the absolute
   path, its resolved real path, and the workspace-relative path, with Windows
   drive letters in both cases. Blank and duplicate forms are dropped."
  [absolute real relative]
  (into []
        (comp (filter #(and (string? %) (not (str/blank? %))))
              (mapcat drive-letter-variants)
              (distinct))
        [absolute real relative]))

;; --- Document edit queue ---
;;
;; The host applies at most one whole-document edit at a time. While one is in
;; flight only the newest requested text is kept; it is applied once the
;; current edit settles. Each step returns [queue action], where action is
;; [:apply text], [:post] (send the current document text to the webview), or
;; nil.

;; @tags mosaic symbol maker
(def idle-edit-queue {:in-flight? false :pending nil})

;; @tags mosaic symbol maker
(defn edit-queue-request
  "Request that the document become `text`."
  [queue text]
  (if (:in-flight? queue)
    [(assoc queue :pending text) nil]
    [{:in-flight? true :pending nil} [:apply text]]))

;; @tags mosaic symbol maker
(defn edit-queue-settled
  "The in-flight edit settled; `applied?` is false when VS Code rejected it.
   A successful edit continues with the newest pending text, if any. A failure
   drops pending text. Whenever the queue goes idle the webview is re-synced
   from the document."
  [queue applied?]
  (if (and applied? (:pending queue))
    [{:in-flight? true :pending nil} [:apply (:pending queue)]]
    [idle-edit-queue [:post]]))
