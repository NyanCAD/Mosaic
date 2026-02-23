#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2022 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0

# Copy webview assets (CSS, icons, fonts) into the VSCode extension output directory.
# Run after shadow-cljs compiles vscode-webview so that the webview can load styles.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="$ROOT_DIR/vscode-ext/out"

mkdir -p "$OUT_DIR"

# Copy stylesheet
cp "$ROOT_DIR/public/css/style.css" "$OUT_DIR/style.css"

# Copy cursor icons referenced by style.css
if [ -d "$ROOT_DIR/public/css/icons" ]; then
  mkdir -p "$OUT_DIR/icons"
  cp -r "$ROOT_DIR/public/css/icons/"* "$OUT_DIR/icons/"
fi

# Copy fonts referenced by style.css (if they exist)
if [ -d "$ROOT_DIR/public/css/fonts" ]; then
  mkdir -p "$OUT_DIR/fonts"
  cp -r "$ROOT_DIR/public/css/fonts/"* "$OUT_DIR/fonts/"
fi

echo "VSCode webview assets copied to $OUT_DIR"
