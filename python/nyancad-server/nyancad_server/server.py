"""Main ASGI server for NyanCAD with marimo integration."""

import argparse
import sys
import os
from importlib import resources
from urllib.parse import quote

import httpx
import uvicorn
from starlette.applications import Starlette
from starlette.staticfiles import StaticFiles
from starlette.routing import Route, Mount
from starlette.responses import JSONResponse, Response, StreamingResponse
from starlette.requests import Request
from starlette.background import BackgroundTask
from slowapi import Limiter
from slowapi.util import get_remote_address
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware
from .mcp_server import mcp
from .oauth import create_oauth_routes
from .config import COUCHDB_URL, COUCHDB_ADMIN_USER, COUCHDB_ADMIN_PASS

# Rate limiting
limiter = Limiter(key_func=get_remote_address)

# HTTP client for GitHub proxy (global for connection pooling)
github_client = httpx.AsyncClient(timeout=30.0, follow_redirects=True)

# Marimo server components (mirroring start.py)
import marimo._server.api.lifespans as lifespans
from marimo._config.manager import get_default_config_manager
from marimo._server.file_router import AppFileRouter
from marimo._server.lsp import NoopLspServer
from marimo._server.main import create_starlette_app
from marimo._server.model import SessionMode
from marimo._server.registry import LIFESPAN_REGISTRY
from marimo._server.sessions import SessionManager
from marimo._server.tokens import AuthToken
from marimo._server.utils import initialize_asyncio, initialize_fd_limit
from marimo._server.uvicorn_utils import initialize_signals
from marimo._utils.lifespans import Lifespans
from marimo._utils.marimo_path import MarimoPath


# Authentication endpoints
async def register_endpoint(request: Request):
    """Handle user registration."""
    try:
        # Parse request body
        body = await request.json()
        username = body.get("username", "").strip()
        password = body.get("password", "").strip()
        
        # Basic validation
        if not username or not password:
            return JSONResponse(
                {"error": "Username and password are required"},
                status_code=400
            )
        
        if len(password) < 6:
            return JSONResponse(
                {"error": "Password must be at least 6 characters long"},
                status_code=400
            )
        
        # Create user document
        user_doc = {
            "_id": f"org.couchdb.user:{username}",
            "name": username,
            "password": password,
            "type": "user",
            "roles": []
        }
        
        async with httpx.AsyncClient() as client:
            # Create user in CouchDB
            user_response = await client.put(
                f"{COUCHDB_URL}/_users/org.couchdb.user:{quote(username)}",
                json=user_doc,
                auth=(COUCHDB_ADMIN_USER, COUCHDB_ADMIN_PASS),
                headers={"Content-Type": "application/json"}
            )
            
            # If user creation failed, return CouchDB response unchanged
            if user_response.status_code >= 300:
                return Response(
                    content=user_response.text,
                    status_code=user_response.status_code,
                    headers=dict(user_response.headers)
                )
            
            # User created successfully, now login to get session
            session_response = await client.post(
                f"{COUCHDB_URL}/_session",
                data={"name": username, "password": password},
                headers={"Content-Type": "application/x-www-form-urlencoded"}
            )
            
            # Forward the session response
            return Response(
                content=session_response.text,
                status_code=session_response.status_code,
                headers=dict(session_response.headers)
            )
            
    except Exception as e:
        return JSONResponse({"error": "Internal server error"}, status_code=500)


async def login_endpoint(request: Request):
    """Handle user login."""
    try:
        # Parse request body
        body = await request.json()
        username = body.get("username", "").strip()
        password = body.get("password", "").strip()
        
        # Basic validation
        if not username or not password:
            return JSONResponse(
                {"error": "Username and password are required"},
                status_code=400
            )
        
        # Forward login request to CouchDB
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{COUCHDB_URL}/_session",
                data={"name": username, "password": password},
                headers={"Content-Type": "application/x-www-form-urlencoded"}
            )
            
            # Forward the response
            return Response(
                content=response.text,
                status_code=response.status_code,
                headers=dict(response.headers)
            )
            
    except Exception as e:
        return JSONResponse({"error": "Internal server error"}, status_code=500)


