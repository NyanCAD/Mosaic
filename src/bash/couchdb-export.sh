#!/bin/bash
# SPDX-FileCopyrightText: 2024 Pepijn de Vos
# SPDX-License-Identifier: MPL-2.0

set -e

DIR="$(dirname "$0")"
DESIGN_DOC="$DIR/../../public/design-doc.json"

# CouchDB configuration from environment (matching server.py)
COUCHDB_URL="${COUCHDB_URL:-https://api.nyancad.com}"
COUCHDB_ADMIN_USER="${COUCHDB_ADMIN_USER:-admin}"
COUCHDB_ADMIN_PASS="${COUCHDB_ADMIN_PASS:-}"

# Remove trailing slash from URL
COUCHDB_URL="${COUCHDB_URL%/}"

# Build design document
jq '.views.lib.cljs = $js' --rawfile js "$DIR/../../public/js/view.js" "$DIR/view.json" > "$DESIGN_DOC"

# Try to fetch existing design document to get _rev
EXISTING_DOC=$(curl -s -u "$COUCHDB_ADMIN_USER:$COUCHDB_ADMIN_PASS" \
  "$COUCHDB_URL/models/_design/models" || echo '{}')

# Extract _rev if document exists
REV=$(echo "$EXISTING_DOC" | jq -r '._rev // empty')

# Add _rev to design document if it exists
if [ -n "$REV" ]; then
  jq "._rev = \"$REV\"" "$DESIGN_DOC" > "$DESIGN_DOC.tmp"
  mv "$DESIGN_DOC.tmp" "$DESIGN_DOC"
fi

# Upload to CouchDB with authentication
curl -f -X PUT "$COUCHDB_URL/models/_design/models" \
  -u "$COUCHDB_ADMIN_USER:$COUCHDB_ADMIN_PASS" \
  -H "Content-Type: application/json" \
  -d @"$DESIGN_DOC"