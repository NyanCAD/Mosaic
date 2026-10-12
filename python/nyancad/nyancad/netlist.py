# SPDX-FileCopyrightText: 2022 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Native topology and SAX netlisting without SPICE dependencies."""

import inspect
import re

from kfnetlist import Netlist, PortRef

from nyancad.identity import (
    STRUCTURAL_TYPES,
    VALID_IDENT_RE,
    SchemId,
    bare_id,
    default_port_order,
    model_key,
)

__all__ = [
    "STRUCTURAL_TYPES",
    "VALID_IDENT_RE",
    "SchemId",
    "bare_id",
    "default_port_order",
    "invert_kf_nets",
    "kf_component_name",
    "kfnetlist_from_nyancad",
    "model_key",
    "recursive_kfnetlist_from_nyancad",
    "resolve_sax_netlist",
]


def kf_component_name(dev_id, dev, models):
    """Resolve a Mosaic device to the component name used in kfnetlist/SAX."""
    model_id = dev.get("model")
    model = models.get(model_key(model_id))
    if not model_id:
        raise ValueError(f"Device {dev_id} has no model")
    if not model:
        raise ValueError(f"Model metadata missing for {model_id!r}")
    if not model.get("name"):
        raise ValueError(f"Model metadata for {model_id!r} has no name")
    return model["name"]


def invert_kf_nets(components, port_devices, instance_ids=None):
    """Build ``{net_name: {"inst": [...], "ports": [...]}}`` from Mosaic nets.

    Port markers resolve via the same precedence as the converter: a
    Livewire-owned ``attached_port`` (layout binding) is the primary resolver,
    then the Mosaic-owned ``nets`` value. A layout-only port (``attached_port``
    present, ``nets`` absent) synthesizes a net joining the top port with its
    attached instance port — without this it would be mis-bucketed under a net
    named after itself and never connect to the device.
    """
    out = {}
    for inst, dev in components:
        for port, net in (dev.get("nets") or {}).items():
            if not net:
                continue
            bucket = out.setdefault(net, {"inst": [], "ports": []})
            member = (str(inst), str(port))
            if member not in bucket["inst"]:
                bucket["inst"].append(member)

    for port_name, dev in port_devices.items():
        port_name_str = str(port_name)

        # 1. attached_port — Livewire layout binding, primary resolver.
        ap = dev.get("attached_port")
        if isinstance(ap, dict):
            cid = ap.get("component_id") or ap.get("device") or ap.get("instance")
            pn = ap.get("port_name") or ap.get("port")
            if cid and pn:
                # Synthesize a net (keyed by the port name) joining the top
                # port with its attached instance port.
                bucket = out.setdefault(port_name_str, {"inst": [], "ports": []})
                member = (
                    instance_ids.get(str(cid), _safe_ident(str(cid)))
                    if instance_ids
                    else _safe_ident(str(cid)),
                    str(pn),
                )
                if member not in bucket["inst"]:
                    bucket["inst"].append(member)
                if port_name_str not in bucket["ports"]:
                    bucket["ports"].append(port_name_str)
                continue

        # 2. else fall back to the Mosaic-owned nets value.
        for net in (dev.get("nets") or {"P": port_name_str}).values():
            if not net:
                continue
            bucket = out.setdefault(net, {"inst": [], "ports": []})
            if port_name_str not in bucket["ports"]:
                bucket["ports"].append(port_name_str)

    return out


def _safe_ident(name):
    """Replace characters that aren't valid in Python identifiers."""
    return re.sub(r"[^A-Za-z0-9_]", "_", name)


def _dedupe_instance_ident(candidate, used):
    base = candidate or "inst"
    unique = base
    suffix = 2
    while unique in used:
        unique = f"{base}_{suffix}"
        suffix += 1
    used.add(unique)
    return unique


