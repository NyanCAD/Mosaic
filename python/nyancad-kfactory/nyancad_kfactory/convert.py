# SPDX-FileCopyrightText: 2026 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Convert Mosaic schematic documents into a kfactory DSchematic.

Connectivity comes from each device's ``:nets`` map — never from walking
wire geometry. Physical routing comes from explicit ``polyline`` docs
(with optional ``via``/``taper`` attachments). Layout placement uses the
nyancir++ fields ``irl_x``/``irl_y``/``orientation``/``mirror``, with a
grid×scale micron fallback when those aren't present. Subcircuits
(``type: "ckt"``) recursively become their own DSchematic, registered as
``@kcl.cell`` factories on the shared KCLayout.
"""

from __future__ import annotations

import re
from collections import defaultdict
from typing import Any, Iterable

import kfactory as kf
from kfactory.netlist import PortRef
from kfactory.schematic import Connection, Link, Route


DEFAULT_GRID_TO_UM = 50.0
"""Schematic grid unit → microns when a device has no irl_x/irl_y. Matches
the editor's 50 px/unit grid."""

_NON_IDENT = re.compile(r"\W")


def _placement(dev: dict[str, Any], scale: float) -> dict[str, Any]:
    x, y = (dev["irl_x"], dev["irl_y"]) if "irl_x" in dev and "irl_y" in dev \
        else (dev["x"] * scale, dev["y"] * scale)
    return {
        "x": float(x),
        "y": float(y),
        "orientation": float(dev.get("orientation", 0)),
        "mirror": bool(dev.get("mirror", False)),
    }


def _invert_nets(devices: Iterable[dict[str, Any]]) -> dict[str, list[tuple[str, str]]]:
    """``{net_name: [(device_id, port_name), …]}`` from per-device ``:nets`` maps."""
    out: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for dev in devices:
        dev_id = dev.get("_id")
        if not dev_id:
            continue
        for port, net in (dev.get("nets") or {}).items():
            if net:
                out[net].append((dev_id, port))
    return dict(out)


def _polyline_terminal(term: Any) -> tuple[str, str] | None:
    if isinstance(term, dict):
        dev = term.get("device") or term.get("instance")
        port = term.get("port")
        return (str(dev), str(port)) if dev and port else None
    if isinstance(term, (list, tuple)) and len(term) == 2:
        return (str(term[0]), str(term[1]))
    return None


def _route_settings(polyline: dict[str, Any], vias: list[dict], tapers: list[dict]) -> dict[str, Any]:
    settings = {k: polyline[k] for k in ("width", "cross_section", "waypoints", "bend_radius") if k in polyline}
    if vias:
        settings["vias"] = [{k: v[k] for k in ("bottom_layer", "top_layer") if k in v} for v in vias]
    if tapers:
        settings["tapers"] = [{k: t[k] for k in ("input_xsection", "output_xsection") if k in t} for t in tapers]
    return settings


