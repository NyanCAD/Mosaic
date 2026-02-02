<!--
SPDX-FileCopyrightText: 2022 Pepijn de Vos

SPDX-License-Identifier: MPL-2.0
-->

# Claude Code Guidelines for Mosaic

This project is funded by NLnet and follows their [Generative AI policy](https://nlnet.nl/foundation/policies/generativeAI/).

## Tech Stack

- **Frontend**: ClojureScript with [shadow-cljs](https://shadow-cljs.github.io/docs/UsersGuide.html) and [Reagent](https://reagent-project.github.io/) (React wrapper)
- **Backend**: Python ASGI server with marimo notebook integration
- **Database**: CouchDB for document storage and sync
- **Build**: shadow-cljs compiles to ES modules

### Development Workflow

**During development:** `npx shadow-cljs watch frontend` is typically already running, providing live reloading. No need to compile manually unless verifying a reported compile error.

**Full release build:** `npx shadow-cljs clj-run nyancad.mosaic.build/release` builds everything including CouchDB views and marimo WASM export.

### ClojureScript Notes

Clojure docstrings come **before** the argument vector (unlike Python):

```clojure
(defn my-function
  "This docstring comes before the arguments."
  [arg1 arg2]
  (do-something arg1 arg2))
```

## Architecture Overview

### Module Structure

**ClojureScript (shadow-cljs ES modules):**
- **common.cljs** - Shared utilities, specs, coordinate transforms, port geometry
- **editor.cljs** - Main schematic canvas, tools, device rendering, undo/redo
- **libman.cljs** - Component library browser, model CRUD, port import
- **auth.cljs** - OAuth login, session management
- **filestore.cljs** - Notebook file persistence
- **anywidget.cljs** - Bridge between editor and Python notebooks
- **hipflask.cljs** - PouchDB reactive atom wrapper with conflict resolution

**Python packages:**
- **nyancad** - Client library: `api.py` (SchematicAPI), `netlist.py` (SPICE generation), `anywidget.py` (SchematicBridge widget), `schemas.py` (Pydantic models)
- **nyancad-server** - ASGI server: `server.py` (3 deployment modes), `oauth.py` (OAuth 2.1 + JWT), `mcp_server.py` (AI tool access), `notebook_middleware.py` (per-user notebooks)

### State Management

The editor uses Reagent atoms with reactive cursors:

```clojure
(defonce ui (r/atom {...}))           ; UI state: zoom, tool, selection, mouse
(defonce schematic (pouch-atom ...))  ; Circuit elements (synced to PouchDB)
(defonce modeldb (pouch-atom ...))    ; Component library definitions
```

**Key state paths:**
- `::zoom` - Viewport bounds `[x y width height]`
- `::tool` - Current tool: `::cursor`, `::wire`, `::pan`, `::device`, `::eraser`, `::probe`
- `::selected` - Set of selected device IDs
- `::staging` - Device being placed or wire being drawn
- `::dragging` - Active operation: `::wire`, `::device`, `::view`, `::box`

### Data Model

**Device document structure:**
```clojure
{:_id "R1"                    ; Device ID
 :_rev "..."                  ; CouchDB revision
 :type "resistor"             ; Device type
 :x 2 :y 3                    ; Grid position
 :transform [1 0 0 1 0 0]     ; 6-element affine matrix
 :name "R1"                   ; Display name
 :model "models:xyz"          ; Reference to model definition (subcircuits)
 :props {:resistance "10k"}}  ; Device properties
```

**Wire devices** use relative endpoints: `:rx`, `:ry` (delta from `:x`, `:y`).

**Model definitions** (in "models" group):
```clojure
{:_id "models:uuid"
 :name "Op-Amp"
 :type "ckt"                           ; or "amp" for triangle shape
 :category ["Analog" "Amplifiers"]
 :ports {:left ["in+" "in-"] :right ["out"]}
 :templates {:spice [{:name "default" :code "..." :use-x false}]}
 :props [{:name "gain" :tooltip "Open-loop gain"}]}
```

### Database Layer (hipflask.cljs)

**PAtom** - Reactive atom backed by PouchDB with automatic sync:
- Extends `IAtom`, `IDeref`, `ISwap`, `IWatchable`
- Group-based document partitioning via ID prefix (e.g., `"models:"`)
- Tombstone pattern: `nil` values signal deletion
- Optimistic updates with automatic conflict resolution and retry

**Conflict resolution flow:**
1. Apply update optimistically with current `_rev`
2. On 409 conflict, fetch latest revision
3. Retry with new `_rev` until success

**Change watching:** `watch-changes` streams remote changes to local atoms, deduplicating by `_rev`.

### Editor Canvas

**Rendering:** SVG with grid-based coordinates (50px per unit). Devices rendered in layers: backgrounds → symbols → connection points.

**Built-in device symbols:** `resistor-sym`, `capacitor-sym`, `mosfet-sym`, `bjt-sym`, etc. Each has:
- `::bg` - Bounding box `[width height]`
- `::conn` - Connection points as `[[x y port-char] ...]`
- `::sym` - Render function

**Subcircuit symbols:** Box or triangle (amp) shape with ports spread around perimeter.

**Tools:** Each tool has drag-start, drag, and drag-end handlers:
- **Cursor** - Select, move, box-select
- **Wire** - Draw orthogonal wire segments (Ctrl for diagonal)
- **Device** - Place component at mouse position
- **Eraser** - Delete on hover
- **Pan** - Drag viewport
- **Probe** - Send element ID to notebook via BroadcastChannel

**Location indexing:** `build-location-index` creates spatial index `{[x y] → #{device-ids}}` for connection detection and wire splitting.

### Port Geometry

Ports spread evenly around device perimeter with gap in middle when count < dimension:

```clojure
(defn spread-ports [n size]
  (if (= n size) (range 1 (inc n))
    (concat (range 1 (inc (quot n 2)))
            (range (- size (quot n 2) -1) (inc size)))))
```

Python mirrors this algorithm in `netlist.py` for SPICE port mapping consistency.

### Python Server Modes

**LOCAL** (default): Single shared marimo notebook, no auth.

**LAN**: Per-user notebooks at `/notebook/{username}/{schematic}/`, CouchDB session auth, notebooks created from template on first access.

**WASM**: Static files only, all computation in Pyodide.

### Anywidget Bridge

**ClojureScript → Python:**
1. `anywidget.cljs` loads schematic from PouchDB
2. BFS traversal loads subcircuit models
3. Data synced to Python via anywidget trait (`schematic_data`)

**Python → ClojureScript:**
1. Notebook sets `simulation_data` trait
2. JavaScript stores result in PouchDB under `{group}$result:{timestamp}`

**Probe integration:** BroadcastChannel forwards clicked element ID to `probed_element_id` trait.

### SPICE Netlist Generation

`netlist.py` converts schematics to InSpice circuits:
1. **Netlist extraction:** Trace wires, map ports to net names
2. **Template selection:** Choose SPICE variant by simulator
3. **Device instantiation:** Map device types to InSpice elements (R, M, X, etc.)
4. **Subcircuit handling:** Recursively create NyanSubCircuit for schematic-based models

**use-x flag:** Forces subcircuit instantiation even for built-in types (e.g., resistor with custom model).

### MCP Server

Exposes tools for AI agents via Model Context Protocol:
- `get_schematic(id)` - Fetch schematic with computed ports + SPICE
- `list_library(filter, category)` - Browse component models
- `bulk_update_schematic(id, docs)` - Batch device updates
- `update_model(model)` - Model CRUD
- `get_simulation_result(id)` - Latest simulation data

Authentication via OAuth 2.1 with JWT tokens (shared secret with CouchDB).

## Commit Messages: Collaborative Work Statement

When committing AI-assisted work, include a **Collaborative Work Statement** that demonstrates the human intellectual contribution. This is not about logging prompts—it's about showing the shape of the collaborative process.

### What to Include

The statement should help a reviewer understand:

1. **Origin** - How did this work come about? What problem were you solving?
2. **Design decisions** - What approaches were considered? Why was this one chosen?
3. **Iteration** - What feedback shaped the code? What was rejected or revised?
4. **Issues caught** - What bugs, edge cases, or problems did you identify during review?
5. **Testing** - How was correctness verified?

### Example Commit Message

```
Add CLAUDE.md with architecture docs and NLnet AI policy compliance

Collaborative Work Statement:
- Originated from NLnet funding requirement to comply with their GenAI policy
- Claude fetched and summarized policy; Pepijn reframed the problem: NLnet's concern
  is "vibe coded" work that looks polished but lacks genuine design and review
- Pepijn rejected mechanical prompt logging—what matters is demonstrating human
  intellectual contribution, not proving AI was used
- Pepijn defined the "Collaborative Work Statement" concept: show division of labor,
  iteration, and the shape of the collaborative process
- Claude ran 5 explore agents to map the architecture; Pepijn provided additional
  context: ClojureScript docstring order, shadow-cljs watch workflow, prior CLAUDE.md
  was lost
- Claude synthesized ~15k words of exploration into concise reference; Pepijn reviewed
  for accuracy

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>
```

### Why This Matters

NLnet funds open source work by individuals and reviews that work to ensure quality. AI-assisted code can have surface-level polish (comments, docstrings, structure) without the thorough design, review, and testing that polish implies.

The Collaborative Work Statement demonstrates that:
- The human shaped the design, not just accepted generated code
- Problems were caught and addressed through review
- The work reflects genuine understanding, not just prompting

This accountability benefits both NLnet's review process and the long-term maintainability of the codebase.