def _kf_instance_id_map(docs):
    """Choose stable kfnetlist instance IDs for Mosaic device docs.

    Prefer the user-visible ``name`` when it is unique and already a valid
    identifier so schematic netlists line up with layout extraction names.
    Fall back to a sanitized document key and de-duplicate collisions.
    """
    components = [
        (str(dev_id), dev)
        for dev_id, dev in docs.items()
        if dev.get("type") not in STRUCTURAL_TYPES
    ]

    valid_name_counts = {}
    for _dev_id, dev in components:
        name = dev.get("name")
        if isinstance(name, str) and VALID_IDENT_RE.fullmatch(name):
            valid_name_counts[name] = valid_name_counts.get(name, 0) + 1

    used = set()
    instance_ids = {}

    for dev_id, dev in components:
        name = dev.get("name")
        if not (
            isinstance(name, str)
            and VALID_IDENT_RE.fullmatch(name)
            and valid_name_counts.get(name) == 1
            and not VALID_IDENT_RE.fullmatch(dev_id)
        ):
            continue
        chosen = _dedupe_instance_ident(name, used)
        instance_ids[dev_id] = chosen
        raw_id = dev.get("_id")
        if raw_id:
            instance_ids[str(raw_id)] = chosen
        instance_ids[_safe_ident(dev_id)] = chosen

    for dev_id, dev in components:
        if dev_id in instance_ids:
            continue
        fallback = _safe_ident(dev_id)
        chosen = _dedupe_instance_ident(fallback, used)
        instance_ids[dev_id] = chosen
        raw_id = dev.get("_id")
        if raw_id:
            instance_ids[str(raw_id)] = chosen
        instance_ids[fallback] = chosen

    return instance_ids


def kfnetlist_from_nyancad(name, schem, *, kcl="PDK"):
    """Build a ``kfnetlist.Netlist`` directly from Mosaic ``nets``.

    This is intentionally topology-only: it does not require layout placement,
    DSchematic conversion, kfactory, or a PDK runtime.
    """
    docs = schem.get(name, {})
    models = schem.get("models", {})
    instance_ids = _kf_instance_id_map(docs)

    components = []
    port_devices = {}
    for dev_id, dev in docs.items():
        if dev.get("type") == "port":
            port_devices[str(dev.get("name") or dev_id)] = dev
        elif dev.get("type") not in STRUCTURAL_TYPES:
            components.append((instance_ids[str(dev_id)], dev))

    netlist = Netlist()
    for dev_id, dev in components:
        settings = dict(dev.get("props") or {})
        netlist.create_inst(
            dev_id,
            kcl,
            kf_component_name(dev_id, dev, models),
            settings,
        )

    top_ports = {}
    for port_name in sorted(port_devices):
        top_ports[port_name] = netlist.create_port(str(port_name))

    net_map = invert_kf_nets(components, port_devices, instance_ids=instance_ids)
    for net_name in sorted(net_map):
        members = [
            *(top_ports[port] for port in net_map[net_name]["ports"]),
            *(PortRef(inst, port) for inst, port in net_map[net_name]["inst"]),
        ]
        if members:
            netlist.create_net(*members)

    netlist.sort()
    return netlist


def recursive_kfnetlist_from_nyancad(name, schem, *, kcl="PDK"):
    """Build recursive ``kfnetlist.Netlist`` objects from Mosaic subcircuits."""
    models = schem.get("models", {})
    result = {}
    queue = [name]
    seen = set()

    while queue:
        schem_name = queue.pop(0)
        if schem_name in seen or schem_name not in schem:
            continue
        seen.add(schem_name)
        if schem_name == name:
            netlist_name = name
        else:
            netlist_name = kf_component_name(schem_name, {"model": schem_name}, models)
        result[netlist_name] = kfnetlist_from_nyancad(schem_name, schem, kcl=kcl)

        for dev in schem.get(schem_name, {}).values():
            model_id = dev.get("model")
            model = models.get(model_key(model_id))
            if (
                model_id
                and model_id in schem
                and model is not None
                and not model.get("models")
            ):
                queue.append(model_id)

    return result


def _accepted_params(factory):
    """Return the set of parameter names *factory* accepts, or None if it
    accepts arbitrary keyword arguments.
    """
    try:
        sig = inspect.signature(factory)
    except (ValueError, TypeError):
        return None
    if any(p.kind == inspect.Parameter.VAR_KEYWORD for p in sig.parameters.values()):
        return None
    return set(sig.parameters.keys()) - {"self"}


