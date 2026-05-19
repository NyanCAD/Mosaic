"""Run the bundled marimo notebook: python -m nyancad_server.run [marimo args...]"""

import os
import sys

from nyancad_server import get_notebook_path


def main():
    os.execvp("marimo", ["marimo", "run", get_notebook_path()] + sys.argv[1:])


if __name__ == "__main__":
    main()
