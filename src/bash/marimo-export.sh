#!/bin/bash
# SPDX-FileCopyrightText: 2024 Pepijn de Vos
# SPDX-License-Identifier: MPL-2.0

set -e

rm -rf public/notebook
marimo export html-wasm src/marimo/notebook.py \
  -o public/notebook/index.html \
  --mode edit
sed -i 's|</title>|</title>\n<script type="module" src="../js/filestore.js"></script>|' \
  public/notebook/index.html
sed -i '/display: none !important;/d' \
  public/notebook/index.html