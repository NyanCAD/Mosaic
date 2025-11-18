# SPDX-FileCopyrightText: 2024 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""SSE (Server-Sent Events) transport for NyanCAD MCP server."""

import asyncio
import json
import logging
from typing import Optional
from contextlib import asynccontextmanager

from mcp.server import Server
from mcp.server.session import ServerSession
from mcp.shared.session import RequestResponder
from starlette.requests import Request
from starlette.responses import StreamingResponse, Response
from starlette.applications import Starlette
from starlette.routing import Route

from .server import serve, cleanup

logger = logging.getLogger("nyancad-mcp.sse")

# Global server instance
_server: Optional[Server] = None
_sessions: dict[str, ServerSession] = {}


async def get_server() -> Server:
    """Get or create the global MCP server instance."""
    global _server
    if _server is None:
        _server = await serve()
    return _server


async def sse_endpoint(request: Request) -> StreamingResponse:
    """SSE endpoint for streaming MCP messages to client."""
    session_id = request.query_params.get("session_id", "default")
    server = await get_server()

    async def event_generator():
        """Generate SSE events."""
        try:
            # Create a queue for messages from server to client
            message_queue: asyncio.Queue = asyncio.Queue()

            # Create a session if it doesn't exist
            if session_id not in _sessions:
                # Create session with message handler
                async def send_message(message):
                    await message_queue.put(message)

                session = ServerSession(
                    server.request_handlers,
                    send_message
                )
                _sessions[session_id] = session
                logger.info(f"Created new MCP session: {session_id}")

            # Send endpoint event
            yield f"event: endpoint\ndata: /mcp/messages\n\n"

            # Stream messages
            while True:
                try:
                    # Wait for messages with timeout to send keepalive
                    message = await asyncio.wait_for(message_queue.get(), timeout=30.0)
                    data = json.dumps(message.model_dump(mode="json", by_alias=True))
                    yield f"event: message\ndata: {data}\n\n"
                except asyncio.TimeoutError:
                    # Send keepalive comment
                    yield ": keepalive\n\n"

        except asyncio.CancelledError:
            logger.info(f"SSE connection closed for session: {session_id}")
            raise
        except Exception as e:
            logger.error(f"Error in SSE stream: {e}", exc_info=True)
            raise

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",  # Disable nginx buffering
        }
    )


async def messages_endpoint(request: Request) -> Response:
    """POST endpoint for receiving client messages."""
    session_id = request.query_params.get("session_id", "default")

    try:
        # Get the session
        if session_id not in _sessions:
            return Response(
                content=json.dumps({"error": "Session not found"}),
                status_code=404,
                media_type="application/json"
            )

        session = _sessions[session_id]

        # Parse the JSON-RPC message
        body = await request.json()

        # Handle the message
        await session.handle_message(body)

        return Response(status_code=202)  # Accepted

    except Exception as e:
        logger.error(f"Error handling message: {e}", exc_info=True)
        return Response(
            content=json.dumps({"error": str(e)}),
            status_code=500,
            media_type="application/json"
        )


def create_sse_app() -> Starlette:
    """Create a Starlette app with MCP SSE endpoints."""

    @asynccontextmanager
    async def lifespan(app):
        """Lifespan context manager for cleanup."""
        yield
        # Cleanup on shutdown
        await cleanup()
        _sessions.clear()

    app = Starlette(
        routes=[
            Route("/sse", sse_endpoint),
            Route("/messages", messages_endpoint, methods=["POST"]),
        ],
        lifespan=lifespan
    )

    return app
