# SPDX-FileCopyrightText: 2022 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Fetch schematics from CouchDB and generate SPICE netlists."""

import hashlib
import inspect
import logging
import re
import shutil

# package download dependencies
import sys
import tempfile
import warnings
from collections import deque
from collections.abc import Callable, Iterable
from enum import Enum
from numbers import Number
from pathlib import Path
from typing import Any, NamedTuple
from urllib.parse import urlparse

from InSpice.Spice.HighLevelElement import (
    ExponentialCurrentSource,
    ExponentialVoltageSource,
    PulseCurrentSource,
    PulseVoltageSource,
    SingleFrequencyFMCurrentSource,
    SingleFrequencyFMVoltageSource,
    SinusoidalCurrentSource,
    SinusoidalVoltageSource,
)
from InSpice.Spice.Netlist import Circuit, SubCircuit
from InSpice.Spice.Parser.HighLevelParser import SpiceSource
from InSpice.Spice.Parser.Translator import Builder

# Conditional imports based on environment
if sys.platform == "emscripten":  # Pyodide/WASM
    from pyodide.http import pyfetch

    async def download_file(url, dest_path):
        """Download using native Pyodide pyfetch"""
        response = await pyfetch(url)
        if not response.ok:
            raise Exception(f"HTTP {response.status}: {response.status_text}")  # noqa: TRY002
        content = await response.bytes()
        Path(dest_path).write_bytes(content)  # noqa: ASYNC240
else:  # Native Python
    import urllib.request

    async def download_file(url, dest_path):
        """Download using urllib"""
        urllib.request.urlretrieve(url, dest_path)  # noqa: S310


try:
    import py7zr

    shutil.register_unpack_format("7zip", [".7z"], py7zr.unpack_7zarchive)
except ImportError:
    pass


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


class _GeneratedCircuit(NamedTuple):
    component: str
    settings_key: tuple


class _CellContext(NamedTuple):
    kcl: Any
    wrapper_type: type[Any]


class _SettingGroup(NamedTuple):
    settings: dict[str, object]
    references: list[dict[str, Any]]


def default_port_order(ports):
    """Canonical port order for subcircuit calls: sorted by name.

    Used as the default argument order when a model entry doesn't declare
    ``port-order`` explicitly. Sorting by name is stable regardless of how
    the user arranges ports around the device perimeter in the editor, and
    applies symmetrically to both the SUBCKT definition and its X call.
    """
    return [p["name"] for p in sorted(ports, key=lambda p: p["name"])]


def _select_corner(sections, corners):
    """Select a corner/section for a library include.

    Args:
        sections: list of available sections from model entry
        corners: list of preferred corners from user (or None)

    Returns:
        Selected section string, or None if no sections available
    """
    if not sections:
        return None
    if corners:
        match = set(corners) & set(sections)
        if match:
            return match.pop()
    return sections[0]


IDENTIFIER_RE = re.compile(r"\b[A-Za-z_]\w*", re.ASCII)
VALID_IDENT_RE = re.compile(r"^[A-Za-z_]\w*$", re.ASCII)
STRUCTURAL_TYPES = {"wire", "text", "port", "polyline", "via", "taper", "net"}
_SETTING_BUFFER_COPY_LIMIT = 4_096
_NUMERIC_BUFFER_FORMATS = frozenset("?bBhHiIlLqQnNefd") | {"Zf", "Zd"}
_LOG = logging.getLogger(__name__)

_SIM_LANGUAGES = {
    "ngspice": ("spice",),
    "xyce": ("spice",),
    "vacask": ("spectre",),
}

_SPICE_SUFFIX_SCALE = {
    "t": 1e12,
    "g": 1e9,
    "meg": 1e6,
    "k": 1e3,
    "mil": 25.4e-6,
    "m": 1e-3,
    "u": 1e-6,
    "n": 1e-9,
    "p": 1e-12,
    "f": 1e-15,
}
_SPICE_NUMBER_RE = re.compile(
    r"^([+-]?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?)\s*(\w*)$", re.IGNORECASE
)


def _parse_spice_value(s):
    """Convert a SPICE engineering-notation string to a float.

    Handles suffixes like ``1k`` (1e3), ``10n`` (1e-8), ``4.7Meg`` (4.7e6),
    and plain numeric strings. Returns None for None or empty strings.
    """
    if s is None or (isinstance(s, str) and not s.strip()):
        return None
    if isinstance(s, (int, float)):
        return float(s)
    s = s.strip()
    try:
        return float(s)
    except ValueError:
        pass
    m = _SPICE_NUMBER_RE.match(s)
    if not m:
        return None
    num_str, suffix = m.group(1), m.group(2)
    scale = _SPICE_SUFFIX_SCALE.get(suffix.lower())
    if scale is None and suffix:
        # Try "meg" prefix match for case variations like "Meg", "MEG"
        if suffix.lower().startswith("meg"):
            scale = 1e6
        else:
            return None
    return float(num_str) * (scale if scale is not None else 1.0)


_TRAN_SOURCE_CLASSES = {
    "sin": {
        "voltage": SinusoidalVoltageSource,
        "current": SinusoidalCurrentSource,
    },
    "pulse": {
        "voltage": PulseVoltageSource,
        "current": PulseCurrentSource,
    },
    "exp": {
        "voltage": ExponentialVoltageSource,
        "current": ExponentialCurrentSource,
    },
    "sffm": {
        "voltage": SingleFrequencyFMVoltageSource,
        "current": SingleFrequencyFMCurrentSource,
    },
}

