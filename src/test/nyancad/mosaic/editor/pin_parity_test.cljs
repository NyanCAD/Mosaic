(ns nyancad.mosaic.editor.pin-parity-test
  (:require ["fs" :as fs]
            ["path" :as path]
            [cljs.test :refer [deftest is testing]]
            [nyancad.mosaic.test-dommatrix]
            [nyancad.mosaic.editor :as e]
            [nyancad.mosaic.editor.platform-test :as pform]))

;; @tags factory card picyaml, photonic model ports
(defn- parity-fixtures [file]
  (let [relative (str "src/test/fixtures/" file)
        candidates [(path/resolve relative)
                    (path/resolve "frontend/mosaic" relative)]
        fixture (some #(when (.existsSync fs %) %) candidates)]
    (when-not fixture
      (throw (ex-info "Shared schematic pin fixture is unavailable" {:paths candidates})))
    (js->clj (js/JSON.parse (.readFileSync fs fixture "utf8")) :keywordize-keys true)))

;; @tags factory card picyaml, photonic model ports
(deftest named-pins-match-shared-rust-fixtures
  (doseq [{:keys [name device symbol pins]} (parity-fixtures "schematic_pin_parity.json")]
    (testing name
      (let [models {"models:test" (assoc symbol :generated true)}
            actual (into {} (map (fn [port]
                                  [(keyword (:name port)) [(:x port) (:y port)]])
                                (e/device-port-types models device)))]
        (is (= pins actual))))))

;; @tags factory card picyaml, photonic model ports
(defn- normalize-wire-contacts [models document]
  (with-redefs [pform/schematic (atom document)]
    (loop [remaining 100]
      (let [schematic @pform/schematic
            index (e/build-point-index schematic models)
            splits (e/build-wire-split-index schematic index)]
        (if (empty? splits)
          schematic
          (do
            (when (zero? remaining)
              (throw (ex-info "Wire splitting did not settle" {})))
            (doseq [[id coordinates] splits]
              (e/split-wire id coordinates index))
            (recur (dec remaining))))))))

;; @tags factory card picyaml, photonic model ports
(deftest network-verdict-matches-shared-rust-fixtures
  (doseq [{:keys [name document symbol source_nets valid]}
          (parity-fixtures "schematic_network_parity.json")]
    (testing name
      (let [models {"models:test" (assoc symbol :generated true)}
            document (into {} (map (fn [[id dev]] [(cljs.core/name id) dev])) document)
            normalized (normalize-wire-contacts models document)
            index (e/build-point-index normalized models)
            networks (e/build-wire-networks normalized index)
            groups (into #{} (map (fn [{:keys [attributions port-ids]}]
                                   (into (set (map (fn [[id pin]] (str id "," (cljs.core/name pin))) attributions))
                                         (map #(str % ",P") port-ids))))
                         (:components networks))
            expected (set (map set source_nets))]
        (is (= valid (= expected groups)))))))
