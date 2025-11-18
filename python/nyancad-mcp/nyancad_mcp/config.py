# SPDX-FileCopyrightText: 2024 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Configuration management for NyanCAD MCP server."""

import os
import json
from pathlib import Path
from typing import Optional, Dict, Any


class MCPConfig:
    """Configuration for MCP server with dual-mode authentication."""

    def __init__(self):
        """Initialize configuration from file or environment."""
        self.config_path = Path.home() / ".config" / "nyancad" / "credentials.json"
        self.config = self._load_config()

    def _load_config(self) -> Dict[str, Any]:
        """Load configuration from file if it exists."""
        if self.config_path.exists():
            try:
                with open(self.config_path, 'r') as f:
                    return json.load(f)
            except Exception as e:
                # Log error but continue with env vars
                print(f"Warning: Failed to load config file: {e}", file=__import__('sys').stderr)
                return {}
        return {}

    def get_couchdb_url(self) -> str:
        """Get CouchDB URL from config or environment."""
        return (
            self.config.get("couchdb", {}).get("url") or
            os.getenv("COUCHDB_URL") or
            "https://api.nyancad.com/"
        ).rstrip('/')

    def get_couchdb_username(self) -> Optional[str]:
        """Get CouchDB username from config or environment."""
        return (
            self.config.get("couchdb", {}).get("username") or
            os.getenv("COUCHDB_USER") or
            os.getenv("COUCHDB_ADMIN_USER")
        )

    def get_couchdb_password(self) -> Optional[str]:
        """Get CouchDB password from config or environment."""
        return (
            self.config.get("couchdb", {}).get("password") or
            os.getenv("COUCHDB_PASSWORD") or
            os.getenv("COUCHDB_ADMIN_PASS") or
            ""
        )

    def get_default_db(self) -> str:
        """Get default database name."""
        return (
            self.config.get("default_db") or
            os.getenv("NYANCAD_DB") or
            "offline"
        )

    def save_config_template(self):
        """Save a configuration template file if it doesn't exist."""
        if not self.config_path.exists():
            self.config_path.parent.mkdir(parents=True, exist_ok=True)
            template = {
                "couchdb": {
                    "url": "https://api.nyancad.com/",
                    "username": "your-username",
                    "password": "your-password"
                },
                "default_db": "offline"
            }
            with open(self.config_path, 'w') as f:
                json.dump(template, f, indent=2)
            print(f"Created config template at: {self.config_path}", file=__import__('sys').stderr)


# Global config instance
_config: Optional[MCPConfig] = None


def get_config() -> MCPConfig:
    """Get or create the global config instance."""
    global _config
    if _config is None:
        _config = MCPConfig()
    return _config