_TRAN_PARAM_MAP = {
    "sin": {
        "offset": "offset",
        "amplitude": "amplitude",
        "frequency": "frequency",
        "delay": "delay",
        "damping": "damping_factor",
    },
    "pulse": {
        "initial": "initial_value",
        "pulsed": "pulsed_value",
        "width": "pulse_width",
        "period": "period",
        "delay": "delay_time",
        "rise": "rise_time",
        "fall": "fall_time",
    },
    "exp": {
        "initial": "initial_value",
        "pulsed": "pulsed_value",
        "rise-delay": "rise_delay_time",
        "rise-tau": "rise_time_constant",
        "fall-delay": "fall_delay_time",
        "fall-tau": "fall_time_constant",
    },
    "sffm": {
        "offset": "offset",
        "amplitude": "amplitude",
        "carrier-freq": "carrier_frequency",
        "mod-index": "modulation_index",
        "signal-freq": "signal_frequency",
    },
}


def _eval_params(entry_params, device_props, sim="NgSpice"):
    """Substitute device props into SPICE param expressions.

    - Bare identifier ("rename"): pass the raw prop value through unchanged.
    - Missing rename target: skip the param so SPICE uses its model default.
    - Arithmetic expression: substitute identifiers and let the simulator
      evaluate. NgSpice requires braces around expressions; Spectre-family
      simulators (VACASK, etc.) use bare expressions.
    """
    if not entry_params:
        return device_props
    use_braces = sim.lower() == "ngspice"
    result = {}
    for param_name, expr in entry_params.items():
        stripped = expr.strip()
        if VALID_IDENT_RE.fullmatch(stripped):
            if stripped in device_props:
                result[param_name] = device_props[stripped]
            continue
        substituted = IDENTIFIER_RE.sub(
            lambda m: (
                str(device_props[m.group(0)])
                if m.group(0) in device_props
                else m.group(0)
            ),
            expr,
        )
        if use_braces:
            result[param_name] = "{" + substituted + "}"
        else:
            result[param_name] = substituted
    return result


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
    from kfnetlist import Netlist, PortRef

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


def _accepted_params(factory: Callable[..., object]) -> set[str] | None:
    """Return the set of parameter names *factory* accepts, or None if it
    accepts arbitrary keyword arguments.
    """
    try:
        sig = inspect.signature(factory)
    except (ValueError, TypeError):
        return None
    parameters = sig.parameters.values()
    if any(p.kind == inspect.Parameter.VAR_KEYWORD for p in parameters):
        return None
    return {
        parameter.name
        for parameter in parameters
        if parameter.kind
        in (inspect.Parameter.POSITIONAL_OR_KEYWORD, inspect.Parameter.KEYWORD_ONLY)
    }


def _to_python(value: object) -> object:
    """Recursively normalize container values."""
    match value:
        case list() | tuple():
            return tuple(_to_python(item) for item in value)
        case set():
            return {_to_python(item) for item in value}
        case frozenset():
            return frozenset(_to_python(item) for item in value)
        case dict():
            return {key: _to_python(item) for key, item in value.items()}
        case _:
            return value


def _coerce_settings(
    raw: dict[str, object], accepted: set[str] | None = None
) -> dict[str, object]:
    """Normalise prop values from native JSON to Python types.

    All editors now write native JSON types, so the only transforms
    needed are JSON array → Python tuple and filtering to parameters
    the factory actually accepts (extra keys cause TypeError on call).
    """
    if accepted is None:
        return {key: _to_python(value) for key, value in raw.items()}
    return {key: _to_python(value) for key, value in raw.items() if key in accepted}


def _buffer_identity(value: bytes | bytearray | memoryview) -> tuple:
    view = memoryview(value)
    if view.nbytes <= _SETTING_BUFFER_COPY_LIMIT:
        return ("buffer", view.tobytes())
    try:
        content = view.cast("B")
    except (TypeError, ValueError):
        content = view.tobytes()
    digest = hashlib.blake2b(content, digest_size=16).digest()
    return ("buffer_digest", view.nbytes, digest)


def _large_numeric_buffer_identity(
    value: object,
    serializer: Callable[[object], object] | None = None,
) -> tuple | None:
    try:
        view = memoryview(value)
    except (TypeError, ValueError):
        return None
    format_code = view.format.lstrip("@=<>!")
    if (
        view.nbytes <= _SETTING_BUFFER_COPY_LIMIT
        or format_code not in _NUMERIC_BUFFER_FORMATS
    ):
        return None
    try:
        content = view.cast("B")
    except (TypeError, ValueError):
        content = view.tobytes()
    digest = hashlib.blake2b(content, digest_size=16).digest()
    identity = ("numeric_buffer_digest", view.format, view.shape, digest)
    if serializer is None:
        return identity
    try:
        return _setting_identity(serializer(value))
    except (TypeError, ValueError, OverflowError, RecursionError):
        return identity


