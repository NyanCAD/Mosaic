"""MCP (Model Context Protocol) server for NyanCAD.

This module provides an MCP server that exposes tools and resources
for interacting with NyanCAD projects, schematics, and simulations.
"""

from typing import Any

import httpx
import jwt
from mcp.server.fastmcp import FastMCP, Context
from mcp.server.auth.settings import AuthSettings
from pydantic import AnyHttpUrl

from nyancad.api import ServerAPI
from nyancad.netlist import getports, NyanCircuit

from .config import SERVER_URL, COUCHDB_URL, JWT_SECRET
from .oauth import JWTTokenVerifier


def str_to_hex(s: str) -> str:
    """Convert string to hex encoding (character codes)."""
    return ''.join(format(ord(c), 'x') for c in s)


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

def get_api_from_context(ctx: Context) -> ServerAPI:
    """Extract authentication from context and create ServerAPI instance.

    Args:
        ctx: MCP context with request information

    Returns:
        ServerAPI instance configured for the authenticated user
    """
    request = ctx.request_context.request

    # Extract JWT token
    auth_header = request.headers.get('authorization', '')
    token = None
    if auth_header and ' ' in auth_header:
        token = auth_header.split(' ')[1]

    # Extract username from JWT claims
    username = None
    if token:
        try:
            # Decode without verification (already verified by FastMCP)
            claims = jwt.decode(token, options={"verify_signature": False})
            username = claims.get('sub')  # 'sub' claim contains username
        except Exception:
            pass  # Token decode failed

    # Build database URL for user's database
    if username:
        db_url = f"{COUCHDB_URL}/userdb-{str_to_hex(username)}"
    else:
        # Fallback to default if no username (shouldn't happen with auth)
        db_url = f"{COUCHDB_URL}/schematics"

    return ServerAPI(db_url, auth_token=token)


def augment_schematic_with_computed(name: str, schem: dict) -> dict:
    """Add _computed fields to devices and _spice to schematic.

    Args:
        name: Top-level schematic name
        schem: Full schematic dict with 'models' and device groups

    Returns:
        Augmented schematic with _computed and _spice fields
    """
    import logging
    logger = logging.getLogger(__name__)

    models = schem.get("models", {})
    result = schem.copy()

    # Augment each schematic group (skip 'models' key)
    for group_name, docs in schem.items():
        if group_name == "models":
            continue

        augmented_docs = {}
        for doc_id, doc in docs.items():
            # Compute port locations
            ports = getports(doc, models)

            # Add _computed field
            augmented_doc = doc.copy()
            augmented_doc["_computed"] = {
                "ports": {
                    f"({x},{y})": port_name
                    for (x, y), port_name in ports.items()
                }
            }
            augmented_docs[doc_id] = augmented_doc

        result[group_name] = augmented_docs

    # Generate SPICE netlist for specified top-level schematic
    try:
        circuit = NyanCircuit(name, schem)
        result["_spice"] = str(circuit)
    except Exception as e:
        logger.error(f"Failed to generate SPICE: {e}")
        result["_spice"] = f"# Error generating SPICE: {e}"

    return result


@mcp.tool()
async def hello(ctx: Context) -> dict[str, Any]:
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


@mcp.resource("schematic://{name}")
async def get_schematic(ctx: Context, name: str) -> dict[str, Any]:
    """Get schematic with computed port locations and SPICE netlist.

    This resource returns the complete schematic data structure with:
    - _computed field on each device with absolute port locations
    - _spice field at top level with SPICE netlist

    Args:
        ctx: MCP context
        name: Top-level schematic group name

    Returns:
        Augmented schematic with _computed and _spice fields
    """
    api = get_api_from_context(ctx)
    try:
        _, schem = await api.get_all_schem_docs(name)

        # Augment with computed data and SPICE
        augmented = augment_schematic_with_computed(name, schem)

        return augmented
    finally:
        await api.close()


@mcp.resource("library://list")
async def get_library_list(ctx: Context) -> dict[str, Any]:
    """List all available models with URIs to their resources.

    Returns:
        Dictionary mapping model keys to metadata with category, model, and schematic URIs
    """
    api = get_api_from_context(ctx)
    try:
        models = await api.get_library(filter=None, category=None)
        # Transform to return resource URIs
        result = {}
        for model_key, model_data in models.items():
            # Strip "models:" prefix to get name
            name = model_key.replace("models:", "", 1)

            # Build category URI from category path
            category = model_data.get("category", [])
            category_uri = f"library://category/{'/'.join(category)}" if category else None

            # Include only metadata not already in URIs
            result[model_key] = {
                "name": model_data.get("name"),
                "type": model_data.get("type"),
                "has_templates": bool(model_data.get("templates")),
                "category_uri": category_uri,
                "model_uri": f"library://model/{name}",
                "schematic_uri": f"schematic://{name}"
            }
        return result
    finally:
        await api.close()


@mcp.resource("library://category/{path}")
async def get_library_category(ctx: Context, path: str) -> dict[str, Any]:
    """List models in a hierarchical category path.

    Args:
        ctx: MCP context
        path: Hierarchical category path (e.g., "passives/resistors/fixed")

    Returns:
        Dictionary mapping model keys to metadata with category, model, and schematic URIs
    """
    api = get_api_from_context(ctx)
    try:
        # Split path into category hierarchy
        category_path = path.split('/') if path else None
        models = await api.get_library(filter=None, category=category_path)

        # Transform to return resource URIs
        result = {}
        for model_key, model_data in models.items():
            # Strip "models:" prefix to get name
            name = model_key.replace("models:", "", 1)

            # Build category URI from category path
            category = model_data.get("category", [])
            category_uri = f"library://category/{'/'.join(category)}" if category else None

            # Include only metadata not already in URIs
            result[model_key] = {
                "name": model_data.get("name"),
                "type": model_data.get("type"),
                "has_templates": bool(model_data.get("templates")),
                "category_uri": category_uri,
                "model_uri": f"library://model/{name}",
                "schematic_uri": f"schematic://{name}"
            }
        return result
    finally:
        await api.close()


@mcp.resource("library://model/{name}")
async def get_model(ctx: Context, name: str) -> dict[str, Any]:
    """Get full model definition including templates, symbols, and parameters.

    Args:
        ctx: MCP context
        name: Model name

    Returns:
        Complete model definition with all templates and metadata
    """
    api = get_api_from_context(ctx)
    try:
        # Get full model data
        models = await api.get_library(filter=None, category=None)
        model_key = f"models:{name}"

        if model_key not in models:
            raise ValueError(f"Model not found: {name}")

        model_data = models[model_key]

        # Build URIs
        category = model_data.get("category", [])
        category_uri = f"library://category/{'/'.join(category)}" if category else None

        # Return full model data without duplicating name/category
        result = {
            "type": model_data.get("type"),
            "templates": model_data.get("templates", {}),
            "ports": model_data.get("ports", []),
            "parameters": model_data.get("parameters", {}),
            "symbol": model_data.get("symbol"),
            "category_uri": category_uri,
            "schematic_uri": f"schematic://{name}"
        }

        # Remove None values
        return {k: v for k, v in result.items() if v is not None}
    finally:
        await api.close()