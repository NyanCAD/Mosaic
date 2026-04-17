"""Shared fixtures for nyancad tests."""

import pytest


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