def _setting_identity(
    value: object,
    *,
    serializer: Callable[[object], object] | None = None,
) -> tuple:
    """Return a hashable identity for a setting value."""
    match value:
        case Enum():
            return _setting_identity(value.value, serializer=serializer)
        case str():
            return (str, str(value))
        case bytes() | bytearray():
            return _buffer_identity(value)
        case memoryview() if value.format == "B" and value.ndim == 1:
            return _buffer_identity(value)
        case memoryview():
            if (
                buffer_identity := _large_numeric_buffer_identity(value, serializer)
            ) is not None:
                return buffer_identity
            try:
                return _setting_identity(value.tolist(), serializer=serializer)
            except (NotImplementedError, TypeError, ValueError):
                return ("object", type(value), id(value))
        case Number():
            try:
                if bool(value != value):  # noqa: PLR0124
                    return ("nan",)
            except (TypeError, ValueError, ArithmeticError):
                pass
            if serializer is not None:
                try:
                    return _setting_identity(serializer(value))
                except (
                    TypeError,
                    ValueError,
                    OverflowError,
                    RecursionError,
                ):
                    pass
            return ("number", value)
        case dict():
            return (
                "mapping",
                frozenset(
                    (
                        _setting_identity(key, serializer=serializer),
                        _setting_identity(item, serializer=serializer),
                    )
                    for key, item in value.items()
                ),
            )
        case list() | tuple():
            return (
                "sequence",
                tuple(_setting_identity(item, serializer=serializer) for item in value),
            )
        case set() | frozenset():
            if serializer is not None:
                try:
                    return _setting_identity(serializer(value))
                except (
                    TypeError,
                    ValueError,
                    OverflowError,
                    RecursionError,
                ):
                    pass
            return (
                "set",
                frozenset(
                    _setting_identity(item, serializer=serializer) for item in value
                ),
            )
        case _:
            if (
                buffer_identity := _large_numeric_buffer_identity(value, serializer)
            ) is not None:
                return buffer_identity
            tolist = getattr(value, "tolist", None)
            if callable(tolist):
                try:
                    converted = tolist()
                except (TypeError, ValueError, OverflowError):
                    pass
                else:
                    if converted is not value:
                        return _setting_identity(converted, serializer=serializer)
            if serializer is not None:
                try:
                    serialized = serializer(value)
                except (
                    TypeError,
                    ValueError,
                    OverflowError,
                    RecursionError,
                ):
                    pass
                else:
                    return _setting_identity(serialized)
            try:
                hash(value)
            except (TypeError, ValueError):
                return ("object", type(value), id(value))
            return (type(value), value)


def _settings_identity(settings: dict[str, object]) -> tuple:
    """Return a canonical identity for a factory settings mapping."""
    return _setting_identity(settings)


