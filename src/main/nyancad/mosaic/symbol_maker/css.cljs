; SPDX-License-Identifier: MPL-2.0
(ns nyancad.mosaic.symbol-maker.css
  "Filter active and external CSS resources in an isolated SVG image."
  (:require [clojure.string :as str]
            ["css-tree" :as css]))

;; @tags mosaic symbol maker
(defn- safe-value? [value sanitize-resource]
  (let [safe? (atom true)]
    (css/walk value
              #js{:visit "Url"
                  :enter (fn [^js node]
                           (if-let [safe (sanitize-resource (.-value node))]
                             (set! (.-value node) safe)
                             (reset! safe? false)))})
    (css/walk value
              #js{:visit "Function"
                  :enter (fn [^js node]
                           (let [name (str/lower-case (.decode css/ident (.-name node)))]
                             (when (#{"expression" "paint"} name) (reset! safe? false))
                             (when (= "url" name)
                               (let [children (array-seq (.toArray ^js (.-children node)))
                                     ^js child (first children)
                                     value (if (and (= 1 (count children)) (= "String" (.-type child)))
                                             (.-value child)
                                             (.decode css/ident (apply str (map css/generate children))))]
                                 (if-let [safe (sanitize-resource value)]
                                   (do (set! (.-type node) "Url") (set! (.-value node) safe))
                                   (reset! safe? false))))
                             (when (#{"image-set" "-webkit-image-set" "image"} name)
                               (doseq [^js child (array-seq (.toArray ^js (.-children node)))]
                                 (when (= "String" (.-type child))
                                   (if-let [safe (sanitize-resource (.-value child))]
                                     (set! (.-value child) safe)
                                     (reset! safe? false)))))))})
    @safe?))

;; @tags mosaic symbol maker
(defn sanitize-css
  "Strip imports and unsafe resource declarations; retain safe selectors, local
   references, colors, grouping rules and keyframes without changing their scope."
  [source inline? sanitize-resource]
  (try
    (let [ast (css/parse source #js{:context (if inline? "declarationList" "stylesheet")
                                   :parseCustomProperty true
                                   :onParseError (fn [error] (throw error))})]
      (css/walk ast
                #js{:visit "Atrule"
                    :enter (fn [^js node item ^js list]
                             (when (= "import" (str/lower-case (.decode css/ident (.-name node))))
                               (.remove list item)
                               (aget css/walk "skip")))})
      (css/walk ast
                #js{:visit "Declaration"
                    :enter (fn [^js node item ^js list]
                             (when (or (#{"behavior" "-moz-binding"} (str/lower-case (.decode css/ident (.-property node))))
                                       (not (safe-value? (.-value node) sanitize-resource)))
                               (.remove list item)
                               (aget css/walk "skip")))})
      (css/generate ast))
    (catch :default _ "")))
