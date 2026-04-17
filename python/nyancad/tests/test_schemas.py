"""Tests for Pydantic schemas in nyancad.schemas.

Tests verify that the schemas correctly validate real-world schematic data
and reject invalid input. Focus is on the data contract, not Pydantic internals.
"""

import pytest
from pydantic import ValidationError, TypeAdapter
from nyancad.schemas import (
    Wire, Component, ModelEntry, PortEntry, ModelMetadata, Device,
)

DeviceAdapter = TypeAdapter(Device)


# ---------------------------------------------------------------------------
# Wire
# ---------------------------------------------------------------------------

class TestWire:
    """Wire schema validates start position + relative delta."""

    def test_valid_wire(self):
        w = Wire.model_validate({
            "_id": "schem:W1", "type": "wire",
            "x": 3, "y": 3, "rx": 2, "ry": 0, "name": "W1"
        })
        assert w.id == "schem:W1"
        assert w.device_type == "wire"
        assert w.rx == 2
        assert w.ry == 0

    def test_field_names_also_work(self):
        """populate_by_name=True means both alias and field name are accepted."""
        w = Wire.model_validate({
            "id": "schem:W1", "device_type": "wire",
            "x": 0, "y": 0, "rx": 1, "ry": 1, "name": "W1"
        })
        assert w.id == "schem:W1"

    def test_missing_rx_fails(self):
        with pytest.raises(ValidationError):
            Wire.model_validate({
                "_id": "schem:W1", "type": "wire",
                "x": 0, "y": 0, "ry": 1, "name": "W1"
            })

    def test_wrong_type_literal_fails(self):
        with pytest.raises(ValidationError):
            Wire.model_validate({
                "_id": "schem:W1", "type": "resistor",
                "x": 0, "y": 0, "rx": 1, "ry": 1, "name": "W1"
            })

    def test_roundtrip(self):
        data = {
            "_id": "schem:W1", "type": "wire",
            "x": 1, "y": 2, "rx": 3, "ry": -1, "name": "W1"
        }
        w = Wire.model_validate(data)
        dumped = w.model_dump(by_alias=True)
        w2 = Wire.model_validate(dumped)
        assert w.id == w2.id
        assert w.rx == w2.rx


# ---------------------------------------------------------------------------
# Component
# ---------------------------------------------------------------------------

class TestComponent:
    """Component schema validates electronic components on the schematic."""

    def test_valid_resistor(self):
        c = Component.model_validate({
            "_id": "schem:R1", "type": "resistor",
            "x": 5, "y": 7, "transform": [1, 0, 0, 1, 0, 0],
            "name": "R1", "props": {"resistance": "1k"}
        })
        assert c.device_type == "resistor"
        assert c.props == {"resistance": "1k"}

    def test_all_valid_types(self):
        """Every supported component type should parse."""
        valid_types = [
            "resistor", "capacitor", "inductor", "vsource", "isource",
            "diode", "pmos", "nmos", "npn", "pnp", "ckt", "port", "text"
        ]
        for t in valid_types:
            c = Component.model_validate({
                "_id": "s:X1", "type": t,
                "x": 0, "y": 0, "transform": [1,0,0,1,0,0], "name": "X1"
            })
            assert c.device_type == t

    def test_invalid_type_rejected(self):
        with pytest.raises(ValidationError):
            Component.model_validate({
                "_id": "s:X1", "type": "widget",
                "x": 0, "y": 0, "transform": [1,0,0,1,0,0], "name": "X1"
            })

    def test_optional_fields_default_none(self):
        c = Component.model_validate({
            "_id": "s:R1", "type": "resistor",
            "x": 0, "y": 0, "transform": [1,0,0,1,0,0], "name": "R1"
        })
        assert c.model is None
        assert c.props is None
        assert c.variant is None
        assert c.template is None

    def test_rev_and_deleted(self):
        c = Component.model_validate({
            "_id": "s:R1", "_rev": "1-abc", "_deleted": True,
            "type": "resistor",
            "x": 0, "y": 0, "transform": [1,0,0,1,0,0], "name": "R1"
        })
        assert c.rev == "1-abc"
        assert c.deleted is True