def resolve_sax_netlist(
    recnet: dict[str, dict[str, Any]],
    pdk_cells: Any,
    model_resolver: Callable[[str], Any | None] | Iterable[str] | None = None,
    *,
    model_names: Iterable[str] | None = None,
    cell: Any | None = None,
    cell_wrapper: Callable[..., Any] | None = None,
    setting_serializer: Callable[[object], object] | None = None,
) -> list[str]:
    """Expand unmodeled factories one hierarchy level at a time.

    A component for which ``model_resolver`` returns a model remains a leaf at
    any depth. Otherwise its factory is materialized with the instance settings
    and only its direct child netlist is added. Newly exposed children go
    through the same decision, so separate branches can stop at different
    semantic boundaries. Setting variants retain separate circuit definitions
    (for example ``ring_single_v0`` and ``ring_single_v1``).

    Existing circuit definitions are treated as already expanded. Callers with
    recursive netlists must first remove definitions below modeled components.

    Args:
        recnet: SAX RecursiveNetlist dict, mutated in place.
        pdk_cells: PDK cell namespace (for example ``cspdk.si220.cband.cells``).
        model_resolver: Callable returning a model or ``None`` for a component.
        model_names: Deprecated iterable of modeled component names.
        cell: Materialized layout cell whose KLayout registry owns ``recnet``.
        cell_wrapper: Wrapper that provides ``get_netlist`` for registry cells.
        setting_serializer: Serializer used to match materialized cell settings.

    Returns:
        Sorted top-level leaf component names considered for expansion.

    """
    top_name = next(iter(recnet), None)
    top_instances = (
        ((recnet.get(top_name) or {}).get("instances") or {})
        if top_name is not None
        else {}
    )
    sub_netlists = set(recnet) - ({top_name} if top_name is not None else set())
    leaf_components = sorted(
        {
            component
            for name, info in top_instances.items()
            if (component := info.get("component", name)) not in sub_netlists
        }
    )
    legacy_model_names = None
    if model_names is not None:
        if model_resolver is not None:
            raise TypeError("pass either model_resolver or model_names, not both")
        legacy_model_names = set(model_names)
        warnings.warn(
            "model_names is deprecated; pass model_resolver instead",
            DeprecationWarning,
            stacklevel=2,
        )
    elif model_resolver is not None and not callable(model_resolver):
        legacy_model_names = set(model_resolver)
        model_resolver = None
        warnings.warn(
            "passing model_names positionally is deprecated; "
            "pass model_resolver instead",
            DeprecationWarning,
            stacklevel=2,
        )
    resolver = model_resolver if callable(model_resolver) else None

    modeled_components: dict[str, bool] = {}

    def is_modeled(component: str) -> bool:
        """Resolve each component model at most once per expansion."""
        if legacy_model_names is not None:
            return component in legacy_model_names
        if resolver is None:
            return False
        try:
            return modeled_components[component]
        except KeyError:
            pass
        modeled = resolver(component) is not None
        modeled_components[component] = modeled
        return modeled

    factory_cache: dict[str, Callable[..., Any] | None] = {}

    def get_factory(component: str) -> Callable[..., Any] | None:
        if pdk_cells is None:
            return None
        try:
            return factory_cache[component]
        except KeyError:
            pass
        factory = getattr(pdk_cells, component, None)
        factory_cache[component] = factory
        return factory

    reserved_components = {
        info.get("component", instance_name)
        for netlist in recnet.values()
        for instance_name, info in ((netlist or {}).get("instances") or {}).items()
    }
    pending = deque(recnet)
    queued = set(recnet)
    processed: set[str] = set()
    variants: dict[str, dict[tuple, str]] = {}
    variant_indices: dict[str, int] = {}
    unexpandable: set[tuple[str, tuple]] = set()
    generated_references: dict[str, list[dict[str, Any]]] = {}
    resolver_reference_ids: set[int] = set()
    generated_owners: dict[str, _GeneratedCircuit] = {}
    # Serialized names are only unique within one KLayout context.
    kcl = getattr(cell, "kcl", None)
    wrapper_type = cell_wrapper or type(cell)
    initial_context = _CellContext(kcl, wrapper_type) if kcl is not None else None
    circuit_contexts = (
        dict.fromkeys(recnet, initial_context) if initial_context is not None else {}
    )
    accepted_params: dict[str, set[str] | None] = {}
    failed_components: set[str] = set()

    def enqueue(circuit_name: str) -> None:
        if circuit_name not in processed and circuit_name not in queued:
            pending.append(circuit_name)
            queued.add(circuit_name)

    def allocate_variant_name(component: str) -> str:
        """Allocate a circuit name without shadowing models or PDK factories."""
        index = variant_indices.get(component, 0)
        while True:
            candidate = f"{component}_v{index}"
            if (
                candidate not in recnet
                and candidate not in reserved_components
                and get_factory(candidate) is None
                and not is_modeled(candidate)
            ):
                variant_indices[component] = index + 1
                return candidate
            index += 1

    def record_generated_reference(circuit_name: str, ref_info: dict[str, Any]) -> None:
        resolver_reference_ids.add(id(ref_info))
        reserved_components.add(circuit_name)
        generated_references.setdefault(circuit_name, []).append(ref_info)

    def rename_generated_circuit(circuit_name: str) -> str:
        component, key = generated_owners.pop(circuit_name)
        replacement = allocate_variant_name(component)
        recnet[replacement] = recnet.pop(circuit_name)
        generated_owners[replacement] = _GeneratedCircuit(component, key)
        variants[component][key] = replacement
        reserved_components.add(replacement)

        if context := circuit_contexts.pop(circuit_name, None):
            circuit_contexts[replacement] = context

        was_processed = circuit_name in processed
        processed.discard(circuit_name)
        if was_processed:
            processed.add(replacement)
        else:
            enqueue(replacement)

        references = generated_references.pop(circuit_name, [])
        for ref_info in references:
            ref_info["component"] = replacement
        if references:
            generated_references[replacement] = references
        return replacement

    def find_materialized_cell(
        context: _CellContext | None, component: str
    ) -> tuple[Any, int] | None:
        if context is None:
            return None
        try:
            kcl, wrapper_type = context
            cell = kcl[component]
        except (ImportError, KeyError, ValueError):
            return None
        if hasattr(cell, "get_netlist"):
            return cell, id(kcl)
        if (base := getattr(cell, "base", None)) is None:
            return None
        try:
            wrapped = wrapper_type(base=base)
        except (TypeError, ValueError):
            return None
        return (wrapped, id(kcl)) if hasattr(wrapped, "get_netlist") else None

    def cell_settings_match(
        actual: dict[str, object],
        actual_identities: dict[str, tuple],
        serialized_actual_identities: dict[str, tuple],
        expected: dict[str, object],
    ) -> bool:
        for key, value in expected.items():
            try:
                actual_identity = actual_identities[key]
            except KeyError:
                try:
                    actual_value = actual[key]
                except KeyError:
                    return False
                actual_value = _to_python(actual_value)
                actual_identity = _setting_identity(actual_value)
                actual_identities[key] = actual_identity
            if _setting_identity(value) == actual_identity:
                continue
            if setting_serializer is None:
                return False
            try:
                serialized_actual_identity = serialized_actual_identities[key]
            except KeyError:
                serialized_actual_identity = _setting_identity(
                    _to_python(actual[key]), serializer=setting_serializer
                )
                serialized_actual_identities[key] = serialized_actual_identity
            if (
                _setting_identity(value, serializer=setting_serializer)
                != serialized_actual_identity
            ):
                return False
        return True

    while pending:
        circuit_name = pending.popleft()
        queued.discard(circuit_name)
        if circuit_name in processed or circuit_name not in recnet:
            continue
        processed.add(circuit_name)
        circuit_context = circuit_contexts.get(circuit_name)

        instances = (recnet[circuit_name] or {}).get("instances") or {}
        comp_instances: dict[str, list[dict[str, Any]]] = {}
        for inst_name, inst_info in instances.items():
            component = inst_info.get("component", inst_name)
            reserved_components.add(component)
            owner = generated_owners.get(component)
            if (
                owner is not None
                and owner.component != component
                and id(inst_info) not in resolver_reference_ids
            ):
                # A real child can claim a variant name allocated before it was visible.
                rename_generated_circuit(component)
                owner = None
            if is_modeled(component):
                continue
            if component in recnet and (owner is None or owner.component != component):
                enqueue(component)
                continue
            if pdk_cells is not None:
                comp_instances.setdefault(component, []).append(inst_info)

        for component, inst_list in comp_instances.items():
            cell_fn = get_factory(component)
            materialized = (
                find_materialized_cell(circuit_context, component)
                if cell_fn is None
                else None
            )
            if cell_fn is None and materialized is None:
                if component in generated_owners:
                    rename_generated_circuit(component)
                failed_components.add(component)
                continue
            materialized_cell, source_id = (
                materialized if materialized is not None else (None, None)
            )
            actual_settings: dict[str, object] = {}
            actual_setting_identities: dict[str, tuple] = {}
            serialized_actual_setting_identities: dict[str, tuple] = {}
            if materialized_cell is not None:
                cell_settings = getattr(materialized_cell, "settings", None)
                model_dump = getattr(cell_settings, "model_dump", None)
                if callable(model_dump):
                    actual_settings = model_dump()

            if cell_fn is None:
                accepted = set(actual_settings)
            else:
                try:
                    accepted = accepted_params[component]
                except KeyError:
                    accepted_params[component] = _accepted_params(cell_fn)
                    accepted = accepted_params[component]
            setting_groups: dict[tuple, _SettingGroup] = {}
            for inst_info in inst_list:
                coerced = _coerce_settings(
                    inst_info.get("settings", {}), accepted=accepted
                )
                key = (source_id, _settings_identity(coerced))
                try:
                    setting_groups[key].references.append(inst_info)
                except KeyError:
                    setting_groups[key] = _SettingGroup(coerced, [inst_info])

            cache = variants.setdefault(component, {})
            needs_variants = (
                len(cache) > 1
                or len(setting_groups) > 1
                or (bool(cache) and next(iter(setting_groups)) not in cache)
            )

            for key, group in setting_groups.items():
                try:
                    variant_name = cache[key]
                except KeyError:
                    pass
                else:
                    for ref_info in group.references:
                        ref_info["component"] = variant_name
                        record_generated_reference(variant_name, ref_info)
                    continue

                if needs_variants and len(cache) == 1 and component in cache.values():
                    rename_generated_circuit(component)

                if (component, key) in unexpandable:
                    continue

                if materialized_cell is not None and not cell_settings_match(
                    actual_settings,
                    actual_setting_identities,
                    serialized_actual_setting_identities,
                    group.settings,
                ):
                    _LOG.warning(
                        "%s: materialized cell settings do not match %r, skipping",
                        component,
                        group.settings,
                    )
                    unexpandable.add((component, key))
                    failed_components.add(component)
                    continue

                try:
                    cell = (
                        materialized_cell
                        if materialized_cell is not None
                        else cell_fn(**group.settings)
                    )
                except Exception:
                    _LOG.warning(
                        "%s: cell_fn(**%r) failed, skipping",
                        component,
                        group.settings,
                        exc_info=True,
                    )
                    unexpandable.add((component, key))
                    failed_components.add(component)
                    continue

                try:
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore")
                        direct = cell.get_netlist(recursive=False)
                except Exception:
                    _LOG.warning(
                        "%s: get_netlist failed, skipping",
                        component,
                        exc_info=True,
                    )
                    unexpandable.add((component, key))
                    failed_components.add(component)
                    continue

                if not direct.get("instances"):
                    unexpandable.add((component, key))
                    failed_components.add(component)
                    continue

                reserved_components.update(
                    child_info.get("component", child_name)
                    for child_name, child_info in direct["instances"].items()
                )

                if needs_variants or cache or component in failed_components:
                    variant_name = allocate_variant_name(component)
                else:
                    variant_name = component
                recnet[variant_name] = direct
                cache[key] = variant_name
                generated_owners[variant_name] = _GeneratedCircuit(component, key)
                if (kcl := getattr(cell, "kcl", None)) is not None:
                    circuit_contexts[variant_name] = _CellContext(kcl, type(cell))
                for ref_info in group.references:
                    ref_info["component"] = variant_name
                    record_generated_reference(variant_name, ref_info)
                enqueue(variant_name)

    return leaf_components


