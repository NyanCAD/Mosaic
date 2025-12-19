"""Middleware for per-user/schematic notebook routing.

Adapts marimo's DynamicDirectoryMiddleware pattern for EDIT mode with
CouchDB session authentication.
"""

import logging
import shutil
from pathlib import Path
from typing import Callable, Awaitable

import httpx
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.types import ASGIApp, Receive, Scope, Send

from marimo._config.manager import get_default_config_manager
from marimo._server.file_router import AppFileRouter
from marimo._server.lsp import NoopLspServer
from marimo._server.main import create_starlette_app
from marimo._server.model import SessionMode
from marimo._server.sessions import SessionManager
from marimo._server.tokens import AuthToken
from marimo._utils.marimo_path import MarimoPath
import marimo._server.api.lifespans as lifespans
from marimo._server.registry import LIFESPAN_REGISTRY
from marimo._utils.lifespans import Lifespans

from .config import COUCHDB_URL

logger = logging.getLogger(__name__)


async def validate_couchdb_session(cookies: dict[str, str]) -> tuple[bool, str]:
    """Validate CouchDB session cookie and return username.

    Args:
        cookies: Request cookies dict

    Returns:
        (valid, username) tuple
    """
    session_cookie = cookies.get("AuthSession")
    if not session_cookie:
        return False, ""

    async with httpx.AsyncClient() as client:
        try:
            response = await client.get(
                f"{COUCHDB_URL}/_session",
                cookies={"AuthSession": session_cookie}
            )

            if response.status_code != 200:
                return False, ""

            data = response.json()
            user_ctx = data.get("userCtx", {})
            username = user_ctx.get("name")

            if not username:
                return False, ""

            return True, username

        except Exception as e:
            logger.error(f"CouchDB session validation error: {e}")
            return False, ""


class UserNotebookMiddleware:
    """Middleware that routes to per-user/schematic notebooks.

    Creates notebook directories on demand and caches marimo apps.
    Validates CouchDB sessions for authentication in LAN mode.
    """

    def __init__(
        self,
        app: ASGIApp,
        notebooks_dir: str | Path,
        default_notebook: str | Path,
        require_auth: bool = True,
        host: str = "localhost",
        port: int = 8080,
    ):
        """Initialize middleware.

        Args:
            app: Wrapped ASGI application (fallback for non-notebook routes)
            notebooks_dir: Base directory for user notebooks
            default_notebook: Path to default notebook template
            require_auth: Whether to require CouchDB authentication
            host: Server host for marimo app state
            port: Server port for marimo app state
        """
        self.app = app
        self.notebooks_dir = Path(notebooks_dir)
        self.default_notebook = Path(default_notebook)
        self.require_auth = require_auth
        self.host = host
        self.port = port

        # Cache of marimo apps keyed by notebook path
        self._app_cache: dict[str, ASGIApp] = {}

        # Ensure notebooks directory exists
        self.notebooks_dir.mkdir(parents=True, exist_ok=True)

    def _get_notebook_path(self, username: str, schematic: str) -> Path:
        """Get path to user's schematic notebook, creating if needed.

        Args:
            username: Authenticated username
            schematic: Schematic ID from query param

        Returns:
            Path to notebook file
        """
        notebook_dir = self.notebooks_dir / username / schematic
        notebook_path = notebook_dir / "notebook.py"

        if not notebook_path.exists():
            logger.info(f"Creating notebook for {username}/{schematic}")
            notebook_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy(self.default_notebook, notebook_path)

        return notebook_path

    def _create_marimo_app(self, notebook_path: Path) -> ASGIApp:
        """Create a marimo app in EDIT mode for a notebook.

        Args:
            notebook_path: Path to notebook file

        Returns:
            Starlette ASGI app for the notebook
        """
        notebook_file = str(notebook_path)

        file_router = AppFileRouter.from_filename(MarimoPath(notebook_file))
        config_manager = get_default_config_manager(current_path=notebook_file)

        session_manager = SessionManager(
            file_router=file_router,
            mode=SessionMode.EDIT,
            development_mode=False,
            quiet=True,
            include_code=True,
            ttl_seconds=None,
            lsp_server=NoopLspServer(),
            config_manager=config_manager,
            cli_args={},
            argv=[],
            auth_token=AuthToken(""),
            redirect_console_to_browser=False,
            watch=False,
        )

        marimo_app = create_starlette_app(
            base_url="",
            host=self.host,
            lifespan=Lifespans([
                lifespans.etc,
                lifespans.logging,
                *LIFESPAN_REGISTRY.get_all(),
            ]),
            enable_auth=False,
            allow_origins=("*",),
            skew_protection=True,
        )

        # Set required state
        marimo_app.state.session_manager = session_manager
        marimo_app.state.config_manager = config_manager
        marimo_app.state.base_url = ""
        marimo_app.state.headless = True
        marimo_app.state.watch = False
        marimo_app.state.enable_auth = False
        marimo_app.state.host = self.host
        marimo_app.state.port = self.port
        marimo_app.state.skew_protection = True
        marimo_app.state.mcp_server_enabled = False
        marimo_app.state.asset_url = None

        return marimo_app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        """Handle ASGI request."""
        if scope["type"] not in ("http", "websocket"):
            await self.app(scope, receive, send)
            return

        # Parse request
        path = scope.get("path", "")

        # Only handle /notebook routes
        if not path.startswith("/notebook"):
            await self.app(scope, receive, send)
            return

        # Extract query params
        query_string = scope.get("query_string", b"").decode()
        query_params = dict(
            param.split("=", 1) for param in query_string.split("&") if "=" in param
        )

        schematic = query_params.get("schem", "default")

        # Get cookies
        headers = dict(scope.get("headers", []))
        cookie_header = headers.get(b"cookie", b"").decode()
        cookies = dict(
            cookie.split("=", 1) for cookie in cookie_header.split("; ") if "=" in cookie
        )

        # Validate authentication if required
        if self.require_auth:
            valid, username = await validate_couchdb_session(cookies)
            if not valid:
                if scope["type"] == "http":
                    response = JSONResponse(
                        {"error": "Authentication required"},
                        status_code=401
                    )
                    await response(scope, receive, send)
                    return
                else:
                    # WebSocket - close with error
                    await send({"type": "websocket.close", "code": 4001})
                    return
        else:
            # Local mode - use default user
            username = "local"

        # Get or create notebook
        notebook_path = self._get_notebook_path(username, schematic)
        cache_key = str(notebook_path)

        # Get or create cached marimo app
        if cache_key not in self._app_cache:
            logger.info(f"Creating marimo app for {cache_key}")
            self._app_cache[cache_key] = self._create_marimo_app(notebook_path)

        marimo_app = self._app_cache[cache_key]

        # Rewrite path to remove /notebook prefix for marimo
        # e.g., /notebook/foo -> /foo, /notebook -> /
        new_path = path[len("/notebook"):] or "/"
        scope = dict(scope)
        scope["path"] = new_path

        # Forward to marimo app
        await marimo_app(scope, receive, send)
