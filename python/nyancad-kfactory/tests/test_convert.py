# SPDX-FileCopyrightText: 2026 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""End-to-end conversion tests. Requires kfactory."""

import pytest

kf = pytest.importorskip("kfactory")

from nyancad_kfactory.convert import MosaicToDSchematic, convert_schematic  # noqa: E402


def make_kcl(name: str = "test_kcl") -> "kf.KCLayout":
    """Fresh KCLayout so tests don't pollute each other's factory registry."""
    return kf.KCLayout(name)


def test_instance_placement_from_irl_fields():
    kcl = make_kcl("place_irl")
    # Register a dummy factory so the instance's component resolves.
    @kcl.cell
    def resistor() -> "kf.DKCell":
        c = kcl.dkcell("resistor")
        return c

    all_docs = {
        "models": {},
        "top": {
            "top:R1": {
                "_id": "top:R1",
                "type": "resistor",
                "name": "R1",
                "x": 3,
                "y": 2,
                "irl_x": 10.0,
                "irl_y": 5.0,
                "orientation": 90,
                "mirror": True,
                "transform": [1, 0, 0, 1, 0, 0],
                "props": {},
                "nets": {},
            }
        },
    }

    schem = convert_schematic("top", all_docs, kcl=kcl, component_map={"resistor": "resistor"})
    assert "top:R1" in schem.instances
    placement = schem.placements["top:R1"]
    assert placement.x == 10.0
    assert placement.y == 5.0
    assert placement.orientation == 90
    assert placement.mirror is True


def test_grid_fallback_when_no_irl():
    kcl = make_kcl("place_grid")

    @kcl.cell
    def resistor() -> "kf.DKCell":
        return kcl.dkcell("resistor")

    all_docs = {
        "models": {},
        "top": {
            "top:R1": {
                "_id": "top:R1",
                "type": "resistor",
                "name": "R1",
                "x": 3,
                "y": 2,
                "transform": [1, 0, 0, 1, 0, 0],
                "nets": {},
            }
        },
    }
    schem = convert_schematic(
        "top",
        all_docs,
        kcl=kcl,
        grid_to_um=50.0,
        component_map={"resistor": "resistor"},
    )
    placement = schem.placements["top:R1"]
    assert placement.x == 150.0
    assert placement.y == 100.0
    assert placement.orientation == 0
    assert placement.mirror is False


def test_two_endpoint_net_emits_single_connection():
    kcl = make_kcl("conn_two")

    @kcl.cell
    def straight() -> "kf.DKCell":
        return kcl.dkcell("straight")

    all_docs = {
        "models": {},
        "top": {
            "top:S1": {
                "_id": "top:S1", "type": "straight", "name": "S1",
                "x": 0, "y": 0, "transform": [1, 0, 0, 1, 0, 0],
                "nets": {"o1": "net_x"},
            },
            "top:S2": {
                "_id": "top:S2", "type": "straight", "name": "S2",
                "x": 2, "y": 0, "transform": [1, 0, 0, 1, 0, 0],
                "nets": {"o1": "net_x"},
            },
        },
    }
    schem = convert_schematic(
        "top", all_docs, kcl=kcl, component_map={"straight": "straight"}
    )
    assert len(schem.connections) == 1
    endpoints = {(ref.instance, ref.port) for ref in schem.connections[0].root}
    assert endpoints == {("top:S1", "o1"), ("top:S2", "o1")}


def test_polyline_becomes_route_with_terminals_and_settings():
    kcl = make_kcl("route_poly")

    @kcl.cell
    def straight() -> "kf.DKCell":
        return kcl.dkcell("straight")

    all_docs = {
        "models": {},
        "top": {
            "top:S1": {
                "_id": "top:S1", "type": "straight", "name": "S1",
                "x": 0, "y": 0, "transform": [1, 0, 0, 1, 0, 0],
                "nets": {"o2": "bus"},
            },
            "top:S2": {
                "_id": "top:S2", "type": "straight", "name": "S2",
                "x": 2, "y": 0, "transform": [1, 0, 0, 1, 0, 0],
                "nets": {"o1": "bus"},
            },
            "top:P1": {
                "_id": "top:P1", "type": "polyline", "name": "P1",
                "x": 1, "y": 0,
                "net": "bus",
                "terminals": {
                    "start": {"device": "top:S1", "port": "o2"},
                    "finish": {"device": "top:S2", "port": "o1"},
                },
                "cross_section": "strip",
                "bend_radius": 10,
            },
            "top:V1": {
                "_id": "top:V1", "type": "via", "name": "V1",
                "x": 1, "y": 0,
                "net": "bus",
                "bottom_layer": "M1", "top_layer": "M2",
            },
        },
    }
    schem = convert_schematic(
        "top", all_docs, kcl=kcl, component_map={"straight": "straight"}
    )
    # One Route, no redundant Connections.
    assert len(schem.routes) == 1
    assert schem.connections == []

    route = next(iter(schem.routes.values()))
    link_ports = {(r.instance, r.port) for link in route.links for r in link.root}
    assert link_ports == {("top:S1", "o2"), ("top:S2", "o1")}

    settings = route.settings
    assert settings["cross_section"] == "strip"
    assert settings["bend_radius"] == 10
    assert settings["vias"] == [{"bottom_layer": "M1", "top_layer": "M2"}]


