# SPDX-FileCopyrightText: 2024 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""
Unified API for accessing NyanCAD schematic data.

Provides abstract interface with two implementations:
- BridgeAPI: Browser PouchDB via anywidget (no HTTP calls)
- ServerAPI: CouchDB access via httpx (replaces legacy aiohttp)
"""

from abc import ABC, abstractmethod
from collections import deque
from typing import Any, Optional
import httpx
from .netlist import model_key


class SchematicAPI(ABC):
    """Abstract base class for unified schematic data access."""

    @abstractmethod
    async def get_docs(self, name: str) -> tuple[Any, dict[str, dict]]:
        """Get all documents for a single schematic group (no subcircuits).

        Args:
            name: Schematic group name

        Returns:
            Tuple of (sequence_number, {document_id: document_data})
            Sequence number may be None for implementations without sequences.
        """
        pass

    @abstractmethod
    async def get_all_schem_docs(self, name: str) -> tuple[Any, dict[str, dict]]:
        """Get schematic with all subcircuits and models recursively.

        Args:
            name: Top-level schematic group name

        Returns:
            Tuple of (sequence_number, full_schematic_dict)
            Structure: {
                "models": {model_id: model_def, ...},
                group_name: {device_id: device_spec, ...},
                subcircuit_name: {device_id: device_spec, ...},
                ...
            }
        """
        pass

    @abstractmethod
    async def get_library(
        self,
        filter: Optional[str] = None,
        category: Optional[list[str]] = None
    ) -> dict[str, dict]:
        """List available models and schematics with filtering.

        Args:
            filter: Name filter pattern (regex)
            category: Category path to filter by (hierarchical)

        Returns:
            Dictionary of {model_id: model_data} with complete model definitions
        """
        pass


class BridgeAPI(SchematicAPI):
    """API implementation using anywidget SchematicBridge data."""

    def __init__(self, bridge_widget):
        """Create BridgeAPI wrapping a SchematicBridge widget.

        Args:
            bridge_widget: SchematicBridge instance from anywidget.py
        """
        self.bridge = bridge_widget

    async def get_docs(self, name: str) -> tuple[Any, dict[str, dict]]:
        """Extract single schematic group from complete bridge data.

        Args:
            name: Schematic group name

        Returns:
            Tuple of (None, {document_id: document_data}) for the requested group
        """
        # Get complete data
        data = self.bridge.schematic_data

        # Extract just the requested group
        docs = data.get(name, {})

        return (None, docs)

    async def get_all_schem_docs(self, name: str) -> tuple[Any, dict[str, dict]]:
        """Return complete schematic data from bridge (already includes all subcircuits)."""
        # Bridge schematic_data already has full hierarchy with subcircuits and models
        return (None, self.bridge.schematic_data)

    async def get_library(
        self,
        filter: Optional[str] = None,  # noqa: ARG002
        category: Optional[list[str]] = None  # noqa: ARG002
    ) -> dict[str, dict]:
        """Not implemented - adds complexity not needed for bridge mode.

        Args:
            filter: Unused (kept for interface compatibility)
            category: Unused (kept for interface compatibility)
        """
        raise NotImplementedError(
            "BridgeAPI does not support library queries. "
            "Library data is already included in schematic_data."
        )


class ServerAPI(SchematicAPI):
    """API implementation using httpx CouchDB access."""

    def __init__(
        self,
        db_url: str,
        username: Optional[str] = None,
        password: Optional[str] = None,
        auth_token: Optional[str] = None
    ):
        """Create ServerAPI with CouchDB connection.

        Args:
            db_url: CouchDB database URL (e.g., "https://api.nyancad.com/userdb-alice")
            username: Optional CouchDB username for basic auth
            password: Optional CouchDB password for basic auth
            auth_token: Optional JWT Bearer token (alternative to username/password)
        """
        self.base_url = db_url.rstrip('/')

        # Build headers
        headers = {}
        if auth_token:
            headers["Authorization"] = f"Bearer {auth_token}"

        # Build auth tuple for basic auth
        auth = None
        if username and password:
            auth = (username, password)

        # Create persistent httpx client (reused across calls)
        self.client = httpx.AsyncClient(
            headers=headers,
            auth=auth,
            timeout=30.0
        )

    async def close(self):
        """Close httpx client."""
        await self.client.aclose()

    async def __aenter__(self):
        """Async context manager entry."""
        return self

    async def __aexit__(self, *args):  # noqa: ANN002
        """Async context manager exit.

        Args:
            *args: Exception info (unused, handled by context manager protocol)
        """
        await self.close()

    async def get_docs(self, name: str) -> tuple[Any, dict[str, dict]]:
        """Get documents for a single schematic group via CouchDB range query.

        Args:
            name: Schematic group name

        Returns:
            Tuple of (update_seq, {document_id: document_data})
        """
        response = await self.client.get(
            f"{self.base_url}/_all_docs",
            params={
                "include_docs": "true",
                "startkey": f'"{name}:"',
                "endkey": f'"{name}:\ufff0"',  # \ufff0 is unicode max for range
                "update_seq": "true"
            }
        )
        response.raise_for_status()
        data = response.json()

        seq = data.get('update_seq')
        docs = {row['id']: row['doc'] for row in data.get('rows', [])}

        return seq, docs

    async def get_all_schem_docs(self, name: str) -> tuple[Any, dict[str, dict]]:
        """Get schematic with all subcircuits and models via BFS traversal.

        Ports the legacy netlist.py BFS algorithm to httpx.

        Args:
            name: Top-level schematic group name

        Returns:
            Tuple of (update_seq, full_schematic_dict)
        """
        schem = {}

        # First fetch models
        seq, models = await self.get_docs("models")
        schem["models"] = models

        # Fetch main schematic
        seq, docs = await self.get_docs(name)
        if not docs:
            return seq, schem

        schem[name] = docs

        # BFS to resolve hierarchical circuits
        queue = deque(docs.values())
        seen_models = set()

        while queue:
            dev = queue.popleft()
            model_id_bare = dev.get('model')

            if not model_id_bare:
                continue

            model_id_prefixed = model_key(model_id_bare)

            # Skip if already processed
            if model_id_prefixed in seen_models:
                continue

            seen_models.add(model_id_prefixed)

            # Check if model exists and is a schematic (no templates)
            if model_id_prefixed in models:
                model_def = models[model_id_prefixed]

                # Schematic models have no templates field
                if not model_def.get('templates'):
                    # Fetch subcircuit documents
                    if model_id_bare not in schem:
                        seq, subdocs = await self.get_docs(model_id_bare)
                        if subdocs:
                            schem[model_id_bare] = subdocs
                            queue.extend(subdocs.values())

        return seq, schem

    def _build_selector(
        self,
        filter: Optional[str],
        category: Optional[list[str]]
    ) -> dict:
        """Build Mango selector for category and name filtering.

        Args:
            filter: Name filter pattern (regex)
            category: Category path (hierarchical)

        Returns:
            Mango selector dict
        """
        selector = {}

        # Add category path constraints
        if category:
            for i, cat in enumerate(category):
                selector[f"category.{i}"] = cat

        # Add name regex filter
        if filter:
            selector["name"] = {"$regex": f"(?i){filter}"}

        return selector

    async def get_library(
        self,
        filter: Optional[str] = None,
        category: Optional[list[str]] = None
    ) -> dict[str, dict]:
        """List available models with filtering via CouchDB views/queries.

        Uses same query patterns as ClojureScript libman:
        - Category search: Mango query via _find
        - Name search only: CouchDB view models/name_search
        - No criteria: Basic range query

        Args:
            filter: Name filter pattern
            category: Category path to filter by

        Returns:
            Dictionary of {model_id: model_data} with complete model definitions
        """
        models = {}

        # Category search (with or without name filter)
        if category:
            selector = self._build_selector(filter, category)
            response = await self.client.post(
                f"{self.base_url}/_find",
                json={"selector": selector}
            )
            response.raise_for_status()
            data = response.json()
            models = {doc["_id"]: doc for doc in data.get("docs", [])}

        # Name search only (no category)
        elif filter:
            response = await self.client.get(
                f"{self.base_url}/_design/models/_view/name_search",
                params={
                    "startkey": f'"{filter.lower()}"',
                    "include_docs": "true"
                }
            )
            response.raise_for_status()
            data = response.json()
            models = {row["id"]: row["doc"] for row in data.get("rows", [])}

        # No criteria - get all models
        else:
            response = await self.client.get(
                f"{self.base_url}/_all_docs",
                params={
                    "include_docs": "true",
                    "startkey": '"models:"',
                    "endkey": '"models:\ufff0"'
                }
            )
            response.raise_for_status()
            data = response.json()
            models = {row["id"]: row["doc"] for row in data.get("rows", [])}

        return models
