# NyanCAD MCP Server

Model Context Protocol (MCP) server for NyanCAD circuit operations. Provides intent-based tools for working with circuit schematics and component libraries stored in CouchDB.

Built following MCP spec 2025-03-26+ with Streamable HTTP transport and OAuth 2.1 authentication patterns.

## Features

### Intent-Based Tools

**Simple tools (implemented):**
- **query_circuit** - Ask natural language questions about circuits ("What devices are in this circuit?", "Show me the signal path")
- **search_components** - Search component library with natural queries ("nmos 180nm", "opamp low noise")
- **get_component_details** - Get complete model information including ports, parameters, SPICE templates

**Complex tools (planned):**
- **add_device** - Add component with connections in one call
- **modify_device** - Update device properties or connections
- **remove_device** - Remove component with auto-reconnect
- **validate_circuit** - Check for common circuit issues
- **simulate_circuit** - Run SPICE analysis

### Resources

- `circuit://{name}` - Full schematic with hierarchy
- `circuit://{name}/device/{id}` - Individual device details
- `circuit://{name}/netlist` - Generated netlist (read-only)
- `model://{model_id}` - Model definition
- `library://category/{path}` - Browse model library

### Prompts

- **analyze_circuit** - Comprehensive circuit analysis with insights

## Installation

```bash
cd python/nyancad-mcp
pip install -e .
```

## Usage

### Stdio Transport (for Claude Code, CLI)

```bash
nyancad-mcp
```

**Configuration:**

Config file: `~/.config/nyancad/credentials.json`
```json
{
  "couchdb": {
    "url": "https://api.nyancad.com/",
    "username": "your-username",
    "password": "your-password"
  },
  "default_db": "offline"
}
```

Fallback to environment variables:
- `COUCHDB_URL`
- `COUCHDB_USER` or `COUCHDB_ADMIN_USER`
- `COUCHDB_PASSWORD` or `COUCHDB_ADMIN_PASS`
- `NYANCAD_DB`

### Streamable HTTP Transport (NyanCAD Server)

Integrated into NyanCAD server at `/mcp` endpoint using Streamable HTTP (MCP spec 2025-03-26+).

Authentication: Inherits CouchDB session from parent NyanCAD server (no separate auth needed).

## Claude Code Integration

Add to `.claude/mcp.json`:

```json
{
  "mcpServers": {
    "nyancad": {
      "command": "nyancad-mcp",
      "description": "NyanCAD circuit operations - netlist generation, schematic queries, model search"
    }
  }
}
```

## Development

```bash
# Install with dev dependencies
pip install -e ".[dev]"

# Run tests
pytest
```

## Architecture

The MCP server:
1. Connects to CouchDB using existing patterns from nyancad-server
2. Leverages the nyancad.netlist module for netlist generation
3. Provides stdio transport for local clients (Claude Code)
4. Provides SSE transport for HTTP streaming (integrated into NyanCAD server)

## License

MPL-2.0
