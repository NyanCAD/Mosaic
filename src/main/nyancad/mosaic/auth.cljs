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
            (js/window.location.replace "/"))
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
  [username password state]
  (go
    (try
      (reset! loading true)
      (reset! error-message nil)
      (let [response (<p! (make-backend-request "POST"
                                              "/oauth/login"
                                              {:username username
                                               :password password
                                               :state state}))]
        (if (.-ok response)
          (let [result (<p! (.json response))
                redirect-url (.-redirect_url result)]
            (js/console.log "OAuth login successful, redirecting to:" redirect-url)
            ;; Navigate to redirect URL provided by server
            (set! js/window.location redirect-url))
          (let [error-response (<p! (.json response))]
            (reset! error-message (.-error error-response)))))
      (catch js/Error e
        (js/console.error "OAuth login error:" e)
        (reset! error-message "Network error. Please try again."))
      (finally
        (reset! loading false)))))

(defn logout-user []
  (go
    (try
      (<p! (make-backend-request "POST" "/auth/logout" {}))
      (cm/clear-current-user)
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
    [:h1 "Account"]
    [:div.user-info
     [:p "Logged in as: " [:strong (cm/get-current-user)]]
     [:p "Sync URL: " [:code (cm/get-sync-url)]]]
    [:div.account-actions
     [:a.button.secondary {:href "/"} "Back to Editor"]
     [:button.danger {:on-click logout-user} "Logout"]]]])

(defn auth-page []
  (if (cm/is-authenticated?)
    [user-account]
    [auth-form]))

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
  (render))
