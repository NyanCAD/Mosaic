# NyanCAD MCP Server

Model Context Protocol (MCP) server for NyanCAD circuit operations. Provides tools and resources for working with circuit schematics, netlists, and models stored in CouchDB.

## Features

### Tools

- **get_schematic** - Fetch schematic documents from CouchDB
- **generate_netlist** - Generate SPICE netlist from schematic
- **list_devices** - List all devices in a schematic
- **query_models** - Search for circuit models by name
- **get_model** - Get detailed model information

### Resources

- `schematic://{name}` - Access schematic documents
- `model://{model_id}` - Access model definitions
- `netlist://{name}` - Generate netlists on-demand

### Prompts

- **analyze_circuit** - Analyze a circuit and provide insights

## Installation

```bash
cd python/nyancad-mcp
pip install -e .
```

## Usage

### Stdio Transport (for Claude Code, etc.)

```bash
nyancad-mcp
```

### SSE Transport (HTTP streaming)

Integrated into NyanCAD server at `/mcp` endpoint.

## Configuration

Environment variables:

- `COUCHDB_URL` - CouchDB server URL (default: https://api.nyancad.com/)
- `COUCHDB_ADMIN_USER` - CouchDB admin username (default: admin)
- `COUCHDB_ADMIN_PASS` - CouchDB admin password
- `NYANCAD_DB` - Default database name (default: offline)

## Claude Code Integration

Add to your Claude Code MCP settings:

```json
{
  "mcpServers": {
    "nyancad": {
      "command": "nyancad-mcp"
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
