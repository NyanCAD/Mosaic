# SPDX-FileCopyrightText: 2026 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Convert Mosaic schematic documents into a kfactory DSchematic.

Entry points:
  - :class:`MosaicToDSchematic` — the orchestrator, stateful (caches
    registered subcircuit factories per KCLayout).
  - :func:`convert_schematic` — one-shot helper for a pre-fetched
    ``all_docs`` dict (shape returned by
    :meth:`nyancad.api.SchematicAPI.get_all_schem_docs`).
  - :func:`convert_from_api` — async helper that fetches via a
    :class:`~nyancad.api.SchematicAPI`.

Design constraints (see the plan at
``~/.claude/plans/i-want-you-to-generic-spring.md``):
  - Connectivity comes from each device's ``:nets`` map — **no wire tracing**.
  - Polylines carry explicit physical routing; plain ``"wire"`` docs are
    ignored.
  - Layout placement / orientation / mirror are read from dedicated
    nyancir++ fields on the device (``irl_x``, ``irl_y``, ``orientation``,
    ``mirror``). The schematic-view ``transform`` matrix is not consulted.
  - Subcircuit hierarchy is preserved: each ``type: "ckt"`` model becomes a
    factory registered on the shared KCLayout.
"""

from __future__ import annotations

import re
from typing import Any

import kfactory as kf
from kfactory.netlist import PortRef
from kfactory.schematic import Connection, Link, Route

from .geometry import DEFAULT_GRID_TO_UM, placement_fields
from .routing import invert_nets, polyline_net, polyline_terminals, route_settings


_NAME_SANITIZE = re.compile(r"[^A-Za-z0-9_]")


def _sanitize_name(name: str) -> str:
    """Normalise a model id into a valid Python identifier for factory names."""
    sanitized = _NAME_SANITIZE.sub("_", name)
    if sanitized and sanitized[0].isdigit():
        sanitized = "_" + sanitized
    return sanitized or "_unnamed"


def _port_device_endpoint_ids(port_devices: dict[str, dict[str, Any]]) -> set[str]:
    return {pd.get("_id") for pd in port_devices.values() if pd.get("_id")}


class MosaicToDSchematic:
    """Stateful converter from Mosaic (nyancir / nyancir++) docs to DSchematic.

    A single instance caches subcircuit factories it has registered on
    ``self.kcl``, so converting a schematic that uses the same subcircuit
    twice (or converting multiple schematics that share subcircuits) does
    not re-register.

    Args:
        kcl: Target :class:`kfactory.KCLayout`. Built-in component types
            (``resistor``, ``mmi1x2``, …) must already be registered as
            factories on this layout, or supplied via ``component_map``.
        grid_to_um: Scale applied when a device has no ``irl_x`` / ``irl_y``
            pseudo-layout fields. Default 50 µm per schematic unit, matching
            the editor's 50 px/unit grid.
        component_map: Optional mapping from Mosaic ``model`` bare id or
            ``type`` → factory name on the KCL. Used before falling back to
            the raw model id / type string.
    """

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
        """Build a DSchematic for ``schem_name`` from a full docs dict.

        ``all_docs`` is the shape returned by
        :meth:`nyancad.api.SchematicAPI.get_all_schem_docs` — i.e. keyed by
        ``"models"`` plus every schematic group used transitively.
        """
        models = all_docs.get("models", {})
        devices = all_docs.get(schem_name, {})
        return self._build_one(schem_name, devices, models, all_docs)

    def _build_one(
        self,
        schem_name: str,
        devices: dict[str, dict[str, Any]],
        models: dict[str, dict[str, Any]],
        all_docs: dict[str, Any],
    ) -> kf.DSchematic:
        schematic = kf.DSchematic(kcl=self.kcl, name=schem_name)

        components, port_devices, polylines, vias, tapers = self._partition(devices)

        for dev in components:
            self._ensure_subcircuit_factory(dev, models, all_docs)

        for dev in components:
            self._add_instance(schematic, dev, models)

        # Logical connectivity: invert the :nets maps. Port devices contribute
        # too — they sit on a net which reveals the interior endpoint of a
        # top-level port binding.
        net_map = invert_nets(components + list(port_devices.values()))

        used_nets: set[str] = set()
        for polyline in polylines:
            self._emit_route(schematic, polyline, net_map, vias, tapers, used_nets)

        self._emit_connections(schematic, net_map, used_nets, port_devices)

        self._emit_top_ports(schematic, schem_name, port_devices, net_map, models)

        return schematic

    @staticmethod
    def _partition(
        devices: dict[str, dict[str, Any]],
    ) -> tuple[
        list[dict[str, Any]],
        dict[str, dict[str, Any]],
        list[dict[str, Any]],
        list[dict[str, Any]],
        list[dict[str, Any]],
    ]:
        components: list[dict[str, Any]] = []
        port_devices: dict[str, dict[str, Any]] = {}
        polylines: list[dict[str, Any]] = []
        vias: list[dict[str, Any]] = []
        tapers: list[dict[str, Any]] = []

        for dev_id, raw in devices.items():
            dev = dict(raw)
            dev.setdefault("_id", dev_id)
            dtype = dev.get("type")
            if dtype == "port":
                port_name = dev.get("name") or dev_id
                port_devices[port_name] = dev
            elif dtype == "polyline":
                polylines.append(dev)
            elif dtype == "via":
                vias.append(dev)
            elif dtype == "taper":
                tapers.append(dev)
            elif dtype in ("wire", "text"):
                continue
            else:
                components.append(dev)

        return components, port_devices, polylines, vias, tapers

    def _ensure_subcircuit_factory(
        self,
        dev: dict[str, Any],
        models: dict[str, dict[str, Any]],
        all_docs: dict[str, Any],
    ) -> None:
        model_id_bare = dev.get("model")
        if not model_id_bare or model_id_bare in self._subcircuit_cache:
            return
        model_def = models.get(f"models:{model_id_bare}")
        if not model_def:
            return
        # Models with a ``models`` entry list are leaf (SPICE/etc.) — not a
        # subcircuit. Schematic-backed subcircuits leave it empty.
        if model_def.get("models"):
            return
        sub_devices = all_docs.get(model_id_bare, {})
        if not sub_devices:
            return

        factory_name = _sanitize_name(model_id_bare)
        # Cache BEFORE building to break any accidental cycles.
        self._subcircuit_cache[model_id_bare] = factory_name
        sub_schem = self._build_one(model_id_bare, sub_devices, models, all_docs)

        def _factory() -> kf.DKCell:
            return sub_schem.create_cell(kf.DKCell)

        _factory.__name__ = factory_name
        self.kcl.cell(_factory)

    def _factory_name_for(self, dev: dict[str, Any]) -> str:
        model_id_bare = dev.get("model")
        if model_id_bare and model_id_bare in self._subcircuit_cache:
            return self._subcircuit_cache[model_id_bare]

        lookup_keys = [k for k in (model_id_bare, dev.get("type")) if k]
        for key in lookup_keys:
            if key in self.component_map:
                return self.component_map[key]

        if model_id_bare:
            return _sanitize_name(model_id_bare)
        return dev.get("type", "unknown")

    def _add_instance(
        self,
        schematic: kf.DSchematic,
        dev: dict[str, Any],
        models: dict[str, dict[str, Any]],
    ) -> None:
        dev_id = dev["_id"]
        component = self._factory_name_for(dev)
        settings = dict(dev.get("props") or {})
        inst = schematic.create_inst(name=dev_id, component=component, settings=settings)
        inst.place(**placement_fields(dev, grid_to_um=self.grid_to_um))

    def _emit_route(
        self,
        schematic: kf.DSchematic,
        polyline: dict[str, Any],
        net_map: dict[str, list[tuple[str, str]]],
        vias: list[dict[str, Any]],
        tapers: list[dict[str, Any]],
        used_nets: set[str],
    ) -> None:
        terminals = polyline_terminals(polyline)
        if not terminals:
            return
        start, finish = terminals

        net_name = polyline_net(polyline)
        if not net_name:
            for candidate_net, endpoints in net_map.items():
                if start in endpoints and finish in endpoints:
                    net_name = candidate_net
                    break

        vias_here = [v for v in vias if v.get("net") == net_name] if net_name else []
        tapers_here = [t for t in tapers if t.get("net") == net_name] if net_name else []

        link = Link(root=(PortRef(instance=start[0], port=start[1]),
                          PortRef(instance=finish[0], port=finish[1])))
        route_name = polyline.get("_id") or f"route_{len(schematic.routes)}"
        route = Route(
            name=route_name,
            links=[link],
            routing_strategy=polyline.get("routing_strategy", "route_bundle"),
            settings=route_settings(polyline, vias_here, tapers_here),
        )
        schematic.routes[route_name] = route
        if net_name:
            used_nets.add(net_name)

    def _emit_connections(
        self,
        schematic: kf.DSchematic,
        net_map: dict[str, list[tuple[str, str]]],
        used_nets: set[str],
        port_devices: dict[str, dict[str, Any]],
    ) -> None:
        port_dev_ids = _port_device_endpoint_ids(port_devices)
        for net_name, endpoints in net_map.items():
            if net_name in used_nets:
                continue
            # Exclude port-device endpoints — they become top-level ports, not
            # Connections between instances.
            inst_endpoints = [(d, p) for (d, p) in endpoints if d not in port_dev_ids]
            if len(inst_endpoints) < 2:
                continue
            anchor_dev, anchor_port = inst_endpoints[0]
            anchor_ref = PortRef(instance=anchor_dev, port=anchor_port)
            for other_dev, other_port in inst_endpoints[1:]:
                other_ref = PortRef(instance=other_dev, port=other_port)
                schematic.connections.append(Connection((anchor_ref, other_ref)))

    def _emit_top_ports(
        self,
        schematic: kf.DSchematic,
        schem_name: str,
        port_devices: dict[str, dict[str, Any]],
        net_map: dict[str, list[tuple[str, str]]],
        models: dict[str, dict[str, Any]],
    ) -> None:
        # nyanlib stores the canonical port list on the parent model doc — it
        # was extracted from the schematic's "port" devices at save time, with
        # any extended gdsfactory metadata (cross_section / orientation /
        # bend_radius) riding on each entry.
        model_def = models.get(f"models:{schem_name}")
        if not model_def:
            return
        model_ports = model_def.get("ports") or []
        if not model_ports:
            return

        port_dev_ids = _port_device_endpoint_ids(port_devices)

        for model_port in model_ports:
            pname = model_port.get("name") if isinstance(model_port, dict) else model_port
            if not pname:
                continue
            port_dev = port_devices.get(pname)
            if not port_dev:
                continue
            dev_nets = port_dev.get("nets") or {}
            if not dev_nets:
                continue
            net_name = next(iter(v for v in dev_nets.values() if v), None)
            if not net_name:
                continue
            target = next(
                ((d, p) for (d, p) in net_map.get(net_name, []) if d not in port_dev_ids),
                None,
            )
            if not target:
                continue
            schematic.add_port(pname, port=PortRef(instance=target[0], port=target[1]))


def convert_schematic(
    schem_name: str,
    all_docs: dict[str, Any],
    kcl: kf.KCLayout | None = None,
    grid_to_um: float = DEFAULT_GRID_TO_UM,
    component_map: dict[str, str] | None = None,
) -> kf.DSchematic:
    """One-shot conversion from a pre-fetched ``all_docs`` dict."""
    converter = MosaicToDSchematic(kcl=kcl, grid_to_um=grid_to_um, component_map=component_map)
    return converter.convert(schem_name, all_docs)


async def convert_from_api(
    api: Any,
    schem_name: str,
    kcl: kf.KCLayout | None = None,
    grid_to_um: float = DEFAULT_GRID_TO_UM,
    component_map: dict[str, str] | None = None,
) -> kf.DSchematic:
    """Async helper: fetch via a :class:`~nyancad.api.SchematicAPI` then convert."""
    _, all_docs = await api.get_all_schem_docs(schem_name)
    return convert_schematic(
        schem_name,
        all_docs,
        kcl=kcl,
        grid_to_um=grid_to_um,
        component_map=component_map,
    )
