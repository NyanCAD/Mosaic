; SPDX-License-Identifier: MPL-2.0
(ns nyancad.mosaic.symbol-svg-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            ["jsdom" :refer [JSDOM]]
            ["node:fs" :as fs]
            ["node:buffer" :refer [Buffer]]
            [nyancad.mosaic.symbol-maker.svg :as svg]))

;; @tags mosaic symbol maker
(defn- with-svg-dom [test]
  (let [window (.-window (JSDOM. ""))]
    (set! js/global.DOMParser (.-DOMParser window))
    (set! js/global.XMLSerializer (.-XMLSerializer window))
    (try (test) (finally (.close window)))))

(use-fixtures :once with-svg-dom)

;; @tags mosaic symbol maker
(defn- data-uri [source]
  (str "data:image/svg+xml;base64," (.toString (Buffer.from source "utf8") "base64")))

;; @tags mosaic symbol maker
(defn- parse-source [source]
  (.parseFromString (js/DOMParser.) source "image/svg+xml"))

;; @tags mosaic symbol maker
(deftest valid-static-vectors-keep-colors-styles-definitions-and-identifiers
  (let [source "<svg xmlns='http://www.w3.org/2000/svg' class='art' viewBox='10 20 40 20'><defs><linearGradient id='paint'><stop stop-color='blue'/></linearGradient></defs><style>.ink{fill:red;stroke:url('#paint')}@media (prefers-color-scheme:dark){.ink{opacity:.5}}@keyframes pulse{from{opacity:.5}to{opacity:1}}</style><path id='body' class='ink shape' fill='blue' stroke='red' d='M10 20h40v20z'/></svg>"
        result (svg/import-source source)
        doc (parse-source (:source result))]
    (is (= [10 20 40 20] (:viewBox result)))
    (is (= (data-uri (:source result)) (:href result)))
    (is (= "ink shape" (.getAttribute (.querySelector doc "path") "class")))
    (is (= "blue" (.getAttribute (.querySelector doc "path") "fill")))
    (is (some? (.querySelector doc "#paint")))
    (is (str/includes? (.-textContent (.querySelector doc "style")) ".ink"))
    (is (str/includes? (.-textContent (.querySelector doc "style")) "@media"))
    (is (str/includes? (.-textContent (.querySelector doc "style")) "@keyframes"))))

;; @tags mosaic symbol maker
(deftest active-and-external-content-is-stripped-without-dropping-safe-artwork
  (let [source "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10' onload='alert(1)'><script>alert(1)</script><foreignObject><div xmlns='http://www.w3.org/1999/xhtml'>html</div></foreignObject><style>@import url('https://example.com/a.css');.safe{fill:blue;stroke:url(https://example.com/a.svg)}.local{fill:url('#paint')}</style><defs><linearGradient id='paint'/></defs><image href='https://example.com/image.svg'/><path class='safe local' onclick='alert(1)' fill='red' style='opacity:.6;filter:url(https://example.com/filter)' d='M0 0h10v10z'/></svg>"
        result (try (svg/import-source source) (catch :default error {:error (.-message error)}))]
    (is (nil? (:error result)) (:error result))
    (when (:source result)
      (let [doc (parse-source (:source result))
            path (.querySelector doc "path")
            css (.-textContent (.querySelector doc "style"))]
        (is (nil? (.querySelector doc "script,foreignObject")))
        (is (not (.hasAttribute (.-documentElement doc) "onload")))
        (is (not (.hasAttribute path "onclick")))
        (is (= "red" (.getAttribute path "fill")))
        (is (not (str/includes? (:source result) "https://example.com")))
        (is (not (str/includes? css "@import")))
        (is (str/includes? css "fill:blue"))
        (is (str/includes? css "url(#paint)"))
        (is (= "0.6" (.-opacity (.-style path))))))))

