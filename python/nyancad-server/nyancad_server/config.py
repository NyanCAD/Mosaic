"""Shared configuration for nyancad-server.

Environment variables:
- JWT_SECRET: Secret key for signing JWT tokens (required)
- SERVER_URL: Base URL of this server (default: http://localhost:8080)
- COUCHDB_URL: CouchDB server URL (default: https://api.nyancad.com/)
- COUCHDB_ADMIN_USER: CouchDB admin username (default: admin)
- COUCHDB_ADMIN_PASS: CouchDB admin password (default: empty string)
"""

import os

# JWT configuration
JWT_SECRET = os.getenv("JWT_SECRET")

# Server configuration
SERVER_URL = os.getenv("SERVER_URL", "http://localhost:8080")

# CouchDB configuration
COUCHDB_URL = os.getenv("COUCHDB_URL", "https://api.nyancad.com/").rstrip('/')
COUCHDB_ADMIN_USER = os.getenv("COUCHDB_ADMIN_USER", "admin")
COUCHDB_ADMIN_PASS = os.getenv("COUCHDB_ADMIN_PASS", "")
