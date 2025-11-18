# NyanCAD MCP Server Design Plan

## Research Findings

### MCP Transport Evolution (2025)

**Critical Update**: SSE transport was **deprecated** in MCP spec 2025-03-26, replaced by **Streamable HTTP**.

**Three Transport Options:**
1. **stdio** - For local subprocess communication (Claude Code, CLI tools)
   - Simple, lowest friction for local integration
   - Client spawns server as subprocess
   - Communication via stdin/stdout

2. **SSE (Server-Sent Events)** - **DEPRECATED** ❌
   - Legacy method for remote communication
   - Should NOT be used for new implementations

3. **Streamable HTTP** - **Current Standard** ✅
   - For networked, production deployments
   - Single HTTP endpoint supporting POST and GET
   - Stateless server support
   - Better alignment with modern web architecture

**Decision**:
- Keep **stdio** for Claude Code (local/CLI usage)
- Replace **SSE** with **Streamable HTTP** for NyanCAD server integration

### Authentication Best Practices

**MCP Authentication Requirements:**
- OAuth 2.1 is the foundation
- **MUST NOT use sessions for MCP authentication**
- Mandatory PKCE for authorization code exchanges
- OAuth 2.0 Authorization Server Metadata (RFC8414)
- Secure, non-deterministic session IDs if needed internally

**Two-Mode Authentication Strategy:**

1. **stdio Mode (Claude Code):**
   - Runs as subprocess with user's file system permissions
   - Configuration file for CouchDB credentials
   - Environment variables: `COUCHDB_URL`, `COUCHDB_ADMIN_USER`, `COUCHDB_ADMIN_PASS`, `NYANCAD_DB`

2. **Streamable HTTP Mode (NyanCAD Server):**
   - Inherit CouchDB session from parent NyanCAD server
   - Pass session cookie/auth token to CouchDB client
   - MCP itself uses OAuth 2.1 (future implementation)
   - For now: trust internal network calls from NyanCAD server

### LLM-Optimized API Design

**Key Principles:**

1. **Intent-Based Design** (Not API-Centric)
   - High-level tools that accomplish complete tasks
   - Don't expose low-level APIs that mirror every endpoint
   - Example: `add_transistor(type, position, connections)` NOT `create_device()` + `set_type()` + `set_position()` + `connect_ports()`

2. **Tool Names/Descriptions as Prompts**
   - Tool metadata directly guides LLM behavior
   - Descriptive names: `analyze_circuit_topology` NOT `get_netlist`
   - Rich descriptions explaining intent and usage

3. **Hybrid Discipline**
   - Blend API design with prompt engineering
   - Each tool is both a function AND a prompt

4. **Complete Tasks in Single Tools**
   - Don't force LLM to orchestrate multiple API calls
   - Better: One tool that internally calls multiple operations
   - Reduces errors and improves reliability

## Proposed API Redesign

### Remove These Tools ❌

1. **generate_netlist** - Low-level, not intent-based
2. **get_model** - Too granular, fold into other operations
3. **list_devices** - Redundant with better resource access

### New Intent-Based Tools ✅

**Circuit Understanding:**
```python
analyze_circuit(schematic_name, aspect="topology|power|signal|all")
  → Returns high-level analysis: what the circuit does, key components, signal flow

query_circuit(schematic_name, question)
  → Natural language queries about circuit: "What is the gain?", "Which devices are in the signal path?"
```

**Circuit Modification:**
```python
add_device(schematic_name, device_type, name, position, properties, connections)
  → Add a component with all parameters in one call

modify_device(schematic_name, device_id, changes)
  → Update device properties, position, or connections

remove_device(schematic_name, device_id, reconnect_strategy="auto|manual")
  → Remove component and optionally reconnect surrounding nets

connect_devices(schematic_name, connections)
  → Create wires between device ports: [{from: "R1.P", to: "C1.N"}, ...]
```

**Circuit Validation:**
```python
validate_circuit(schematic_name, checks=["connectivity", "dc_bias", "floating_nets"])
  → Check for common issues, return actionable feedback

simulate_circuit(schematic_name, analysis_type, parameters)
  → Run SPICE simulation and return results (DC, AC, TRAN)
```

**Model/Library:**
```python
search_components(query, category=None, limit=20)
  → Search for models: "nmos 180nm", "opamp low noise"

get_component_details(model_id)
  → Full model info including SPICE template, ports, parameters
```

### Resources (Redesigned)

