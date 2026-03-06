; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.libman.platform-web
  "PouchDB-backed platform module for the library manager (web deployment)."
  (:require clojure.string
            [reagent.core :as r]
            [clojure.spec.alpha :as s]
            [nyancad.hipflask :refer [pouch-atom pouchdb watch-changes get-group alldocs get-view-group get-mango-group]]
            [nyancad.hipflask.util :refer [sep json->clj]]
            [cljs.core.async :refer [go <!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [nyancad.mosaic.common :as cm]))

;; --- Database setup ---

(defonce db (pouchdb (or (cm/get-db-name) "schematics")))
(defonce remotedb (pouchdb (str cm/couchdb-url "models")))
(def sync-url (cm/get-sync-url))

;; --- State ---

(defonce modeldb (pouch-atom db "models" (r/atom {})))
(set-validator! (.-cache modeldb)
                #(or (s/valid? :nyancad.mosaic.common/modeldb %) (.log js/console (pr-str %) (s/explain-str :nyancad.mosaic.common/modeldb %))))
(defonce watcher (watch-changes db modeldb))

(defonce syncactive (r/atom false))
(defonce remotemodeldb (r/atom {}))
(defonce remote-search-loading (r/atom false))
(defonce preview-url (r/atom nil))

;; --- Preview ---

(defn get-preview
  "Watch function that loads preview for circuit models from PouchDB snapshots."
  [_ _ _ new-model]
  (if new-model
    (go
      (let [schematic-id (cm/bare-id new-model)
            snapshot-group (str schematic-id "$snapshots")
            docs (<p! (alldocs db #js{:include_docs true
                                      :attachments true
                                      :binary true
                                      :endkey (str snapshot-group sep)
                                      :startkey (str snapshot-group sep "\ufff0")
                                      :descending true
                                      :limit 1}))
            rows (json->clj (.-rows docs))]
        (if-let [preview-attachment (get-in rows [0 :doc :_attachments :preview.svg :data])]
          (reset! preview-url (js/URL.createObjectURL preview-attachment))
          (reset! preview-url nil))))
    (reset! preview-url nil)))

;; --- Remote search ---

(defn build-model-selector
  "Build a Mango selector for models based on category and filter text."
  [selected-category filter-text]
  (let [selected-type (cm/device-types (peek selected-category))
        category-path (if selected-type
                        (pop selected-category)
                        selected-category)]
    (cond-> (into {} (map-indexed (fn [i cat] [(str "category." i) cat]) category-path))
      selected-type (assoc :type selected-type)
      (seq filter-text) (assoc :name {"$regex" (str "(?i)" filter-text)}))))

(defn search-remote-models
  "Search remote CouchDB for models matching filter and category."
  [filter-text selected-category]
  (go
    (reset! remote-search-loading true)
    (try
      (let [results (cond
                      (seq selected-category)
                      (<! (get-mango-group remotedb (build-model-selector selected-category filter-text) 10))

                      (seq filter-text)
                      (<! (get-view-group remotedb "models/name_search" (clojure.string/lower-case filter-text) 10))

                      :else
                      (<! (get-group remotedb "models" 10)))]
        (reset! remotemodeldb results))
      (catch js/Error e
        (js/console.error "Remote search error:" e)
        (reset! remotemodeldb {}))
      (finally
        (reset! remote-search-loading false)))))

;; --- Replication ---

(defn replicate-model
  "Replicate a specific model by ID from remote to local database."
  [model-id]
  (let [replication (.. remotedb -replicate (to db #js{:doc_ids #js[model-id]
                                                       :live false}))]
    (.on replication "complete" #(js/console.log "Model replicated:" model-id))
    (.on replication "error" #(js/console.error "Replication error:" %))))

(defn replicate-filtered-models
  "Replicate models matching current search criteria from remote to local database."
  [selected-category filter-text]
  (let [selector (build-model-selector selected-category filter-text)
        replication (.. remotedb -replicate (to db #js{:selector (clj->js selector)
                                                       :live false}))]
    (println selector)
    (.on replication "complete" #(js/console.log "Filtered models replicated"))
    (.on replication "error" #(js/console.error "Replication error:" %))))

;; --- Edit & Import ---

(defn edit-url
  "Open the schematic editor for a model in a new browser tab."
  [model-id]
  (let [url (js/URL. ".." js/window.location)]
    (.. url -searchParams (set "schem" (cm/bare-id model-id)))
    (when cm/current-workspace
      (.. url -searchParams (set "ws" cm/current-workspace)))
    (js/window.open url model-id)))

(defn import-ports
  "Import port definitions from the selected model's schematic using get-group."
  [model-id mod]
  (go
    (let [schematic-docs (<! (get-group db (cm/bare-id model-id)))
          port-xf (comp
                   (filter #(= (:type %) "port"))
                   (remove #(= (:variant %) "text"))
                   (map (juxt cm/transform-direction :name)))]
      (swap! mod update :ports
             #(transduce port-xf
                         (fn [acc [side name]] (update acc side conj name))
                         {:top [] :bottom [] :left [] :right []}
                         (vals schematic-docs))))))

;; --- Workspace management ---

(defn fetch-user-workspaces
  "Fetch user's workspace list via backend endpoint."
  []
  (go
    (when (cm/get-current-user)
      (try
        (let [resp (<p! (js/fetch "/auth/me" #js{:credentials "include"}))]
          (when (.-ok resp)
            (let [data (js->clj (<p! (.json resp)) :keywordize-keys true)]
              (reset! cm/user-workspaces (or (:workspaces data) [])))))
        (catch js/Error e
          (js/console.warn "Failed to fetch workspaces" e))))))

(defn switch-workspace
  "Reload page with new workspace URL param."
  [workspace-id]
  (let [url (js/URL. js/window.location)]
    (if workspace-id
      (.. url -searchParams (set "ws" workspace-id))
      (.. url -searchParams (delete "ws")))
    (set! js/window.location url)))

(defn create-workspace
  "Create a new workspace with the given slug."
  [slug]
  (go
    (let [username (cm/get-current-user)
          resp (<p! (js/fetch (str "/workspaces/" slug)
                              #js{:method "PUT"
                                  :credentials "include"
                                  :headers #js{"Content-Type" "application/json"}
                                  :body (js/JSON.stringify
                                         #js{:members #js{:names #js[username]}})}))]
      (if (.-ok resp)
        (switch-workspace (str "ws-" slug))
        (let [err (js->clj (<p! (.json resp)) :keywordize-keys true)]
          (cm/alert (or (:error err) "Failed to create workspace")))))))

(defn add-member
  "Add a member to the current workspace."
  [username]
  (go
    (when-let [ws cm/current-workspace]
      (let [slug (subs ws 3)
            get-resp (<p! (js/fetch (str "/workspaces/" slug)
                                    #js{:credentials "include"}))
            ws-info (when (.-ok get-resp)
                      (js->clj (<p! (.json get-resp)) :keywordize-keys true))
            current-members (or (:members ws-info) [])
            new-members (vec (distinct (conj current-members username)))
            resp (<p! (js/fetch (str "/workspaces/" slug)
                                #js{:method "PUT"
                                    :credentials "include"
                                    :headers #js{"Content-Type" "application/json"}
                                    :body (js/JSON.stringify
                                           #js{:members #js{:names (clj->js new-members)}})}))]
        (if (.-ok resp)
          (cm/alert (str "Added " username " to workspace"))
          (let [err (js->clj (<p! (.json resp)) :keywordize-keys true)]
            (cm/alert (or (:error err) "Failed to add member"))))))))

;; --- Workspace selector component ---

(defn workspace-selector []
  [:div.dbprops
   [:div.properties
    [:label {:for "workspace"} "Workspace"]
    [:select {:id "workspace"
              :value (or cm/current-workspace "")
              :on-change #(switch-workspace
                           (let [v (.. % -target -value)]
                             (when (seq v) v)))}
     [:option {:value ""} "My Library"]
     (for [ws @cm/user-workspaces]
       ^{:key ws}
       [:option {:value ws}
        (subs ws 3)])]

    [:label "Actions"]
    [:div.workspace-actions
     [:button {:on-click #(cm/prompt "Workspace name (lowercase, hyphens ok):" create-workspace)}
      [cm/add-model] " New"]
     (when cm/current-workspace
       [:button {:on-click #(cm/prompt "Username to add:" add-member)}
        [cm/login] " Invite"])]]])

;; --- Sync ---

(defn handle-sync-error
  "Handle sync errors, checking for 401 auth failures."
  [error]
  (if (or (= (.-status error) 401)
          (and (.-name error)
               (= (.toLowerCase (.-name error)) "unauthorized")))
    (do
      (js/console.warn "Sync error 401 (auth failure)")
      (cm/logout!)
      (cm/alert "Session expired. Please login again. Your changes are saved locally."))
    (do
      (js/console.error "Sync error:" error)
      (cm/alert (str "Error synchronising to " sync-url
                     ", changes are saved locally")))))

(defn synchronise []
  (when (seq sync-url)
    (let [es (.sync db sync-url #js{:live true :retry true})]
      (.on es "paused" #(reset! syncactive false))
      (.on es "active" #(reset! syncactive true))
      (.on es "denied" #(do (cm/logout!)
                            (cm/alert "Session expired. Please login again.")))
      (.on es "error" handle-sync-error))))

;; --- Init ---

(defn init-extra!
  "Set up watches, remote search, auth, and sync for web deployment."
  [selmodel selcat filter-text]
  ;; Preview watch
  (add-watch selmodel ::preview-loader get-preview)
  ;; Remote search watches
  (add-watch filter-text ::remote-search
             (fn [_ _ _ new-filter]
               (search-remote-models new-filter @selcat)))
  (add-watch selcat ::remote-search-category
             (fn [_ _ _ new-category]
               (search-remote-models @filter-text new-category)))
  ;; Initial remote search
  (search-remote-models @filter-text @selcat)
  ;; Auth and sync
  (cm/init-auth-state!)
  (fetch-user-workspaces)
  (synchronise))
