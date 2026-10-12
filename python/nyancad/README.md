# NyanCAD Python Library

A Python library for the [NyanCAD schematic editor](https://github.com/NyanCAD/Mosaic) with anywidget integration. Provides live access to schematic data from marimo notebooks.

## Installation

```bash
pip install nyancad
```

Optional dependencies are selected by workflow:

```bash
pip install 'nyancad[spice]'     # SPICE netlisting and circuit classes
pip install 'nyancad[vacask]'    # SPICE plus the VACASK simulator
pip install 'nyancad[topology]'  # Native kfnetlist/SAX conversion
pip install 'nyancad[test]'      # Complete Python test dependencies
```

SPICE entrypoints such as `inspice_netlist` and `NyanCircuit` live in
`nyancad.spice`. Native conversion entrypoints remain in `nyancad.netlist`,
which requires `kfnetlist` when imported. Shared helpers live in
`nyancad.identity`; the shared API does not import either simulation backend.

Publish NyanCAD 0.18.6 before releasing consumers of the separated modules,
including NyanCAD server and the GF+ SDK. Version 0.18.5 lacks the new module
layout and extras.

## Usage

### Basic Usage

```python
import marimo as mo
from nyancad import SchematicBridge

# Create a schematic bridge widget
bridge = SchematicBridge()

# Display the widget (shows connection status)
bridge

# Access live schematic data
print(bridge.schematic_data)

# Send simulation data back to the editor
bridge.simulation_data = {
    "results": [1.2, 3.4, 5.6],
    "timestamp": "2024-01-01T00:00:00Z"
}
```

### Integration with Marimo

The SchematicBridge widget automatically connects to the NyanCAD editor running in the same marimo session. Any changes made in the schematic editor will be immediately reflected in the Python widget.

```python
# In a marimo cell
bridge = SchematicBridge()
bridge  # This will show the connection status

# In another marimo cell - access the live data
mo.md(f"""
## Schematic Analysis
Raw schematic data: {len(bridge.schematic_data)} items
""")

# View the actual schematic data structure
bridge.schematic_data
```

## Features

- **Live Sync**: Real-time synchronization with NyanCAD schematic editor via PouchDB
- **Bidirectional Communication**: Send simulation data back to the editor from Python
- **Zero Configuration**: Automatically detects and connects to the active schematic
- **Raw Data Access**: Direct access to the complete schematic data structure
- **Marimo Integration**: Seamless integration with marimo notebooks

## API Reference

### SchematicBridge

The main widget class that provides bidirectional communication with the Mosaic editor.

#### Properties

- `schematic_data` (dict): Raw schematic data from the Mosaic editor, automatically synced
- `simulation_data` (dict): Simulation data to send to the Mosaic editor. Setting this will store the data with a timestamp in the editor's database

## Development

This package is part of the [NyanCAD](https://github.com/NyanCAD/Mosaic) project. The anywidget integration uses a ClojureScript bridge that compiles to an ESM module, allowing seamless data sharing between the schematic editor and Python environment.

## License

This project is licensed under the Mozilla Public License 2.0 - see the [LICENSE](../../LICENSE) file for details.