class NyanCADMixin:
    """Mixin providing NyanCAD integration for InSpice netlist objects."""

    def _select_model_entry(self, model_def, sim):
        """Select a model entry compatible with the target simulator.

        Each simulator only accepts specific languages (see ``_SIM_LANGUAGES``).
        Priority: exact implementation match first, then first language-compatible
        entry. Returns None when no compatible entry exists.
        """
        entries = model_def.get("models", [])
        if not entries:
            return None
        accepted = _SIM_LANGUAGES.get(sim.lower(), ("spice",))
        for entry in entries:
            if entry.get("implementation", "").lower() == sim.lower():
                return entry
        for entry in entries:
            if entry.get("language") in accepted:
                return entry
        return None

    _DC_OFFSET_VARIANTS = {"sin", "pulse"}

    def _add_source(self, name, node_plus, node_minus, props, *, is_voltage):
        """Add a voltage or current source, dispatching to structural InSpice
        classes when ``props["tran"]`` is a tagged variant map.
        """
        dc = props.get("dc")
        ac = props.get("ac")
        tran = props.get("tran")

        if isinstance(tran, dict) and tran.get("type"):
            variant = tran["type"]
            param_map = _TRAN_PARAM_MAP.get(variant, {})
            kind = "voltage" if is_voltage else "current"
            cls = _TRAN_SOURCE_CLASSES.get(variant, {}).get(kind)
            if cls:
                kwargs = {}
                for tran_key, inspice_kwarg in param_map.items():
                    val = _parse_spice_value(tran.get(tran_key))
                    if val is not None:
                        kwargs[inspice_kwarg] = val
                if variant in self._DC_OFFSET_VARIANTS and dc is not None:
                    kwargs["dc_offset"] = _parse_spice_value(dc) or 0
                if variant in self._DC_OFFSET_VARIANTS and ac is not None:
                    ac_val = _parse_spice_value(ac)
                    if ac_val is not None:
                        kwargs["ac_magnitude"] = ac_val
                method = getattr(self, cls.__name__)
                method(name, node_plus, node_minus, **kwargs)
                return

        fn = self.V if is_voltage else self.I
        fn(name, node_plus, node_minus, dc, ac)

    def populate_from_nyancad(self, docs, models, corners=None, sim="NgSpice"):
        """Populate this netlist with elements from NyanCAD docs.

        Each device doc carries its net assignments in ``dev['nets']`` (written
        by the ClojureScript editor). Devices without ``nets`` — disconnected,
        or legacy data not yet re-annotated — are skipped.
        """
        self.used_models = set()
        for dev_id, dev in docs.items():
            if dev.get("type") in STRUCTURAL_TYPES:
                continue
            ports = dev.get("nets")
            if not ports:
                continue
            ports = {k: (self.gnd if v == "GND" else v) for k, v in ports.items()}
            self._add_nyancad_element(dev_id, dev, ports, models, corners, sim)

    def _add_nyancad_element(self, dev_id, dev, ports, models, corners, sim):
        """Add a single NyanCAD element to this netlist."""
        device_type = dev["type"]
        name = dev.get("name") or dev_id.replace(
            ":", "_"
        )  # InSpice names can't have colons
        props = dev.get("props", {}).copy()

        model_id = model_key(dev.get("model"))
        model_use_x = False
        model_name = None

        selected_entry = None
        port_order = None

        if model_id and model_id in models:
            self.used_models.add(model_id)
            model_def = models[model_id]
            model_name = model_def["name"]

            # Select the best SPICE model entry for this simulator
            selected_entry = self._select_model_entry(model_def, sim)
            if selected_entry:
                props["model"] = model_name
                spice_type = selected_entry.get("spice-type", "")
                model_use_x = bool(spice_type)
                # If the entry has its own name, use it as the model reference
                if selected_entry.get("name"):
                    model_name = selected_entry["name"]
                    props["model"] = model_name
                # If the entry specifies a port order, use it
                if selected_entry.get("port-order"):
                    port_order = selected_entry["port-order"]
                # Apply params mapping (replaces device props with evaluated model
                # params)
                if selected_entry.get("params"):
                    # Merge model default props under device instance props
                    defaults = {
                        p["name"]: p["default"]
                        for p in model_def.get("props", [])
                        if p.get("name") and p.get("default") is not None
                    }
                    merged = {**defaults, **dev.get("props", {})}
                    props = _eval_params(selected_entry["params"], merged, sim)
            else:
                for mp in model_def.get("props", []):
                    if mp.get("name") and mp.get("default") is not None:
                        props.setdefault(mp["name"], mp["default"])
                props.setdefault("model", model_name)

        # Drop params with no value: an empty override is meaningless to SPICE
        # and would emit invalid `param=` lines, clobbering the model/subckt's
        # own default. Only ""/None are dropped — 0, False and other native-JSON
        # values are valid and kept. The "model" key is non-empty and preserved.
        props = {k: v for k, v in props.items() if v != "" and v is not None}

        # Helper to get port by name. The editor annotates every port with
        # a net — disconnected pins get their own generated netN — so the
        # lookup is total. Built-in device symbols label pins with uppercase
        # chars (D/G/S/B), while a model's port-order may use any case (sky130
        # subckts migrated by SpiceArmyKnife.jl use lowercase d/g/s/b). SPICE is
        # case-insensitive, so resolve port names case-insensitively.
        ports_ci = {k.lower(): v for k, v in ports.items()}

        def p(port_name):
            return ports_ci[port_name.lower()]

        # Map SPICE element type letters to InSpice methods
        _spice_type_map = {
            "R": self.R,
            "C": self.C,
            "L": self.L,
            "D": self.D,
            "V": self.V,
            "I": self.I,
            "M": self.M,
            "Q": self.Q,
            "X": self.X,
            "SUBCKT": self.X,
        }

        # If the model entry specifies a spice-type, use it to pick the element method
        if model_use_x and selected_entry:
            spice_type = selected_entry.get("spice-type", "")
            subcircuit_model = props.pop("model", model_name)
            element_fn = _spice_type_map.get(spice_type.upper(), self.X)

            # Build positional port list from port-order or default (sorted by name)
            if port_order:
                port_list = [p(pn) for pn in port_order]
            elif model_id and model_id in models:
                port_list = [
                    p(pn) for pn in default_port_order(models[model_id]["ports"])
                ]
            else:
                # Fallback for built-in types: use known default port orders
                port_list = self._default_port_list(device_type, ports, p)

            if spice_type.upper() in ("X", "SUBCKT"):
                element_fn(name, subcircuit_model, *port_list, **props)
            else:
                element_fn(name, *port_list, **props)
            return

        # Default handling for built-in device types (no spice-type override)
        if device_type == "resistor":
            resistance = props.get("resistance")
            self.R(name, p("P"), p("N"), resistance)

        elif device_type == "capacitor":
            capacitance = props.get("capacitance")
            self.C(name, p("P"), p("N"), capacitance)

        elif device_type == "inductor":
            inductance = props.get("inductance")
            self.L(name, p("P"), p("N"), inductance)

        elif device_type == "diode":
            self.D(name, p("P"), p("N"), **props)

        elif device_type == "vsource":
            self._add_source(name, p("P"), p("N"), props, is_voltage=True)

        elif device_type == "isource":
            self._add_source(name, p("P"), p("N"), props, is_voltage=False)

        elif device_type in {"pmos", "nmos"}:
            bulk_node = p("B") if "B" in ports else self.gnd
            self.M(name, p("D"), p("G"), p("S"), bulk_node, **props)

        elif device_type in {"npn", "pnp"}:
            self.Q(name, p("C"), p("B"), p("E"), **props)

        elif model_id in models:
            port_list = [p(pn) for pn in default_port_order(models[model_id]["ports"])]
            params = props.copy()
            model_name = params.pop("model", model_id)
            self.X(name, model_name, *port_list, **params)

    @staticmethod
    def _default_port_list(device_type, ports, p):
        """Return default positional port list for built-in device types."""
        if device_type in {
            "resistor",
            "capacitor",
            "inductor",
            "vsource",
            "isource",
            "diode",
        }:
            return [p("P"), p("N")]
        if device_type in {"pmos", "nmos"}:
            bulk = p("B") if "B" in ports else None
            return [p("D"), p("G"), p("S")] + ([bulk] if bulk else [])
        if device_type in {"npn", "pnp"}:
            return [p("C"), p("B"), p("E")]
        return []