**Hierarchical Access:**
```
circuit://{name}                  → Full schematic with devices, wires, hierarchy
circuit://{name}/device/{id}      → Individual device details
circuit://{name}/netlist          → Generated netlist (read-only)
circuit://{name}/analysis         → Last simulation results
model://{model_id}                → Model definition
library://category/{path}         → Browse model library
```

### Prompts

```python
analyze_circuit(schematic_name)
  → "Please analyze this circuit and provide insights on topology, function, and potential issues"

debug_circuit(schematic_name, symptom)
  → "The circuit has [symptom]. Help me debug it."

optimize_circuit(schematic_name, goal)
  → "Optimize this circuit for [goal: power, speed, area, noise]"
```

## Authentication Implementation Plan

### stdio Transport

**Configuration File:** `~/.config/nyancad/credentials.json`
```json
{
  "couchdb": {
    "url": "https://api.nyancad.com/",
    "username": "user@example.com",
    "password": "encrypted_or_token"
  },
  "default_db": "offline"
}
```

**Fallback to Environment Variables:**
- `COUCHDB_URL`
- `COUCHDB_USER`
- `COUCHDB_PASSWORD`
- `NYANCAD_DB`

### Streamable HTTP Transport

**Session Inheritance from NyanCAD Server:**
1. NyanCAD server has authenticated CouchDB session
2. MCP endpoint receives request with session context
3. Pass session cookie to CouchDB operations
4. No separate MCP authentication needed (internal service)

**Future OAuth 2.1:**
- When MCP needs external access
- Implement OAuth 2.1 with PKCE
- Scope-based access control

## Implementation Architecture

```
┌─────────────────────────────────────────┐
│         Claude Code (MCP Client)        │
│                                         │
│  stdio transport                        │
│  ~/.config/nyancad/credentials.json    │
└────────────┬────────────────────────────┘
             │
             ▼
      ┌─────────────┐
      │  nyancad-mcp│  (stdio server)
      │   server.py │
      └──────┬──────┘
             │
             ▼
      ┌──────────────┐
      │  CouchDB     │
      │  Client      │
      │  (with auth) │
      └──────────────┘


┌─────────────────────────────────────────┐
│      Browser → NyanCAD Server           │
│      (User authenticated via CouchDB)   │
└────────────┬────────────────────────────┘
             │
             ▼  HTTP Request with session
      ┌──────────────┐
      │ Streamable   │
      │ HTTP endpoint│
      │  /mcp/*      │
      └──────┬───────┘
             │
             ▼
      ┌─────────────┐
      │  nyancad-mcp│  (http server)
      │   http.py   │  (inherits session)
      └──────┬──────┘
             │
             ▼
      ┌──────────────┐
      │  CouchDB     │
      │  Client      │
      │  (inherited  │
      │   session)   │
      └──────────────┘
```

## Migration Steps

1. ✅ Keep stdio transport (already implemented)
2. 🔄 Replace SSE with Streamable HTTP
3. 🔄 Implement configuration file reader for stdio mode
4. 🔄 Implement session inheritance for HTTP mode
5. 🔄 Redesign tools to be intent-based
6. 🔄 Implement new circuit manipulation tools
7. 🔄 Update resources to hierarchical URIs
8. 🔄 Remove low-level tools
9. ✅ Test both transports
10. ✅ Update documentation

## Benefits

**For LLMs:**
- Intent-based tools are easier to use correctly
- Less orchestration = fewer errors
- Natural language integration
- Better understanding through rich descriptions

**For Users:**
- More powerful single-call operations
- Better error handling
- Clearer separation between local and remote usage

**For Developers:**
- Modern transport (Streamable HTTP)
- Proper authentication patterns
- OAuth 2.1 ready for future expansion
- Maintainable API design

## Security Considerations

1. **Credential Storage:**
   - stdio: Encrypted credentials file or environment variables
   - HTTP: Session-based, no credentials in MCP layer

2. **Network Security:**
   - stdio: Local subprocess, no network exposure
   - HTTP: HTTPS required, origin validation

3. **Scope Limitation:**
   - Read-only operations for analysis
   - Write operations require explicit intent
   - Validation before destructive changes

4. **Audit Logging:**
   - Log all circuit modifications
   - Include model + version identifiers
   - Traceability for debugging

## Next Steps

1. Get approval on this design
2. Implement Streamable HTTP transport
3. Implement authentication for both modes
4. Redesign tools API
5. Test with real circuits
6. Update documentation
