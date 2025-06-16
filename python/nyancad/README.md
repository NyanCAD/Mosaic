# NyanCAD Python Library

A Python library for the [NyanCAD schematic editor](https://github.com/NyanCAD/Mosaic) with anywidget integration. Provides live access to schematic data from marimo notebooks.

## Installation

```bash
pip install nyancad
```

## Usage

### Basic Usage

```python
import marimo as mo
from nyancad import SchematicReader

# Create a schematic reader widget
reader = SchematicReader()

# Display the widget (shows connection status)
reader

# Access live schematic data
print(f"Components: {reader.component_count}")
print(f"Resistors: {len(reader.get_components_by_type('resistor'))}")

# Get all components
for component in reader.components:
    print(f"{component['name']}: {component['type']} at ({component['position']['x']}, {component['position']['y']})")
```

### Integration with Marimo

The SchematicReader widget automatically connects to the NyanCAD editor running in the same marimo session. Any changes made in the schematic editor will be immediately reflected in the Python widget.

```python
# In a marimo cell
reader = SchematicReader()
reader  # This will show the connection status

# In another marimo cell - access the live data
mo.md(f"""
## Schematic Analysis
- **Total Components**: {reader.component_count}
- **Resistors**: {len(reader.get_components_by_type('resistor'))}
- **Capacitors**: {len(reader.get_components_by_type('capacitor'))}
""")
```

## Features

- **Live Sync**: Real-time synchronization with NyanCAD schematic editor via PouchDB
- **Zero Configuration**: Automatically detects and connects to the active schematic
- **Component Analysis**: Easy access to component data, positions, and properties
- **Type Filtering**: Filter components by type (resistor, capacitor, etc.)
- **Marimo Integration**: Seamless integration with marimo notebooks

## API Reference

### SchematicReader

The main widget class that provides access to live schematic data.

#### Properties

- `schematic_data` (dict): Raw schematic data from the Mosaic editor
- `components` (list): Processed list of all components with metadata
- `component_count` (int): Total number of components in the schematic

#### Methods

- `get_components_by_type(component_type)`: Get all components of a specific type

## Development

This package is part of the [NyanCAD](https://github.com/NyanCAD/Mosaic) project. The anywidget integration uses a ClojureScript bridge that compiles to an ESM module, allowing seamless data sharing between the schematic editor and Python environment.

## License

This project is licensed under the Mozilla Public License 2.0 - see the [LICENSE](../../LICENSE) file for details.