class NyanCircuit(NyanCADMixin, Circuit):
    """InSpice Circuit populated from NyanCAD schematic data."""

    def __init__(self, name, schem, corners=None, sim="NgSpice", **kwargs) -> None:
        """Create InSpice Circuit from full NyanCAD schematic data.

        Parameters:
        - name: Top-level schematic name (key in schem)
        - schem: Full schematic dictionary with models and subcircuits
        - corners: List of preferred corner/section names (e.g., ['mos_ff', 'cap_bcs']).
                   Each model entry uses the first match from its sections list,
                   falling back to sections[0] (typical).
        - sim: Simulator name for model entry selection
        """
        super().__init__(title="schematic", **kwargs)
        self._pending_downloads = []  # List of (url, dest_path) tuples

        models = schem["models"]

        # First populate main circuit elements to collect used models
        self.populate_from_nyancad(schem[name], models, corners, sim)

        # Then process only the used models: create subcircuits for schematic
        # models, add SPICE for others
        for model_key_str in self.used_models:
            model_def = models[model_key_str]
            # Extract bare model ID for schematic lookup (models dict keys always
            # have "models:" prefix)
            model_id = bare_id(model_key_str)
            # Skip the top-level circuit itself
            if model_id != name:
                # Create subcircuits for schematic models or SPICE models with
                # model entries
                if model_id in schem:
                    # Create subcircuit for models with schematic implementations
                    docs = schem[model_id]
                    nodes = default_port_order(model_def["ports"])
                    # Pass model parameter definitions as subcircuit parameters
                    # (default to 0)
                    model_params = {
                        p["name"]: p.get("default", "0")
                        for p in model_def.get("props", [])
                        if p.get("name")
                    }
                    subcircuit = NyanSubCircuit(
                        model_def["name"],
                        nodes,
                        docs,
                        models,
                        corners,
                        sim,
                        **model_params,
                    )
                    self.subcircuit(subcircuit)
                else:
                    # Add SPICE code / library includes for model entries
                    entry = self._select_model_entry(model_def, sim)
                    if entry:
                        # Handle library includes
                        if entry.get("library"):
                            library = entry["library"]
                            resolved = self._resolve_url(library)
                            if resolved is not None:
                                library = str(resolved)
                            section = _select_corner(entry.get("sections"), corners)
                            if section:
                                self.lib(library, section)
                            else:
                                self.include(library)
                        # Handle inline SPICE code
                        code = entry.get("code", "").strip()
                        if code:
                            self.add_spice_code(code)

    async def download_includes(self):
        """Download all pending URL includes."""
        for url, dest_path, entrypoint in self._pending_downloads:
            try:
                print(f"Downloading: {url}")
                await download_file(url, dest_path)
                # Extract if archive with entrypoint
                if entrypoint:
                    cache_dir = dest_path.parent
                    base_name = dest_path.stem
                    extract_dir = cache_dir / base_name
                    if not extract_dir.exists():
                        shutil.unpack_archive(str(dest_path), str(extract_dir))
            except Exception as e:
                print(f"Warning: Failed to download/extract {url}: {e}")
                # Continue with other downloads
        self._pending_downloads.clear()

    def add_spice_code(self, spice_code: str):
        """Add SPICE code to circuit.

        Try structured parsing first, fallback to raw injection.

        Args:
            spice_code: Raw SPICE code (models, subcircuits, etc.)
        """
        try:
            # Parse SPICE code
            spice_source = SpiceSource(spice_code, title_line=False)

            builder = Builder()
            parsed_circuit = builder.translate(spice_source)
            # Copy all content to self (models, subcircuits, elements)
            parsed_circuit.copy_to(self)
            # copy includes and parameters
            for include in parsed_circuit._includes:
                self.include(include)
            for path, section in parsed_circuit._libs:
                self.lib(path, section)
            for name, value in parsed_circuit._parameters.items():
                self.parameter(name, value)

        except Exception as e:
            import traceback

            print(f"SPICE parsing failed: {type(e).__name__}: {e}")
            print(f"Traceback:\n{traceback.format_exc()}")
            print("Falling back to raw SPICE injection")
            # Append to raw_spice
            self.raw_spice += "\n" + spice_code.strip() + "\n"

    def _resolve_url(self, path_str):
        """Resolve an http(s) library/include URL to a local cache path and register
        the archive for download. Returns the resolved Path, or None when path_str is
        not an http(s) URL (caller keeps the original path).
        """
        parsed = urlparse(path_str)
        if parsed.scheme not in ("http", "https"):
            return None
        archive_url = f"{parsed.scheme}://{parsed.netloc}{parsed.path}"
        entrypoint = parsed.fragment
        cache_dir = Path(tempfile.gettempdir()) / "nyancad_archive_cache"
        cache_dir.mkdir(exist_ok=True)
        url_hash = hashlib.md5(archive_url.encode()).hexdigest()[:8]  # noqa: S324
        cached_file = cache_dir / f"{url_hash}_{Path(parsed.path).name}"
        if not cached_file.exists() and not any(
            u == archive_url for u, _, _ in self._pending_downloads
        ):
            self._pending_downloads.append((archive_url, cached_file, entrypoint))
        return (
            (cache_dir / cached_file.stem / entrypoint) if entrypoint else cached_file
        )


