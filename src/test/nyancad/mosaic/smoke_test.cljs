; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.smoke-test
  "Sanity check: the test build loads common + editor namespaces without
   hitting browser-only code. If this fails, the `:test` reader conditional
   or platform stub is broken."
  (:require [cljs.test :refer [deftest is]]
            nyancad.mosaic.common
            nyancad.mosaic.editor))

(deftest namespaces-load
  (is (some? (find-ns 'nyancad.mosaic.common)))
  (is (some? (find-ns 'nyancad.mosaic.editor))))