;; @tags mosaic symbol maker
(deftest nested-self-contained-svg-images-are-recursively-sanitized
  (let [nested "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'><script>alert(1)</script><path fill='red' onclick='evil()' d='M0 0h10v10z'/></svg>"
        source (str "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><image x='10' y='20' width='50' height='40' href='" (data-uri nested) "'/></svg>")
        result (try (svg/import-source source) (catch :default error {:error (.-message error)}))
        reimport (resolve 'nyancad.mosaic.symbol-maker.svg/import-data-uri)]
    (is (some? reimport))
    (is (nil? (:error result)))
    (when (and reimport (:source result))
      (let [image (.querySelector (parse-source (:source result)) "image")
            nested-result (reimport (.getAttribute image "href"))
            nested-doc (parse-source (:source nested-result))]
        (is (= "50" (.getAttribute image "width")))
        (is (nil? (.querySelector nested-doc "script")))
        (is (= "red" (.getAttribute (.querySelector nested-doc "path") "fill")))
        (is (not (.hasAttribute (.querySelector nested-doc "path") "onclick")))))))

;; @tags mosaic symbol maker
(deftest imported-data-uri-is-revalidated-and-canonicalized
  (let [reimport (resolve 'nyancad.mosaic.symbol-maker.svg/import-data-uri)
        source "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'><path fill='red' d='M0 0h10'/></svg>"]
    (is (some? reimport))
    (when reimport
      (is (= (svg/import-source source) (reimport (data-uri source))))
      (doseq [href ["https://example.com/a.svg" "data:text/html;base64,PHNjcmlwdD4=" "data:image/svg+xml;base64,!"]]
        (is (thrown? js/Error (reimport href)))))))

;; @tags mosaic symbol maker
(deftest malformed-non-svg-internal-entities-and-oversized-input-are-rejected
  (doseq [source ["not XML" "<html/>" "<svg xmlns='http://www.w3.org/2000/svg'><path></svg>"
                  "<!DOCTYPE svg [<!ENTITY x 'evil'>]><svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1 1'/ >"
                  (str "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1 1'><desc>" (apply str (repeat 1048576 "x")) "</desc></svg>")]]
    (is (thrown? js/Error (svg/import-source source)))))

;; @tags mosaic symbol maker
(deftest normal-generated-preview-css-and-nested-vector-images-are-importable
  (let [nested "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'><path fill='red' d='M0 0h10v10z'/></svg>"
        style (-> (fs/readFileSync "public/css/style.css" "utf8")
                  (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;"))
        source (str "<svg xmlns='http://www.w3.org/2000/svg' class='gsch-symbol' viewBox='0 0 100 100'><style>" style "</style><image href='" (data-uri nested) "' width='50' height='40'/></svg>")
        result (try (svg/import-source source) (catch :default error {:error (.-message error)}))]
    (is (nil? (:error result)) (:error result))
    (is (str/starts-with? (or (:href result) "") "data:image/svg+xml;base64,"))))

;; @tags mosaic symbol maker
(deftest every-bundled-catalog-entry-is-static-self-contained-artwork
  (doseq [entry (js->clj (js/JSON.parse (fs/readFileSync "public/symbols/catalog.json" "utf8")) :keywordize-keys true)]
    (let [result (try (svg/import-source (:svg entry)) (catch :default error {:error (.-message error)}))]
      (is (nil? (:error result)) (str (:id entry) ": " (:error result)))
      (is (str/starts-with? (or (:href result) "") "data:image/svg+xml;base64,") (:id entry)))))

;; @tags mosaic symbol maker
(deftest sanitized-local-styles-retain-their-computed-colors-in-isolation
  (let [source "<svg xmlns='http://www.w3.org/2000/svg' class='art' viewBox='0 0 10 10'><style>.art{fill:rgb(220,20,60)}.ink{stroke:rgb(0,128,0);opacity:.6}</style><path class='ink' d='M0 0h10'/></svg>"
        result (svg/import-source source)
        window (.-window (JSDOM. (:source result)))
        doc (.-document window)
        style (.createElement doc "style")]
    ;; jsdom computes selectors but does not activate SVG style elements.
    ;; Activate exactly the sanitized CSS in this isolated test document.
    (set! (.-textContent style) (.-textContent (.querySelector doc "svg style")))
    (.append (.-head doc) style)
    (try
      (is (= "rgb(220, 20, 60)" (.-fill (.getComputedStyle window (.querySelector doc "svg")))))
      (is (= "rgb(0, 128, 0)" (.-stroke (.getComputedStyle window (.querySelector doc "path")))))
      (is (= "0.6" (.-opacity (.getComputedStyle window (.querySelector doc "path")))))
      (finally (.close window)))))

;; @tags mosaic symbol maker
(deftest valid-large-unicode-svg-roundtrips-below-the-byte-limit
  (let [payload (.repeat "μ" 350000)
        source (str "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'><desc>" payload "</desc><path fill='red' d='M0 0h10v10z'/></svg>")
        result (try (svg/import-source source) (catch :default error {:error (.-message error)}))]
    (is (nil? (:error result)) (:error result))
    (when (:href result)
      (let [restored (svg/import-data-uri (:href result))
            description (.-textContent (.querySelector (parse-source (:source restored)) "desc"))]
        (is (= 350000 (count description)))
        (is (= "μ" (subs description 0 1)))
        (is (= "μ" (subs description (dec (count description)))))
        (is (true? (= (:href result) (:href restored))))))))

;; @tags mosaic symbol maker
(deftest escaped-css-url-spellings-use-the-same-resource-filter
  (let [source "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'><defs><linearGradient id='paint'/></defs><style>.external{fill:u\\72l('https://example.com/fill.svg');stroke:red}.local{stroke:u\\72l('#paint')}.relative{fill:u\\72l(externalAsset)}</style><path class='external' fill=\"u\\72l('https://example.com/presentation.svg')\" stroke='blue' d='M0 0h10v10z'/><rect class='local' fill='red' x='0' y='0' width='1' height='1'/></svg>"
        result (svg/import-source source)
        doc (parse-source (:source result))
        style (.-textContent (.querySelector doc "style"))]
    (is (not (str/includes? (:source result) "https://example.com")))
    (is (not (str/includes? (:source result) "externalAsset")))
    (is (not (.hasAttribute (.querySelector doc "path") "fill")))
    (is (= "blue" (.getAttribute (.querySelector doc "path") "stroke")))
    (is (= "red" (.getAttribute (.querySelector doc "rect") "fill")))
    (is (str/includes? style "#paint"))
    (is (str/includes? style "stroke:red"))))
