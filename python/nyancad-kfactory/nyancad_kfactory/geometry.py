# SPDX-FileCopyrightText: 2026 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Placement/geometry helpers for the Mosaic → DSchematic converter.

Reads the nyancir++ layout fields (irl_x, irl_y, orientation, mirror) off a
device document with graceful fallback to the schematic grid scaled to microns.
"""

from typing import Any

DEFAULT_GRID_TO_UM = 50.0
"""Default scale when a device has no irl_x/irl_y. Matches the editor grid of
50 px per schematic unit, interpreted as 50 µm per unit. Override via the
``grid_to_um`` kwarg on the converter."""


def placement_fields(device: dict[str, Any], grid_to_um: float = DEFAULT_GRID_TO_UM) -> dict[str, Any]:
    """Extract (x, y, orientation, mirror) for a device's DSchematic Placement.

    Priority:
      1. ``irl_x`` / ``irl_y`` (micron coords from nyancir++ pseudo-layout)
      2. schematic ``x`` / ``y`` scaled by ``grid_to_um``

    Orientation / mirror are read from layout-side fields with the same names
    as DSchematic's Placement (intentional — keep naming consistent). Defaults
    are ``orientation=0, mirror=False`` when absent. The schematic-view
    ``transform`` matrix is **not** inspected.
    """
    if "irl_x" in device and "irl_y" in device:
        x = float(device["irl_x"])
        y = float(device["irl_y"])
    else:
        x = float(device["x"]) * grid_to_um
        y = float(device["y"]) * grid_to_um

    return {
        "x": x,
        "y": y,
        "orientation": float(device.get("orientation", 0)),
        "mirror": bool(device.get("mirror", False)),
    }
