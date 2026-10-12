# SPDX-FileCopyrightText: 2022 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""SPICE netlisting, imported only by explicit SPICE consumers."""

import hashlib
import re
import shutil

# package download dependencies
import sys
import tempfile
from pathlib import Path
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


from nyancad.identity import (
    STRUCTURAL_TYPES,
    VALID_IDENT_RE,
    bare_id,
    default_port_order,
    model_key,
)


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