def test_top_level_ports_from_model_definition():
    kcl = make_kcl("top_ports")

    @kcl.cell
    def straight() -> "kf.DKCell":
        return kcl.dkcell("straight")

    all_docs = {
        "models": {
            "models:my_sub": {
                "_id": "models:my_sub",
                "name": "MySub",
                "type": "ckt",
                "ports": [{"name": "in", "side": "left"}],
            }
        },
        "my_sub": {
            "my_sub:S1": {
                "_id": "my_sub:S1", "type": "straight", "name": "S1",
                "x": 0, "y": 0, "transform": [1, 0, 0, 1, 0, 0],
                "nets": {"o1": "interior"},
            },
            "my_sub:P_in": {
                "_id": "my_sub:P_in", "type": "port", "name": "in",
                "x": -1, "y": 0, "transform": [1, 0, 0, 1, 0, 0],
                "nets": {"P": "interior"},
            },
        },
    }
    converter = MosaicToDSchematic(kcl=kcl, component_map={"straight": "straight"})
    schem = converter.convert("my_sub", all_docs)
    assert "in" in schem.ports
    ref = schem.ports["in"]
    assert (ref.instance, ref.port) == ("my_sub:S1", "o1")


def test_subcircuit_registered_as_factory():
    kcl = make_kcl("hier")

    @kcl.cell
    def straight() -> "kf.DKCell":
        return kcl.dkcell("straight")

    # Sub-schematic "my_sub" that the top uses via model reference.
    all_docs = {
        "models": {
            "models:my_sub": {
                "_id": "models:my_sub",
                "name": "MySub",
                "type": "ckt",
                "ports": [],
            }
        },
        "my_sub": {
            "my_sub:S1": {
                "_id": "my_sub:S1", "type": "straight", "name": "S1",
                "x": 0, "y": 0, "transform": [1, 0, 0, 1, 0, 0],
                "nets": {},
            },
        },
        "top": {
            "top:U1": {
                "_id": "top:U1", "type": "ckt", "name": "U1",
                "model": "my_sub",
                "x": 0, "y": 0, "transform": [1, 0, 0, 1, 0, 0],
                "nets": {},
            },
        },
    }
    convert_schematic("top", all_docs, kcl=kcl, component_map={"straight": "straight"})
    assert "my_sub" in kcl.factories


def test_wires_and_text_are_ignored():
    kcl = make_kcl("ignore_wires")

    @kcl.cell
    def resistor() -> "kf.DKCell":
        return kcl.dkcell("resistor")

    all_docs = {
        "models": {},
        "top": {
            "top:R1": {
                "_id": "top:R1", "type": "resistor", "name": "R1",
                "x": 0, "y": 0, "transform": [1, 0, 0, 1, 0, 0],
                "nets": {"p": "n1"},
            },
            "top:R2": {
                "_id": "top:R2", "type": "resistor", "name": "R2",
                "x": 2, "y": 0, "transform": [1, 0, 0, 1, 0, 0],
                "nets": {"p": "n1"},
            },
            "top:W1": {
                "_id": "top:W1", "type": "wire", "name": "W1",
                "x": 0, "y": 0, "rx": 2, "ry": 0, "net": "n1",
            },
            "top:T1": {
                "_id": "top:T1", "type": "text", "name": "T1",
                "x": 0, "y": 1, "transform": [1, 0, 0, 1, 0, 0],
            },
        },
    }
    schem = convert_schematic(
        "top", all_docs, kcl=kcl, component_map={"resistor": "resistor"}
    )
    # Only the two resistors — wire and text are schematic-only.
    assert set(schem.instances) == {"top:R1", "top:R2"}
    assert len(schem.connections) == 1
