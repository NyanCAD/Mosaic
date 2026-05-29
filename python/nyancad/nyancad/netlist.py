# SPDX-FileCopyrightText: 2022 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Fetch schematics from CouchDB and generate SPICE netlists."""

import hashlib
import re
import shutil

# package download dependencies
import sys
import tempfile
from pathlib import Path
from typing import NamedTuple
from urllib.parse import urlparse

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


def invert_kf_nets(components, port_devices):
    """Build ``{net_name: {"inst": [...], "ports": [...]}}`` from Mosaic nets."""
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


def kfnetlist_from_nyancad(name, schem, *, kcl="PDK"):
    """Build a ``kfnetlist.Netlist`` directly from Mosaic ``nets``.

    This is intentionally topology-only: it does not require layout placement,
    DSchematic conversion, kfactory, or a PDK runtime.
    """
    from kfnetlist import Netlist, PortRef

    docs = schem.get(name, {})
    models = schem.get("models", {})

    components = []
    port_devices = {}
    for dev_id, dev in docs.items():
        if dev.get("type") == "port":
            port_devices[str(dev.get("name") or dev_id)] = dev
        elif dev.get("type") not in STRUCTURAL_TYPES:
            components.append((_safe_ident(dev_id), dev))

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

    net_map = invert_kf_nets(components, port_devices)
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


def _coerce_settings(raw, factory=None):
    """Coerce string prop values to Python types using factory signature.

    Adapted from ``gfp_kfactory.convert._coerce_settings`` to avoid
    a hard dependency on gfp-kfactory (not published on PyPI).
    """
    import inspect
    import types
    from typing import Annotated, Union, get_args, get_origin

    _drop = inspect.Parameter.empty

    def _base_types(annotation):
        if annotation is _drop:
            return set()
        if get_origin(annotation) is Annotated:
            annotation = get_args(annotation)[0]
        if isinstance(annotation, types.UnionType) or get_origin(annotation) is Union:
            return set(get_args(annotation))
        if isinstance(annotation, type):
            return {annotation}
        return set()

    def _coerce_value(v, expected):
        if v == "None":
            return None if (type(None) in expected or not expected) else _drop
        if bool in expected:
            if v == "True":
                return True
            if v == "False":
                return False
        if int in expected:
            try:
                return int(v)
            except ValueError:
                pass
        if float in expected:
            try:
                return float(v)
            except ValueError:
                pass
        if str in expected:
            return v
        if not expected:
            if v == "True":
                return True
            if v == "False":
                return False
            try:
                return int(v)
            except ValueError:
                pass
            try:
                return float(v)
            except ValueError:
                pass
            return v
        return _drop

    param_types = {}
    if factory is not None:
        try:
            sig = inspect.signature(factory)
            param_types = {
                name: _base_types(p.annotation) for name, p in sig.parameters.items()
            }
        except (ValueError, TypeError):
            pass

    out = {}
    for k, v in raw.items():
        if not isinstance(v, str):
            out[k] = v
            continue
        coerced = _coerce_value(v, param_types.get(k, set()))
        if coerced is not _drop:
            out[k] = coerced
    return out


def resolve_sax_netlist(recnet, pdk_cells):
    """Expand hierarchical components in a SAX RecursiveNetlist using PDK cells.

    For each component referenced by top-level instances but not already
    defined as a sub-netlist or available as a model, creates gdsfactory
    cells with per-instance coerced settings and merges their netlists.
    Instances with different settings get separate variant sub-netlists
    (e.g. ``ring_single_v0``, ``ring_single_v1``).

    Args:
        recnet: SAX RecursiveNetlist dict (mutated in place).
        pdk_cells: PDK cells module (e.g. ``cspdk.si220.cband.cells``).

    Returns:
        List of leaf component names that were expanded.
    """
    top_name = next(iter(recnet))
    top = recnet.get(top_name, {})
    instances = top.get("instances", {})
    sub_netlists = set(recnet.keys()) - {top_name}

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

    comp_instances = {}
    for inst_name, inst_info in instances.items():
        comp = inst_info.get("component", inst_name)
        if comp not in leaf_components:
            continue
        comp_instances.setdefault(comp, []).append((inst_name, inst_info))

    for comp, inst_list in comp_instances.items():
        cell_fn = getattr(pdk_cells, comp, None)
        if cell_fn is None:
            continue

        unique_keys = {
            tuple(
                sorted(
                    _coerce_settings(ii.get("settings", {}), factory=cell_fn).items()
                )
            )
            for _, ii in inst_list
        }
        needs_variants = len(unique_keys) > 1
        variant_map = {}
        idx = 0

        for _inst_name, inst_info in inst_list:
            raw = inst_info.get("settings", {})
            coerced = _coerce_settings(raw, factory=cell_fn)
            key = tuple(sorted(coerced.items()))

            if key not in variant_map:
                try:
                    cell = cell_fn(**coerced)
                except Exception:
                    try:
                        cell = cell_fn()
                    except Exception:  # noqa: S112
                        continue

                try:
                    sub = cell.get_netlist(recursive=True)
                except Exception:  # noqa: S112
                    continue

                variant_name = f"{comp}_v{idx}" if needs_variants else comp
                idx += 1

                if comp in sub and variant_name != comp:
                    sub[variant_name] = sub.pop(comp)

                recnet.update(sub)
                variant_map[key] = variant_name

            if key in variant_map:
                inst_info["component"] = variant_map[key]

    return leaf_components


