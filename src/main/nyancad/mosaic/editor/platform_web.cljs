; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.platform-web
  (:require [nyancad.hipflask :as hf :refer [pouch-atom pouchdb watch-changes]]
            [nyancad.mosaic.common :as cm]
            [reagent.core :as r]
            [clojure.spec.alpha :as s]))

;; Re-export done? so editor can :refer it from this namespace
(def done? hf/done?)

;; --- State ---

(def params (js/URLSearchParams. js/window.location.search))
(def group (or (.get params "schem") (.getItem js/localStorage "schem") (cm/random-name)))
(def sync (cm/get-sync-url))
(defonce db (pouchdb (or (cm/get-db-name) "schematics")))
(defonce schematic (pouch-atom db group (r/atom {})))
(set-validator! schematic
                #(or (s/valid? :nyancad.mosaic.common/schematic %) (.log js/console (pr-str %) (s/explain-str :nyancad.mosaic.common/schematic %))))
(defonce modeldb (pouch-atom db "models" (r/atom {})))
(set-validator! modeldb
                #(or (s/valid? :nyancad.mosaic.common/modeldb %) (.log js/console (pr-str %) (s/explain-str :nyancad.mosaic.common/modeldb %))))
(defonce snapshots (pouch-atom db (str group "$snapshots") (r/atom {})))
(defonce simulations (pouch-atom db (str group "$result") (r/atom (sorted-map))))
(defonce watcher (watch-changes db schematic modeldb snapshots simulations))
(def ldb (pouchdb "local"))
(defonce local (pouch-atom ldb "local"))
(defonce lwatcher (watch-changes ldb local))

(defonce syncactive (r/atom false))

;; --- Sync ---

(defn handle-auth-failure
  "Handle authentication failure - immediate logout."
  []
  (js/console.warn "Authentication failure detected - 401 from sync")
  (cm/logout!)
  (cm/alert "Session expired. Please login again. Your changes are saved locally."))

(defn handle-sync-error
  "Handle sync errors, checking for 401 auth failures.
  PouchDB fires 'error' event for 401, not 'denied' (with retry: true)."
  [^js error]
  (if (or (= (.-status error) 401)
          (and (.-name error)
               (= (.toLowerCase (.-name error)) "unauthorized")))
    (do
      (js/console.warn "Sync error 401 (auth failure)")
      (handle-auth-failure))
    (do
      (js/console.error "Sync error:" error)
      (cm/alert (str "Error synchronising to " sync
                     ", changes are saved locally")))))

(defn synchronise []
  (when (seq sync)
    (let [^js es (.sync db sync #js{:live true :retry true})]
      (.on es "paused" #(reset! syncactive false))
      (.on es "active" #(reset! syncactive true))
      (.on es "denied" handle-auth-failure)
      (.on es "error" handle-sync-error))))

;; --- Notebook ---

(defn notebook-url []
  (let [url-params (js/URLSearchParams. #js{:schem group})]
    (str "notebook/?" (.toString url-params))))

(defn notebook-panel
  "Notebook iframe panel. Pass notebook-popped-out cursor."
  [notebook-popped-out]
  (when-not @notebook-popped-out
    [:div#mosaic_notebook_wrapper
     [:div.resize-handle
      {:on-pointer-down
       (fn [e]
         (.preventDefault e)
         (.setPointerCapture (.-target e) (.-pointerId e))
         (let [wrapper (js/document.getElementById "mosaic_notebook_wrapper")
               start-x (.-clientX e)
               start-width (.-offsetWidth wrapper)
               on-move (fn on-move [e]
                         (let [delta (- start-x (.-clientX e))
                               new-width (+ start-width delta)]
                           (set! (.. wrapper -style -width) (str new-width "px"))))
               on-up (fn on-up [e]
                       (.remove (.-classList wrapper) "resizing")
                       (.releasePointerCapture (.-target e) (.-pointerId e))
                       (.removeEventListener js/document "pointermove" on-move)
                       (.removeEventListener js/document "pointerup" on-up))]
           (.add (.-classList wrapper) "resizing")
           (.addEventListener js/document "pointermove" on-move)
           (.addEventListener js/document "pointerup" on-up)))}]
     [:iframe#mosaic_notebook {:src (notebook-url)}]]))

;; --- Secondary menu items ---

(defn secondary-menu-items
  "Web-specific secondary menu items: library, pop-out notebook, login."
  [notebook-popped-out]
  [:<>
   [:a {:href (if cm/current-workspace
                (str "library?ws=" cm/current-workspace)
                "library")
        :target "libman"
        :title "Open library manager"}
    [cm/library]]
   [:a {:title "Pop out notebook"
        :on-click (fn []
                    (let [nb-url (str js/window.location.origin "/" (notebook-url))
                          popup (.open js/window nb-url "mosaic_notebook" "width=1200,height=800")]
                      (when popup
                        (reset! notebook-popped-out true)
                        (set! (.-onbeforeunload popup)
                              #(reset! notebook-popped-out false)))))}
    [cm/external-link]]
   [:a {:href "/auth/"
        :title "Login / Account"}
    [cm/login]]])

;; --- Functions ---

(defn open-schematic
  "Navigate to a subcircuit's schematic in a new browser tab."
  [model-id]
  (let [url (str "?" (.toString (js/URLSearchParams. #js{:schem model-id})))]
    (js/window.open url model-id)))

;; --- Init ---

(defn init-extra! []
  (.setItem js/localStorage "schem" group)
  (let [url-params (if cm/current-workspace
                     (str "?schem=" group "&ws=" cm/current-workspace)
                     (str "?schem=" group))]
    (js/history.replaceState nil nil (str js/window.location.pathname url-params)))
  (cm/init-auth-state!)
  (synchronise)
  (when-not (cm/onboarding-shown?)
    (cm/show-onboarding!)))
