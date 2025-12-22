---
layout: default
title: MCP Server
---

# MCP Server

NyanCAD provides a [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that allows LLM clients to interact with your schematics, component library, and simulations. This enables AI assistants to help you design circuits, analyze simulation results, and manage your component library.

## Connecting to the MCP Server

### NyanCAD Cloud

For the hosted NyanCAD service, use the following MCP server URL:

```
https://nyancad.com/ai/mcp
```

### Self-Hosted Deployments

For your own nyancad-server deployment, the MCP server is available at:

```
https://your-server-url/ai/mcp
```

Replace `your-server-url` with your deployment's base URL.

## Adding to LLM Clients

### Claude Desktop

Add the following to your Claude Desktop configuration file (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "nyancad": {
      "url": "https://nyancad.com/ai/mcp"
    }
  }
}
```

The configuration file location depends on your operating system:

- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
- **Linux**: `~/.config/Claude/claude_desktop_config.json`

### Claude Code

Add the MCP server using the Claude Code CLI:

```bash
claude mcp add nyancad --transport http https://nyancad.com/ai/mcp
```

### Other MCP Clients

Any MCP-compatible client can connect using the HTTP transport with the server URL. The server uses OAuth authentication - you will be prompted to log in with your NyanCAD account when first connecting.

## Authentication

The MCP server uses OAuth 2.0 authentication. When you connect from an LLM client:

1. The client will open a browser window to authenticate
2. Log in with your NyanCAD account credentials
3. Authorize the MCP client to access your account
4. The client will receive a token to make authenticated requests

Your schematics and component library are private to your account. The MCP server accesses your personal database using the authenticated session.

## Available Tools

The MCP server exposes the following tools:

### get_schematic

Get a schematic with computed port locations and SPICE netlist.

**Parameters:**

- `id` (optional): Schematic ID in UUID format
- `name` (optional): Schematic name to search for

Provide either `id` or `name`. If `name` is provided, the tool searches your library and uses the first matching schematic.

**Returns:**

- `schematic`: Dictionary of devices (Wires and Components) with computed port locations
- `spice`: Generated SPICE netlist including all subcircuits and models

### list_library

List available component models with optional filtering.

**Parameters:**

- `filter` (optional): Filter by model name (substring match)
- `category` (optional): Filter by category path (e.g., `["passives", "resistors"]`)
- `include_templates` (optional): Include full template code in results (default: false)

**Returns:** Dictionary mapping model IDs to ModelMetadata objects. Use the dictionary keys (e.g., `models:1ef9c9d7-...`) as identifiers in other tools.

### bulk_update_schematic

Bulk update schematic documents (create, update, or delete devices).

**Parameters:**

- `schematic_id`: The schematic ID to update
- `docs`: List of Wire or Component documents

**Document requirements:**

- **New documents**: Omit the `_rev` field
- **Updates**: Include the current `_rev` from the database
- **Deletions**: Include `_id`, `_rev`, and `_deleted: true`

**Conflict resolution:** If you receive a conflict error, fetch the latest document with `get_schematic()` to get the current `_rev`, then retry.

### update_model

Update a single model in the library (create, update, or delete).

**Parameters:**

- `model`: ModelMetadata document with required fields

**Operations:**

- **Create**: Provide model with `_id` (no `_rev`)
- **Update**: Provide model with `_id` and current `_rev`
- **Delete**: Provide model with `_id`, current `_rev`, and `_deleted: true`

### get_simulation_result

Get the latest simulation result for a schematic.

**Parameters:**

- `id`: Schematic ID

**Returns:** Dictionary containing the simulation result data, or empty dict if none found.

### hello

Test authentication by verifying the JWT token with CouchDB. Useful for debugging connection issues.

## Example Usage

Here are some example prompts you can use with an LLM client connected to the NyanCAD MCP server:

- "List all schematics in my library"
- "Get the schematic named 'inverter' and show me the SPICE netlist"
- "What components are available in the passives category?"
- "Show me the simulation results for my amplifier circuit"
- "Add a 10k resistor to my test schematic"

## Data Model

### Devices

Schematics contain two types of devices:

- **Wire**: Connections between components with a path of coordinates
- **Component**: Circuit elements with position, rotation, model reference, and parameters

Both device types include computed `ports` field showing connection points as `(x,y): port_name` mappings.

### Models

Component models define the symbol, SPICE template, and parameters for circuit elements. Models are referenced by ID (e.g., `models:1ef9c9d7-...`) and can be browsed using `list_library`.

## Troubleshooting

### Authentication Fails

Ensure you have a valid NyanCAD account and can log in to the web interface. The MCP server uses the same authentication system.

### Connection Refused

For self-hosted deployments, verify that:

- The nyancad-server is running and accessible
- The `/ai/mcp` endpoint is properly configured
- Any firewalls allow traffic to the server

### Conflict Errors

When updating documents, always fetch the latest version first to get the current `_rev`. CouchDB uses optimistic concurrency control and will reject updates with stale revision numbers.