async def logout_endpoint(request: Request):
    """Handle user logout."""
    try:
        # Get session cookie from request
        session_cookie = request.cookies.get("AuthSession")

        # Forward logout request to CouchDB
        async with httpx.AsyncClient() as client:
            response = await client.delete(
                f"{COUCHDB_URL}/_session",
                cookies={"AuthSession": session_cookie} if session_cookie else {}
            )

            # Forward the response
            return Response(
                content=response.text,
                status_code=response.status_code,
                headers=dict(response.headers)
            )

    except Exception as e:
        return JSONResponse({"error": "Internal server error"}, status_code=500)


# Whitelisted GitHub repositories for proxy access
ALLOWED_REPOS = {
    "fossi-foundation/skywater-pdk-libs-sky130_fd_pr",
    "fossi-foundation/globalfoundries-pdk-libs-gf180mcu_fd_pr",
    "IHP-GmbH/IHP-Open-PDK",
}


@limiter.limit("10/minute")
async def github_proxy_endpoint(request: Request):
    """Proxy requests to GitHub for whitelisted repositories."""
    try:
        # Get the path after /gh/
        path = request.path_params.get("path", "")

        # Extract owner/repo from path (first two components)
        parts = path.split("/", 2)
        if len(parts) < 2:
            return JSONResponse(
                {"error": "Invalid path format. Expected: /gh/owner/repo/..."},
                status_code=400
            )

        owner, repo = parts[0], parts[1]
        repo_path = f"{owner}/{repo}"

        # Check if repo is whitelisted
        if repo_path not in ALLOWED_REPOS:
            return JSONResponse(
                {"error": f"Repository {repo_path} is not whitelisted"},
                status_code=403
            )

        # Construct GitHub URL
        github_url = f"https://github.com/{path}"

        # Use manual streaming mode: build request and send with stream=True
        # This prevents buffering the entire response in memory
        req = github_client.build_request("GET", github_url)
        response = await github_client.send(req, stream=True)

        # Stream the response (success or error) with background cleanup
        return StreamingResponse(
            response.aiter_bytes(chunk_size=8192),
            status_code=response.status_code,
            headers=dict(response.headers),
            media_type=response.headers.get("content-type", "application/octet-stream"),
            background=BackgroundTask(response.aclose)
        )

    except httpx.TimeoutException as e:
        print(f"Timeout error: {e}")
        return JSONResponse(
            {"error": "Request to GitHub timed out"},
            status_code=504
        )
    except Exception as e:
        import traceback
        print(f"Proxy error: {e}")
        print(traceback.format_exc())
        return JSONResponse(
            {"error": f"Proxy error: {str(e)}"},
            status_code=500
        )


