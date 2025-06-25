# SPDX-FileCopyrightText: 2022 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""
This module communicates with CouchDB to fetch schematics, and generate SPICE netlists out of them.

"""

from collections import deque, namedtuple
from InSpice.Spice.Netlist import Circuit, SubCircuit

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


def getports(doc, models):
    cell = doc['cell']
    x = doc['x']
    y = doc['y']
    tr = doc.get('transform', [1, 0, 0, 1, 0, 0])
    if cell == 'wire':
        rx = doc['rx']
        ry = doc['ry']
        return {(x, y): None,
                (x+rx, y+ry): None}
    elif cell == 'text':
        return {}
    elif cell == 'port':
        return {(x, y): doc['name']}
    elif cell in {'nmos', 'pmos'}:
        return rotate(mosfet_shape, tr, x, y)
    elif cell in {'npn', 'pnp'}:
        return rotate(bjt_shape, tr, x, y)
    elif cell in {'resistor', 'capacitor', 'inductor', 'vsource', 'isource', 'diode'}:
        return rotate(twoport_shape, tr, x, y)
    else:
        return rotate(models[cell]['conn'], tr, x, y)


def port_index(docs, models):
    wire_index = {}
    device_index = {}
    for doc in docs.values():
        cell = doc['cell']
        for (x, y), p in getports(doc, models).items():
            if cell in {'wire', 'port'}:
                wire_index.setdefault((x, y), []).append(doc)
            else:
                device_index.setdefault((x, y), []).append((p, doc))
                # add a dummy net so two devices can connect directly
                wire_index.setdefault((x, y), []).append({"cell": "wire", "x": x, "y": y, "rx": 0, "ry": 0})
    return device_index, wire_index


def wire_net(wireid, docs, models):
    device_index, wire_index = port_index(docs, models)
    netname = None
    net = deque([docs[wireid]]) # all the wires on this net
    while net:
        doc = net.popleft() # take a wire from the net
        cell = doc['cell']
        if cell == 'wire':
            wirename = doc.get('name')
            if netname == None and wirename != None:
                netname = wirename
            for ploc in getports(doc, models).keys(): # get the wire ends
                # if the wire connects to another wire,
                # that we have not seen, add it to the net
                if ploc in wire_index:
                    net.extend(wire_index.pop(ploc))
        elif cell == 'port':
            netname = doc.get('name')
        else:
            raise ValueError(cell)
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
            cell = doc['cell']
            if cell == 'wire':
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
            elif cell == 'port':
                netname = doc.get('name')
            else:
                raise ValueError(cell)
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


def print_props(props):
    prs = []
    for k, v in props.items():
        if k == "model":
            prs.insert(0, v)
        elif k == "spice":
            prs.append(v)
        else:
            prs.append(f"{k}={v}")
    return " ".join(prs)


def circuit_spice(docs, models, declarations, corner, sim):
    nl = netlist(docs, models)
    cir = []
    for id, ports in nl.items():
        dev = docs[id]
        cell = dev['cell']
        mname = dev.get('props', {}).get('model', '')
        name = dev.get('name') or id
        # print(ports)
        def p(p): return ports[p]
        propstr = print_props(dev.get('props', {}))
        if cell == "resistor":
            ports = ' '.join(p(c) for c in ['P', 'N'])
            templ = "R{name} {ports} {properties}"
        elif cell == "capacitor":
            ports = ' '.join(p(c) for c in ['P', 'N'])
            templ = "C{name} {ports} {properties}"
        elif cell == "inductor":
            ports = ' '.join(p(c) for c in ['P', 'N'])
            templ = "L{name} {ports} {properties}"
        elif cell == "diode":
            ports = ' '.join(p(c) for c in ['P', 'N'])
            templ = "D{name} {ports} {properties}"
        elif cell == "vsource":
            ports = ' '.join(p(c) for c in ['P', 'N'])
            templ = "V{name} {ports} {properties}"
        elif cell == "isource":
            ports = ' '.join(p(c) for c in ['P', 'N'])
            templ = "I{name} {ports} {properties}"
        elif cell in {"pmos", "nmos"}:
            ports = ' '.join(p(c) for c in ['D', 'G', 'S', 'B'])
            templ = "M{name} {ports} {properties}"
        elif cell in {"npn", "pnp"}:
            ports = ' '.join(p(c) for c in ['C', 'B', 'E'])
            templ = "Q{name} {ports} {properties}"
        else:  # subcircuit
            m = models[cell]
            ports = ' '.join(p(c[2]) for c in m['conn'])
            templ = "X{name} {ports} {properties}"

        # a spice type model can overwrite its reference
        # for example if the mosfet is really a subcircuit
        try:
            m = models[cell]["models"][mname][sim]
            templ = m['reftempl']
            declarations.add(m['decltempl'].format(corner=corner))
        except KeyError:
            pass

        cir.append(templ.format(name=name, ports=ports, properties=propstr))
    return '\n'.join(cir)


def spice_netlist(name, schem, extra="", corner='tt', temp=None, sim="NgSpice", **params):
    """
    Generate a spice netlist, taking a dictionary of schematic documents, and the name of the top level schematic.
    It is possible to pass extra SPICE code and specify the simulation corner.
    """
    models = schem["models"]
    declarations = set()
    for subname, docs in schem.items():
        if subname in {name, "models"}: continue
        _id = SchemId.from_string(subname)
        mod = models[_id.cell]
        ports = ' '.join(c[2] for c in mod['conn'])
        body = circuit_spice(docs, models, declarations, corner, sim)
        declarations.add(f".subckt {_id.model} {ports}\n{body}\n.ends {_id.model}") # parameters??

    body = circuit_spice(schem[name], models, declarations, corner, sim)
    ckt = []
    ckt.append(f"* {name}")
    ckt.extend(declarations)
    ckt.append(body)
    ckt.append(extra)
    ckt.append(".end\n")

    return "\n".join(ckt)

default_device_vectors = {
    'resistor': ['i'],
    'capacitor': ['i'],
    'inductor': ['i'],
    'vsource': ['i'],
    'isource': [],
    'diode': [],
    'nmos': ['gm', 'id', 'vdsat'],
    'pmos': ['gm', 'id', 'vdsat'],
    'npn': ['gm', 'ic', 'ib'],
    'pnp': ['gm', 'ic', 'ib'],

}
device_prefix = {
    'resistor': 'r',
    'capacitor': 'c',
    'inductor': 'l',
    'vsource': 'v',
    'isource': 'i',
    'diode': 'd',
    'nmos': 'm',
    'pmos': 'm',
    'npn': 'q',
    'pnp': 'q',

}
# @m.xx1.xmc1.msky130_fd_pr__nfet_01v8[gm]
def ngspice_vectors(name, schem, path=()):
    """
    Extract all the relevant vectors from the schematic,
    and format them in NgSpice syntax.
    Saves label/port net names, and vectors indicated on spice models.
    """
    models = schem["models"]
    vectors = []
    for id, elem in schem[name].items():
        if elem['cell'] == 'port' and elem['name'].lower() != 'gnd':
            vectors.append(('.'.join(path + (elem['name'],))).lower())
            continue
        m = models.get(elem['cell'], {})
        n = m.get('models', {}).get(elem.get('props', {}).get('model'), {})
        if n.get('type') == 'spice':
            vex = n.get('NgSpice', {}).get('vectors', [])
            comp = n.get('NgSpice', {}).get('component')
            reftempl = n.get('NgSpice', {}).get('reftempl')
            typ = (comp or reftempl or 'X')[0]
            dtyp = (reftempl or 'X')[0]
            if comp:
                full = typ + '.' + '.'.join(path + (dtyp+elem['name'], comp))
            elif path:
                full = typ + '.' + '.'.join(path + (dtyp+elem['name'],))
            else:
                full = typ+elem['name']
            vectors.extend(f"@{full}[{v}]".lower() for v in vex)
        elif n.get('type') == 'schematic':
            name = elem['cell']+"$"+elem['props']['model']
            vectors.extend(ngspice_vectors(name, schem, path+("X"+elem['name'],)))
        elif elem['cell'] in default_device_vectors: # no model specified
            vex = default_device_vectors[elem['cell']]
            typ = device_prefix.get(elem['cell'], 'x')
            if path:
                full = typ + '.' + '.'.join(path + (typ+elem['name'],))
            else:
                full = typ+elem['name']
            vectors.extend(f"@{full}[{v}]".lower() for v in vex)
    return vectors


class NyanCADMixin:
    """Mixin providing NyanCAD integration for InSpice netlist objects."""
    
    def populate_from_nyancad(self, docs, models, corner='tt', sim='NgSpice'):
        """Populate this netlist with elements from NyanCAD docs."""
        nl = netlist(docs, models)
        
        for dev_id, ports in nl.items():
            dev = docs[dev_id]
            self._add_nyancad_element(dev_id, dev, ports, models, corner, sim)
    
    def _add_nyancad_element(self, dev_id, dev, ports, models, corner, sim):
        """Add a single NyanCAD element to this netlist."""
        cell = dev['cell']
        name = dev.get('name') or dev_id.replace(':', '_')  # InSpice names can't have colons
        props = dev.get('props', {})
        mname = props.get('model', '')
        
        # Parameter name mapping from NyanCAD to InSpice
        param_mapping = {
            'W': 'width',
            'L': 'length', 
            'nf': 'nfin',
        }
        
        # Helper to get port by name
        def p(port_name):
            return ports[port_name]
        
        
        # Map device types to InSpice API methods
        if cell == "resistor":
            resistance = props.get('resistance')
            self.R(name, p('P'), p('N'), resistance)
            
        elif cell == "capacitor":
            capacitance = props.get('capacitance')
            self.C(name, p('P'), p('N'), capacitance)
            
        elif cell == "inductor":
            inductance = props.get('inductance')
            self.L(name, p('P'), p('N'), inductance)
            
        elif cell == "diode":
            self.D(name, p('P'), p('N'), **props)
            
        elif cell == "vsource":
            dc = props.get('dc')
            ac = props.get('ac')
            tran = props.get('tran')
            self.V(name, p('P'), p('N'), dc, ac, tran)
            
        elif cell == "isource":
            dc = props.get('dc')
            ac = props.get('ac')
            tran = props.get('tran')
            self.I(name, p('P'), p('N'), dc, ac, tran)
            
        elif cell in {"pmos", "nmos"}:
            bulk_node = p('B') if 'B' in ports else self.gnd
            # Map parameter names and copy dict to avoid mutation
            self.M(name, p('D'), p('G'), p('S'), bulk_node, **props)
            
        elif cell in {"npn", "pnp"}:
            self.Q(name, p('C'), p('B'), p('E'), **props)
            
        else:  # subcircuit
            if cell in models:
                m = models[cell]
                port_list = [p(c[2]) for c in m['conn']]
                params = props.copy()
                model_name = params.pop('model')
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
        
        models = schem["models"]
        
        # Inverted loop: iterate over models and their implementations, 
        # then check if they exist in the schematic
        for cell_name, model_def in models.items():
            model_implementations = model_def.get('models', {})
            for model_name in model_implementations.keys():
                # model_name is the full subcircuit_key
                if model_name in schem and model_name != name:
                    docs = schem[model_name]
                    nodes = [c[2] for c in model_def.get('conn', [])]
                    subcircuit = NyanSubCircuit(model_name, nodes, docs, models, corner, sim)
                    self.subcircuit(subcircuit)
        
        # Populate main circuit elements
        self.populate_from_nyancad(schem[name], models, corner, sim)


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


def inspice_netlist(name, schem, corner='tt', sim='NgSpice', **kwargs):
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
    circuit = inspice_netlist("top$top", schem_data)
    simulator = circuit.simulator(temperature=25, nominal_temperature=25)
    ```
    """
    return NyanCircuit(name, schem, corner, sim, **kwargs)
