; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.extension
  (:require ["vscode" :as vscode]
            [reagent.dom.server :as rdom]
            [cljs.core.async :refer [go go-loop <! >! chan]]
            [cljs.core.async.interop :refer-macros [<p!]]
            goog.functions))

(def debounce #(goog.functions/debounce % 100))

(defn get-html [^js context ^js document ^js webview]
  (let [css (.asWebviewUri webview (-> context .-extensionUri (vscode/Uri.joinPath "public" "css" "style.css")))
        common (.asWebviewUri webview (-> context .-extensionUri (vscode/Uri.joinPath "public" "js" "common.js")))
        editor (.asWebviewUri webview (-> context .-extensionUri (vscode/Uri.joinPath "public" "js" "editor.js")))
        source (.-cspSource webview)
        nonce (apply str (repeatedly 32 #(rand-nth "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")))]
    (rdom/render-to-static-markup
     [:html
      [:head
       [:meta {:charset "utf-8"}]
       [:meta {:http-equiv "Content-Security-Policy"
               :content (str "default-src " source " 'unsafe-eval';")}]; img-src " source "; style-src " source "; font-src " source "; script-src 'nonce-" nonce "';")}]
       [:link {:rel "stylesheet" :href css}]]
      [:body
       [:div#mosaic_editor]
       [:script#document {:type "application/json"
                          :dangerouslySetInnerHTML
                          {:__html (.getText document)}}]
       [:script {:nonce nonce :src common}]
       [:script {:nonce nonce :src editor}]]])))

(deftype SchematicEditorProvider [context]
  Object
  (resolveCustomTextEditor [this ^js document ^js webviewpanel token]
    (set! (.. webviewpanel -webview -html) (get-html context document (.-webview webviewpanel)))
    (set! (.. webviewpanel -webview -options) #js{:enableScripts true})
    (let [editqueue (chan 128)
          check #(= (.-oldcontent ^js %1) (.getText document %2))]
      (go-loop []
        (doseq [edit (<! editqueue)
                :let [we (vscode/WorkspaceEdit.)
                      vsrange (vscode/Range. (.positionAt document (.-offset edit))
                                           (.positionAt document (+ (.-offset edit) (.-length edit))))]]
          (if (check edit vsrange)
            (do
              (.replace we
                        (.-uri document)
                        vsrange
                        (.-content edit))
              (<p! (.. vscode -workspace (applyEdit we))))
            (js/console.log "Rejected:" edit)))
        (recur))
      (.. webviewpanel -webview
          (onDidReceiveMessage
           (fn [msg]
            ;;  (.log js/console msg)
             (go (>! editqueue (.-update msg)))))))
    (let [handler (fn [^js e]
                    (when (= (.. e -document -uri (toString)) (.. document -uri (toString)))
                      (.. webviewpanel -webview
                          (postMessage
                           #js{:type "update"
                               :version (.. e -document -version)
                               :update (amap (.-contentChanges e) idx ret
                                             #js{:offset ^js (.-rangeOffset (aget ret idx))
                                                 :length ^js (.-rangeLength (aget ret idx))
                                                 :content (.-text (aget ret idx))})}))))
          sub (vscode/workspace.onDidChangeTextDocument handler)]
      (.onDidDispose webviewpanel #(.dispose sub)))))

(defn register-schematic-provider [context]
  (.log js/console "editor now active")
  (let [provider (SchematicEditorProvider. context)]
    (.. vscode -window (registerCustomEditorProvider "Mosaic.schematic", provider, #js{:webviewOptions #js{:enableScripts true, :retainContextWhenHidden true}}))))

(defn hello-world []
  (.. vscode -window (showInformationMessage "Hello World!")))

(defn activate [^js/vscode.ExtensionContext context]
  (.log js/console "Congratulations, cljs-vscode-extension is now active!")

  (let [disposable (.. vscode -commands (registerCommand "Mosaic.schematic.new" #'hello-world))]
    (.. context -subscriptions (push disposable)))
  
  (.. context -subscriptions (push (register-schematic-provider context))))

(defn deactivate [])

(defn ^:dev/after-load reload []
  (.log js/console "Clearning JS cache...")
  (js-delete js/require.cache (js/require.resolve "./main")))

(def exports #js{:activate activate
                 :deactivate deactivate})
