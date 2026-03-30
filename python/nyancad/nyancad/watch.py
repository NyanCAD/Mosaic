# SPDX-FileCopyrightText: 2024 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""
Marimo-reactive file watcher for NyanCAD project directories.

Provides a ProjectState that watches .nyancir/.nyanlib files for content
changes, triggering Marimo cell re-execution when schematics are edited.
"""

import sys
import time
import threading
from pathlib import Path
from typing import Any

from marimo._runtime.watch._path import PathState, WATCHER_SLEEP_INTERVAL

from .api import FileAPI


def _snapshot(path: Path) -> dict[str, float]:
    """Snapshot mtimes of all .nyancir/.nyanlib files in a directory."""
    files = {}
    try:
        for f in path.iterdir():
            if f.suffix in ('.nyancir', '.nyanlib'):
                try:
                    files[f.name] = f.stat().st_mtime
                except FileNotFoundError:
                    pass
    except FileNotFoundError:
        pass
    return files


def watch_project(
    path: Path, state: "ProjectState", should_exit: threading.Event
) -> None:
    """Watch a project directory for .nyancir/.nyanlib content changes."""
    last = _snapshot(path)
    while not should_exit.is_set():
        time.sleep(WATCHER_SLEEP_INTERVAL)
        try:
            current = _snapshot(path)
        except Exception as e:
            sys.stderr.write(f"Error watching project {path}: {e}\n")
            continue

        if current != last:
            last = current
            state._set_value(path)


class ProjectState(PathState):
    """Reactive watcher for a NyanCAD project directory.

    Watches all .nyancir and .nyanlib files for content changes (mtime)
    and structural changes (files added/removed). Triggers Marimo cell
    re-execution when any change is detected.
    """

    _forbidden_attributes = {
        "open", "rename", "replace", "write_text", "write_bytes"
    }
    _target = staticmethod(watch_project)

    def __getattr__(self, name: str) -> Any:
        if name in self._forbidden_attributes:
            raise AttributeError(
                f"'ProjectState' does not expose attribute '{name}'"
            )
        if hasattr(self._value, name):
            return getattr(self._value, name)
        raise AttributeError(
            f"'ProjectState' object has no attribute '{name}'"
        )


def watch_project_dir(path: str | Path) -> ProjectState:
    """Watch a project directory for .nyancir/.nyanlib changes.

    Returns a reactive ProjectState for use in Marimo notebook cells.
    The state triggers re-execution when any schematic or library file
    in the directory is modified, added, or removed.

    Args:
        path: Path to project directory

    Returns:
        ProjectState: Reactive directory watcher
    """
    path = Path(path)
    if not path.is_dir():
        raise ValueError(f"Path must be a directory: {path}")
    return ProjectState(path, allow_self_loops=True)


async def file_schematic(project_dir: str | Path, name: str) -> dict[str, dict]:
    """Load schematic data from .nyancir/.nyanlib files.

    Returns data in the same format as SchematicBridge.schematic_data,
    suitable for passing directly to inspice_netlist(name, data).

    For Marimo reactivity, pass a ProjectState from watch_project_dir():

        project = watch_project_dir("./my_project")
        data = await file_schematic(project, "schematic")

    Args:
        project_dir: Path to project directory (or ProjectState)
        name: Schematic name (without .nyancir extension)

    Returns:
        Full schematic dict: {"models": {...}, name: {...}, ...}
    """
    api = FileAPI(project_dir)
    _, data = await api.get_all_schem_docs(name)
    return data
