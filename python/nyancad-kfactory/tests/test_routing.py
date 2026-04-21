# SPDX-FileCopyrightText: 2026 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Unit tests for routing helpers — no kfactory dependency."""

from nyancad_kfactory.routing import (
    invert_nets,
    polyline_net,
    polyline_terminals,
    route_settings,
)


def test_invert_nets_builds_endpoint_lists():
    devices = [
        {"_id": "R1", "nets": {"a": "net1", "b": "net2"}},
        {"_id": "R2", "nets": {"a": "net1", "b": "net3"}},
        {"_id": "C1", "nets": {"p": "net2", "n": "net3"}},
    ]
    inverted = invert_nets(devices)
    assert sorted(inverted["net1"]) == [("R1", "a"), ("R2", "a")]
    assert sorted(inverted["net2"]) == [("C1", "p"), ("R1", "b")]
    assert sorted(inverted["net3"]) == [("C1", "n"), ("R2", "b")]


def test_invert_nets_skips_devices_without_id():
    devices = [{"nets": {"a": "net1"}}, {"_id": "R1", "nets": {"x": "net1"}}]
    inverted = invert_nets(devices)
    assert inverted == {"net1": [("R1", "x")]}


def test_invert_nets_skips_empty_net_names():
    devices = [{"_id": "R1", "nets": {"a": "", "b": "real"}}]
    inverted = invert_nets(devices)
    assert "real" in inverted
    assert "" not in inverted


def test_polyline_terminals_accepts_dict_form():
    pl = {
        "terminals": {
            "start": {"device": "R1", "port": "o1"},
            "finish": {"device": "R2", "port": "o2"},
        }
    }
    assert polyline_terminals(pl) == (("R1", "o1"), ("R2", "o2"))


def test_polyline_terminals_accepts_instance_alias():
    pl = {
        "terminals": {
            "start": {"instance": "R1", "port": "o1"},
            "finish": {"instance": "R2", "port": "o2"},
        }
    }
    assert polyline_terminals(pl) == (("R1", "o1"), ("R2", "o2"))


def test_polyline_terminals_accepts_tuple_form():
    pl = {"terminals": {"start": ["R1", "o1"], "finish": ("R2", "o2")}}
    assert polyline_terminals(pl) == (("R1", "o1"), ("R2", "o2"))


def test_polyline_terminals_returns_none_on_missing():
    assert polyline_terminals({}) is None
    assert polyline_terminals({"terminals": {"start": None, "finish": None}}) is None


def test_polyline_net_field():
    assert polyline_net({"net": "bus_a"}) == "bus_a"
    assert polyline_net({}) is None


def test_route_settings_forwards_polyline_and_vias_and_tapers():
    polyline = {
        "width": 0.5,
        "cross_section": "strip",
        "waypoints": [[1, 1], [2, 1]],
        "bend_radius": 10,
    }
    vias = [{"bottom_layer": "M1", "top_layer": "M2"}]
    tapers = [{"input_xsection": "strip", "output_xsection": "rib"}]
    out = route_settings(polyline, vias, tapers)
    assert out["width"] == 0.5
    assert out["cross_section"] == "strip"
    assert out["waypoints"] == [[1, 1], [2, 1]]
    assert out["bend_radius"] == 10
    assert out["vias"] == [{"bottom_layer": "M1", "top_layer": "M2"}]
    assert out["tapers"] == [{"input_xsection": "strip", "output_xsection": "rib"}]


def test_route_settings_omits_absent_keys():
    out = route_settings({}, [], [])
    assert out == {}