# ---------------------------------------------------------------------------
# ModelEntry
# ---------------------------------------------------------------------------

class TestModelEntry:
    """ModelEntry validates individual model/template variants."""

    def test_minimal(self):
        e = ModelEntry.model_validate({"language": "spice"})
        assert e.language == "spice"
        assert e.implementation is None

    def test_alias_fields(self):
        e = ModelEntry.model_validate({
            "language": "spice",
            "spice-type": "M",
            "port-order": ["D", "G", "S", "B"]
        })
        assert e.spice_type == "M"
        assert e.port_order == ["D", "G", "S", "B"]

    def test_full_entry(self):
        e = ModelEntry.model_validate({
            "language": "spice",
            "implementation": "NgSpice",
            "name": "nmos_3p3",
            "spice-type": "M",
            "library": "/path/to/lib",
            "sections": ["tt", "ff"],
            "code": ".model nmos_3p3 NMOS",
            "port-order": ["D", "G", "S"],
            "params": {"vth0": "0.5"}
        })
        assert e.sections == ["tt", "ff"]
        assert e.params == {"vth0": "0.5"}


# ---------------------------------------------------------------------------
# PortEntry
# ---------------------------------------------------------------------------

class TestPortEntry:
    """PortEntry validates port definitions on component symbols."""

    def test_valid_port(self):
        p = PortEntry.model_validate({"name": "D", "side": "top"})
        assert p.type == "electric"  # default

    def test_photonic_port(self):
        p = PortEntry.model_validate({"name": "in", "side": "left", "type": "photonic"})
        assert p.type == "photonic"

    def test_invalid_side(self):
        with pytest.raises(ValidationError):
            PortEntry.model_validate({"name": "X", "side": "diagonal"})

    def test_invalid_type(self):
        with pytest.raises(ValidationError):
            PortEntry.model_validate({"name": "X", "side": "left", "type": "magnetic"})


# ---------------------------------------------------------------------------
# ModelMetadata
# ---------------------------------------------------------------------------

class TestModelMetadata:
    """ModelMetadata validates complete component model definitions."""

    def test_full_model(self):
        m = ModelMetadata.model_validate({
            "_id": "models:abc-123",
            "name": "NMOS 3.3V",
            "type": "nmos",
            "tags": ["Transistors", "MOSFET"],
            "has_models": True,
            "models": [{"language": "spice", "implementation": "NgSpice"}],
            "ports": [{"name": "D", "side": "top"}, {"name": "G", "side": "left"}],
            "props": [{"name": "vth", "default": "0.5"}],
        })
        assert m.id == "models:abc-123"
        assert len(m.models) == 1
        assert len(m.ports) == 2

    def test_minimal_model(self):
        m = ModelMetadata.model_validate({
            "_id": "models:xyz",
            "name": "Minimal",
            "has_models": False,
        })
        assert m.tags == []
        assert m.models is None
        assert m.ports is None


# ---------------------------------------------------------------------------
# Device discriminated union
# ---------------------------------------------------------------------------

class TestDeviceUnion:
    """Device union routes to Wire or Component based on 'type' field."""

    def test_wire_routing(self):
        d = DeviceAdapter.validate_python({
            "_id": "s:W1", "type": "wire",
            "x": 0, "y": 0, "rx": 1, "ry": 0, "name": "W1"
        })
        assert isinstance(d, Wire)

    def test_component_routing(self):
        d = DeviceAdapter.validate_python({
            "_id": "s:R1", "type": "resistor",
            "x": 0, "y": 0, "transform": [1,0,0,1,0,0], "name": "R1"
        })
        assert isinstance(d, Component)

    def test_invalid_type_rejected(self):
        with pytest.raises(ValidationError):
            DeviceAdapter.validate_python({
                "_id": "s:X1", "type": "unknown_device",
                "x": 0, "y": 0, "name": "X1"
            })
