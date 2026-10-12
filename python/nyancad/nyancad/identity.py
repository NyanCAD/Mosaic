# SPDX-FileCopyrightText: 2022 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Lightweight schematic identities shared by simulation backends."""

import re
from typing import NamedTuple


def model_key(bare_id):
    """Convert a bare model ID to a database key with 'models:' prefix.
    Returns None if input is None. Asserts that non-None input is not already prefixed.
    """
    if bare_id is None:
        return None

    assert not bare_id.startswith("models:"), (
        f"model_key expects bare ID, got prefixed: {bare_id}"
    )

    return f"models:{bare_id}"


def bare_id(model_key_str):
    """Extract bare ID from a model database key, removing 'models:' prefix.
    Returns None if input is None. Asserts that non-None input is prefixed.
    """
    if model_key_str is None:
        return None

    assert model_key_str.startswith("models:"), (
        f"bare_id expects prefixed model key, got bare ID: {model_key_str}"
    )

    return model_key_str[7:]  # Remove 'models:' prefix (7 characters)


class SchemId(NamedTuple):
    schem: str | None
    device: str | None

    @classmethod
    def from_string(cls, id):
        schem, dev, *_ = id.split(":") + [None]
        return cls(schem, dev)


def default_port_order(ports):
    """Canonical port order for subcircuit calls: sorted by name.

    Used as the default argument order when a model entry doesn't declare
    ``port-order`` explicitly. Sorting by name is stable regardless of how
    the user arranges ports around the device perimeter in the editor, and
    applies symmetrically to both the SUBCKT definition and its X call.
    """
    return [p["name"] for p in sorted(ports, key=lambda p: p["name"])]


VALID_IDENT_RE = re.compile(r"^[A-Za-z_]\w*$", re.ASCII)


STRUCTURAL_TYPES = {"wire", "text", "port", "polyline", "via", "taper", "net"}
