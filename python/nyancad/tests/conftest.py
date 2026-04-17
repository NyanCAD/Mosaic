"""Shared fixtures for nyancad tests."""

import pytest


@pytest.fixture
def simple_ports_left_right():
    """Two ports, one on each vertical side."""
    return [
        {"name": "A", "side": "left", "type": "electric"},
        {"name": "B", "side": "right", "type": "electric"},
    ]


@pytest.fixture
def four_side_ports():
    """One port per side."""
    return [
        {"name": "T", "side": "top", "type": "electric"},
        {"name": "B", "side": "bottom", "type": "electric"},
        {"name": "L", "side": "left", "type": "electric"},
        {"name": "R", "side": "right", "type": "electric"},
    ]


@pytest.fixture
def spice_model_def():
    """A model definition with multiple model entries for _select_model_entry."""
    return {
        "name": "my_nmos",
        "models": [
            {"language": "verilog", "implementation": "Verilator"},
            {"language": "spice", "implementation": "NgSpice", "name": "nmos_3p3"},
            {"language": "spice", "implementation": "Xyce", "name": "nmos_xyce"},
        ]
    }
