"""MCP (Model Context Protocol) server for NyanCAD.

This module provides an MCP server that exposes tools and resources
for interacting with NyanCAD projects, schematics, and simulations.
"""

from typing import Annotated, Any, Dict, List, Optional

import httpx
import jwt
from mcp.server.fastmcp import FastMCP, Context
from mcp.server.auth.settings import AuthSettings
from pydantic import AnyHttpUrl, BaseModel, Field, TypeAdapter

from nyancad.api import ServerAPI
from nyancad.netlist import getports, NyanCircuit
from nyancad.schemas import Device, Wire, Component, ModelMetadata

from .config import SERVER_URL, COUCHDB_URL, JWT_SECRET
from .oauth import JWTTokenVerifier

# Create TypeAdapter for discriminated union validation
DeviceAdapter = TypeAdapter(Device)


class SchematicResponse(BaseModel):
    """Response from get_schematic tool.

    Contains validated devices with computed port locations and a generated SPICE netlist.
    """
    schematic: Dict[str, Device] = Field(
        ...,
        description="Devices keyed by full document ID (format: 'schematic_id:device_name')"
    )
    spice: str = Field(..., description="Generated SPICE netlist including all subcircuits")



def str_to_hex(s: str) -> str:
    """Convert string to hex encoding (character codes)."""
    return ''.join(format(ord(c), 'x') for c in s)


def normalize_to_bare_id(id_str: str) -> str:
    """Normalize ID to bare format (strip 'models:' prefix if present).

    Accepts both formats:
    - "1ef9c9d7-..." → "1ef9c9d7-..."
    - "models:1ef9c9d7-..." → "1ef9c9d7-..."
    """
    if id_str.startswith("models:"):
        return id_str[7:]  # Strip "models:" prefix
    return id_str


def normalize_to_model_key(id_str: str) -> str:
    """Normalize ID to model key format (add 'models:' prefix if missing).

    Accepts both formats:
    - "1ef9c9d7-..." → "models:1ef9c9d7-..."
    - "models:1ef9c9d7-..." → "models:1ef9c9d7-..."
    """
    if id_str.startswith("models:"):
        return id_str
    return f"models:{id_str}"


# Initialize JWT token verifier
token_verifier = JWTTokenVerifier()

