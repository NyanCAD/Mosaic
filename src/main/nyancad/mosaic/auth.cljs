; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.auth
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [nyancad.mosaic.common :as cm]))

;; UI State
(defonce auth-mode (r/atom :login)) ; :login or :register
(defonce username (r/atom ""))
(defonce password (r/atom ""))
(defonce confirm-password (r/atom ""))
(defonce loading (r/atom false))
(defonce error-message (r/atom nil))
(defonce oauth-state (r/atom nil))  ; Holds OAuth state parameter

;; Authentication functions
(defn make-backend-request [method url data]
  (js/fetch url
            (clj->js {:method method
                      :headers {"Content-Type" "application/json"}
                      :credentials "include"
                      :body (when data (js/JSON.stringify (clj->js data)))})))

(defn login-user [username password]
  (go
    (try
      (reset! loading true)
      (reset! error-message nil)
      (let [response (<p! (make-backend-request "POST" 
                                              "/auth/login"
                                              {:username username :password password}))]
        (if (.-ok response)
          (let [result (<p! (.json response))]
            (js/console.log "Login successful:" result)
            (cm/set-current-user (.-name result))
            (reset! cm/auth-state true))
          (let [error-response (<p! (.json response))]
            (reset! error-message (.-error error-response)))))
      (catch js/Error e
        (js/console.error "Login error:" e)
        (reset! error-message "Network error. Please try again."))
      (finally
        (reset! loading false)))))

(defn register-user [username password]
  (go
    (try
      (reset! loading true)
      (reset! error-message nil)
      (let [response (<p! (make-backend-request "POST"
                                              "/auth/register"
                                              {:username username :password password}))]
        (if (.-ok response)
          (let [result (<p! (.json response))]
            (js/console.log "Registration successful:" result)
            (cm/set-current-user (.-name result))
            (reset! cm/auth-state true)
            (js/window.location.replace "/"))
          (let [error-response (<p! (.json response))]
            (reset! error-message (.-error error-response)))))
      (catch js/Error e
        (js/console.error "Registration error:" e)
        (reset! error-message "Network error. Please try again."))
      (finally
        (reset! loading false)))))

(defn oauth-login-user
  "Handle OAuth login with state parameter"
  ([state]
   ;; Consent flow: use existing session
   (oauth-login-user nil nil state))
  ([username password state]
   ;; Credential flow or consent flow
   (go
     (try
       (reset! loading true)
       (reset! error-message nil)
       (let [body (if (and username password)
                    {:username username :password password :state state}
                    {:state state})
             response (<p! (make-backend-request "POST" "/oauth/login" body))]
         (if (.-ok response)
           (let [result (<p! (.json response))
                 result-clj (js->clj result :keywordize-keys true)
                 redirect-url (:redirect_url result-clj)]
             (set! js/window.location redirect-url))
           (let [error-response (<p! (.json response))
                 error-clj (js->clj error-response :keywordize-keys true)]
             (reset! error-message (:error error-clj)))))
       (catch js/Error e
         (reset! error-message "Failed to grant access. Please try again."))
       (finally
         (reset! loading false))))))

(defn logout-user []
  (go
    (try
      (<p! (make-backend-request "POST" "/auth/logout" {}))
      (cm/logout!)
      (js/window.location.reload)
      (catch js/Error e
        (js/console.error "Logout error:" e)))))

(defn get-query-param
  "Extract query parameter from current URL"
  [param-name]
  (let [url-params (js/URLSearchParams. js/window.location.search)]
    (.get url-params param-name)))

;; Form validation
(defn validate-form []
  (cond
    (empty? @username) "Username is required"
    (empty? @password) "Password is required"
    (and (= @auth-mode :register) 
         (not= @password @confirm-password)) "Passwords do not match"
    (and (= @auth-mode :register) 
         (< (count @password) 6)) "Password must be at least 6 characters"
    :else nil))

