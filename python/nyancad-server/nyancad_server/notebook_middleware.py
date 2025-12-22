"""Middleware for per-user/schematic notebook routing.

Uses URL-based routing pattern (similar to marimo's DynamicDirectoryMiddleware)
with CouchDB session authentication for LAN mode.

URL structure:
- /notebook/?schem=xxx → redirects to /notebook/{username}/{schematic}/
- /notebook/{username}/{schematic}/... → routes to cached marimo app
"""

import logging
import shutil
from pathlib import Path
from urllib.parse import urlencode

import httpx
from starlette.responses import JSONResponse, RedirectResponse

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


class LANSessionManager:
    """Minimal session manager for LAN mode that handles shutdown of cached apps.

    Provides the interface expected by marimo's signal_handler lifespan.
    """

    def __init__(self, middleware: "UserNotebookMiddleware"):
        self.middleware = middleware
        self.quiet = True

    def shutdown(self):
        """Shutdown all cached marimo apps."""
        for cache_key, app in self.middleware._app_cache.items():
            if hasattr(app, 'state') and hasattr(app.state, 'session_manager'):
                try:
                    app.state.session_manager.shutdown()
                except Exception as e:
                    logger.warning(f"Error shutting down {cache_key}: {e}")
        self.middleware._app_cache.clear()


class UserNotebookMiddleware:
    """Middleware that routes to per-user/schematic notebooks using URL-based routing.

    URL structure:
    - /notebook/?schem=xxx → redirects to /notebook/{username}/{schematic}/
    - /notebook/{username}/{schematic}/... → routes to cached marimo app

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

        # Cache of marimo apps keyed by URL path (e.g., "/notebook/user/schem")
        self._app_cache: dict[str, ASGIApp] = {}

        # Ensure notebooks directory exists
        self.notebooks_dir.mkdir(parents=True, exist_ok=True)

        # Create session manager for signal handler compatibility
        self._session_manager = LANSessionManager(self)

        # Set session_manager on wrapped app's state for signal_handler lifespan
        self.app.state.session_manager = self._session_manager

    @property
    def state(self):
        """Proxy state to wrapped app for compatibility with uvicorn/marimo."""
        return self.app.state

    def _get_notebook_path(self, username: str, schematic: str) -> Path:
        """Get path to user's schematic notebook, creating if needed.

        Args:
            username: Authenticated username
            schematic: Schematic ID

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

    def _create_marimo_app(self, notebook_path: Path, base_url: str) -> ASGIApp:
        """Create a marimo app in EDIT mode for a notebook.

        Args:
            notebook_path: Path to notebook file
            base_url: URL path prefix for this app (e.g., "/notebook/user/schem")

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
            base_url="",  # Routes built without prefix (we rewrite scope path)
            host=self.host,
            lifespan=Lifespans([
                lifespans.etc,
                lifespans.logging,
                *LIFESPAN_REGISTRY.get_all(),
            ]),
            enable_auth=False,
            allow_origins=("*",),
            skew_protection=True,  # Enabled - works with URL-based routing
        )

        # Set required state
        marimo_app.state.session_manager = session_manager
        marimo_app.state.config_manager = config_manager
        marimo_app.state.base_url = base_url
        marimo_app.state.headless = True
        marimo_app.state.watch = False
        marimo_app.state.enable_auth = False
        marimo_app.state.host = self.host
        marimo_app.state.port = self.port
        marimo_app.state.skew_protection = True
        marimo_app.state.mcp_server_enabled = False
        marimo_app.state.asset_url = None

        return marimo_app

    def _parse_cookies(self, scope: Scope) -> dict[str, str]:
        """Parse cookies from scope headers."""
        headers = dict(scope.get("headers", []))
        cookie_header = headers.get(b"cookie", b"").decode()
        return dict(
            cookie.split("=", 1) for cookie in cookie_header.split("; ") if "=" in cookie
        )

    def _parse_query_params(self, scope: Scope) -> dict[str, str]:
        """Parse query parameters from scope."""
        query_string = scope.get("query_string", b"").decode()
        return dict(
            param.split("=", 1) for param in query_string.split("&") if "=" in param
        )

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        """Handle ASGI request."""
        if scope["type"] not in ("http", "websocket"):
            await self.app(scope, receive, send)
            return

        path = scope.get("path", "")

        # Only handle /notebook routes
        if not path.startswith("/notebook"):
            await self.app(scope, receive, send)
            return

        cookies = self._parse_cookies(scope)

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
            username = "local"

        # Parse the path to determine routing
        # Expected formats:
        # - /notebook or /notebook/ → needs redirect (get schematic from query)
        # - /notebook/{username}/{schematic}/... → route to app

        path_after_notebook = path[len("/notebook"):]
        path_parts = [p for p in path_after_notebook.split("/") if p]

        if len(path_parts) >= 2:
            # URL already has username/schematic structure
            # e.g., /notebook/alice/circuit1/api/...
            url_username = path_parts[0]
            schematic = path_parts[1]
            remaining_path = "/" + "/".join(path_parts[2:]) if len(path_parts) > 2 else "/"

            # Security: ensure user can only access their own notebooks
            if self.require_auth and url_username != username:
                if scope["type"] == "http":
                    response = JSONResponse(
                        {"error": "Access denied"},
                        status_code=403
                    )
                    await response(scope, receive, send)
                    return
                else:
                    await send({"type": "websocket.close", "code": 4003})
                    return

            # Get or create notebook and marimo app
            notebook_path = self._get_notebook_path(url_username, schematic)
            base_url = f"/notebook/{url_username}/{schematic}"
            cache_key = base_url

            if cache_key not in self._app_cache:
                logger.info(f"Creating marimo app for {cache_key}")
                self._app_cache[cache_key] = self._create_marimo_app(notebook_path, base_url)

            marimo_app = self._app_cache[cache_key]

            # Rewrite path for marimo (remove the base_url prefix)
            new_scope = dict(scope)
            new_scope["path"] = remaining_path

            await marimo_app(new_scope, receive, send)
            return

        # Path is /notebook or /notebook/ - need to redirect with schematic
        if scope["type"] != "http":
            # WebSocket to /notebook without user/schematic - invalid
            await send({"type": "websocket.close", "code": 4000})
            return

        query_params = self._parse_query_params(scope)
        schematic = query_params.get("schem", "default")

        # Build redirect URL to /notebook/{username}/{schematic}/
        redirect_path = f"/notebook/{username}/{schematic}/"

        # Preserve other query params (except schem which is now in path)
        other_params = {k: v for k, v in query_params.items() if k != "schem"}
        if other_params:
            redirect_path += "?" + urlencode(other_params)

        logger.info(f"Redirecting to {redirect_path}")
        response = RedirectResponse(url=redirect_path, status_code=307)
        await response(scope, receive, send)
