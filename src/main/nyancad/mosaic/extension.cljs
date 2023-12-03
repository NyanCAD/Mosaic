; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.extension
  (:require ["vscode" :as vscode]
            [reagent.dom.server :as rdom]))

(defn get-html [^js context ^js document ^js webview]
  (let [css (.asWebviewUri webview (-> context .-extensionUri (vscode/Uri.joinPath "public" "css" "style.css")))
        common (.asWebviewUri webview (-> context .-extensionUri (vscode/Uri.joinPath "public" "js" "common.js")))
        editor (.asWebviewUri webview (-> context .-extensionUri (vscode/Uri.joinPath "public" "js" "editor.js")))]
    (rdom/render-to-static-markup
     [:html
      [:head
       [:meta {:charset "utf-8"}]
       [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
       [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :cross-origin ""}]
       [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=Roboto:wght@400;500;700&display=swap"}]
       [:link {:rel "stylesheet" :href css}]
       [:style
        "html, body {
            width: 100%;
            height: 100%;
            overflow: hidden;
            margin: 0;
            padding: 0;
        }"]]
      [:body
       [:div#mosaic_editor]
       [:script#document {:type "application/json"
                          :dangerouslySetInnerHTML
                          {:__html (.getText document)}}]
       [:script {:src common}]
       [:script {:src editor}]]])))

(deftype SchematicEditorProvider [context]
  Object
  (resolveCustomTextEditor [this ^js document ^js webviewpanel token]
    (set! (.. webviewpanel -webview -html) (get-html context document (.-webview webviewpanel)))
    (let [handler (fn [^js e]
                    (when (= (.. e -document -uri (toString)) (.. document -uri (toString)))
                        (.. webviewpanel -webview
                            (postMessage
                             (amap (.-contentChanges e) idx ret
                                   #js{:offset (.-rangeOffset (aget ret idx))
                                       :length (.-rangeLength (aget ret idx))
                                       :content (.-text (aget ret idx))})))))
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