class NyanSubCircuit(NyanCADMixin, SubCircuit):
    """InSpice SubCircuit populated from NyanCAD docs."""

    def __init__(
        self, name, nodes, docs, models, corners=None, sim="NgSpice", **kwargs
    ) -> None:
        """Create InSpice SubCircuit from NyanCAD docs.

        Parameters:
        - name: Subcircuit name
        - nodes: List of external node names
        - docs: NyanCAD document dictionary for this subcircuit
        - models: Model definitions
        - corners: List of preferred corner/section names (or None for defaults)
        - sim: Simulator name
        """
        super().__init__(name, *nodes, **kwargs)
        self.populate_from_nyancad(docs, models, corners, sim)


async def inspice_netlist(
    name, schem, corners=None, sim="NgSpice", *, corner=None, **kwargs
):
    """Convenience function to create InSpice Circuit from NyanCAD schematic.

    Parameters:
    - name: Top-level schematic name
    - schem: Full schematic dictionary
    - corners: List of preferred corner/section names (e.g., ['mos_ff', 'cap_bcs']).
               Each model entry uses the first match from its sections list,
               falling back to sections[0] (typical). None uses all defaults.
    - sim: Simulator name
    - corner: Deprecated single corner string (use corners instead)
    - **kwargs: Additional Circuit constructor arguments

    Returns:
    - NyanCircuit instance

    Usage:
    ```
    circuit = await inspice_netlist("top$top", schem_data)
    circuit = await inspice_netlist(
        "top$top", schem_data, corners=["mos_ff", "cap_bcs"]
    )
    ```
    """
    if corner is not None and corners is None:
        corners = [corner]
    circuit = NyanCircuit(name, schem, corners, sim, **kwargs)
    await circuit.download_includes()
    return circuit


