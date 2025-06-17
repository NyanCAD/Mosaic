"""Main ASGI server for NyanCAD with marimo integration."""

import argparse
import sys
from importlib import resources
from typing import Optional

import uvicorn
from starlette.applications import Starlette
from starlette.staticfiles import StaticFiles

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


def create_app() -> Starlette:
    """Create the Starlette application with static files and marimo edit integration."""
    
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
    
    # Create main Starlette app with signal handler for proper session cleanup
    app = Starlette(
        lifespan=Lifespans([
            lifespans.signal_handler,
        ])
    )
    
    # Set session manager on main app so signal handler can clean it up
    app.state.session_manager = session_manager
    
    # Mount marimo at /notebook
    app.mount("/notebook", marimo_app)
    
    # Get the static files directory via symlinked resources
    with resources.path("nyancad_server", "public") as public_path:
        static_path = str(public_path)
    
    # Mount static files at root
    app.mount("/", StaticFiles(directory=static_path, html=True), name="static")
    
    return app


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
    
    args = parser.parse_args()
    
    try:
        # Initialize marimo components (matching start.py order)
        initialize_fd_limit(limit=4096)
        initialize_signals()
        
        app = create_app()
        
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
        
        # Initialize asyncio last, then start server (marimo pattern)
        initialize_asyncio()
        server.run()
        
    except Exception as e:
        print(f"Error starting server: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
