"""
Anywidget integration for Mosaic schematic editor.

Provides a live bridge to schematic data from the Mosaic editor via PouchDB.
"""

import anywidget
import traitlets
import marimo as mo


class SchematicBridge(anywidget.AnyWidget):
    """
    An anywidget that provides live bidirectional communication with the Mosaic editor.
    
    The widget displays a simple status indicator and automatically syncs schematic
    data from the Mosaic editor's PouchDB storage into the Python model, while also
    enabling Python to send simulation data back to the editor.
    
    Attributes:
        schematic_data (dict): Live schematic data from the Mosaic editor
        simulation_data (dict): Simulation data to send to the Mosaic editor.
                               Setting this will store the data with a timestamp in the editor's database.
    """
    
    # ESM shim that dynamically imports the Shadow CLJS compiled anywidget module
    # Uses window.location.origin to resolve the full URL since ESM runs from data: URL
    _esm = """
const { render } = await import(`${window.location.origin}/js/anywidget.js`);
export default { render };
"""
    
    # Schematic data that gets synced from the ClojureScript side
    schematic_data = traitlets.Dict().tag(sync=True)
    
    # Simulation data that gets synced to the ClojureScript side
    simulation_data = traitlets.Dict().tag(sync=True)
    
    # Schematic name that gets synced from the ClojureScript side
    name = traitlets.Unicode().tag(sync=True)

def schematic_bridge():
    """
    Create a SchematicBridge widget for use in Marimo notebooks.
    
    Returns:
        SchematicBridge: An instance of the SchematicBridge widget.
    """
    return mo.ui.anywidget(SchematicBridge())
