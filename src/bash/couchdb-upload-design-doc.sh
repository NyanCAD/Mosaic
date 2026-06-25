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

for attempt in 1 2 3; do
  # Fetch existing design document to get _rev
  EXISTING_DOC=$(curl -s -u "$COUCHDB_ADMIN_USER:$COUCHDB_ADMIN_PASS" \
    "$COUCHDB_URL/models/_design/models" || echo '{}')

  REV=$(echo "$EXISTING_DOC" | jq -r '._rev // empty')

  # Merge _rev into the design doc for the upload
  if [ -n "$REV" ]; then
    UPLOAD_DOC=$(jq "._rev = \"$REV\"" "$DESIGN_DOC")
  else
    UPLOAD_DOC=$(cat "$DESIGN_DOC")
  fi

  HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X PUT \
    "$COUCHDB_URL/models/_design/models" \
    -u "$COUCHDB_ADMIN_USER:$COUCHDB_ADMIN_PASS" \
    -H "Content-Type: application/json" \
    -d "$UPLOAD_DOC")

  if [ "$HTTP_CODE" = "201" ] || [ "$HTTP_CODE" = "200" ]; then
    echo "Design document uploaded successfully (attempt $attempt)"
    exit 0
  elif [ "$HTTP_CODE" = "409" ] && [ "$attempt" -lt 3 ]; then
    echo "Conflict (409), retrying... (attempt $attempt)"
    sleep 1
  else
    echo "Upload failed with HTTP $HTTP_CODE (attempt $attempt)" >&2
    exit 1
  fi
done
