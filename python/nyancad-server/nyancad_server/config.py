"""Shared configuration for nyancad-server.

Environment variables (all optional):
- JWT_SECRET: Secret key for signing JWT tokens (auto-generated if not set)
- SERVER_URL: Base URL of this server (default: http://localhost:8080)
- COUCHDB_URL: CouchDB server URL (default: https://api.nyancad.com/)
- COUCHDB_ADMIN_USER: CouchDB admin username (default: admin)
- COUCHDB_ADMIN_PASS: CouchDB admin password (default: empty string)
"""

import logging
import os
import secrets

logger = logging.getLogger(__name__)

# JWT configuration - auto-generate if not provided
JWT_SECRET = os.getenv("JWT_SECRET")
if not JWT_SECRET:
    JWT_SECRET = secrets.token_hex(32)
    logger.warning(
        "JWT_SECRET not set - generated random secret. "
        "OAuth tokens will not persist across server restarts. "
        "Set JWT_SECRET environment variable for production use."
    )

# Server configuration
SERVER_URL = os.getenv("SERVER_URL", "http://localhost:8080")

# CouchDB configuration
COUCHDB_URL = os.getenv("COUCHDB_URL", "https://api.nyancad.com/").rstrip('/')
COUCHDB_ADMIN_USER = os.getenv("COUCHDB_ADMIN_USER", "admin")
COUCHDB_ADMIN_PASS = os.getenv("COUCHDB_ADMIN_PASS", "")
