; SPDX-License-Identifier: MPL-2.0
(ns nyancad.mosaic.symbol-maker.svg
  "Validate and sanitize embedded SVG files before inert image rendering."
  (:require [clojure.string :as str]
            [nyancad.mosaic.symbol-maker.css :as css]))

(def ^:private max-bytes 1048576)
(def ^:private active-elements
  #{"script" "foreignObject" "animate" "animateTransform" "animateMotion" "set"
    "discard" "handler" "listener" "audio" "video" "iframe"})
(def ^:private resource-attributes
  #{"fill" "stroke" "filter" "clip-path" "mask" "marker-start" "marker-mid" "marker-end" "cursor"})

;; @tags mosaic symbol maker
(defn- decode-base64 [encoded]
  (when (or (> (count encoded) (* 4 (inc (quot max-bytes 3))))
            (not (re-matches #"[A-Za-z0-9+/]+={0,2}" encoded))
            (not (zero? (mod (count encoded) 4))))
    (throw (js/Error. "Invalid or oversized embedded SVG data URI")))
  (.decode (js/TextDecoder. "utf-8" #js{:fatal true})
           (js/Uint8Array.from (js/atob encoded) (fn [character] (.charCodeAt character 0)))))

;; @tags mosaic symbol maker
(defn- encode-base64 [source]
  (js/btoa (apply str (map (fn [byte] (js/String.fromCharCode byte)) (.encode (js/TextEncoder.) source)))))

;; @tags mosaic symbol maker
(defn- decode-svg-uri [href]
  (when-not (string? href) (throw (js/Error. "Expected an embedded SVG data URI")))
  (if-let [[_ base64? payload]
           (re-matches #"(?i)data:image/svg\+xml(?:;charset=utf-8|;utf8)?(;base64)?,([\s\S]*)" href)]
    (if base64? (decode-base64 payload) (js/decodeURIComponent payload))
    (throw (js/Error. "Expected a self-contained SVG data URI"))))

(declare sanitize-source)

;; @tags mosaic symbol maker
(defn- safe-resource [value depth]
  (let [value (str/trim value)]
    (cond
      (str/starts-with? value "#") value
      (re-find #"(?i)^data:image/svg\+xml[;,]" value)
      (try (:href (sanitize-source (decode-svg-uri value) (inc depth)))
           (catch :default _ nil))
      (and (<= (count value) (+ 100 (* 4 (inc (quot max-bytes 3)))))
           (re-matches #"(?i)data:(?:image/(?:png|jpeg|gif|webp|avif)|font/(?:woff2?|ttf|otf));base64,[a-z0-9+/]+={0,2}" value))
      (try (js/atob (subs value (inc (str/index-of value ",")))) value
           (catch :default _ nil))
      :else nil)))

;; @tags mosaic symbol maker
(defn- sanitize-attributes! [node depth]
  (doseq [^js attr (vec (array-seq (.-attributes node)))]
    (let [name (.-localName attr) value (.-value attr)
          resource (fn [url] (safe-resource url depth))
          href? (#{"href" "src"} name)
          safe (cond
                 (or (str/starts-with? (str/lower-case name) "on")
                     (= "base" name)) nil
                 href? (let [reference (resource value)]
                         (when (or (str/starts-with? (or reference "") "#")
                                   (#{"image" "feImage"} (.-localName node))) reference))
                 (= "style" name) (not-empty (css/sanitize-css value true resource))
                 (resource-attributes name)
                 (let [declaration (css/sanitize-css (str "fill:" value) true resource)]
                   (when (str/starts-with? declaration "fill:") (subs declaration 5)))
                 :else value)]
      (if (some? safe)
        (set! (.-value attr) safe)
        (.removeAttributeNode node attr)))))

;; @tags mosaic symbol maker
(defn- sanitize-source [source depth]
  (when-not (string? source) (throw (js/Error. "Expected SVG text")))
  (when (or (> (count source) max-bytes)
            (> (.-length (.encode (js/TextEncoder.) source)) max-bytes))
    (throw (js/Error. "SVG files must be no larger than 1 MiB")))
  (when (> depth 24) (throw (js/Error. "Embedded SVG nesting is too deep")))
  (when (or (re-find #"(?i)<!ENTITY" source) (re-find #"(?i)<!DOCTYPE[^>]*\[" source))
    (throw (js/Error. "SVG internal entities and document type subsets are unsupported")))
  (let [source (str/replace source #"(?i)<!DOCTYPE[^>]*>" "")
        doc (.parseFromString (js/DOMParser.) source "image/svg+xml")
        root (.-documentElement doc)
        viewbox (or (.getAttribute root "viewBox")
                    (str "0 0 " (.getAttribute root "width") " " (.getAttribute root "height")))
        box (mapv js/Number (str/split (str/trim viewbox) #"[\s,]+"))]
    (when-not (and (= "svg" (.-localName root))
                   (= "http://www.w3.org/2000/svg" (.-namespaceURI root))
                   (nil? (.querySelector doc "parsererror"))
                   (= 4 (count box)) (every? js/isFinite box) (every? pos? (drop 2 box)))
      (throw (js/Error. "Expected well-formed SVG with a viewBox or numeric dimensions")))
    (doseq [node (vec (array-seq (.querySelectorAll doc "*")))]
      (if (or (not= "http://www.w3.org/2000/svg" (.-namespaceURI node))
              (active-elements (.-localName node)))
        (when-let [parent (.-parentNode node)] (.removeChild parent node))
        (do
          (sanitize-attributes! node depth)
          (when (= "style" (.-localName node))
            (set! (.-textContent node)
                  (css/sanitize-css (.-textContent node) false #(safe-resource % depth)))))))
    (let [walker (.createTreeWalker doc root 64)
          instructions (loop [items []]
                         (if-let [node (.nextNode walker)] (recur (conj items node)) items))]
      (doseq [node instructions] (.removeChild (.-parentNode node) node)))
    (.setAttribute root "viewBox" (str/join " " box))
    (.setAttribute root "width" (str (nth box 2)))
    (.setAttribute root "height" (str (nth box 3)))
    (let [sanitized (.serializeToString (js/XMLSerializer.) root)]
      {:source sanitized :viewBox box
       :href (str "data:image/svg+xml;base64," (encode-base64 sanitized))})))

;; @tags mosaic symbol maker
(defn import-source
  "Sanitize one SVG file, rejecting malformed/non-SVG, entities and files over
   1 MiB. Active content and external resources are stripped before embedding."
  [source]
  (sanitize-source source 0))

;; @tags mosaic symbol maker
(defn import-data-uri
  "Decode and revalidate an embedded SVG once when document text changes."
  [href]
  (import-source (decode-svg-uri href)))
