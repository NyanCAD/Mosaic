# SPDX-FileCopyrightText: 2026 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Routing emission: polyline → Route, via/taper → Route settings.

Logical connectivity comes from devices' ``:nets`` map (inverted here into
``net_name → [(device_id, port_name), …]``). No geometric tracing of wire
documents — wires are purely schematic-view entities for this converter.
"""

from collections import defaultdict
from typing import Any, Iterable


def invert_nets(devices: Iterable[dict[str, Any]]) -> dict[str, list[tuple[str, str]]]:
    """Invert per-device ``:nets`` maps into a global ``net → endpoints`` map.

    Args:
        devices: All component-like devices (anything with a ``nets`` map).
                 Wires, polylines, vias, tapers should already be filtered out.

    Returns:
        ``{net_name: [(device_id, port_name), …]}``. Endpoint order matches
        iteration order over the inputs — callers that care about determinism
        should sort ``devices`` first.
    """
    net_map: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for dev in devices:
        nets = dev.get("nets") or {}
        dev_id = dev.get("_id") or dev.get("id")
        if not dev_id:
            continue
        for port_name, net_name in nets.items():
            if net_name:
                net_map[net_name].append((dev_id, port_name))
    return dict(net_map)


def polyline_net(polyline: dict[str, Any]) -> str | None:
    """Return the net name a polyline belongs to, if any.

    Priority:
      - explicit ``net`` field on the polyline doc
      - otherwise None (caller falls back to matching terminals to devices'
        net maps)
    """
    return polyline.get("net")


def polyline_terminals(polyline: dict[str, Any]) -> tuple[tuple[str, str], tuple[str, str]] | None:
    """Extract (device_id, port_name) pairs for a polyline's start and finish.

    The nyancir++ polyline doc has ``terminals: {start: …, finish: …}`` where
    each terminal references a device and one of its ports. Accepts a few
    shapes: dict with ``device``/``port`` keys, or a (device, port) tuple/list.
    """
    terms = polyline.get("terminals")
    if not terms:
        return None

    start = _coerce_terminal(terms.get("start"))
    finish = _coerce_terminal(terms.get("finish"))
    if start is None or finish is None:
        return None
    return start, finish


def _coerce_terminal(term: Any) -> tuple[str, str] | None:
    if term is None:
        return None
    if isinstance(term, dict):
        dev = term.get("device") or term.get("instance")
        port = term.get("port")
        if dev and port:
            return (str(dev), str(port))
        return None
    if isinstance(term, (list, tuple)) and len(term) == 2:
        return (str(term[0]), str(term[1]))
    return None


def route_settings(polyline: dict[str, Any], vias: list[dict[str, Any]], tapers: list[dict[str, Any]]) -> dict[str, Any]:
    """Build the ``settings`` dict for a Route from the polyline + attached
    via/taper docs on the same net.

    Keys forwarded (when present):
      - polyline: ``width``, ``cross_section``, ``waypoints``, ``bend_radius``
      - vias: ``bottom_layer``, ``top_layer`` (list if multiple)
      - tapers: ``input_xsection``, ``output_xsection`` (list if multiple)
    """
    settings: dict[str, Any] = {}

    for key in ("width", "cross_section", "waypoints", "bend_radius"):
        if key in polyline:
            settings[key] = polyline[key]

    if vias:
        settings["vias"] = [
            {k: v[k] for k in ("bottom_layer", "top_layer") if k in v}
            for v in vias
        ]

    if tapers:
        settings["tapers"] = [
            {k: t[k] for k in ("input_xsection", "output_xsection") if k in t}
            for t in tapers
        ]

    return settings
