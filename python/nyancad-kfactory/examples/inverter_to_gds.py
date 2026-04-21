# SPDX-FileCopyrightText: 2026 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Convert IHP inverter.nyancir to a DSchematic and emit a GDS.

Usage (from the nyancad-kfactory dir):

    .venv/bin/python examples/inverter_to_gds.py

Produces ``inverter.gds`` next to the schematic.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import ihp  # noqa: F401 — side-effect: registers IHP factories on kfactory.kcl
import kfactory as kf

ihp.PDK.activate()

from nyancad_kfactory import convert_schematic


IHP_DIR = Path("/Users/pepijndevos/code/IHP")
SCHEMATIC = IHP_DIR / "inverter.nyancir"
MODELS = IHP_DIR / "models.nyanlib"
OUT_GDS = Path(__file__).parent / "inverter.gds"


def load_docs() -> dict:
    """Build the all_docs dict expected by convert_schematic.

    For this toy conversion we inject every device under the ``inverter``
    group key and every model under ``models``. No subcircuits to traverse.
    """
    raw_schem = json.loads(SCHEMATIC.read_text())
    raw_models = json.loads(MODELS.read_text())

    # FileAPI would inject _id; do it manually here.
    for doc_id, doc in raw_schem.items():
        if isinstance(doc, dict):
            doc["_id"] = doc_id
    for doc_id, doc in raw_models.items():
        if isinstance(doc, dict):
            doc["_id"] = doc_id

    return {
        "models": raw_models,
        "inverter": raw_schem,
    }


def register_placeholders(kcl: kf.KCLayout) -> None:
    """Register empty placeholder DKCells for types that don't map to layout.

    vsource is not a layout concept; we still want a cell so the DSchematic
    doesn't choke on an unknown factory. capacitor maps to cmim which has
    different params, so for a first-pass we give it a placeholder too.
    """
    for name in ("vsource_placeholder", "capacitor_placeholder"):
        if name in kcl.factories:
            continue

        def _factory(_name: str = name) -> kf.DKCell:
            return kcl.dkcell(_name)
        _factory.__name__ = name
        kcl.cell(_factory)


def main() -> int:
    kcl = kf.kcl
    register_placeholders(kcl)

    component_map = {
        # model id → kfactory factory name
        "ihp.cells.fet_transistors.nmos": "nmos",
        "ihp.cells.fet_transistors.pmos": "pmos",
        # bare type → placeholder
        "vsource": "vsource_placeholder",
        "capacitor": "capacitor_placeholder",
    }

    all_docs = load_docs()

    # First pass: drop non-layout components (vsources, capacitor with mismatched
    # params) so the remaining inverter is just M1+M2 — a real layout-able piece.
    # Also zero out nmos/pmos props so the factory defaults are used instead of
    # SPICE-style expressions that the kfactory cell doesn't understand.
    inverter = all_docs["inverter"]
    inverter = {
        k: v for k, v in inverter.items()
        if not (isinstance(v, dict) and v.get("type") in {"vsource", "capacitor"})
    }
    # IHP kfactory mos cells only expose S/D/G (bulk is implicit). Mosaic's
    # model declares S/D/G/B, so drop B from the nets map to keep the
    # DSchematic connections in sync with the cell's real port list.
    # Device props (width, length, nf, m) pass through — they already match
    # the kfactory nmos/pmos signature as bare microns/ints. The SPICE
    # "width * 1e-6" scaling lives in the model entry's params, not here.
    MOS_PORTS = {"S", "D", "G"}
    for dev in inverter.values():
        if isinstance(dev, dict) and dev.get("type") in {"nmos", "pmos"}:
            nets = dev.get("nets") or {}
            dev["nets"] = {p: n for p, n in nets.items() if p in MOS_PORTS}
    # Scrub dead nets from remaining devices (connections to V1/V2/C1 ports)
    dropped_nets_present = True  # harmless; the converter ignores nets with <2 endpoints
    all_docs["inverter"] = inverter

    # IHP transistors are ~1 µm — at the default 50 µm/unit the cells look
    # like specks on a huge empty canvas. Bring the grid scale down so the
    # layout is actually viewable without extreme zoom.
    schem = convert_schematic(
        "inverter",
        all_docs,
        kcl=kcl,
        grid_to_um=3.0,
        component_map=component_map,
    )

    print(f"Built DSchematic with:")
    print(f"  {len(schem.instances)} instances: {sorted(schem.instances)}")
    print(f"  {len(schem.connections)} connections")
    print(f"  {len(schem.routes)} routes")
    print(f"  {len(schem.ports)} top-level ports")

    # DSchematic Connections are HARD (ports must physically coincide). Without
    # pseudo-layout data the grid-scaled placements won't satisfy them, and the
    # IHP PDK's routing strategies need pin/drawing layer transitions that the
    # un-annotated schematic doesn't provide. Drop connections for this
    # first-pass — real autorouting waits for actual layout info.
    schem.connections.clear()
    print("  (connections cleared — routing awaits layout annotations)")

    cell = schem.create_cell(kf.DKCell)
    print(f"\nMaterialized DKCell: name={cell.name!r}, insts={len(list(cell.insts))}")

    cell.write(OUT_GDS)
    print(f"\nWrote GDS → {OUT_GDS}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
