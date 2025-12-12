# SPDX-FileCopyrightText: 2024 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Pydantic schemas for NyanCAD schematic data structures.

These schemas mirror the ClojureScript specs in common.cljs and provide
validation and documentation for the Python API and MCP tools.
"""

from typing import Annotated, Any, Dict, Literal, Optional, Union
from pydantic import BaseModel, Field, ConfigDict


class DeviceBase(BaseModel):
    """Base class for all schematic devices.

    Defines common fields shared by wires and components: position, name, CouchDB metadata,
    and computed port locations.
    """
    model_config = ConfigDict(populate_by_name=True)

    # CouchDB metadata
    id: str = Field(
        alias="_id",
        description="Document ID (format: 'schematic_id:device_name')"
    )
    rev: Optional[str] = Field(
        None,
        alias="_rev",
        description="CouchDB revision. Required for updates to prevent conflicts."
    )
    deleted: Optional[bool] = Field(
        None,
        alias="_deleted",
        description="Set to true to delete this document"
    )

    # Common geometry
    x: float = Field(description="X grid coordinate")
    y: float = Field(description="Y grid coordinate")
    name: str = Field(description="Human-readable label (R1, C2, W1, etc). NOT the same as _id.")

    # Computed data (read-only)
    ports: Optional[Dict[str, Optional[str]]] = Field(
        None,
        description="Port locations as '(x,y)' → port_name. Computed from geometry.",
        json_schema_extra={"readOnly": True}
    )


class Wire(DeviceBase):
    """Wire connecting two points on the schematic grid.

    Wires are defined by a start position (x, y) and a relative delta (rx, ry).
    The wire spans from (x, y) to (x+rx, y+ry) in grid coordinates.

    Example:
        ```json
        {
            "_id": "schem:W1",
            "type": "wire",
            "x": 3, "y": 3,
            "rx": 2, "ry": 0,
            "name": "W1"
        }
        ```
        This creates a horizontal wire from (3,3) to (5,3).
    """
    device_type: Literal["wire"] = Field(alias="type")
    rx: float = Field(description="Relative X delta (end_x - start_x)")
    ry: float = Field(description="Relative Y delta (end_y - start_y)")


class Component(DeviceBase):
    """Electronic component (resistor, capacitor, transistor, etc).

    Components are positioned at (x, y) with orientation defined by a transform matrix.
    The transform matrix is a 2D affine transformation [a, b, c, d, e, f] for rotation/mirroring.

    Component properties (props field) depend on the component type:
    - Resistor: {"resistance": "1k"}
    - Capacitor: {"capacitance": "0.1u"}
    - Inductor: {"inductance": "100m"}
    - Voltage source: {"dc": "5", "ac": "1", "tran": "sin(0 5 1k)"}
    - Current source: {"dc": "1m", "ac": "0.5m"}

    Transform matrices for common rotations:
    - Identity (0°): [1, 0, 0, 1, 0, 0]
    - Rotate 90°: [0, 1, -1, 0, 0, 0]
    - Rotate 180°: [-1, 0, 0, -1, 0, 0]
    - Rotate 270°: [0, -1, 1, 0, 0, 0]
    - Mirror horizontal: [-1, 0, 0, 1, 0, 0]
    - Mirror vertical: [1, 0, 0, -1, 0, 0]

    Example:
        ```json
        {
            "_id": "schem:R1",
            "type": "resistor",
            "x": 5, "y": 7,
            "transform": [1, 0, 0, 1, 0, 0],
            "name": "R1",
            "props": {"resistance": "1k"}
        }
        ```
    """
    device_type: Literal[
        "resistor", "capacitor", "inductor", "vsource", "isource",
        "diode", "pmos", "nmos", "npn", "pnp", "ckt", "port", "text"
    ] = Field(alias="type")
    transform: list[float] = Field(
        description="2D affine transform matrix [a, b, c, d, e, f] for rotation/mirroring"
    )

    # Optional component fields
    model: Optional[str] = Field(
        None,
        description="Model reference (bare ID without 'models:' prefix) for subcircuit components"
    )
    props: Optional[dict[str, Any]] = Field(
        None,
        description="Component properties dict. Names and values depend on component type. "
                    "Values use SPICE notation (1k, 10u, 100m, etc)."
    )
    variant: Optional[str] = Field(
        None,
        description="Component variant (e.g., 'ground' for port type)"
    )
    template: Optional[str] = Field(None, description="Custom SPICE template")


class ModelMetadata(BaseModel):
    """Component model metadata from the library.

    Models define reusable component types with their ports, parameters, SPICE templates,
    and schematic symbols. Used for subcircuits (type='ckt') and built-in components.

    Models are read-only through the MCP API.
    """
    model_config = ConfigDict(populate_by_name=True)  # No extra fields allowed

    # CouchDB metadata
    id: str = Field(alias="_id", description="Full model ID with 'models:' prefix")
    rev: Optional[str] = Field(None, alias="_rev", description="CouchDB revision")

    # Model metadata
    name: str = Field(..., description="Human-readable display name (NOT the ID)")
    type: Optional[str] = Field(None, description="Component type (resistor, capacitor, ckt, etc.)")
    category: list[str] = Field(default_factory=list, description="Hierarchical category path")

    # Template information
    has_templates: bool = Field(..., description="Whether templates exist for netlist generation")
    templates: Optional[Dict[str, list[Dict[str, Any]]]] = Field(
        None,
        description="Templates by language: spice, spectre, verilog, vhdl"
    )

    # Port and parameter definitions
    ports: Optional[Dict[str, list[str]]] = Field(None, description="Port definitions by position")
    props: Optional[list[Dict[str, str]]] = Field(None, description="Parameter definitions")

    # Symbol graphics
    symbol: Optional[Any] = Field(None, description="Symbol graphics for schematic rendering")


# Discriminated union - Pydantic routes to Wire or Component based on 'type' field
Device = Annotated[
    Union[Wire, Component],
    Field(discriminator='device_type')
]
"""Discriminated union of Wire and Component.

Pydantic automatically selects the correct type based on the 'type' field:
- type='wire' → Wire (requires: x, y, rx, ry, name)
- type='resistor|capacitor|...' → Component (requires: x, y, transform, name)

This is the type used in MCP tool signatures for type-safe schematic operations.
"""