class MosaicToDSchematic:
    """Stateful converter from Mosaic docs to DSchematic. One instance caches
    subcircuit factories it has registered on ``self.kcl``."""

    def __init__(
        self,
        kcl: kf.KCLayout | None = None,
        grid_to_um: float = DEFAULT_GRID_TO_UM,
        component_map: dict[str, str] | None = None,
    ) -> None:
        self.kcl = kcl if kcl is not None else kf.kcl
        self.grid_to_um = grid_to_um
        self.component_map = component_map or {}
        self._subcircuit_cache: dict[str, str] = {}

    def convert(self, schem_name: str, all_docs: dict[str, Any]) -> kf.DSchematic:
        return self._build(schem_name, all_docs.get(schem_name, {}), all_docs.get("models", {}), all_docs)

    def _build(
        self,
        schem_name: str,
        devices: dict[str, dict[str, Any]],
        models: dict[str, dict[str, Any]],
        all_docs: dict[str, Any],
    ) -> kf.DSchematic:
        schematic = kf.DSchematic(kcl=self.kcl, name=schem_name)

        components: list[dict[str, Any]] = []
        port_devices: dict[str, dict[str, Any]] = {}
        polylines: list[dict[str, Any]] = []
        vias: list[dict[str, Any]] = []
        tapers: list[dict[str, Any]] = []
        for dev_id, raw in devices.items():
            dev = dict(raw, _id=raw.get("_id", dev_id))
            match dev.get("type"):
                case "port":
                    port_devices[dev.get("name") or dev_id] = dev
                case "polyline":
                    polylines.append(dev)
                case "via":
                    vias.append(dev)
                case "taper":
                    tapers.append(dev)
                case "wire" | "text":
                    pass
                case _:
                    components.append(dev)

        for dev in components:
            self._register_subcircuit(dev, models, all_docs)

        for dev in components:
            inst = schematic.create_inst(
                name=dev["_id"],
                component=self._factory_name(dev),
                settings=dict(dev.get("props") or {}),
            )
            inst.place(**_placement(dev, self.grid_to_um))

        net_map = _invert_nets(components + list(port_devices.values()))
        used_nets: set[str] = set()
        for pl in polylines:
            self._emit_route(schematic, pl, net_map, vias, tapers, used_nets)

        port_dev_ids = {pd["_id"] for pd in port_devices.values()}
        for net, endpoints in net_map.items():
            if net in used_nets:
                continue
            insts = [ep for ep in endpoints if ep[0] not in port_dev_ids]
            if len(insts) < 2:
                continue
            anchor = PortRef(instance=insts[0][0], port=insts[0][1])
            for d, p in insts[1:]:
                schematic.connections.append(Connection((anchor, PortRef(instance=d, port=p))))

        self._emit_top_ports(schematic, schem_name, port_devices, net_map, models, port_dev_ids)
        return schematic

    def _register_subcircuit(self, dev: dict[str, Any], models: dict, all_docs: dict) -> None:
        model_id = dev.get("model")
        if not model_id or model_id in self._subcircuit_cache:
            return
        model_def = models.get(f"models:{model_id}")
        if not model_def or model_def.get("models"):  # leaf model (SPICE/etc) — not a subcircuit
            return
        sub_devices = all_docs.get(model_id, {})
        if not sub_devices:
            return
        factory_name = _NON_IDENT.sub("_", model_id)
        self._subcircuit_cache[model_id] = factory_name  # cache before build to break cycles
        sub = self._build(model_id, sub_devices, models, all_docs)

        def factory() -> kf.DKCell:
            return sub.create_cell(kf.DKCell)
        factory.__name__ = factory_name
        self.kcl.cell(factory)

    def _factory_name(self, dev: dict[str, Any]) -> str:
        model_id = dev.get("model")
        if model_id and model_id in self._subcircuit_cache:
            return self._subcircuit_cache[model_id]
        for key in (model_id, dev.get("type")):
            if key and key in self.component_map:
                return self.component_map[key]
        return _NON_IDENT.sub("_", model_id) if model_id else dev.get("type", "unknown")

    def _emit_route(self, schematic, polyline, net_map, vias, tapers, used_nets):
        start = _polyline_terminal((polyline.get("terminals") or {}).get("start"))
        finish = _polyline_terminal((polyline.get("terminals") or {}).get("finish"))
        if not start or not finish:
            return

        net = polyline.get("net") or next(
            (n for n, ep in net_map.items() if start in ep and finish in ep), None
        )
        vias_here = [v for v in vias if v.get("net") == net] if net else []
        tapers_here = [t for t in tapers if t.get("net") == net] if net else []

        name = polyline.get("_id") or f"route_{len(schematic.routes)}"
        schematic.routes[name] = Route(
            name=name,
            links=[Link(root=(PortRef(instance=start[0], port=start[1]),
                              PortRef(instance=finish[0], port=finish[1])))],
            routing_strategy=polyline.get("routing_strategy", "route_bundle"),
            settings=_route_settings(polyline, vias_here, tapers_here),
        )
        if net:
            used_nets.add(net)

    def _emit_top_ports(self, schematic, schem_name, port_devices, net_map, models, port_dev_ids):
        # The parent model's :ports list is the canonical, editor-extracted port set
        # (populated in nyanlib from the schematic's "port" devices at save time).
        model_ports = (models.get(f"models:{schem_name}") or {}).get("ports") or []
        for mp in model_ports:
            pname = mp.get("name") if isinstance(mp, dict) else mp
            port_dev = port_devices.get(pname) if pname else None
            if not port_dev:
                continue
            net = next((v for v in (port_dev.get("nets") or {}).values() if v), None)
            target = next(
                ((d, p) for d, p in net_map.get(net, []) if d not in port_dev_ids), None
            ) if net else None
            if target:
                schematic.add_port(pname, port=PortRef(instance=target[0], port=target[1]))


def convert_schematic(
    schem_name: str,
    all_docs: dict[str, Any],
    kcl: kf.KCLayout | None = None,
    grid_to_um: float = DEFAULT_GRID_TO_UM,
    component_map: dict[str, str] | None = None,
) -> kf.DSchematic:
    return MosaicToDSchematic(kcl, grid_to_um, component_map).convert(schem_name, all_docs)
