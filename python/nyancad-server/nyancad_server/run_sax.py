"""Run the bundled SAX marimo notebook.

Usage:
    python -m nyancad_server.run_sax [marimo args...]
"""

import os
import sys

from nyancad_server import get_sax_notebook_path


def main():
    os.execvp("marimo", ["marimo", "run", get_sax_notebook_path()] + sys.argv[1:])


if __name__ == "__main__":
    main()
