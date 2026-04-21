# SPDX-FileCopyrightText: 2026 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Unit tests for placement/geometry helpers — no kfactory dependency."""

from nyancad_kfactory.geometry import DEFAULT_GRID_TO_UM, placement_fields


def test_irl_coords_take_priority():
    dev = {"x": 3, "y": 2, "irl_x": 12.5, "irl_y": 7.0}
    assert placement_fields(dev) == {
        "x": 12.5,
        "y": 7.0,
        "orientation": 0.0,
        "mirror": False,
    }


def test_grid_fallback_uses_default_scale():
    dev = {"x": 3, "y": 2}
    assert placement_fields(dev) == {
        "x": 3 * DEFAULT_GRID_TO_UM,
        "y": 2 * DEFAULT_GRID_TO_UM,
        "orientation": 0.0,
        "mirror": False,
    }


def test_grid_fallback_honors_custom_scale():
    dev = {"x": 4, "y": 1}
    out = placement_fields(dev, grid_to_um=10.0)
    assert out["x"] == 40.0
    assert out["y"] == 10.0


def test_orientation_and_mirror_read_directly():
    dev = {"x": 0, "y": 0, "irl_x": 0, "irl_y": 0, "orientation": 90, "mirror": True}
    out = placement_fields(dev)
    assert out["orientation"] == 90
    assert out["mirror"] is True


def test_transform_field_is_ignored():
    # A rotate-90 transform matrix must NOT be decoded — layout uses
    # :orientation/:mirror, not :transform. With neither set, we default to 0.
    dev = {"x": 1, "y": 1, "transform": [0, 1, -1, 0, 0, 0]}
    out = placement_fields(dev)
    assert out["orientation"] == 0.0
    assert out["mirror"] is False