def create_app(use_wasm: bool = False) -> Starlette:
    """Create the Starlette application with static files and optional marimo edit integration."""
    
    # Get the notebook file via symlinked resources
    with resources.path("nyancad_server", "notebook.py") as notebook_path:
        notebook_file = str(notebook_path)
    
    # Create marimo components (mirroring start() function)
    file_router = AppFileRouter.from_filename(MarimoPath(notebook_file))
    config_manager = get_default_config_manager(current_path=notebook_file)
    
    # Create session manager in EDIT mode (key difference from ASGI API)
    session_manager = SessionManager(
        file_router=file_router,
        mode=SessionMode.EDIT,  # This enables editing capabilities
        development_mode=False,
        quiet=True,
        include_code=True,
        ttl_seconds=None,
        lsp_server=NoopLspServer(),
        config_manager=config_manager,
        cli_args={},
        argv=[],
        auth_token=AuthToken(""),  # No auth for now
        redirect_console_to_browser=False,
        watch=False,
    )
    
    # Create marimo Starlette app with base_url="" (internal routing is root)
    marimo_app = create_starlette_app(
        base_url="",
        host="localhost",
        lifespan=Lifespans([
            lifespans.etc,
            lifespans.logging,
            *LIFESPAN_REGISTRY.get_all(),
        ]),
        enable_auth=False,  # Simplified for now
        allow_origins=("*",),
        skew_protection=True,
    )
    
    # Set required state on marimo app
    marimo_app.state.session_manager = session_manager
    marimo_app.state.config_manager = config_manager
    marimo_app.state.base_url = ""
    marimo_app.state.headless = True
    marimo_app.state.watch = False

    # Create MCP app and extract its lifespan
    mcp_app = mcp.streamable_http_app()
    mcp_lifespan = mcp_app.router.lifespan_context

    # Create OAuth routes (standard paths at root, custom paths prefixed with /oauth)
    oauth_routes = create_oauth_routes()

    # Create main Starlette app with signal handler for proper session cleanup
    app = Starlette(
        routes=[
            # Legacy auth endpoints (cookie-based)
            Route("/auth/register", register_endpoint, methods=["POST"]),
            Route("/auth/login", login_endpoint, methods=["POST"]),
            Route("/auth/logout", logout_endpoint, methods=["POST"]),
            # GitHub proxy
            Route("/gh/{path:path}", github_proxy_endpoint, methods=["GET"]),
            # MCP server
            Mount("/ai", app=mcp_app),
        ] + oauth_routes,  # Add all OAuth routes directly at root level
        lifespan=Lifespans([
            lifespans.signal_handler,
            mcp_lifespan,
        ])
    )

    # Add rate limiting state
    app.state.limiter = limiter
    app.add_exception_handler(RateLimitExceeded, lambda request, exc: JSONResponse(
        {"error": "Rate limit exceeded. Try again later."},
        status_code=429
    ))
    # Note: SlowAPIMiddleware not added globally to avoid middleware conflicts
    # Rate limiting is applied to specific endpoints via @limiter.limit decorator

    # Set session manager on main app so signal handler can clean it up
    app.state.session_manager = session_manager
    app.state.config_manager = config_manager
    app.state.remote_url = None
    
    if not use_wasm:
        # Mount marimo at /notebook
        app.mount("/notebook", marimo_app)
    
    # Get the static files directory via symlinked resources
    with resources.path("nyancad_server", "public") as public_path:
        static_path = str(public_path)
    
    # Mount static files at root
    app.mount("/", StaticFiles(directory=static_path, html=True), name="static")
    
    return app


def create_wasm_app() -> Starlette:
    """Create the Starlette application in WASM mode."""
    return create_app(use_wasm=True)


def main():
    """Main entry point for the server."""
    parser = argparse.ArgumentParser(description="NyanCAD Server")
    parser.add_argument(
        "--host", 
        default="localhost", 
        help="Host to bind to (default: localhost)"
    )
    parser.add_argument(
        "--port", 
        type=int, 
        default=8080, 
        help="Port to bind to (default: 8080)"
    )
    parser.add_argument(
        "--reload", 
        action="store_true", 
        help="Enable auto-reload for development"
    )
    parser.add_argument(
        "--wasm", 
        action="store_true", 
        help="Serve WASM notebook files instead of marimo app"
    )
    
    args = parser.parse_args()
    
    try:
        # Initialize marimo components (matching start.py order)
        initialize_fd_limit(limit=4096)
        initialize_signals()
        
        app = create_app(use_wasm=args.wasm)
        
        print(f"Starting NyanCAD server on http://{args.host}:{args.port}")
        print(f"  - Static files served at: http://{args.host}:{args.port}/")
        print(f"  - Marimo notebook editor at: http://{args.host}:{args.port}/notebook/")
        
        # Create uvicorn server object (needed for marimo signal handler)
        server = uvicorn.Server(
            uvicorn.Config(
                app,
                port=args.port,
                host=args.host,
                reload_dirs=(
                    None if not args.reload else []
                ),
                # Marimo-specific uvicorn settings (from start.py)
                timeout_keep_alive=int(1e9),  # Large timeout for edit mode
                ws_ping_interval=1,
                ws_ping_timeout=60,
                timeout_graceful_shutdown=1,
                loop="asyncio",  # Force asyncio for edit mode
            )
        )
        
        # Set server on app state (required by marimo signal handler)
        app.state.server = server
        app.state.host = args.host
        app.state.port = args.port
        
        # Initialize asyncio last, then start server (marimo pattern)
        initialize_asyncio()
        server.run()
        
    except Exception as e:
        print(f"Error starting server: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
