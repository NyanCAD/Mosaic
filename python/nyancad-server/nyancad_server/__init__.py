"""NyanCAD Server - ASGI server with marimo integration."""

__version__ = "0.1.0"


def get_notebook_path() -> str:
    """Return path to the bundled file-based marimo notebook."""
    from importlib import resources
    return str(resources.files('nyancad_server') / 'notebook_file.py')
