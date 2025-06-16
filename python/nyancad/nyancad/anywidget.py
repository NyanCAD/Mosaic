"""
Anywidget integration for Mosaic schematic editor.

Provides a live bridge to schematic data from the Mosaic editor via PouchDB.
"""

import anywidget
import traitlets


class SchematicReader(anywidget.AnyWidget):
    """
    An anywidget that provides live access to schematic data from the Mosaic editor.
    
    The widget displays a simple status indicator and automatically syncs schematic
    data from the Mosaic editor's PouchDB storage into the Python model.
    
    Attributes:
        schematic_data (dict): Live schematic data from the Mosaic editor
    """
    
    # ESM shim that dynamically imports the Shadow CLJS compiled anywidget module
    # Uses window.location.origin to resolve the full URL since ESM runs from data: URL
    _esm = """
const { render } = await import(`${window.location.origin}/js/anywidget.js`);
export default { render };
"""
    
    # Schematic data that gets synced from the ClojureScript side
    schematic_data = traitlets.Dict().tag(sync=True)
    
    def __init__(self, **kwargs):
        """Initialize the SchematicReader widget."""
        super().__init__(**kwargs)
