; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.symbol-maker-test
  "Contract tests for the symbol maker's pure core: the drawing frame derived
   from model ports, grid snapping, the canvas fit, and the self-contained SVG
   file format. Expected values are hand-computed from
   specs/mosaic_symbol_maker.md."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.common :as cm]
            [nyancad.mosaic.symbol-file :as sf]
            [nyancad.mosaic.symbol-maker :as sm]))

(def ^:private three-ports
  [{:name "in1" :side "left" :type "electric"}
   {:name "in2" :side "left" :type "electric"}
   {:name "out" :side "right" :type "electric"}])

(def ^:private two-ports
  [{:name "o1" :side "left" :type "photonic"}
   {:name "o2" :side "right" :type "photonic"}])

(def ^:private python-doc
  {:version 1
   :target {:kind "python" :fqn "pkg.cells.ring_modulator"}
   :frame [2 1]
   :shapes [["lines" [[[0 0.5] [2 0.5]]] {"token" "ink"}]
            ["rect" {"x" 0.2 "y" 0.4 "width" 1 "height" 0.4 "token" "ink"}]
            ["ellipse" {"cx" 1 "cy" 0.5 "rx" 0.4 "ry" 0.2 "token" "accent"}]
            ["text" {"x" 0.5 "y" 0.5 "token" "ink"} "RM"]]})

;; @tags mosaic symbol maker
(defn- approx= [a b]
  (< (js/Math.abs (- a b)) 1e-9))

;; @tags mosaic symbol maker
(defn- tags [svg tag]
  (re-seq (re-pattern (str "<" tag "\\b[^>]*>")) svg))

;; @tags mosaic symbol maker
(defn- attr= [tag-str attr value]
  (str/includes? (str tag-str) (str attr "=\"" value "\"")))

;; @tags mosaic symbol maker
(deftest metadata-id-is-stable
  (is (= "gfp-symbol-maker" sm/metadata-id)))

;; @tags mosaic symbol maker
(deftest frame-geometry-reproduces-circuit-box
  (testing "two left ports and one right port: odd/even mismatch grows height to 3"
    (let [{:keys [frame ports]} (sm/frame-geometry three-ports "ckt")]
      (is (= [1 3] frame))
      (is (= [["in1" 0 1] ["in2" 0 3] ["out" 2 2]]
             (sort (map (juxt :name :x :y) ports))))))
  (testing "frame and ports agree with Mosaic's own placement"
    (let [{:keys [frame ports]} (sm/frame-geometry three-ports "ckt")]
      (is (= (cm/port-perimeter three-ports "ckt") frame))
      (is (= (set (cm/port-locations three-ports "ckt")) (set ports))))))

;; @tags mosaic symbol maker
(deftest frame-geometry-honours-shape-opts
  (testing "ring-single min-dims [1 2] and :end alignment"
    (let [{:keys [frame ports]} (sm/frame-geometry two-ports "ring-single")]
      (is (= [1 2] frame))
      (is (= [["o1" 0 2] ["o2" 2 2]]
             (sort (map (juxt :name :x :y) ports)))))))

;; @tags mosaic symbol maker
(deftest frame-geometry-without-ports-is-unit-box
  (doseq [ports [nil []]]
    (let [{:keys [frame] locs :ports} (sm/frame-geometry ports "ckt")]
      (is (= [1 1] frame))
      (is (empty? locs)))))

;; @tags mosaic symbol maker
(deftest snap-rounds-to-tenth-grid-unit
  (is (approx= 0.3 (sm/snap 0.26)))
  (is (approx= 0.2 (sm/snap 0.24)))
  (is (approx= -0.3 (sm/snap -0.26)))
  (is (approx= 2 (sm/snap 2.04)))
  (testing "alt held disables snapping"
    (is (= 0.26 (sm/snap 0.26 true)))))

;; @tags mosaic symbol maker
(deftest doc->svg-sizes-root-to-frame
  (let [svg (str (sm/doc->svg python-doc))
        root (first (tags svg "svg"))]
    (is (some? root))
    (is (attr= root "viewBox" "0 0 100 50"))
    (is (attr= root "width" "100"))
    (is (attr= root "height" "50"))))

