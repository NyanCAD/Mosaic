# SPDX-FileCopyrightText: 2022 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""
This module communicates with CouchDB to fetch schematics, and generate SPICE netlists out of them.

"""

import math
from collections import deque, namedtuple
from InSpice.Spice.Netlist import Circuit, SubCircuit
from InSpice.Spice.Parser.HighLevelParser import SpiceSource
from InSpice.Spice.Parser.Translator import Builder

# package download dependencies
import sys
import shutil
import tempfile
import hashlib
from pathlib import Path
from urllib.parse import urlparse

# Conditional imports based on environment
if sys.platform == 'emscripten':  # Pyodide/WASM
    from pyodide.http import pyfetch

    async def download_file(url, dest_path):
        """Download using native Pyodide pyfetch"""
        response = await pyfetch(url)
        if not response.ok:
            raise Exception(f"HTTP {response.status}: {response.status_text}")
        content = await response.bytes()
        Path(dest_path).write_bytes(content)
else:  # Native Python
    import urllib.request

    async def download_file(url, dest_path):
        """Download using urllib"""
        urllib.request.urlretrieve(url, dest_path)

try:
    import py7zr
    shutil.register_unpack_format('7zip', ['.7z'], py7zr.unpack_7zarchive)
except ImportError:
    pass


def model_key(bare_id):
    """
    Convert a bare model ID to a database key with 'models:' prefix.
    Returns None if input is None. Asserts that non-None input is not already prefixed.
    """
    if bare_id is None:
        return None
    
    assert not bare_id.startswith("models:"), \
        f"model_key expects bare ID, got prefixed: {bare_id}"
    
    return f"models:{bare_id}"


def bare_id(model_key_str):
    """
    Extract bare ID from a model database key, removing 'models:' prefix.
    Returns None if input is None. Asserts that non-None input is prefixed.
    """
    if model_key_str is None:
        return None
    
    assert model_key_str.startswith("models:"), \
        f"bare_id expects prefixed model key, got bare ID: {model_key_str}"
    
    return model_key_str[7:]  # Remove 'models:' prefix (7 characters)

class SchemId(namedtuple("SchemId", ["schem", "device"])):
    @classmethod
    def from_string(cls, id):
        schem, dev, *_= id.split(':') + [None]
        return cls(schem, dev)


def shape_ports(shape, port_type='electric'):
    for y, s in enumerate(shape):
        for x, c in enumerate(s):
            if c != ' ':
                yield {'name': c, 'x': x, 'y': y, 'type': port_type}


mosfet_shape = list(shape_ports([
    " D ",
    "GB ",
    " S ",
]))

bjt_shape = list(shape_ports([
    " C ",
    "B  ",
    " E ",
]))


twoport_shape = list(shape_ports([
    " P ",
    "   ",
    " N ",
]))


def rotate(shape, transform, devx, devy):
    a, b, c, d, e, f = transform
    width = max(max(p['x'], p['y']) for p in shape)+1
    mid = width/2-0.5
    res = {}
    for port in shape:
        x = port['x']-mid
        y = port['y']-mid
        nx = a*x+c*y+e
        ny = b*x+d*y+f
        res[round(devx+nx+mid), round(devy+ny+mid)] = port['name']
    return res


def port_perimeter(ports, shape=None):
    """Calculate device perimeter [width, height] based on ports.

    Ports are [{name, side, type}].
    Height is determined by left/right port counts (vertical sides).
    Width is determined by top/bottom port counts (horizontal sides).
    Optional shape parameter: 'amp' constrains aspect ratio.
    """
    by_side = {}
    for p in ports:
        by_side.setdefault(p['side'], []).append(p)
    left_n = len(by_side.get('left', []))
    right_n = len(by_side.get('right', []))
    top_n = len(by_side.get('top', []))
    bottom_n = len(by_side.get('bottom', []))
    raw_height = max(1, left_n, right_n)
    raw_width = max(1, top_n, bottom_n)
    # Widen to odd if parities differ on opposite sides
    height = raw_height + 1 if (left_n % 2 != right_n % 2) and (raw_height % 2 == 0) else raw_height
    base_width = raw_width + 1 if (top_n % 2 != bottom_n % 2) and (raw_width % 2 == 0) else raw_width
    # For amp: ensure minimum width proportional to height
    width = max(base_width, math.ceil(height / 2)) if shape == 'amp' else base_width
    return width, height

def spread_ports(n, size):
    """Spread n ports in size slots. Gap in middle if n < size."""
    if n == size:
        return list(range(1, n + 1))
    elif n == 0:
        return []
    elif n < size:
        mid = (size + 1) // 2
        half = n // 2
        first_half = list(range(1, half + 1))
        second_half = list(range(size - half + 1, size + 1))
        if n % 2 == 1:  # odd
            return first_half + [mid] + second_half
        else:
            return first_half + second_half
    else:
        return list(range(1, n + 1))

def port_locations(ports, shape=None):
    """Calculate port positions from [{name, side, type}].

    Returns list of port dicts with x, y added.
    Optional shape parameter: 'amp' left-aligns top/bottom ports.
    """
    width, height = port_perimeter(ports, shape)
    by_side = {}
    for p in ports:
        by_side.setdefault(p['side'], []).append(p)
    top = by_side.get('top', [])
    bottom = by_side.get('bottom', [])
    left = by_side.get('left', [])
    right = by_side.get('right', [])
    left_ys = spread_ports(len(left), height)
    right_ys = spread_ports(len(right), height)
    # For amp: left-align top/bottom (triangle narrows to right)
    if shape == 'amp':
        top_xs = list(range(1, len(top) + 1))
        bottom_xs = list(range(1, len(bottom) + 1))
    else:
        top_xs = spread_ports(len(top), width)
        bottom_xs = spread_ports(len(bottom), width)
    top_locs = [{**p, 'x': top_xs[i], 'y': 0} for i, p in enumerate(top)]
    bottom_locs = [{**p, 'x': bottom_xs[i], 'y': height + 1} for i, p in enumerate(bottom)]
    left_locs = [{**p, 'x': 0, 'y': left_ys[i]} for i, p in enumerate(left)]
    right_locs = [{**p, 'x': width + 1, 'y': right_ys[i]} for i, p in enumerate(right)]
    return top_locs + bottom_locs + left_locs + right_locs

def getports(doc, models):
    device_type = doc['type']
    x = doc['x']
    y = doc['y']
    tr = doc.get('transform', [1, 0, 0, 1, 0, 0])
    if device_type == 'wire':
        rx = doc.get('rx', 0)
        ry = doc.get('ry', 0)
        return {(x, y): None,
                (x+rx, y+ry): None}
    elif device_type == 'text':
        return {}
    elif device_type == 'port':
        return {(x, y): doc['name']}
    elif device_type in {'nmos', 'pmos'}:
        return rotate(mosfet_shape, tr, x, y)
    elif device_type in {'npn', 'pnp'}:
        return rotate(bjt_shape, tr, x, y)
    elif device_type in {'resistor', 'capacitor', 'inductor', 'vsource', 'isource', 'diode'}:
        return rotate(twoport_shape, tr, x, y)
    else:
        # For user-defined circuit models, use the model field
        model_id = model_key(doc.get('model'))
        if model_id and model_id in models:
            model = models[model_id]
            shape = 'amp' if device_type == 'amp' else None
            return rotate(port_locations(model['ports'], shape), tr, x, y)
        return {}


def port_index(docs, models):
    wire_index = {}
    device_index = {}
    for doc in docs.values():
        device_type = doc['type']
        for (x, y), p in getports(doc, models).items():
            if device_type in {'wire', 'port'}:
                wire_index.setdefault((x, y), []).append(doc)
            else:
                device_index.setdefault((x, y), []).append((p, doc))
                # add a dummy net so two devices can connect directly
                wire_index.setdefault((x, y), []).append({"type": "wire", "x": x, "y": y, "rx": 0, "ry": 0})
    return device_index, wire_index


def wire_net(wireid, docs, models):
    device_index, wire_index = port_index(docs, models)
    netname = None
    net = deque([docs[wireid]]) # all the wires on this net
    while net:
        doc = net.popleft() # take a wire from the net
        device_type = doc['type']
        if device_type == 'wire':
            wirename = doc.get('name')
            if netname == None and wirename != None:
                netname = wirename
            for ploc in getports(doc, models).keys(): # get the wire ends
                # if the wire connects to another wire,
                # that we have not seen, add it to the net
                if ploc in wire_index:
                    net.extend(wire_index.pop(ploc))
        elif device_type == 'port':
            netname = doc.get('name')
        else:
            raise ValueError(device_type)
    return netname

def netlist(docs, models):
    """
    Turn a collection of documents as returned by `get_docs` into a netlist structure.
    Returns a dictionary of device ID: {port: net}
    Usage:
    ```
    async with SchematicService("http://localhost:5984/offline") as service:
        name = "top$top"
        seq, docs = await service.get_all_schem_docs(name)
        print(netlist(docs[name], models))
    ```
    """
    device_index, wire_index = port_index(docs, models)
    nl = {}
    netnum = 0
    while wire_index:  # while there are wires left
        loc, locwires = wire_index.popitem()  # take one
        netname = None
        net = deque(locwires) # all the wires on this net
        netdevs = {} # all the devices on this net
        while net:
            doc = net.popleft() # take a wire from the net
            device_type = doc['type']
            if device_type == 'wire':
                wirename = doc.get('name')
                if netname == None and wirename != None:
                    netname = wirename
                for ploc in getports(doc, models).keys(): # get the wire ends
                    # if the wire connects to another wire,
                    # that we have not seen, add it to the net
                    if ploc in wire_index:
                        net.extend(wire_index.pop(ploc))
                    # if the wire connect to a device, add its port to netdevs
                    if ploc in device_index:
                        for p, dev in device_index[ploc]:
                            netdevs.setdefault(dev['_id'], []).append(p)
            elif device_type == 'port':
                netname = doc.get('name')
            else:
                raise ValueError(device_type)
        if netname == None:
            netname = f"net{netnum}"
            netnum += 1
        for k, v in netdevs.items():
            nl.setdefault(netname, {}).setdefault(k, []).extend(v)
    inl = {}
    for net, devs in nl.items():
        for dev, pts in devs.items():
            for port in pts:
                inl.setdefault(dev, {})[port] = net
    return inl


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


def _eval_params(entry_params, device_props):
    """Evaluate model entry params expressions against device properties.

    When a model entry has a params mapping, it defines the complete set of
    SPICE parameters. Each value is an expression evaluated with device props
    as variables (e.g., "width * 1e-6").

    Args:
        entry_params: dict mapping model param names to expressions
        device_props: dict of device property values

    Returns:
        dict of evaluated model parameters, or device_props if no mapping
    """
    if not entry_params:
        return device_props
    # Convert string prop values to numbers where possible for arithmetic
    locals_dict = {}
    for k, v in device_props.items():
        if isinstance(v, str):
            try:
                locals_dict[k] = float(v)
            except ValueError:
                locals_dict[k] = v
        else:
            locals_dict[k] = v
    result = {}
    for param_name, expr in entry_params.items():
        try:
            result[param_name] = eval(expr, {"__builtins__": {}}, locals_dict)
        except NameError:
            pass  # Variable not set — let the SPICE model use its own default
        except Exception:
            result[param_name] = expr
    return result


class NyanCADMixin:
    """Mixin providing NyanCAD integration for InSpice netlist objects."""
    
    def _select_model_entry(self, model_def, sim):
        """Select a SPICE model entry from the flat models list.

        Reads from model_def['models'] (flat list), filters for language=='spice',
        and matches by implementation name (case-insensitive).

        Returns:
            dict: selected model entry or None if no SPICE entries
        """
        entries = model_def.get('models', [])
        spice_entries = [e for e in entries if e.get('language') == 'spice']
        if not spice_entries:
            return None

        # Look for implementation matching sim parameter, fallback to first spice entry
        for entry in spice_entries:
            if entry.get('implementation', '').lower() == sim.lower():
                return entry

        return spice_entries[0]  # Use first as default
    
    def populate_from_nyancad(self, docs, models, corners=None, sim='NgSpice'):
        """Populate this netlist with elements from NyanCAD docs."""
        self.used_models = set()
        nl = netlist(docs, models)

        for dev_id, ports in nl.items():
            dev = docs[dev_id]
            self._add_nyancad_element(dev_id, dev, ports, models, corners, sim)
    
    def _add_nyancad_element(self, dev_id, dev, ports, models, corners, sim):
        """Add a single NyanCAD element to this netlist."""
        device_type = dev['type']
        name = dev.get('name') or dev_id.replace(':', '_')  # InSpice names can't have colons
        props = dev.get('props', {}).copy()

        model_id = model_key(dev.get('model'))
        model_use_x = False
        model_name = None

        selected_entry = None
        port_order = None

        if model_id and model_id in models:
            self.used_models.add(model_id)
            model_def = models[model_id]
            model_name = model_def['name']
            props['model'] = model_name

            # Select the best SPICE model entry for this simulator
            selected_entry = self._select_model_entry(model_def, sim)
            if selected_entry:
                spice_type = selected_entry.get('spice-type', '')
                model_use_x = bool(spice_type)
                # If the entry has its own name, use it as the model reference
                if selected_entry.get('name'):
                    model_name = selected_entry['name']
                    props['model'] = model_name
                # If the entry specifies a port order, use it
                if selected_entry.get('port-order'):
                    port_order = selected_entry['port-order']
                # Apply params mapping (replaces device props with evaluated model params)
                if selected_entry.get('params'):
                    # Merge model default props under device instance props
                    defaults = {p['name']: p['default']
                                for p in model_def.get('props', [])
                                if p.get('name') and p.get('default') is not None}
                    merged = {**defaults, **dev.get('props', {})}
                    props = _eval_params(selected_entry['params'], merged)

        # Helper to get port by name
        def p(port_name):
            return ports[port_name]

        # Map SPICE element type letters to InSpice methods
        _spice_type_map = {
            'R': self.R, 'C': self.C, 'L': self.L, 'D': self.D,
            'V': self.V, 'I': self.I, 'M': self.M, 'Q': self.Q,
            'X': self.X, 'SUBCKT': self.X,
        }

        # If the model entry specifies a spice-type, use it to pick the element method
        if model_use_x and selected_entry:
            spice_type = selected_entry.get('spice-type', '')
            subcircuit_model = props.pop('model', model_name)
            element_fn = _spice_type_map.get(spice_type.upper(), self.X)

            # Build positional port list from port-order or default geometry
            if port_order:
                port_list = [p(pn) for pn in port_order]
            elif model_id and model_id in models:
                m = models[model_id]
                shape = 'amp' if device_type == 'amp' else None
                port_locs = port_locations(m['ports'], shape)
                port_list = [p(c['name']) for c in port_locs]
            else:
                # Fallback for built-in types: use known default port orders
                port_list = self._default_port_list(device_type, ports, p)

            if spice_type.upper() in ('X', 'SUBCKT'):
                element_fn(name, subcircuit_model, *port_list, **props)
            else:
                element_fn(name, *port_list, **props)
            return

        # Default handling for built-in device types (no spice-type override)
        if device_type == "resistor":
            resistance = props.get('resistance')
            self.R(name, p('P'), p('N'), resistance)

        elif device_type == "capacitor":
            capacitance = props.get('capacitance')
            self.C(name, p('P'), p('N'), capacitance)

        elif device_type == "inductor":
            inductance = props.get('inductance')
            self.L(name, p('P'), p('N'), inductance)

        elif device_type == "diode":
            self.D(name, p('P'), p('N'), **props)

        elif device_type == "vsource":
            dc = props.get('dc')
            ac = props.get('ac')
            tran = props.get('tran')
            self.V(name, p('P'), p('N'), dc, ac, tran)

        elif device_type == "isource":
            dc = props.get('dc')
            ac = props.get('ac')
            tran = props.get('tran')
            self.I(name, p('P'), p('N'), dc, ac, tran)

        elif device_type in {"pmos", "nmos"}:
            bulk_node = p('B') if 'B' in ports else self.gnd
            self.M(name, p('D'), p('G'), p('S'), bulk_node, **props)

        elif device_type in {"npn", "pnp"}:
            self.Q(name, p('C'), p('B'), p('E'), **props)

        else:  # subcircuit
            if model_id in models:
                m = models[model_id]
                shape = 'amp' if device_type == 'amp' else None
                port_locs = port_locations(m['ports'], shape)
                port_list = [p(c['name']) for c in port_locs]
                params = props.copy()
                model_name = params.pop('model', model_id)
                self.X(name, model_name, *port_list, **params)

    @staticmethod
    def _default_port_list(device_type, ports, p):
        """Return default positional port list for built-in device types."""
        if device_type in {'resistor', 'capacitor', 'inductor', 'vsource', 'isource', 'diode'}:
            return [p('P'), p('N')]
        elif device_type in {'pmos', 'nmos'}:
            bulk = p('B') if 'B' in ports else None
            return [p('D'), p('G'), p('S')] + ([bulk] if bulk else [])
        elif device_type in {'npn', 'pnp'}:
            return [p('C'), p('B'), p('E')]
        else:
            return []
        


class NyanCircuit(NyanCADMixin, Circuit):
    """InSpice Circuit populated from NyanCAD schematic data."""

    def __init__(self, name, schem, corners=None, sim='NgSpice', **kwargs):
        """
        Create InSpice Circuit from full NyanCAD schematic data.

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

        # Then process only the used models: create subcircuits for schematic models, add SPICE for others
        for model_key_str in self.used_models:
            model_def = models[model_key_str]
            # Extract bare model ID for schematic lookup (models dict keys always have "models:" prefix)
            model_id = bare_id(model_key_str)
            # Skip the top-level circuit itself
            if model_id != name:
                # Create subcircuits for schematic models or SPICE models with model entries
                if model_id in schem:
                    # Create subcircuit for models with schematic implementations
                    docs = schem[model_id]
                    shape = 'amp' if model_def.get('type') == 'amp' else None
                    nodes = [c['name'] for c in port_locations(model_def['ports'], shape)]
                    # Pass model parameter definitions as subcircuit parameters (default to 0)
                    model_params = {p['name']: p.get('default', '0')
                                    for p in model_def.get('props', [])
                                    if p.get('name')}
                    subcircuit = NyanSubCircuit(model_def['name'], nodes, docs, models, corners, sim, **model_params)
                    self.subcircuit(subcircuit)
                else:
                    # Add SPICE code / library includes for model entries
                    entry = self._select_model_entry(model_def, sim)
                    if entry:
                        # Handle library includes
                        if entry.get('library'):
                            section = _select_corner(entry.get('sections'), corners)
                            if section:
                                self.lib(entry['library'], section)
                            else:
                                self.include(entry['library'])
                        # Handle inline SPICE code
                        code = entry.get('code', '').strip()
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
        """Add SPICE code to circuit. Try structured parsing first, fallback to raw injection.

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
            self.raw_spice += '\n' + spice_code.strip() + '\n'
    
    def _resolve_url_path(self, path_obj):
        """Helper to resolve a single URL path to a local file path."""
        cache_dir = Path(tempfile.gettempdir()) / "nyancad_archive_cache"
        cache_dir.mkdir(exist_ok=True)

        # Get the raw path from the AST
        ast = path_obj._ast
        path_str = str(next(iter(ast))).strip("\"'")

        parsed = urlparse(path_str)

        if parsed.scheme in ('http', 'https'):
            archive_url = f"{parsed.scheme}://{parsed.netloc}{parsed.path}"
            entrypoint = parsed.fragment

            # Generate cache key from URL
            url_hash = hashlib.md5(archive_url.encode()).hexdigest()[:8]
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
        """Resolve HTTP includes (archives or bare files) to local file paths in a SpiceSource object."""
        # Process includes
        for include in spice_source._includes:
            self._resolve_url_path(include)

        # Also process libs if they exist
        if hasattr(spice_source, '_libs'):
            for lib in spice_source._libs:
                self._resolve_url_path(lib)


class NyanSubCircuit(NyanCADMixin, SubCircuit):
    """InSpice SubCircuit populated from NyanCAD docs."""
    
    def __init__(self, name, nodes, docs, models, corners=None, sim='NgSpice', **kwargs):
        """
        Create InSpice SubCircuit from NyanCAD docs.

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


async def inspice_netlist(name, schem, corners=None, sim='NgSpice', *, corner=None, **kwargs):
    """
    Convenience function to create InSpice Circuit from NyanCAD schematic.

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
    circuit = await inspice_netlist("top$top", schem_data, corners=["mos_ff", "cap_bcs"])
    ```
    """
    if corner is not None and corners is None:
        corners = [corner]
    circuit = NyanCircuit(name, schem, corners, sim, **kwargs)
    await circuit.download_includes()
    return circuit


async def inspice_netlist_from_api(api, name, corners=None, sim='NgSpice', *, corner=None, **kwargs):
    """
    Create InSpice Circuit from any SchematicAPI source (Bridge or Server).

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
        "https://api.nyancad.com/userdb-alice",
        username="alice",
        password="secret"
    ) as api:
        circuit = await inspice_netlist_from_api(api, "my_circuit")
    ```
    """
    if corner is not None and corners is None:
        corners = [corner]
    seq, schem = await api.get_all_schem_docs(name)
    circuit = NyanCircuit(name, schem, corners, sim, **kwargs)
    await circuit.download_includes()
    return circuit
