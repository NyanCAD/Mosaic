# SPDX-FileCopyrightText: 2024 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Pydantic schemas for NyanCAD schematic data structures.

These schemas mirror the ClojureScript specs in common.cljs and provide
validation and documentation for the Python API and MCP tools.
"""

from typing import Any, Literal, Optional, Union
from pydantic import BaseModel, Field, ConfigDict


class WireDevice(BaseModel):
    """Wire connecting two points on the schematic grid.

    Wires are defined by a start position (x, y) and a relative delta (rx, ry).
    They connect component ports and can form complex routing paths.

    **Tip**: Use port labels instead of complex wire routing for cleaner schematics.
    A port label creates an implicit connection without drawing wires.
    """
    model_config = ConfigDict(extra='allow', populate_by_name=True)

    # CouchDB metadata - DO NOT modify these directly
    id: str = Field(
        alias="_id",
        description="CouchDB document ID (format: 'schematic_id:device_name'). "
                    "DO NOT modify this field."
    )
    rev: Optional[str] = Field(
        None,
        alias="_rev",
        description="CouchDB revision token. Required for updates, omit for new documents. "
                    "If you get a conflict error, fetch the latest document to get the new _rev."
    )
    deleted: Optional[bool] = Field(
        None,
        alias="_deleted",
        description="Set to true to delete this document from the database."
    )

    # Wire geometry (optional for partial updates)
    type: Optional[Literal["wire"]] = Field(None, description="Device type (must be 'wire')")
    x: Optional[float] = Field(None, description="Start X coordinate on schematic grid")
    y: Optional[float] = Field(None, description="Start Y coordinate on schematic grid")
    rx: Optional[float] = Field(None, description="Relative X delta (end_x - start_x)")
    ry: Optional[float] = Field(None, description="Relative Y delta (end_y - start_y)")

    # Optional metadata
    name: Optional[str] = Field(None, description="Optional wire identifier")


class ComponentDevice(BaseModel):
    """Electronic component (resistor, capacitor, transistor, etc).

    Components have a type (resistor, capacitor, etc), position, orientation,
    and properties. The transform matrix handles rotation and mirroring.

    Common transform matrices:
    - Identity (0°): [1, 0, 0, 1, 0, 0]
    - Rotate 90°: [0, 1, -1, 0, 0, 0]
    - Rotate 180°: [-1, 0, 0, -1, 0, 0]
    - Rotate 270°: [0, -1, 1, 0, 0, 0]
    - Mirror horizontal: [-1, 0, 0, 1, 0, 0]
    - Mirror vertical: [1, 0, 0, -1, 0, 0]
    """
    model_config = ConfigDict(extra='allow', populate_by_name=True)

    # CouchDB metadata
    id: str = Field(
        alias="_id",
        description="CouchDB document ID (format: 'schematic_id:device_name'). "
                    "DO NOT modify this field."
    )
    rev: Optional[str] = Field(
        None,
        alias="_rev",
        description="CouchDB revision token. Required for updates, omit for new documents. "
                    "If you get a conflict error, fetch the latest document to get the new _rev."
    )
    deleted: Optional[bool] = Field(
        None,
        alias="_deleted",
        description="Set to true to delete this document from the database."
    )

    # Component type and geometry (optional for partial updates)
    type: Optional[str] = Field(
        None,
        description="Device type: 'resistor', 'capacitor', 'inductor', 'vsource', "
                    "'isource', 'diode', 'pmos', 'nmos', 'npn', 'pnp', 'ckt', 'port', 'text'"
    )
    x: Optional[float] = Field(None, description="X coordinate of component origin")
    y: Optional[float] = Field(None, description="Y coordinate of component origin")
    transform: Optional[list[float]] = Field(
        None,
        description="2D affine transform matrix [a, b, c, d, e, f] for rotation/mirroring. "
                    "See class docstring for common values."
    )

    # Component metadata
    name: Optional[str] = Field(None, description="Component reference designator (R1, C2, etc)")
    model: Optional[str] = Field(
        None,
        description="Model reference (bare ID without 'models:' prefix) for subcircuit components"
    )
    props: Optional[dict[str, Any]] = Field(
        None,
        description="Component properties (resistance='1k', capacitance='10u', etc)"
    )
    variant: Optional[str] = Field(
        None,
        description="Component variant (e.g., 'ground' for port type)"
    )
    template: Optional[str] = Field(None, description="Custom SPICE template")


Device = Union[WireDevice, ComponentDevice]