;; UI Components
(defn form-input [label type value placeholder]
  [:div.form-group
   [:label label]
   [:input {:type type
            :value @value
            :placeholder placeholder
            :on-change #(reset! value (.. % -target -value))}]])

(defn auth-form []
  [:div.auth-container
   [:div.auth-card
    [:h1 (if @oauth-state
           "Sign in to NyanCAD"
           (if (= @auth-mode :login) "Login" "Register"))]

    (when @error-message
      [:div.error-message @error-message])
    
    [:form {:on-submit (fn [e]
                         (.preventDefault e)
                         (if-let [error (validate-form)]
                           (reset! error-message error)
                           ;; Check if we're in OAuth mode
                           (if @oauth-state
                             (oauth-login-user @username @password @oauth-state)
                             (if (= @auth-mode :login)
                               (login-user @username @password)
                               (register-user @username @password)))))}

     [form-input "Username" "text" username "Enter your username"]
     [form-input "Password" "password" password "Enter your password"]
     
     (when (= @auth-mode :register)
       [form-input "Confirm Password" "password" confirm-password "Confirm your password"])
     
     [:button.primary {:type "submit" :disabled @loading}
      (if @loading
        "Please wait..."
        (if (= @auth-mode :login) "Login" "Register"))]

     (when-not @oauth-state
       [:div.auth-toggle
        (if (= @auth-mode :login)
          [:p "Don't have an account? "
           [:a {:href "#" :on-click #(do (.preventDefault %)
                                         (reset! auth-mode :register)
                                         (reset! error-message nil))}
            "Register here"]]
          [:p "Already have an account? "
           [:a {:href "#" :on-click #(do (.preventDefault %)
                                         (reset! auth-mode :login)
                                         (reset! error-message nil))}
            "Login here"]])])]
    
    [:div.local-option
     [:p "Or continue without an account:"]
     [:a.button.secondary {:href "/"} "Work Locally"]]]])

(defn user-account []
  [:div.account-container
   [:div.account-card
    [:h1 (if @oauth-state "Grant Access" "Account")]

    (when @error-message
      [:div.error-message @error-message])

    [:div.user-info
     [:p "Logged in as: " [:strong (cm/get-current-user)]]
     [:p "Sync URL: " [:code (cm/get-sync-url)]]]
    [:div.account-actions
     (if @oauth-state
       ;; OAuth mode: show grant/cancel
       [:<>
        [:button.primary
         {:on-click #(oauth-login-user @oauth-state)
          :disabled @loading}
         (if @loading "Please wait..." "Grant Access")]
        [:a.button.secondary {:href "/"} "Cancel"]]
       ;; Normal mode: show back/logout
       [:<>
        [:a.button.secondary {:href "/"} "Back to Editor"]
        [:button.danger {:on-click logout-user} "Logout"]])]]])

(defn auth-page []
  (if (cm/is-authenticated?)
    [user-account]
    [auth-form]))

;; Session validation
(defn validate-session-async
  "Asynchronously check if session is still valid and update auth state.
   Only runs on auth page load - editor uses sync 'denied' event."
  []
  (when (cm/is-authenticated?)
    ;; Background validation - don't block UI
    (go
      (try
        (let [response (<p! (js/fetch (str cm/couchdb-url "_session")
                                      (clj->js {:credentials "include"})))]
          (if (.-ok response)
            (let [data (<p! (.json response))
                  user-ctx (.-userCtx data)
                  session-username (.-name user-ctx)
                  current-username (cm/get-current-user)]
              (when-not (= session-username current-username)
                ;; Session invalid or different user
                (cm/logout!)
                (js/console.log "Session validation failed, logged out")))
            ;; 401 or other error - logout
            (do
              (cm/logout!)
              (js/console.log "Session validation returned error, logged out"))))
        (catch js/Error e
          (js/console.error "Session validation error:" e)
          ;; Network error - don't logout, user might be offline
          nil)))))

;; Initialization
(defn ^:dev/after-load render []
  (rd/render [auth-page]
             (.getElementById js/document "mosaic_auth")))

(defn ^:export init []
  (set! js/window.name "auth")
  ;; Check for OAuth state parameter
  (when-let [state (get-query-param "state")]
    (reset! oauth-state state)
    (reset! auth-mode :login))  ; Force login mode for OAuth
  ;; Initialize auth state from localStorage
  (cm/init-auth-state!)
  ;; Validate session in background (updates atom if invalid)
  (validate-session-async)
  (render))