async def inspice_netlist_from_api(
    api, name, corners=None, sim="NgSpice", *, corner=None, **kwargs
):
    """Create InSpice Circuit from any SchematicAPI source (Bridge or Server).

    Parameters:
    - api: SchematicAPI instance (BridgeAPI or ServerAPI)
    - name: Top-level schematic name
    - corners: List of preferred corner/section names (or None for defaults)
    - sim: Simulator name
    - corner: Deprecated single corner string (use corners instead)
    - **kwargs: Additional Circuit constructor arguments

    Returns:
    - NyanCircuit instance

    Usage with BridgeAPI:
    ```
    from nyancad.api import BridgeAPI

    bridge = schematic_bridge()
    api = BridgeAPI(bridge)
    circuit = await inspice_netlist_from_api(api, "my_circuit")
    ```

    Usage with ServerAPI:
    ```
    from nyancad.api import ServerAPI

    async with ServerAPI(
        "https://api.nyancad.com/userdb-alice", username="alice", password="secret"
    ) as api:
        circuit = await inspice_netlist_from_api(api, "my_circuit")
    ```
    """
    if corner is not None and corners is None:
        corners = [corner]
    _seq, schem = await api.get_all_schem_docs(name)
    circuit = NyanCircuit(name, schem, corners, sim, **kwargs)
    await circuit.download_includes()
    return circuit