;; @tags mosaic symbol maker
(deftest doc->svg-embeds-metadata-and-self-styles
  (let [svg (str (sm/doc->svg python-doc))]
    (is (str/includes? svg "<metadata id=\"gfp-symbol-maker\">"))
    (is (re-find #"<style[^>]*>[\s\S]*color-scheme:\s*light dark[\s\S]*</style>" svg))
    (is (re-find #"<style[^>]*>[\s\S]*light-dark\([\s\S]*</style>" svg))))

;; @tags mosaic symbol maker
(deftest doc->svg-scales-shapes-by-grid-size
  (let [svg (str (sm/doc->svg python-doc))
        rect (first (tags svg "rect"))
        ellipse (first (tags svg "ellipse"))
        text (first (tags svg "text"))]
    (testing "rect coordinates ×50"
      (is (some? rect))
      (is (every? (fn [[a v]] (attr= rect a v))
                  [["x" "10"] ["y" "20"] ["width" "50"] ["height" "20"]])))
    (testing "ellipse centre and radii ×50"
      (is (some? ellipse))
      (is (every? (fn [[a v]] (attr= ellipse a v))
                  [["cx" "50"] ["cy" "25"] ["rx" "20"] ["ry" "10"]])))
    (testing "text anchor ×50 and content kept"
      (is (some? text))
      (is (attr= text "x" "25"))
      (is (attr= text "y" "25"))
      (is (str/includes? svg ">RM</text>")))
    (testing "polyline points ×50"
      (is (re-find #"0[ ,]25[ ,]+100[ ,]25" svg)))
    (testing "shapes carry their colour token class"
      (is (re-find #"class=\"[^\"]*\bink\b" (str rect)))
      (is (re-find #"class=\"[^\"]*\baccent\b" (str ellipse))))))

;; @tags mosaic symbol maker
(deftest svg-round-trips-document-exactly
  (testing "python target"
    (is (= {:doc python-doc} (sm/svg->doc (sm/doc->svg python-doc)))))
  (testing "nyancir target"
    (let [doc (assoc python-doc :target {:kind "nyancir" :id "schematics/amp.gsch"})]
      (is (= {:doc doc} (sm/svg->doc (sm/doc->svg doc)))))))

;; @tags mosaic symbol maker
(deftest shapes-outside-frame-are-preserved-in-metadata
  (let [outside ["rect" {"x" 5 "y" 5 "width" 1 "height" 1 "token" "ink"}]
        doc (update python-doc :shapes conj outside)
        svg (str (sm/doc->svg doc))]
    (testing "the rendered body only draws shapes touching the frame"
      (is (not (some #(attr= % "x" "250") (tags svg "rect")))))
    (testing "the metadata keeps every shape, so a shrunken frame loses nothing"
      (is (= (:shapes doc) (:shapes (:doc (sm/svg->doc svg))))))))

;; @tags mosaic symbol maker
(deftest frame-ready-gates-edits-until-ports-resolve
  (is (false? (sm/frame-ready? {:kind "python" :fqn "pkg.cells.x"} nil)))
  (is (false? (sm/frame-ready? {:kind "nyancir" :id "a.gsch"} nil)))
  (is (true? (sm/frame-ready? {:kind "python" :fqn "pkg.cells.x"} {:frame [2 1] :ports []})))
  (testing "a file without a target has nothing to wait for"
    (is (true? (sm/frame-ready? nil nil)))))

;; @tags mosaic symbol maker
(deftest cancel-exits-drawing-tools
  (testing "a line in progress is finished first, keeping the Line tool"
    (is (= :finish-line (sm/cancel-action {:tool :line :draft [[0 0]]}))))
  (testing "any other tool returns to Select"
    (doseq [tool [:line :rect :ellipse :text :pan]]
      (is (= :exit-tool (sm/cancel-action {:tool tool :draft []})) (str tool))))
  (testing "a gesture in Select is aborted"
    (is (= :exit-tool (sm/cancel-action {:tool :select :draft [] :drag {:kind :move}}))))
  (testing "idle Select has nothing to cancel"
    (is (nil? (sm/cancel-action {:tool :select :draft [] :drag nil})))))

;; @tags mosaic symbol maker
(deftest malformed-metadata-shapes-are-dropped
  (let [good ["rect" {"x" 0 "y" 0 "width" 1 "height" 1 "token" "ink"}]
        data {"version" 1
              "target" {"kind" "python" "fqn" "pkg.cells.x"}
              "frame" [1 1]
              "shapes" [good
                        ["star" {"x" 0}]
                        ["lines" [] {"token" "ink"}]
                        ["lines" [[[0 0]]] {"token" "ink"}]
                        ["lines" [[[0 "a"] [1 1]]] {"token" "ink"}]
                        ["rect" {"x" "0" "y" 0 "width" 1 "height" 1}]
                        ["rect" {"x" 0 "y" 0 "width" 0 "height" 1}]
                        ["ellipse" {"cx" 0 "cy" 0 "rx" nil "ry" 1}]
                        ["text" {"x" 0 "y" 0}]
                        "not-a-shape"]}
        svg (str "<svg><metadata id=\"gfp-symbol-maker\">"
                 (js/JSON.stringify (clj->js data))
                 "</metadata></svg>")
        {:keys [doc]} (sm/svg->doc svg)]
    (is (= [good] (:shapes doc)))
    (testing "the surviving document still renders"
      (is (string? (sm/doc->svg doc))))))

;; @tags mosaic symbol maker
(deftest malformed-frame-and-target-are-defaulted
  (let [svg (str "<svg><metadata id=\"gfp-symbol-maker\">"
                 "{\"version\":1,\"target\":\"pkg\",\"frame\":[0,\"x\"],\"shapes\":{}}"
                 "</metadata></svg>")]
    (is (= {:version 1 :target nil :frame [1 1] :shapes []}
           (:doc (sm/svg->doc svg))))))

;; @tags mosaic symbol maker
(deftest non-object-metadata-opens-as-background
  (doseq [json ["[]" "5" "\"text\""]]
    (let [svg (str "<svg><metadata id=\"gfp-symbol-maker\">" json "</metadata></svg>")]
      (is (= {:background svg} (sm/svg->doc svg)) json))))

;; @tags mosaic symbol maker
(deftest foreign-svg-opens-as-background
  (let [foreign "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"5\" height=\"5\"/></svg>"]
    (is (= {:background foreign} (sm/svg->doc foreign)))))

;; @tags mosaic symbol maker
(deftest owned-svg-detects-symbol-maker-metadata
  (is (true? (sm/owned-svg? (sm/doc->svg python-doc))))
  (is (not (sm/owned-svg? "<svg><rect/></svg>")))
  (is (not (sm/owned-svg? "<svg><metadata id=\"other\">{}</metadata></svg>")))
  (is (not (sm/owned-svg? nil))))

;; @tags mosaic symbol maker
(deftest seed-file-parses-as-empty-document
  (testing "python seed written by the extension"
    (let [seed (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 50 50\" width=\"50\" height=\"50\">"
                    "<metadata id=\"gfp-symbol-maker\">"
                    "{\"version\":1,\"target\":{\"kind\":\"python\",\"fqn\":\"pkg.cells.x\"},\"frame\":[1,1],\"shapes\":[]}"
                    "</metadata></svg>")]
      (is (= {:doc {:version 1 :target {:kind "python" :fqn "pkg.cells.x"} :frame [1 1] :shapes []}}
             (sm/svg->doc seed)))))
  (testing "nyancir seed"
    (let [seed (str "<svg><metadata id=\"gfp-symbol-maker\">"
                    "{\"version\":1,\"target\":{\"kind\":\"nyancir\",\"id\":\"schematics/a.gsch\"},\"frame\":[1,1],\"shapes\":[]}"
                    "</metadata></svg>")]
      (is (= {:kind "nyancir" :id "schematics/a.gsch"}
             (get-in (sm/svg->doc seed) [:doc :target]))))))

;; @tags mosaic symbol maker
(deftest metadata-escapes-markup-in-text
  (let [doc (assoc python-doc :shapes [["text" {"x" 1 "y" 0.5 "token" "ink"} "a<b & c>d"]])
        svg (sm/doc->svg doc)]
    (is (not (str/includes? svg "a<b")))
    (is (str/includes? svg ">a&lt;b &amp; c&gt;d</text>"))
    (is (= {:doc doc} (sm/svg->doc svg)))))

;; @tags mosaic symbol maker
(deftest unreadable-metadata-opens-as-background
  (let [svg "<svg><metadata id=\"gfp-symbol-maker\">{not json</metadata></svg>"]
    (is (= {:background svg} (sm/svg->doc svg)))))

;; @tags mosaic symbol maker
(deftest infer-target-recognises-gsch-sidecars
  (is (= {:kind "nyancir" :id "schematics/amp.gsch"} (sm/infer-target "schematics/amp.gsch.svg")))
  (is (= {:kind "nyancir" :id "schematics/amp.gsch"} (sm/infer-target "schematics\\amp.gsch.svg")))
  (is (nil? (sm/infer-target "pkg/symbols/ring.svg")))
  (is (nil? (sm/infer-target nil))))

;; @tags mosaic symbol maker, gsch symbol sidecar
(deftest invalidate-symbol-paths-marks-only-changed-paths-stale
  (let [cache {"/abs/a.svg" {:revision [1 2] :url :url-a}
               "rel/b.gsch.svg" {:revision nil :url :url-b}
               "other.svg" {:revision [3 4] :url :url-c}}
        result (sm/invalidate-symbol-paths cache ["/abs/a.svg" "rel/b.gsch.svg" "missing.svg"])]
    (testing "matched entries keep their URL but no longer match any revision"
      (is (= :url-a (get-in result ["/abs/a.svg" :url])))
      (is (not= [1 2] (get-in result ["/abs/a.svg" :revision])))
      (is (= :url-b (get-in result ["rel/b.gsch.svg" :url])))
      (is (some? (get-in result ["rel/b.gsch.svg" :revision]))))
    (testing "unmatched entries and paths are untouched"
      (is (= (get cache "other.svg") (get result "other.svg")))
      (is (not (contains? result "missing.svg"))))))

;; @tags mosaic symbol maker, gsch symbol sidecar
(deftest symbol-change-evicts-deleted-and-invalidates-changed
  (let [cache {"/abs/a.svg" {:revision [1 2] :url :url-a}
               "other.svg" {:revision [3 4] :url :url-c}}]
    (testing "a deleted file is evicted so the canvas falls back to its box"
      (is (= {"other.svg" {:revision [3 4] :url :url-c}}
             (sf/apply-symbol-change cache ["/abs/a.svg"] true))))
    (testing "a changed file keeps its image while it re-reads"
      (let [result (sf/apply-symbol-change cache ["/abs/a.svg"] false)]
        (is (= :url-a (get-in result ["/abs/a.svg" :url])))
        (is (not= [1 2] (get-in result ["/abs/a.svg" :revision])))))))

;; @tags mosaic symbol maker, gsch symbol sidecar
(deftest symbol-path-forms-cover-every-cache-spelling
  (testing "absolute, real and relative paths, deduplicated"
    (is (= ["/ws/pkg/symbols/ring.svg" "/real/pkg/symbols/ring.svg" "pkg/symbols/ring.svg"]
           (sf/symbol-path-forms "/ws/pkg/symbols/ring.svg"
                                 "/real/pkg/symbols/ring.svg"
                                 "pkg/symbols/ring.svg")))
    (is (= ["/ws/a.svg" "a.svg"]
           (sf/symbol-path-forms "/ws/a.svg" "/ws/a.svg" "a.svg"))))
  (testing "Windows drive letters appear in both cases"
    (is (= ["c:\\ws\\ring.svg" "C:\\ws\\ring.svg" "ring.svg"]
           (sf/symbol-path-forms "c:\\ws\\ring.svg" nil "ring.svg")))))

;; @tags mosaic symbol maker
(deftest edit-queue-keeps-one-edit-in-flight-and-coalesces
  (let [[q1 a1] (sf/edit-queue-request sf/idle-edit-queue "one")
        [q2 a2] (sf/edit-queue-request q1 "two")
        [q3 a3] (sf/edit-queue-request q2 "three")]
    (testing "the first request applies immediately"
      (is (= [:apply "one"] a1)))
    (testing "later requests wait, keeping only the newest text"
      (is (nil? a2))
      (is (nil? a3))
      (is (= "three" (:pending q3))))
    (testing "settling applies the newest pending text, then goes idle and re-syncs"
      (let [[q4 a4] (sf/edit-queue-settled q3 true)
            [q5 a5] (sf/edit-queue-settled q4 true)]
        (is (= [:apply "three"] a4))
        (is (:in-flight? q4))
        (is (= [:post] a5))
        (is (= sf/idle-edit-queue q5))))))

;; @tags mosaic symbol maker
(deftest edit-queue-failure-drops-pending-and-resyncs
  (let [[q1] (sf/edit-queue-request sf/idle-edit-queue "one")
        [q2] (sf/edit-queue-request q1 "two")
        [q3 action] (sf/edit-queue-settled q2 false)]
    (is (= [:post] action))
    (is (= sf/idle-edit-queue q3))))

;; @tags mosaic symbol maker
(deftest port-overlay-matches-circuit-box-placement
  (let [items (sm/port-overlay-items (sm/frame-geometry three-ports "ckt"))
        by-name (into {} (map (juxt :name identity)) items)]
    (testing "left port stub runs from the frame edge to the tile centre outside it"
      (is (= [[0 0.5] [-0.5 0.5]] (:stub (by-name "in1"))))
      (is (= {:x 0.15 :y 0.5 :anchor "start" :baseline "middle" :text "in1"}
             (:label (by-name "in1")))))
    (testing "right port sits past the frame's right edge"
      (is (= [[1 1.5] [1.5 1.5]] (:stub (by-name "out"))))
      (is (= {:x 0.85 :y 1.5 :anchor "end" :baseline "middle" :text "out"}
             (:label (by-name "out")))))))

;; @tags mosaic symbol maker
(deftest drawing-helpers-build-shapes
  (is (= ["rect" {"x" 0.2 "y" 0.1 "width" 0.5 "height" 0.4 "token" "ink"}]
         (sm/rect-between [0.7 0.5] [0.2 0.1] "ink")))
  (is (nil? (sm/rect-between [0 0] [0 1] "ink")))
  (is (= ["ellipse" {"cx" 0.5 "cy" 0.5 "rx" 0.5 "ry" 0.5 "token" "accent"}]
         (sm/ellipse-between [0 0] [1 0.4] true "accent")))
  (is (= ["lines" [[[0 0] [1 1]]] {"token" "ink"}]
         (sm/polyline-shape [[0 0] [0 0] [1 1] [1 1]] "ink")))
  (is (nil? (sm/polyline-shape [[0 0] [0 0]] "ink")))
  (is (= ["text" {"x" 1.5 "y" 0 "token" "ink"} "t"]
         (sm/translate-shape ["text" {"x" 1 "y" 0.5 "token" "ink"} "t"] [0.5 -0.5]))))

;; @tags mosaic symbol maker
(deftest library-results-are-alphabetical-before-and-after-filtering
  (let [catalog [{:id "z" :name "zeta" :category "RF" :keywords ["oscillator"]}
                 {:id "b" :name "alpha" :category "RF" :keywords ["oscillator"]}
                 {:id "m" :name "Beta" :category "MEMS" :keywords ["oscillator"]}
                 {:id "a" :name "Alpha" :category "RF" :keywords ["Oscillator"]}
                 {:id "c" :name "gamma" :category "RF" :keywords ["amplifier"]}]
        entries (resolve 'nyancad.mosaic.symbol-maker/catalog-entries)]
    (is (some? entries) "Library has one shared filtering and alphabetical ordering contract")
    (when entries
      (is (= ["a" "b" "m" "c" "z"] (mapv :id (entries catalog "" nil))))
      (is (= ["a" "b" "z"] (mapv :id (entries catalog "OSCILLATOR" "RF"))))
      (is (= ["a" "b"] (mapv :id (entries catalog "ALPHA" "RF"))))
      (is (empty? (entries catalog "does not exist" "RF"))))))

;; @tags mosaic symbol maker
(deftest version-one-image-shapes-roundtrip-without-presentation-port-metadata
  (let [href "data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHZpZXdCb3g9JzEwIDIwIDQwIDIwJz48cGF0aCBkPSdNMTAgMjBoNDB2MjB6Jy8+PC9zdmc+"
        shape ["image" {"x" 0 "y" 0.5 "width" 4 "height" 2 "href" href}]
        doc {:version 1 :target nil :frame [4 3] :shapes [shape]}
        raw (str "<svg><metadata id=\"gfp-symbol-maker\">" (js/JSON.stringify (clj->js doc)) "</metadata></svg>")
        parsed (:doc (sm/svg->doc raw))]
    (is (= doc parsed))
    (is (not (contains? parsed :port-layout)))
    (when (seq (:shapes parsed))
      (is (str/includes? (sm/doc->svg parsed) "<image"))
      (is (str/includes? (sm/doc->svg parsed) "data:image/svg+xml;base64,"))
      (is (= [0 0.5 4 2.5] (sm/shape-bounds shape)))
      (is (= ["image" {"x" 1 "y" -0.5 "width" 4 "height" 2 "href" href}]
             (sm/translate-shape shape [1 -1]))))))

;; @tags mosaic symbol maker
(deftest static-image-fit-and-resize-preserve-the-self-contained-source
  (let [fit (resolve 'nyancad.mosaic.symbol-maker/fit-image)
        resize (resolve 'nyancad.mosaic.symbol-maker/resize-image)
        imported {:href "data:image/svg+xml;base64,PHN2Zy8+" :viewBox [10 20 40 20]}]
    (is (some? fit))
    (is (some? resize))
    (when (and fit resize)
      (let [shape (fit [4 3] imported)]
        (is (= ["image" {"x" 0 "y" 0.5 "width" 4 "height" 2 "href" (:href imported)}] shape))
        (is (= ["image" {"x" 0 "y" 0.5 "width" 6 "height" 3 "href" (:href imported)}]
               (resize shape [0 0.5] [6 2] false)))
        (is (= ["image" {"x" 0 "y" 0.5 "width" 6 "height" 1.5 "href" (:href imported)}]
               (resize shape [0 0.5] [6 2] true)))
        (is (= ["image" {"x" -2 "y" -0.5 "width" 6 "height" 3 "href" (:href imported)}]
               (resize shape [4 2.5] [-2 1] false)))
        (is (= [0.01 0.01]
               ((juxt #(get-in % [1 "width"]) #(get-in % [1 "height"]))
                (resize shape [0 0.5] [0 0.5] true))))))))

;; @tags mosaic symbol maker
(deftest non-self-contained-image-shapes-are-not-restored
  (doseq [href ["https://example.com/a.svg" "file:///a.svg" "data:text/html;base64,PHNjcmlwdD4="
                "data:image/png;base64,YQ==" "data:image/svg+xml;base64,!"]]
    (let [data {:version 1 :target nil :frame [1 1]
                :shapes [["image" {"x" 0 "y" 0 "width" 1 "height" 1 "href" href}]]}
          raw (str "<svg><metadata id=\"gfp-symbol-maker\">" (js/JSON.stringify (clj->js data)) "</metadata></svg>")]
      (is (= [] (:shapes (:doc (sm/svg->doc raw)))) href))))

;; @tags mosaic symbol maker
(deftest future-metadata-version-is-preserved-for-read-only-ui
  (let [raw "<svg><metadata id=\"gfp-symbol-maker\">{\"version\":2,\"frame\":[1,1],\"shapes\":[]}</metadata></svg>"]
    (is (= 2 (:version (:doc (sm/svg->doc raw)))))))

;; @tags mosaic symbol maker
(deftest fit-viewbox-matches-the-pane-aspect
  (testing "a wide pane widens the viewBox about the frame, which keeps its margin"
    (let [[x y w h] (sm/fit-viewbox [2 1] [1000 400])]
      (is (approx= 2.5 (/ w h)))
      (is (approx= -200 x))
      (is (approx= -75 y))
      (is (approx= 500 w))
      (is (approx= 200 h))))
  (testing "a tall pane heightens it"
    (let [[x y w h] (sm/fit-viewbox [2 1] [400 1000])]
      (is (approx= 0.4 (/ w h)))
      (is (approx= -75 x))
      (is (approx= -287.5 y))
      (is (approx= 250 w))
      (is (approx= 625 h))))
  (testing "the frame and its 1.5-unit margin stay inside the fitted box"
    (doseq [pane [[1000 400] [400 1000] [250 200]]]
      (let [[x y w h] (sm/fit-viewbox [2 1] pane)]
        (is (<= x -75) (str "pane " pane))
        (is (<= y -75) (str "pane " pane))
        (is (>= (+ x w) 175) (str "pane " pane))
        (is (>= (+ y h) 125) (str "pane " pane)))))
  (testing "a pane that is not laid out yet keeps the frame-only box"
    (is (= [-75 -75 250 200] (sm/fit-viewbox [2 1] nil)))
    (is (= [-75 -75 250 200] (sm/fit-viewbox [2 1] [0 0])))))

;; @tags mosaic symbol maker
(deftest canvas-bounds-cover-the-fitted-viewbox
  (doseq [pane [[1000 400] [400 1000] nil]]
    (let [[vx vy vw vh] (sm/fit-viewbox [2 1] pane)
          [gx gy gw gh] (sm/canvas-bounds [vx vy vw vh])]
      (is (<= gx vx) (str "pane " pane))
      (is (<= gy vy) (str "pane " pane))
      (is (>= (+ gx gw) (+ vx vw)) (str "pane " pane))
      (is (>= (+ gy gh) (+ vy vh)) (str "pane " pane))
      (is (>= gw (* 10 vw)) (str "pane " pane)))))