# Initialize FastMCP server with authentication (standard OAuth at root level)
# Using stateless mode for multi-worker support (ClosedResourceError fixed in PR #1384)
mcp = FastMCP(
    "nyancad-mcp",
    host="0.0.0.0",  # Accept connections from any host
    stateless_http=True,
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


@mcp.tool()
async def get_schematic(
    ctx: Context,
    id: Annotated[Optional[str], "Schematic ID (UUID format)"] = None,
    name: Annotated[Optional[str], "Schematic name to search for"] = None
) -> SchematicResponse:
    """Get schematic with computed port locations and SPICE netlist.

    Retrieves all devices in the schematic, computes port locations from geometry,
    and generates a complete SPICE netlist including all subcircuits and models.

    **Parameters (provide one):**
    - id: Direct schematic ID (UUID format, e.g., "1ef9c9d7-...")
    - name: Schematic name to search for (performs lookup to find ID)

    If both are provided, id takes precedence. If only name is provided,
    searches the library and uses the first matching schematic.

    Returns:
        SchematicResponse with:
        - schematic: Dict of document ID → Device (Wire or Component with computed ports)
        - spice: Generated SPICE netlist as string

    Raises:
        ValueError: If neither id nor name is provided, or if name search finds no match
    """
    api = get_api_from_context(ctx)
    try:
        # Resolve schematic_id from id or name
        if id:
            schematic_id = normalize_to_bare_id(id)
        elif name:
            # Search for schematic by name
            models = await api.get_library(filter=name)

            # Find first schematic (no templates) with exact name match
            first_partial = None
            for model_id, model in models.items():
                # Schematics have no templates field
                if not model.get('templates'):
                    model_name = model.get('name', '')
                    if model_name.lower() == name.lower():
                        # Exact match - use immediately
                        schematic_id = normalize_to_bare_id(model_id)
                        break
                    elif first_partial is None:
                        first_partial = model_id
            else:
                # No exact match found, use first partial match if available
                if first_partial:
                    schematic_id = normalize_to_bare_id(first_partial)
                else:
                    raise ValueError(
                        f"No schematic found with name '{name}'. "
                        "Use list_library() to see available schematics."
                    )
        else:
            raise ValueError("Either 'id' or 'name' must be provided")

        # Get full schematic with all subcircuits and models
        _, full_schem = await api.get_all_schem_docs(schematic_id)

        # Extract top-level devices for this schematic
        devices = full_schem.get(schematic_id, {})
        models = full_schem.get("models", {})

        # Process each device to add ports field
        processed_devices = {}
        for doc_id, device_dict in devices.items():
            # Compute port locations
            ports = getports(device_dict, models)

            # Add ports field
            device_dict["ports"] = {
                f"({x},{y})": port_name
                for (x, y), port_name in ports.items()
            }

            # Parse device dict using discriminated union (automatically routes to Wire or Component)
            device = DeviceAdapter.validate_python(device_dict)
            processed_devices[doc_id] = device

        # Generate SPICE netlist
        circuit = NyanCircuit(schematic_id, full_schem)
        spice_netlist = str(circuit)

        return SchematicResponse(schematic=processed_devices, spice=spice_netlist)
    finally:
        await api.close()


@mcp.tool()
async def list_library(
    ctx: Context,
    filter: Annotated[Optional[str], "Filter by model name (substring match)"] = None,
    category: Annotated[Optional[list[str]], "Filter by category path (e.g., ['passives', 'resistors'])"] = None,
    include_templates: Annotated[bool, "Include full template code in results"] = False
) -> dict[str, ModelMetadata]:
    """List available component models with optional filtering.

    Use this to browse the component library or search for specific models.
    By default, returns lightweight metadata without template code.

    Returns:
        Dictionary mapping model IDs to ModelMetadata objects.
        Dict keys are in database format (e.g., "models:1ef9c9d7-...").
        Use dict keys as schematic_id in other tools.
        The 'name' field is for display only, not an identifier.
    """
    api = get_api_from_context(ctx)
    try:
        models = await api.get_library(filter, category)
        result = {}
        for model_id, model in models.items():
            # Compute has_templates (not stored in DB)
            model['has_templates'] = bool(model.get('templates'))

            # Conditionally exclude templates
            if not include_templates:
                model.pop('templates', None)

            metadata = ModelMetadata.model_validate(model)
            result[model_id] = metadata
        return result
    finally:
        await api.close()






@mcp.tool()
async def bulk_update_schematic(
    ctx: Context,
    schematic_id: Annotated[str, "Schematic ID"],
    docs: Annotated[
        list[Device],
        "List of complete Wire or Component documents. See Device schema for required fields."
    ]
) -> list[dict[str, Any]]:
    """Bulk update schematic documents with complete device data.

    **Document Requirements:**
    - Each document must include all required fields (see Wire and Component schemas)
    - **New documents**: Omit `_rev` field
    - **Updates**: Include current `_rev` from database to prevent conflicts
    - **Deletions**: Include `_id`, `_rev`, and `_deleted: true`

    **Conflict Resolution:**
    If you receive a conflict error, the `_rev` is stale. Fetch the latest document
    with get_schematic() to obtain the current `_rev`, then retry your update.

    Returns:
        List of results, one per document. Success: {'id', 'rev'}.
        Failure: {'id', 'error', 'reason'}.

    Raises:
        ValidationError: If any document is incomplete or has invalid types
    """
    api = get_api_from_context(ctx)
    try:
        # Normalize schematic_id to bare ID
        schematic_id = normalize_to_bare_id(schematic_id)

        docs_to_write = []

        for device in docs:  # Pydantic already validated as Wire or Component
            # Convert to dict for CouchDB
            doc = device.model_dump(by_alias=True, exclude_none=True)

            # Normalize model field to bare ID if present
            if "model" in doc and doc["model"]:
                doc["model"] = normalize_to_bare_id(doc["model"])

            # Strip computed ports field
            doc.pop("ports", None)

            docs_to_write.append(doc)

        # Submit to CouchDB
        result = await api.bulk_update(docs_to_write)

        return result

    finally:
        await api.close()

@mcp.tool()
async def update_model(
    ctx: Context,
    model: Annotated[ModelMetadata, "Model document to create, update, or delete"]
) -> dict[str, Any]:
    """Update a single model in the library (create, update, or delete).

    **Unified CRUD Operation:**
    - **Create new model**: Provide model with `_id` (no `_rev`)
    - **Update existing model**: Provide model with `_id` and current `_rev`
    - **Delete model**: Provide model with `_id`, current `_rev`, and `_deleted: true`

    **Model Document Requirements:**
    - Must include `_id` field (use `models:` prefix for new models)
    - For updates/deletes: Must include current `_rev` from database
    - Follows ModelMetadata schema for validation

    **Conflict Resolution:**
    If you receive a conflict error, the `_rev` is stale. Fetch the latest model
    with list_library() to obtain the current `_rev`, then retry your update.

    Returns:
        CouchDB update response with result array

    Raises:
        ValidationError: If model document is incomplete or has invalid types
    """
    api = get_api_from_context(ctx)
    try:
        # Convert Pydantic model to dict
        model_dict = model.model_dump(by_alias=True, exclude_none=True)

        # Normalize model ID to ensure proper format
        if "_id" in model_dict:
            model_dict["_id"] = normalize_to_model_key(model_dict["_id"])

        # Use the new update_model API method
        result = await api.update_model(model_dict)
        return result
    finally:
        await api.close()

@mcp.tool()
async def get_simulation_result(
    ctx: Context,
    id: Annotated[str, "Schematic ID"]
) -> dict[str, Any]:
    """Get the latest simulation result for a schematic.

    Retrieves the most recent simulation data stored in the `$result` group
    for the specified schematic. Simulation results are stored with timestamp-based
    keys and this returns the latest one.

    Args:
        id: Schematic ID

    Returns:
        Dictionary containing the simulation result data, or empty dict if none found

    Raises:
        HTTPStatusError: If the request fails
    """
    api = get_api_from_context(ctx)
    try:
        # Use the new get_simulation_results API method
        result = await api.get_simulation_results(normalize_to_bare_id(id))
        return result
    finally:
        await api.close()
