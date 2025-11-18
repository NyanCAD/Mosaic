# SPDX-FileCopyrightText: 2024 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""CouchDB client utilities for NyanCAD MCP server."""

import os
from typing import Dict, List, Any, Optional
from urllib.parse import quote
import httpx

from .config import get_config


class CouchDBClient:
    """Async CouchDB client for fetching schematics and models.

    Supports two authentication modes:
    1. stdio mode: Uses config file or environment variables
    2. HTTP mode: Inherits session cookie from NyanCAD server
    """

    def __init__(
        self,
        url: Optional[str] = None,
        username: Optional[str] = None,
        password: Optional[str] = None,
        session_cookie: Optional[str] = None
    ):
        """Initialize CouchDB client.

        Args:
            url: CouchDB URL (defaults to config or env)
            username: CouchDB username (defaults to config or env)
            password: CouchDB password (defaults to config or env)
            session_cookie: Session cookie from NyanCAD server (HTTP mode)
        """
        # Load config for stdio mode
        config = get_config()

        self.url = (url or config.get_couchdb_url()).rstrip('/')
        self.session_cookie = session_cookie

        # Setup authentication
        if session_cookie:
            # HTTP mode: Use inherited session
            self.client = httpx.AsyncClient(
                cookies={"AuthSession": session_cookie},
                timeout=30.0
            )
        else:
            # stdio mode: Use basic auth from config/env
            self.username = username or config.get_couchdb_username()
            self.password = password or config.get_couchdb_password()
            self.client = httpx.AsyncClient(
                auth=(self.username, self.password) if self.password else None,
                timeout=30.0
            )

    async def close(self):
        """Close the HTTP client."""
        await self.client.aclose()

    async def get_document(self, db: str, doc_id: str) -> Optional[Dict[str, Any]]:
        """Get a document from CouchDB.

        Args:
            db: Database name
            doc_id: Document ID

        Returns:
            Document dict or None if not found
        """
        try:
            response = await self.client.get(f"{self.url}/{db}/{quote(doc_id, safe='')}")
            if response.status_code == 200:
                return response.json()
            elif response.status_code == 404:
                return None
            else:
                response.raise_for_status()
        except Exception as e:
            raise Exception(f"Failed to get document {doc_id} from {db}: {e}")

    async def get_schematic_docs(self, db: str, name: str) -> Dict[str, Any]:
        """Get all documents for a schematic (by prefix).

        Args:
            db: Database name
            name: Schematic name (e.g., "top$top")

        Returns:
            Dict of doc_id -> doc
        """
        try:
            # Use allDocs with prefix query
            response = await self.client.get(
                f"{self.url}/{db}/_all_docs",
                params={
                    "include_docs": "true",
                    "startkey": f'"{name}:"',
                    "endkey": f'"{name}:\\ufff0"'
                }
            )
            response.raise_for_status()
            data = response.json()

            # Convert to dict
            docs = {}
            for row in data.get("rows", []):
                if "doc" in row:
                    doc = row["doc"]
                    docs[doc["_id"]] = doc

            return docs
        except Exception as e:
            raise Exception(f"Failed to get schematic {name} from {db}: {e}")

    async def get_models_by_prefix(self, db: str, prefix: str = "models:") -> Dict[str, Any]:
        """Get all model documents.

        Args:
            db: Database name
            prefix: Key prefix (default: "models:")

        Returns:
            Dict of doc_id -> doc
        """
        try:
            response = await self.client.get(
                f"{self.url}/{db}/_all_docs",
                params={
                    "include_docs": "true",
                    "startkey": f'"{prefix}"',
                    "endkey": f'"{prefix}\\ufff0"'
                }
            )
            response.raise_for_status()
            data = response.json()

            # Convert to dict
            docs = {}
            for row in data.get("rows", []):
                if "doc" in row:
                    doc = row["doc"]
                    docs[doc["_id"]] = doc

            return docs
        except Exception as e:
            raise Exception(f"Failed to get models from {db}: {e}")

    async def search_models(self, db: str, search_term: str, limit: int = 20) -> List[Dict[str, Any]]:
        """Search for models by name (using Mango query).

        Args:
            db: Database name
            search_term: Search string
            limit: Maximum results

        Returns:
            List of matching model documents
        """
        try:
            # Use Mango query to search by name
            response = await self.client.post(
                f"{self.url}/{db}/_find",
                json={
                    "selector": {
                        "_id": {"$regex": "^models:"},
                        "name": {"$regex": f"(?i){search_term}"}
                    },
                    "limit": limit
                }
            )
            response.raise_for_status()
            data = response.json()
            return data.get("docs", [])
        except Exception as e:
            raise Exception(f"Failed to search models in {db}: {e}")