def _to_python(v):
    """Convert JSON-native value to Python: list → tuple, rest unchanged."""
    if isinstance(v, list):
        return tuple(_to_python(item) for item in v)
    return v


def _coerce_settings(raw, accepted=None):
    """Normalise prop values from native JSON to Python types.

    All editors now write native JSON types, so the only transforms
    needed are JSON array → Python tuple and filtering to parameters
    the factory actually accepts (extra keys cause TypeError on call).
    """
    return {
        k: _to_python(v) for k, v in raw.items() if accepted is None or k in accepted
    }


def resolve_sax_netlist(recnet, pdk_cells, model_names=None):
    """Expand hierarchical components in a SAX RecursiveNetlist using PDK cells.

    For each component referenced by top-level instances but not already
    defined as a sub-netlist or available as a model, creates gdsfactory
    cells with per-instance coerced settings and merges their netlists.
    Instances with different settings get separate variant sub-netlists
    (e.g. ``ring_single_v0``, ``ring_single_v1``).

    Args:
        recnet: SAX RecursiveNetlist dict (mutated in place).
        pdk_cells: PDK cells module (e.g. ``cspdk.si220.cband.cells``).
        model_names: Optional set of SAX model names. Components in this set
            are left as leaf models so SAX can call the model factory with the
            instance settings.

    Returns:
        List of leaf component names that were expanded.
    """
    import logging
    import warnings

    log = logging.getLogger(__name__)

    top_name = next(iter(recnet))
    top = recnet.get(top_name, {})
    instances = top.get("instances", {})
    sub_netlists = set(recnet.keys()) - {top_name}
    model_names = set(model_names or ())

    leaf_components = sorted(
        {
            info.get("component", name)
            for name, info in instances.items()
            if name not in sub_netlists
            and info.get("component", name) not in sub_netlists
        }
    )

    if pdk_cells is None:
        return leaf_components

    comp_instances: dict[str, list[tuple[str, dict]]] = {}
    for inst_name, inst_info in instances.items():
        comp = inst_info.get("component", inst_name)
        if comp not in leaf_components:
            continue
        if comp in model_names:
            continue
        comp_instances.setdefault(comp, []).append((inst_name, inst_info))

    for comp, inst_list in comp_instances.items():
        cell_fn = getattr(pdk_cells, comp, None)
        if cell_fn is None:
            continue

        accepted = _accepted_params(cell_fn)

        coerced_per_inst = []
        for inst_name, inst_info in inst_list:
            coerced = _coerce_settings(inst_info.get("settings", {}), accepted=accepted)
            key = tuple(sorted(coerced.items()))
            coerced_per_inst.append((inst_name, inst_info, coerced, key))

        unique_keys = {key for _, _, _, key in coerced_per_inst}
        needs_variants = len(unique_keys) > 1
        variant_map: dict[tuple, str] = {}
        idx = 0

        for _inst_name, inst_info, coerced, key in coerced_per_inst:
            if key not in variant_map:
                try:
                    cell = cell_fn(**coerced)
                except Exception:
                    log.warning(
                        "%s: cell_fn(**%r) failed, skipping",
                        comp,
                        coerced,
                        exc_info=True,
                    )
                    continue

                try:
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore")
                        sub = cell.get_netlist(recursive=True)
                except Exception:
                    log.warning(
                        "%s: get_netlist failed, skipping",
                        comp,
                        exc_info=True,
                    )
                    continue

                variant_name = f"{comp}_v{idx}" if needs_variants else comp
                idx += 1
                if comp in sub and comp != variant_name:
                    sub[variant_name] = sub.pop(comp)

                for existing_key in set(recnet.keys()) & set(sub.keys()):
                    if existing_key != variant_name:
                        log.debug(
                            "%s: sub-netlist key %r already in recnet, overwriting",
                            comp,
                            existing_key,
                        )
                recnet.update(sub)
                variant_map[key] = variant_name

            if key in variant_map:
                inst_info["component"] = variant_map[key]

    return leaf_components