class NyanCADMixin:
    """Mixin providing NyanCAD integration for InSpice netlist objects."""

    def _select_model_entry(self, model_def, sim):
        """Select a SPICE model entry from the flat models list.

        Reads from model_def['models'] (flat list), filters for language=='spice',
        and matches by implementation name (case-insensitive).

        Returns:
            dict: selected model entry or None if no SPICE entries
        """
        entries = model_def.get("models", [])
        spice_entries = [e for e in entries if e.get("language") == "spice"]
        if not spice_entries:
            return None

        # Look for implementation matching sim parameter, fallback to first spice entry
        for entry in spice_entries:
            if entry.get("implementation", "").lower() == sim.lower():
                return entry

        return spice_entries[0]  # Use first as default

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

        # Helper to get port by name. The editor annotates every port with
        # a net — disconnected pins get their own generated netN — so the
        # lookup is total and a plain dict access is correct.
        def p(port_name):
            return ports[port_name]

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
            dc = props.get("dc")
            ac = props.get("ac")
            tran = props.get("tran")
            self.V(name, p("P"), p("N"), dc, ac, tran)

        elif device_type == "isource":
            dc = props.get("dc")
            ac = props.get("ac")
            tran = props.get("tran")
            self.I(name, p("P"), p("N"), dc, ac, tran)

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
                            section = _select_corner(entry.get("sections"), corners)
                            if section:
                                self.lib(entry["library"], section)
                            else:
                                self.include(entry["library"])
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

            # Resolve URL includes in the SpiceSource before building
            self.resolve_url_includes(spice_source)

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

    def _resolve_url_path(self, path_obj):
        """Helper to resolve a single URL path to a local file path."""
        cache_dir = Path(tempfile.gettempdir()) / "nyancad_archive_cache"
        cache_dir.mkdir(exist_ok=True)

        # Get the raw path from the AST
        ast = path_obj._ast
        path_str = str(next(iter(ast))).strip("\"'")

        parsed = urlparse(path_str)

        if parsed.scheme in ("http", "https"):
            archive_url = f"{parsed.scheme}://{parsed.netloc}{parsed.path}"
            entrypoint = parsed.fragment

            # Generate cache key from URL
            url_hash = hashlib.md5(archive_url.encode()).hexdigest()[:8]  # noqa: S324
            filename = Path(parsed.path).name
            cached_file = cache_dir / f"{url_hash}_{filename}"

            # Schedule download if not cached
            if not cached_file.exists():
                self._pending_downloads.append((archive_url, cached_file, entrypoint))

            # Compute the expected resolved path (will exist after download/extract)
            if entrypoint:
                base_name = cached_file.stem
                extract_dir = cache_dir / base_name
                resolved_path = extract_dir / entrypoint
            else:
                # Bare SPICE file - use directly
                resolved_path = cached_file

            # Replace the _path in the object
            path_obj._path = resolved_path

    def resolve_url_includes(self, spice_source):
        """Resolve HTTP includes (archives or bare files) to local paths."""
        # Process includes
        for include in spice_source._includes:
            self._resolve_url_path(include)

        # Also process libs if they exist
        if hasattr(spice_source, "_libs"):
            for lib in spice_source._libs:
                self._resolve_url_path(lib)


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
