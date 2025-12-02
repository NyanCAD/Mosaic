"""MCP (Model Context Protocol) server for NyanCAD.

This module provides an MCP server that exposes tools and resources
for interacting with NyanCAD projects, schematics, and simulations.
"""

import time
import uuid

import httpx
import jwt
from mcp.server.fastmcp import FastMCP, Context
from mcp.server.auth.settings import AuthSettings
from pydantic import AnyHttpUrl

from .auth import JWTTokenVerifier
from .config import SERVER_URL, COUCHDB_URL, JWT_SECRET

# Initialize JWT token verifier
token_verifier = JWTTokenVerifier()

# Initialize FastMCP server with authentication
mcp = FastMCP(
    "nyancad-mcp",
    stateless_http=True,
    json_response=True,
    token_verifier=token_verifier,
    auth=AuthSettings(
        issuer_url=AnyHttpUrl(f"{SERVER_URL}/oauth"),
        resource_server_url=AnyHttpUrl(f"{SERVER_URL}/ai"),
        required_scopes=["user"],
    ),
)

@mcp.tool()
async def hello(ctx: Context) -> dict:
    """Test CouchDB authentication with the JWT token from the current MCP request.

    This tool extracts the JWT token from the authenticated MCP request and uses it
    to authenticate to CouchDB, proving that the OAuth integration works end-to-end.

    Returns:
        Dictionary with greeting and CouchDB session information.
    """
    try:
        # Access the authorization header from the Starlette Request object
        authorization = None

        if hasattr(ctx, 'request_context') and hasattr(ctx.request_context, 'request'):
            request = ctx.request_context.request
            authorization = request.headers.get('authorization') or request.headers.get('Authorization')

        if not authorization:
            return {
                "error": "No authorization header found in request",
                "hint": "The MCP request should include an Authorization header",
                "available_headers": list(ctx.request_context.request.headers.keys()) if hasattr(ctx, 'request_context') and hasattr(ctx.request_context, 'request') else [],
            }

        # Extract the token from "Bearer <token>"
        if authorization.startswith("Bearer "):
            token = authorization[7:]
        else:
            return {"error": "Invalid authorization header format", "header": authorization}

        # Decode the token to get user info
        if JWT_SECRET:
            payload = jwt.decode(token, JWT_SECRET, algorithms=["HS256"], audience=COUCHDB_URL)
            username = payload.get("sub")
            roles = payload.get("_couchdb.roles", [])
        else:
            username = "unknown"
            roles = []

        # Use the JWT token to authenticate to CouchDB
        async with httpx.AsyncClient() as client:
            # Query CouchDB session endpoint with the actual JWT token from OAuth
            response = await client.get(
                f"{COUCHDB_URL}/_session",
                headers={"Authorization": f"Bearer {token}"}
            )

            if response.status_code == 200:
                session_data = response.json()
                return {
                    "greeting": f"Hello, {username}!",
                    "couchdb_auth": "SUCCESS",
                    "session": session_data,
                    "authenticated_as": session_data.get("userCtx", {}).get("name"),
                    "roles": session_data.get("userCtx", {}).get("roles", []),
                    "token_claims": {"username": username, "roles": roles},
                    "token_verified": True,
                }
            else:
                return {
                    "greeting": f"Hello, {username}!",
                    "couchdb_auth": "FAILED",
                    "status_code": response.status_code,
                    "error": response.text,
                }
    except Exception as e:
        import traceback
        return {
            "error": "Exception occurred",
            "details": str(e),
            "type": type(e).__name__,
            "traceback": traceback.format_exc(),
        }