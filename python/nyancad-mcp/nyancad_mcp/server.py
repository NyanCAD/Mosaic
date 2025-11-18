# SPDX-FileCopyrightText: 2024 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""NyanCAD MCP Server implementation."""

import asyncio
import json
import logging
import os
import sys
from typing import Any, Optional

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import (
    Tool,
    TextContent,
    Resource,
    GetPromptResult,
    Prompt,
    PromptMessage,
)

from nyancad.netlist import netlist
from .couchdb import CouchDBClient

# Configure logging to stderr (NEVER stdout for MCP)
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    stream=sys.stderr
)
logger = logging.getLogger("nyancad-mcp")

# Global CouchDB client
db_client: Optional[CouchDBClient] = None

# Default database from config
from .config import get_config


def get_db_client() -> CouchDBClient:
    """Get or create the global CouchDB client."""
    global db_client
    if db_client is None:
        db_client = CouchDBClient()
    return db_client


async def cleanup():
    """Cleanup resources."""
    global db_client
    if db_client is not None:
        await db_client.close()
        db_client = None


async def serve(session_cookie: Optional[str] = None) -> Server:
    """Create and configure the MCP server.

    Args:
        session_cookie: Optional session cookie for HTTP mode (inherited from NyanCAD server)
    """
    # Initialize DB client with session cookie if provided
    global db_client
    if db_client is None:
        db_client = CouchDBClient(session_cookie=session_cookie)

    server = Server("nyancad-mcp")

    # ===== TOOLS =====

    @server.list_tools()
    async def list_tools() -> list[Tool]:
        """List available tools."""
        config = get_config()
        default_db = config.get_default_db()

        return [
            Tool(
                name="query_circuit",
                description=(
                    "Ask natural language questions about a circuit schematic. "
                    "Examples: 'What devices are in this circuit?', 'What is the topology?', "
                    "'Which nets are connected to device X?', 'Show me the signal path from input to output'."
                ),
                inputSchema={
                    "type": "object",
                    "properties": {
                        "schematic_name": {
                            "type": "string",
                            "description": "Name of the schematic to query (e.g., 'top$top')",
                        },
                        "question": {
                            "type": "string",
                            "description": "Natural language question about the circuit",
                        },
                        "db": {
                            "type": "string",
                            "description": f"Database name (default: {default_db})",
                        },
                    },
                    "required": ["schematic_name", "question"],
                },
            ),
            Tool(
                name="search_components",
                description=(
                    "Search for circuit components/models in the library. "
                    "Use natural queries like 'nmos transistor', 'opamp low noise', 'resistor high precision'. "
                    "Returns matching models with their IDs, types, and key parameters."
                ),
                inputSchema={
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "description": "Search query (e.g., 'nmos 180nm', 'opamp')",
                        },
                        "category": {
                            "type": "string",
                            "description": "Optional category filter (e.g., 'transistor', 'passive', 'analog')",
                        },
                        "limit": {
                            "type": "integer",
                            "description": "Maximum results to return (default: 20)",
                            "default": 20,
                        },
                        "db": {
                            "type": "string",
                            "description": f"Database name (default: {default_db})",
                        },
                    },
                    "required": ["query"],
                },
            ),
            Tool(
                name="get_component_details",
                description=(
                    "Get complete details about a specific component/model including ports, "
                    "parameters, SPICE templates, and usage information. "
                    "Use this after search_components to get full information."
                ),
                inputSchema={
                    "type": "object",
                    "properties": {
                        "model_id": {
                            "type": "string",
                            "description": "Model ID from search results (e.g., 'nmos_standard')",
                        },
                        "db": {
                            "type": "string",
                            "description": f"Database name (default: {default_db})",
                        },
                    },
                    "required": ["model_id"],
                },
            ),
        ]

    @server.call_tool()
    async def call_tool(name: str, arguments: Any) -> list[TextContent]:
        """Handle tool calls."""
        client = get_db_client()
        config = get_config()
        db = arguments.get("db", config.get_default_db())

        try:
            if name == "query_circuit":
                schem_name = arguments["schematic_name"]
                question = arguments["question"]

                # Fetch schematic and netlist
                docs = await client.get_schematic_docs(db, schem_name)
                if not docs:
                    return [TextContent(
                        type="text",
                        text=f"No schematic found with name: {schem_name}"
                    )]

                models = await client.get_models_by_prefix(db, "models:")
                nl = netlist(docs, models)

                # Build context for answering the question
                devices = []
                for doc_id, doc in docs.items():
                    device_type = doc.get("type", "unknown")
                    if device_type not in ["wire", "port", "text"]:
                        devices.append({
                            "id": doc_id,
                            "type": device_type,
                            "name": doc.get("name", ""),
                            "model": doc.get("model", ""),
                        })

                context = {
                    "question": question,
                    "schematic": schem_name,
                    "devices": devices,
                    "netlist": nl,
                    "device_count": len(devices),
                }

                return [TextContent(
                    type="text",
                    text=json.dumps(context, indent=2)
                )]

            elif name == "search_components":
                query = arguments["query"]
                limit = arguments.get("limit", 20)
                category = arguments.get("category")

                models = await client.search_models(db, query, limit)

                if not models:
                    return [TextContent(
                        type="text",
                        text=f"No components found matching: {query}"
                    )]

                # Simplify output - show only useful info
                results = []
                for model in models:
                    model_category = model.get("category", [])
                    # Filter by category if specified
                    if category and category.lower() not in [c.lower() for c in model_category]:
                        continue

                    results.append({
                        "id": model.get("_id", "").replace("models:", ""),
                        "name": model.get("name", ""),
                        "type": model.get("type", ""),
                        "category": model_category,
                        "ports": model.get("ports", {}),
                    })

                return [TextContent(
                    type="text",
                    text=json.dumps({
                        "query": query,
                        "results": results[:limit],
                        "count": len(results)
                    }, indent=2)
                )]

            elif name == "get_component_details":
                model_id = arguments["model_id"]

                # Add prefix if not present
                if not model_id.startswith("models:"):
                    model_id = f"models:{model_id}"

                model = await client.get_document(db, model_id)

                if not model:
                    return [TextContent(
                        type="text",
                        text=f"No component found with ID: {model_id}"
                    )]

                return [TextContent(
                    type="text",
                    text=json.dumps(model, indent=2)
                )]

            else:
                return [TextContent(
                    type="text",
                    text=f"Unknown tool: {name}"
                )]

        except Exception as e:
            logger.error(f"Error in tool {name}: {e}", exc_info=True)
            return [TextContent(
                type="text",
                text=f"Error: {str(e)}"
            )]

    # ===== RESOURCES =====

    @server.list_resources()
    async def list_resources() -> list[Resource]:
        """List available resource templates."""
        return [
            Resource(
                uri="schematic://{name}",
                name="Schematic",
                description="Get schematic documents by name",
                mimeType="application/json",
            ),
            Resource(
                uri="model://{model_id}",
                name="Model",
                description="Get model definition by ID",
                mimeType="application/json",
            ),
            Resource(
                uri="netlist://{name}",
                name="Netlist",
                description="Generate netlist for schematic",
                mimeType="application/json",
            ),
        ]

    @server.read_resource()
    async def read_resource(uri: str) -> str:
        """Read a resource by URI."""
        client = get_db_client()

        try:
            if uri.startswith("schematic://"):
                schem_name = uri[12:]  # Remove "schematic://"
                docs = await client.get_schematic_docs(DEFAULT_DB, schem_name)
                return json.dumps(docs, indent=2)

            elif uri.startswith("model://"):
                model_id = uri[8:]  # Remove "model://"
                if not model_id.startswith("models:"):
                    model_id = f"models:{model_id}"
                model = await client.get_document(DEFAULT_DB, model_id)
                return json.dumps(model, indent=2)

            elif uri.startswith("netlist://"):
                schem_name = uri[10:]  # Remove "netlist://"
                docs = await client.get_schematic_docs(DEFAULT_DB, schem_name)
                models = await client.get_models_by_prefix(DEFAULT_DB, "models:")
                nl = netlist(docs, models)
                return json.dumps(nl, indent=2)

            else:
                raise ValueError(f"Unknown resource URI scheme: {uri}")

        except Exception as e:
            logger.error(f"Error reading resource {uri}: {e}", exc_info=True)
            raise

    # ===== PROMPTS =====

    @server.list_prompts()
    async def list_prompts() -> list[Prompt]:
        """List available prompts."""
        return [
            Prompt(
                name="analyze_circuit",
                description="Analyze a circuit schematic and provide insights",
                arguments=[
                    {
                        "name": "schematic_name",
                        "description": "Name of the schematic to analyze",
                        "required": True,
                    }
                ],
            ),
        ]

    @server.get_prompt()
    async def get_prompt(name: str, arguments: dict[str, str] | None) -> GetPromptResult:
        """Get a prompt by name."""
        if name == "analyze_circuit":
            if not arguments or "schematic_name" not in arguments:
                raise ValueError("schematic_name argument is required")

            schem_name = arguments["schematic_name"]

            # Fetch the schematic
            client = get_db_client()
            docs = await client.get_schematic_docs(DEFAULT_DB, schem_name)

            if not docs:
                raise ValueError(f"Schematic not found: {schem_name}")

            # Generate netlist
            models = await client.get_models_by_prefix(DEFAULT_DB, "models:")
            nl = netlist(docs, models)

            return GetPromptResult(
                description=f"Analysis of circuit schematic: {schem_name}",
                messages=[
                    PromptMessage(
                        role="user",
                        content=TextContent(
                            type="text",
                            text=f"""Please analyze this circuit schematic and provide insights:

Schematic: {schem_name}

Netlist:
{json.dumps(nl, indent=2)}

Schematic Documents:
{json.dumps(docs, indent=2)}

Please provide:
1. Overview of the circuit topology
2. List of all devices and their connections
3. Any potential issues or recommendations
"""
                        )
                    )
                ]
            )

        raise ValueError(f"Unknown prompt: {name}")

    return server


async def main_async():
    """Async main entry point."""
    logger.info("Starting NyanCAD MCP server...")

    try:
        async with stdio_server() as (read_stream, write_stream):
            server = await serve()
            await server.run(
                read_stream,
                write_stream,
                server.create_initialization_options()
            )
    finally:
        await cleanup()


def main():
    """Main entry point for stdio transport."""
    try:
        asyncio.run(main_async())
    except KeyboardInterrupt:
        logger.info("Server stopped by user")
    except Exception as e:
        logger.error(f"Server error: {e}", exc_info=True)
        sys.exit(1)


if __name__ == "__main__":
    main()
