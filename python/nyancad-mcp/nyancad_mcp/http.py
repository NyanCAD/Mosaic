# SPDX-FileCopyrightText: 2024 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Streamable HTTP transport for NyanCAD MCP server (MCP spec 2025-03-26+)."""

import asyncio
import json
import logging
from typing import Optional
from contextlib import asynccontextmanager

from mcp.server import Server
from mcp.server.session import ServerSession
from starlette.requests import Request
from starlette.responses import StreamingResponse, Response, JSONResponse
from starlette.applications import Starlette
from starlette.routing import Route

from .server import serve, cleanup

logger = logging.getLogger("nyancad-mcp.http")

# Global server instance
_server: Optional[Server] = None
_sessions: dict[str, ServerSession] = {}


async def get_server(request: Request) -> Server:
    """Get or create the global MCP server instance with session context."""
    global _server
    if _server is None:
        # Extract session info from request if available (from NyanCAD server)
        session_cookie = request.cookies.get("AuthSession")
        _server = await serve(session_cookie=session_cookie)
    return _server


async def mcp_endpoint(request: Request) -> Response:
    """
    Single MCP endpoint supporting both POST and GET (Streamable HTTP).

    - POST: Send JSON-RPC messages, optionally stream responses
    - GET: Server-initiated messages (if needed)
    """
    server = await get_server(request)
    session_id = request.query_params.get("sessionId", "default")

    if request.method == "POST":
        return await handle_post(request, server, session_id)
    elif request.method == "GET":
        return await handle_get(request, server, session_id)
    else:
        return JSONResponse(
            {"error": "Method not allowed"},
            status_code=405
        )


async def handle_post(request: Request, server: Server, session_id: str) -> Response:
    """Handle POST requests with JSON-RPC messages."""
    try:
        # Get or create session
        if session_id not in _sessions:
            # Create a queue for responses
            response_queue: asyncio.Queue = asyncio.Queue()

            async def send_message(message):
                await response_queue.put(message)

            session = ServerSession(
                server.request_handlers,
                send_message
            )
            _sessions[session_id] = session
            logger.info(f"Created new MCP session: {session_id}")

        session = _sessions[session_id]

        # Parse JSON-RPC message
        body = await request.json()

        # Handle the message
        await session.handle_message(body)

        # Check if client wants streaming response
        accept = request.headers.get("accept", "")
        if "text/event-stream" in accept:
            # Stream responses using SSE
            async def event_stream():
                try:
                    while True:
                        try:
                            message = await asyncio.wait_for(
                                _sessions[session_id]._write_stream.get() if hasattr(_sessions[session_id], '_write_stream') else asyncio.sleep(0.1),
                                timeout=30.0
                            )
                            if message:
                                data = json.dumps(message.model_dump(mode="json", by_alias=True))
                                yield f"data: {data}\n\n"
                        except asyncio.TimeoutError:
                            yield ": keepalive\n\n"
                except asyncio.CancelledError:
                    logger.info(f"Stream cancelled for session: {session_id}")
                    raise

            return StreamingResponse(
                event_stream(),
                media_type="text/event-stream",
                headers={
                    "Cache-Control": "no-cache",
                    "Connection": "keep-alive",
                    "X-Accel-Buffering": "no",
                }
            )
        else:
            # Simple response (non-streaming)
            return Response(status_code=202)  # Accepted

    except Exception as e:
        logger.error(f"Error handling POST: {e}", exc_info=True)
        return JSONResponse(
            {"error": str(e)},
            status_code=500
        )


async def handle_get(request: Request, server: Server, session_id: str) -> Response:
    """Handle GET requests (for server-initiated messages if needed)."""
    try:
        # For now, GET is not used, but spec allows it
        return JSONResponse(
            {"message": "GET endpoint available for future use"},
            status_code=200
        )
    except Exception as e:
        logger.error(f"Error handling GET: {e}", exc_info=True)
        return JSONResponse(
            {"error": str(e)},
            status_code=500
        )


def create_http_app() -> Starlette:
    """Create a Starlette app with MCP Streamable HTTP endpoint."""

    @asynccontextmanager
    async def lifespan(app):
        """Lifespan context manager for cleanup."""
        yield
        # Cleanup on shutdown
        await cleanup()
        _sessions.clear()

    app = Starlette(
        routes=[
            Route("/", mcp_endpoint, methods=["POST", "GET"]),
        ],
        lifespan=lifespan
    )

    return app
