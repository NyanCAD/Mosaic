"""MCP (Model Context Protocol) server for NyanCAD.

This module provides an MCP server that exposes tools and resources
for interacting with NyanCAD projects, schematics, and simulations.
"""

import httpx
from mcp.server.fastmcp import FastMCP, Context
from mcp.server.auth.settings import AuthSettings
from pydantic import AnyHttpUrl

from .config import SERVER_URL, COUCHDB_URL
from .oauth import JWTTokenVerifier

# Initialize JWT token verifier
token_verifier = JWTTokenVerifier()

# Initialize FastMCP server with authentication (standard OAuth at root level)
# Using stateful mode to avoid ClosedResourceError bug in stateless mode
# See: https://github.com/modelcontextprotocol/python-sdk/issues/1219
mcp = FastMCP(
    "nyancad-mcp",
    stateless_http=False,
    json_response=True,
    token_verifier=token_verifier,
    auth=AuthSettings(
        issuer_url=AnyHttpUrl(SERVER_URL),
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
    # Extract authorization header from request
    request = ctx.request_context.request
    authorization = request.headers.get('authorization')

    # Forward to CouchDB session endpoint
    async with httpx.AsyncClient() as client:
        response = await client.get(
            f"{COUCHDB_URL}/_session",
            headers={"Authorization": authorization}
        )
        return response.json()