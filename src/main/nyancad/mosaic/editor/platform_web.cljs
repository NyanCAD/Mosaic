; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.editor.platform-web
  (:require [nyancad.hipflask :as hf :refer [pouch-atom pouchdb watch-changes]]
            [nyancad.mosaic.common :as cm]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
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

;; React root for the editor canvas.
(defonce root (rdc/create-root (.querySelector js/document ".mosaic-app.mosaic-editor")))

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
  "Notebook iframe panel with embedded/collapsed/popped-out states."
  [notebook-state]
  (when-not (= @notebook-state :nyancad.mosaic.editor/popped-out)
    (if (= @notebook-state :nyancad.mosaic.editor/collapsed)
      [:div#mosaic_notebook_collapsed
       {:title "Show notebook"
        :on-click #(reset! notebook-state :nyancad.mosaic.editor/embedded)}
       "◀"]
      [:div#mosaic_notebook_wrapper
       [:div.resize-handle
        {:on-pointer-down
         (fn [e]
           (.preventDefault e)
           (.setPointerCapture (.-target e) (.-pointerId e))
           (let [wrapper (js/document.getElementById "mosaic_notebook_wrapper")
                 start-x (.-clientX e)
                 start-width (.-offsetWidth wrapper)
                 collapsed (atom false)
                 on-move (fn on-move [e]
                           (let [delta (- start-x (.-clientX e))
                                 new-width (+ start-width delta)]
                             (if (< new-width 200)
                               (reset! collapsed true)
                               (do (reset! collapsed false)
                                   (set! (.. wrapper -style -width) (str new-width "px"))))))
                 on-up (fn on-up [e]
                         (.remove (.-classList wrapper) "resizing")
                         (.releasePointerCapture (.-target e) (.-pointerId e))
                         (.removeEventListener js/document "pointermove" on-move)
                         (.removeEventListener js/document "pointerup" on-up)
                         (when @collapsed
                           (set! (.. wrapper -style -width) "")
                           (reset! notebook-state :nyancad.mosaic.editor/collapsed)))]
             (.add (.-classList wrapper) "resizing")
             (.addEventListener js/document "pointermove" on-move)
             (.addEventListener js/document "pointerup" on-up)))}]
       [:div.notebook-chevron
        {:title "Hide notebook"
         :on-click (fn []
                     (when-let [wrapper (js/document.getElementById "mosaic_notebook_wrapper")]
                       (set! (.. wrapper -style -width) ""))
                     (reset! notebook-state :nyancad.mosaic.editor/collapsed))}
        "▶"]
       [:iframe#mosaic_notebook {:src (notebook-url)}]])))

;; --- Secondary menu items ---

(defn secondary-menu-items
  "Web-specific secondary menu items: library, pop-out notebook, login."
  [notebook-state]
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
                      (reset! notebook-state :nyancad.mosaic.editor/popped-out)
                      (when popup
                        (let [check (atom nil)]
                          (reset! check
                            (js/setInterval
                              (fn []
                                (when (.-closed popup)
                                  (js/clearInterval @check)
                                  (reset! notebook-state :nyancad.mosaic.editor/embedded)))
                              500))))))}
    [cm/external-link]]
   [:a {:href "/auth/"
        :title "Login / Account"}
    [cm/login]]])

;; --- Symbol URL resolution ---

(defn resolve-symbol-url
  "On web, symbol paths resolve directly as relative URLs."
  [path]
  (when (seq path)
    (delay path)))

;; --- Functions ---

(defn open-schematic
  "Navigate to a subcircuit's schematic in a new browser tab."
  [model-id]
  (let [url (str "?" (.toString (js/URLSearchParams. #js{:schem model-id})))]
    (js/window.open url model-id)))

;; --- Toolbar & tray ---

(defn menu-toolbar
  "Web: grouped toolbar with separators."
  [{:keys [tool cancel transform-selected delete-selected
           copy cut paste button-zoom undo-schematic redo-schematic]}]
  [:<>
   [:div.toolbar-group.tools-group
    [cm/radiobuttons tool
     [[[cm/cursor] :nyancad.mosaic.editor/cursor "Cursor [esc]"]
      [[cm/wire] :nyancad.mosaic.editor/wire "Wire [w]"]
      [[cm/eraser] :nyancad.mosaic.editor/eraser "Eraser [e]"]
      [[cm/move] :nyancad.mosaic.editor/pan "Pan [space]"]
      [[cm/probe] :nyancad.mosaic.editor/probe "Probe nodes in a connected simulator"]]
     nil nil cancel]]
   [:div.toolbar-group
    [:a {:title "Rotate selected clockwise [s]"
         :on-click (fn [_] (transform-selected #(.rotate % 90)))}
     [cm/rotatecw]]
    [:a {:title "Rotate selected counter-clockwise [shift+s]"
         :on-click (fn [_] (transform-selected #(.rotate % -90)))}
     [cm/rotateccw]]
    [:a {:title "Mirror selected horizontal [shift+f]"
         :on-click (fn [_] (transform-selected #(.flipY %)))}
     [cm/mirror-horizontal]]
    [:a {:title "Mirror selected vertical [f]"
         :on-click (fn [_] (transform-selected #(.flipX %)))}
     [cm/mirror-vertical]]
    [:a {:title "Delete selected [del]"
         :on-click (fn [_] (delete-selected))}
     [cm/delete]]
    [:a {:title "Copy selected [ctrl+c]"
         :on-click (fn [_] (copy))}
     [cm/copyi]]
    [:a {:title "Cut selected [ctrl+x]"
         :on-click (fn [_] (cut))}
     [cm/cuti]]
    [:a {:title "Paste [ctrl+v]"
         :on-click (fn [_] (paste))}
     [cm/pastei]]]
   [:div.toolbar-group
    [:a {:title "zoom in [scroll wheel/pinch]"
         :on-click #(button-zoom -1)}
     [cm/zoom-in]]
    [:a {:title "zoom out [scroll wheel/pinch]"
         :on-click #(button-zoom 1)}
     [cm/zoom-out]]
    [:a {:title "undo [ctrl+z]"
         :on-click undo-schematic}
     [cm/undoi]]
    [:a {:title "redo [ctrl+shift+z]"
         :on-click redo-schematic}
     [cm/redoi]]]])

(defn menu-extras
  "Web: status bar with document name and sync indicator."
  [{:keys [modeldb group syncactive toggle-theme! dark-mode?
           notebook-state show-history-panel]}]
  [:<>
   [:div.status
    [cm/renamable (r/cursor modeldb [(cm/model-key group) :name]) "Untitled"]
    (if @syncactive
      [:span.syncstatus.active {:title "saving changes"} [cm/sync-active]]
      [:span.syncstatus.done   {:title "changes saved"} [cm/sync-done]])]
   [:div.secondary
    [secondary-menu-items notebook-state]
    [:a {:title "Toggle light/dark theme"
         :on-click #(toggle-theme!)}
     (if (dark-mode?) [cm/sun-icon] [cm/moon-icon])]
    [:a {:title "Keyboard shortcuts & help"
         :on-click cm/show-onboarding!}
     [cm/help]]
    [:a {:title "Snapshot History"
         :on-click show-history-panel}
     [cm/history]]]])

(defn device-tray-items
  "Web: delegate to the full-tray component passed via context."
  [{:keys [full-tray]}]
  [full-tray])

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
