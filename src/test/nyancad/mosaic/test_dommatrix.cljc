; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.mosaic.test-dommatrix
  "Node has no `DOMMatrix`/`DOMMatrixReadOnly`; the node-test build installs
   @thednp/dommatrix (a spec-compatible implementation) as the global shim so
   production transform code that uses `js/DOMMatrixReadOnly` (e.g. `cm/flip-x`,
   `selected-port-labels`) runs unchanged under Node. Require this namespace for
   its load-time side effect from any test that touches that code."
  (:require ["@thednp/dommatrix" :as DOMMatrix]))

(set! (.-DOMMatrix js/globalThis) DOMMatrix)
(set! (.-DOMMatrixReadOnly js/globalThis) DOMMatrix)
