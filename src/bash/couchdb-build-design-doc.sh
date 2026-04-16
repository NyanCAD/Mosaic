#!/bin/bash
# SPDX-FileCopyrightText: 2024 Pepijn de Vos
# SPDX-License-Identifier: MPL-2.0

set -e

DIR="$(dirname "$0")"
DESIGN_DOC="$DIR/../../public/design-doc.json"

# Build design document by injecting compiled view.js into the template
jq '.views.lib.cljs = $js' --rawfile js "$DIR/../../public/js/view.js" "$DIR/view.json" > "$DESIGN_DOC"
