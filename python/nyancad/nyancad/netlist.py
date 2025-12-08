# SPDX-FileCopyrightText: 2022 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""
This module communicates with CouchDB to fetch schematics, and generate SPICE netlists out of them.

"""

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


def shape_ports(shape):
    for y, s in enumerate(shape):
        for x, c in enumerate(s):
            if c != ' ':
                yield x, y, c


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
    width = max(max(x, y) for x, y, _ in shape)+1
    mid = width/2-0.5
    res = {}
    for px, py, p in shape:
        x = px-mid
        y = py-mid
        nx = a*x+c*y+e
        ny = b*x+d*y+f
        res[round(devx+nx+mid), round(devy+ny+mid)] = p
    return res


def port_perimeter(ports):
    width = 1 + max(len(ports.get('left', [])), len(ports.get('right', [])))
    height = 1 + max(len(ports.get('top', [])), len(ports.get('bottom', [])))
    return width, height

def port_locations(ports):
    width, height = port_perimeter(ports)
    top = ports.get('top', [])
    bottom = ports.get('bottom', [])
    left = ports.get('left', [])
    right = ports.get('right', [])
    top_locs = [((i + 1), 0, n) for i, n in enumerate(top)]
    bottom_locs = [((i + 1), height + 1, n) for i, n in enumerate(bottom)]
    left_locs = [(0, (i + 1), n) for i, n in enumerate(left)]
    right_locs = [(width + 1, (i + 1), n) for i, n in enumerate(right)]
    return top_locs + bottom_locs + left_locs + right_locs

def getports(doc, models):
    device_type = doc['type']
    x = doc['x']
    y = doc['y']
    tr = doc.get('transform', [1, 0, 0, 1, 0, 0])
    if device_type == 'wire':
        rx = doc['rx']
        ry = doc['ry']
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
            return rotate(port_locations(model['ports']), tr, x, y)
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


class NyanCADMixin:
    """Mixin providing NyanCAD integration for InSpice netlist objects."""
    
    def _select_template(self, templates, sim):
        """Select template from templates dict based on sim parameter.
        
        Returns:
            dict: selected template or None if no templates
        """
        spice_templates = templates.get('spice', [])
        if not spice_templates:
            return None
            
        # Look for implementation matching sim parameter, fallback to first (default)
        for template in spice_templates:
            if template.get('name', '').lower() == sim.lower():
                return template
        
        return spice_templates[0]  # Use first as default
    
    def populate_from_nyancad(self, docs, models, corner='tt', sim='NgSpice'):
        """Populate this netlist with elements from NyanCAD docs."""
        self.used_models = set()
        nl = netlist(docs, models)
        
        for dev_id, ports in nl.items():
            dev = docs[dev_id]
            self._add_nyancad_element(dev_id, dev, ports, models, corner, sim)
    
    def _add_nyancad_element(self, dev_id, dev, ports, models, corner, sim):
        """Add a single NyanCAD element to this netlist."""
        device_type = dev['type']
        name = dev.get('name') or dev_id.replace(':', '_')  # InSpice names can't have colons
        props = dev.get('props', {}).copy()

        model_id = model_key(dev.get('model'))
        model_use_x = False
        model_name = None

        if model_id and model_id in models:
            self.used_models.add(model_id)
            model_def = models[model_id]
            model_name = model_def['name']
            props['model'] = model_name
            
            # Check if model should be used as subcircuit (X) instead of component
            templates = model_def.get('templates', {})
            selected_template = self._select_template(templates, sim)
            model_use_x = selected_template.get('use-x', False) if selected_template else False

        print(props)
        # Helper to get port by name
        def p(port_name):
            return ports[port_name]
        
        # Map device types to InSpice API methods
        if device_type == "resistor":
            if model_use_x:
                subcircuit_model = props.pop('model', model_name)
                self.X(name, subcircuit_model, p('P'), p('N'), **props)
            else:
                resistance = props.get('resistance')
                self.R(name, p('P'), p('N'), resistance)
            
        elif device_type == "capacitor":
            if model_use_x:
                subcircuit_model = props.pop('model', model_name)
                self.X(name, subcircuit_model, p('P'), p('N'), **props)
            else:
                capacitance = props.get('capacitance')
                self.C(name, p('P'), p('N'), capacitance)
            
        elif device_type == "inductor":
            if model_use_x:
                subcircuit_model = props.pop('model', model_name)
                self.X(name, subcircuit_model, p('P'), p('N'), **props)
            else:
                inductance = props.get('inductance')
                self.L(name, p('P'), p('N'), inductance)
            
        elif device_type == "diode":
            if model_use_x:
                subcircuit_model = props.pop('model', model_name)
                self.X(name, subcircuit_model, p('P'), p('N'), **props)
            else:
                self.D(name, p('P'), p('N'), **props)
            
        elif device_type == "vsource":
            if model_use_x:
                subcircuit_model = props.pop('model', model_name)
                self.X(name, subcircuit_model, p('P'), p('N'), **props)
            else:
                dc = props.get('dc')
                ac = props.get('ac')
                tran = props.get('tran')
                self.V(name, p('P'), p('N'), dc, ac, tran)
            
        elif device_type == "isource":
            if model_use_x:
                subcircuit_model = props.pop('model', model_name)
                self.X(name, subcircuit_model, p('P'), p('N'), **props)
            else:
                dc = props.get('dc')
                ac = props.get('ac')
                tran = props.get('tran')
                self.I(name, p('P'), p('N'), dc, ac, tran)
            
        elif device_type in {"pmos", "nmos"}:
            if model_use_x:
                subcircuit_model = props.pop('model', model_name)
                bulk_node = p('B') if 'B' in ports else self.gnd
                self.X(name, subcircuit_model, p('D'), p('G'), p('S'), bulk_node, **props)
            else:
                bulk_node = p('B') if 'B' in ports else self.gnd
                self.M(name, p('D'), p('G'), p('S'), bulk_node, **props)
            
        elif device_type in {"npn", "pnp"}:
            if model_use_x:
                subcircuit_model = props.pop('model', model_name)
                self.X(name, subcircuit_model, p('C'), p('B'), p('E'), **props)
            else:
                self.Q(name, p('C'), p('B'), p('E'), **props)
            
        else:  # subcircuit
            if model_id in models:
                m = models[model_id]
                port_locs = port_locations(m['ports'])
                port_list = [p(c[2]) for c in port_locs]
                params = props.copy()
                model_name = params.pop('model', model_id)
                self.X(name, model_name, *port_list, **params)
        


class NyanCircuit(NyanCADMixin, Circuit):
    """InSpice Circuit populated from NyanCAD schematic data."""

    def __init__(self, name, schem, corner='tt', sim='NgSpice', **kwargs):
        """
        Create InSpice Circuit from full NyanCAD schematic data.

        Parameters:
        - name: Top-level schematic name (key in schem)
        - schem: Full schematic dictionary with models and subcircuits
        - corner, sim: Simulation parameters
        """
        super().__init__(title="schematic", **kwargs)
        self._pending_downloads = []  # List of (url, dest_path) tuples

        models = schem["models"]

        # First populate main circuit elements to collect used models
        self.populate_from_nyancad(schem[name], models, corner, sim)

        # Then process only the used models: create subcircuits for schematic models, add SPICE for others
        for model_key_str in self.used_models:
            model_def = models[model_key_str]
            # Extract bare model ID for schematic lookup (models dict keys always have "models:" prefix)
            model_id = bare_id(model_key_str)
            # Skip the top-level circuit itself
            if model_id != name:
                # Create subcircuits for schematic models or SPICE models with templates
                if model_id in schem:
                    # Create subcircuit for models with schematic implementations
                    docs = schem[model_id]
                    nodes = [c[2] for c in port_locations(model_def['ports'])]
                    subcircuit = NyanSubCircuit(model_id, nodes, docs, models, corner, sim)
                    self.subcircuit(subcircuit)
                else:
                    # Add SPICE code for models with templates (SPICE subcircuits)
                    templates = model_def.get('templates', {})
                    selected_template = self._select_template(templates, sim)
                    if selected_template:
                        code = selected_template.get('code', '').strip()
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
    
    def __init__(self, name, nodes, docs, models, corner='tt', sim='NgSpice', **kwargs):
        """
        Create InSpice SubCircuit from NyanCAD docs.
        
        Parameters:
        - name: Subcircuit name
        - nodes: List of external node names
        - docs: NyanCAD document dictionary for this subcircuit
        - models: Model definitions
        - corner, sim: Simulation parameters
        """
        super().__init__(name, *nodes, **kwargs)
        self.populate_from_nyancad(docs, models, corner, sim)


async def inspice_netlist(name, schem, corner='tt', sim='NgSpice', **kwargs):
    """
    Convenience function to create InSpice Circuit from NyanCAD schematic.

    Parameters:
    - name: Top-level schematic name
    - schem: Full schematic dictionary
    - corner, sim: Simulation parameters
    - **kwargs: Additional Circuit constructor arguments

    Returns:
    - NyanCircuit instance

    Usage:
    ```
    circuit = await inspice_netlist("top$top", schem_data)
    simulator = circuit.simulator(temperature=25, nominal_temperature=25)
    ```
    """
    circuit = NyanCircuit(name, schem, corner, sim, **kwargs)
    await circuit.download_includes()
    return circuit


async def inspice_netlist_from_api(api, name, corner='tt', sim='NgSpice', **kwargs):
    """
    Create InSpice Circuit from any SchematicAPI source (Bridge or Server).

    This convenience function works with both BridgeAPI and ServerAPI,
    automatically fetching the complete schematic hierarchy and creating
    the InSpice circuit.

    Parameters:
    - api: SchematicAPI instance (BridgeAPI or ServerAPI)
    - name: Top-level schematic name
    - corner, sim: Simulation parameters
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
    seq, schem = await api.get_all_schem_docs(name)
    circuit = NyanCircuit(name, schem, corner, sim, **kwargs)
    await circuit.download_includes()
    return circuit
