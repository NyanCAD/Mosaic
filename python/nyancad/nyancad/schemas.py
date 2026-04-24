# SPDX-FileCopyrightText: 2024 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Pydantic schemas for NyanCAD schematic data structures.

These schemas mirror the ClojureScript specs in common.cljs and provide
validation and documentation for the Python API and MCP tools.
"""

from typing import Annotated, Any, Dict, Literal, Optional, Union
from pydantic import BaseModel, Field, ConfigDict


# Device type enum — the single source of truth is
# nyancad.mosaic.common/device-types (common.cljc). Keep this Literal in
# sync when that set changes. Excludes "wire" (handled by the Wire class
# below) but includes "port" and "text" from schematic-only-types because
# Components are the class that represents those.
ComponentType = Literal[
    # Electrical devices
    "pmos", "nmos", "npn", "pnp",
    "resistor", "capacitor", "inductor",
    "vsource", "isource",
    "diode", "led", "photodiode", "modulator",
    # Subcircuits
    "ckt", "amp",
    # Schematic-only (handled as Components on this side)
    "port", "text",
    # Photonics
    "straight", "bend", "sbend", "taper", "transition",
    "terminator", "crossing",
    "ring-single", "ring-double", "spiral",
    "splitter-1x2", "coupler", "coupler-ring",
    "mmi-1x2", "mmi-2x2", "mzi-1x2", "mzi-2x2",
    "grating-coupler",
]


class DeviceBase(BaseModel):
    """Base class for all schematic devices.

    Defines common fields shared by wires and components: position, name, CouchDB metadata,
    and editor-computed net assignments.
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

    # Net assignments (read-only)
    nets: Optional[Dict[str, str]] = Field(
        None,
        description=(
            "Net assignments for this device: port_name → net_name. "
            "Computed and persisted by the editor. Read-only from Python."
        ),
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
    net: Optional[str] = Field(
        None,
        description=(
            "Net name this wire carries. Computed and persisted by the editor. "
            "Read-only from Python."
        ),
        json_schema_extra={"readOnly": True}
    )


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
    device_type: ComponentType = Field(alias="type")
    transform: list[float] = Field(
        min_length=6,
        max_length=6,
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


class ModelEntry(BaseModel):
    """A single model/template entry within a component model definition.

    Each entry describes one implementation variant (e.g., a SPICE model for NgSpice,
    a Spectre model, etc.). The flat list replaces the old nested templates dict.
    """
    model_config = ConfigDict(populate_by_name=True)

    language: str = Field(..., description="Entry language: 'spice', 'spectre', 'verilog', 'vhdl'")
    implementation: Optional[str] = Field(
        None, description="Simulator/tool name this entry targets (e.g., 'NgSpice', 'Xyce')"
    )
    name: Optional[str] = Field(
        None, description="Model reference name override (used instead of parent model name)"
    )
    spice_type: Optional[str] = Field(
        None, alias='spice-type',
        description="SPICE element type letter (R, C, M, X, SUBCKT, etc.)"
    )
    library: Optional[str] = Field(
        None, description="Path or URL to external library file"
    )
    sections: Optional[list[str]] = Field(
        None, description="Available corner/section names in the library"
    )
    code: Optional[str] = Field(
        None, description="Inline SPICE/HDL code for this model"
    )
    port_order: Optional[list[str]] = Field(
        None, alias='port-order',
        description="Explicit port connection order for SPICE netlist generation"
    )
    params: Optional[Dict[str, str]] = Field(
        None, description="Default parameter values for this model entry"
    )


class PortEntry(BaseModel):
    """A single port on a component symbol."""
    name: str = Field(..., description="Port name")
    side: Literal["top", "bottom", "left", "right"] = Field(..., description="Which side of the symbol")
    type: Literal["electric", "photonic"] = Field("electric", description="Port type")


class ModelMetadata(BaseModel):
    """Component model metadata from the library.

    Models define reusable component types with their ports, parameters, model entries,
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
    tags: list[str] = Field(default_factory=list, description="Flat tag list (replaces hierarchical category)")

    # Model entries (flat list replacing nested templates dict). has_models
    # is a DERIVED flag — it's not stored; mcp_server.py computes it before
    # returning a model to API consumers. Declared Optional here so validation
    # accepts either shape: dicts arriving from CouchDB (no has_models) and
    # dicts that have been through the MCP server (has_models injected).
    has_models: Optional[bool] = Field(
        None,
        description="Whether model entries exist for netlist generation (derived; set by mcp_server.py, not stored in DB)"
    )
    models: Optional[list[ModelEntry]] = Field(
        None,
        description="Flat list of model/template entries by language and implementation"
    )

    # Port and parameter definitions
    ports: Optional[list[PortEntry]] = Field(None, description="Port definitions as typed entries with side")
    props: Optional[list[Dict[str, str]]] = Field(None, description="Parameter definitions")

    # Symbol graphics
    symbol: Optional[str] = Field(None, description="URL to image rendered inside the schematic symbol")


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
