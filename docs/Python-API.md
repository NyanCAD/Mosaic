---
layout: default
title: Python API
---
<a id="pyttoresque"></a>

# pyttoresque

<a id="pyttoresque.simserver"></a>

# pyttoresque.simserver

This module handles communication with a simulation server.
The underlying protocol is described in https://github.com/NyanCAD/SimServer/blob/main/Simulator.capnp

Basic usage of this module:
```
con = await connect("localhost", simulator=Xyce)
fs = loadFiles(con, "test.cir")
res = fs.commands.run(["V(*)", "I(*)"])
print(await readAll(res))
```

For streaming plots, have a look at `holoviews.streams.Buffer` and https://holoviews.org/user_guide/Streaming_Data.html.

<a id="pyttoresque.simserver.connect"></a>

#### connect

```python
async def connect(host, port=5923, simulator=Ngspice, autostart=True)
```

Connect to a simulation server at the given `host:port`,
which should be a `simulator` such as `Ngspice` or `Xyce`.

If `host` is set to "localhost" and no server is running,
we will attempt to start one automatically,
unless `autostart=False`.

<a id="pyttoresque.simserver.loadFiles"></a>

#### loadFiles

```python
def loadFiles(sim, *names)
```

Load the specified filenames into the simulation server.
The first file is the entrypoint for the simulator.
Returns a handle to run simulation commands on.

For in-memory data, directly call `sim.loadFiles`.
The data should be of the form `[{"name": name, "contents": contents}]`

For files already present on the simulator use `sim.loadPath`.

<a id="pyttoresque.simserver.read"></a>

#### read

```python
async def read(response, io=stdout)
```

Read one chunk from a simulation command

<a id="pyttoresque.simserver.stream"></a>

#### stream

```python
async def stream(response,
                 streamdict,
                 newkey=lambda k: None,
                 io=stdout,
                 suffix="")
```

Stream simulation data into a Buffer (DataFrame)

`streamdict` is a dictionary, where Buffers are added as needed.
This is done because some simulation commands have multiple results.

The `newkey` function is called when a new Buffer is added.

Additionally, a custom "file-like" object can be passed for logging,
and a suffix can be passed that is appended to the dictionary key.

<a id="pyttoresque.simserver.readAll"></a>

#### readAll

```python
async def readAll(response, io=stdout, suffix="")
```

Read all the simulation data from a simulation command.

<a id="pyttoresque.netlist"></a>

# pyttoresque.netlist

This module communicates with CouchDB to fetch schematics, and generate SPICE netlists out of them.

Basic usage:
```
async with SchematicService("http://localhost:5984/offline") as service:
    name = "top$top"
    seq, docs = await service.get_all_schem_docs(name)
    print(spice_netlist(name, docs))
```

The sequence number can later be used to efficiently update the netlist with `update_schem`.
For live updates, use `live_schem_docs`.

<a id="pyttoresque.netlist.StatusError"></a>

## StatusError Objects

```python
class StatusError(ClientError)
```

Non-200 response

<a id="pyttoresque.netlist.SchematicService"></a>

## SchematicService Objects

```python
class SchematicService(AbstractAsyncContextManager)
```

A context manager for getting schematics from a CouchDB database

<a id="pyttoresque.netlist.SchematicService.__init__"></a>

#### \_\_init\_\_

```python
def __init__(url)
```

Create a HTTP session with the given database URL

<a id="pyttoresque.netlist.SchematicService.dbget"></a>

#### dbget

```python
async def dbget(path, **kwargs)
```

Do a GET request to the given database endpoint and query parameters

<a id="pyttoresque.netlist.SchematicService.dbpost"></a>

#### dbpost

```python
async def dbpost(path, json, **kwargs)
```

Do a POST request to the given database endpoint, JSON data, and query parameters

<a id="pyttoresque.netlist.SchematicService.dbput"></a>

#### dbput

```python
async def dbput(path, json, **kwargs)
```

Do a PUT request to the given database endpoint, data, and query parameters

<a id="pyttoresque.netlist.SchematicService.dbstream"></a>

#### dbstream

```python
async def dbstream(path, json, **kwargs)
```

Stream data from the given database endpoint, JSON data, and query parameters

<a id="pyttoresque.netlist.SchematicService.get_docs"></a>

#### get\_docs

```python
async def get_docs(name)
```

Get all the documents with the specified schematic ID

<a id="pyttoresque.netlist.SchematicService.get_all_schem_docs"></a>

#### get\_all\_schem\_docs

```python
async def get_all_schem_docs(name)
```

Recursively get all the documents of the specified schematic and all the subcircuits inside it.
And all the model definitions.
Returns a sequence number and a dictionary of schematic ID: documents.

<a id="pyttoresque.netlist.SchematicService.update_schem"></a>

#### update\_schem

```python
async def update_schem(seq, schem)
```

Take a sequence number and dictionary as returned by `get_all_schem_docs` and update it.

<a id="pyttoresque.netlist.SchematicService.live_schem_docs"></a>

#### live\_schem\_docs

```python
async def live_schem_docs(name)
```

A live stream of updated dictionaries, as returned by `get_all_schem_docs`

<a id="pyttoresque.netlist.SchematicService.save_simulation"></a>

#### save\_simulation

```python
async def save_simulation(name, data)
```

takes a schematic name and data as populated by
`pyttoresque.simserver.stream` and saves it to the database.
Additional keys can be added as the designer sees fit.

<a id="pyttoresque.netlist.netlist"></a>

#### netlist

```python
def netlist(docs, models)
```

Turn a collection of documents as returned by `get_docs` into a netlist structure.
Returns a dictionary of device ID: {port: net}
Usage:
```
async with SchematicService("http://localhost:5984/offline") as service:
    name = "top$top"
    seq, docs = await service.get_all_schem_docs(name)
    print(netlist(docs[name], models))
```

<a id="pyttoresque.netlist.spice_netlist"></a>

#### spice\_netlist

```python
def spice_netlist(name,
                  schem,
                  extra="",
                  corner='tt',
                  temp=None,
                  sim="NgSpice",
                  **params)
```

Generate a spice netlist, taking a dictionary of schematic documents, and the name of the top level schematic.
It is possible to pass extra SPICE code and specify the simulation corner.

<a id="pyttoresque.netlist.ngspice_vectors"></a>

#### ngspice\_vectors

```python
def ngspice_vectors(name, schem, path=())
```

Extract all the relevant vectors from the schematic,
and format them in NgSpice syntax.
Saves label/port net names, and vectors indicated on spice models.

<a id="pyttoresque.app.main"></a>

# pyttoresque.app.main

<a id="pyttoresque.app"></a>

# pyttoresque.app

<a id="pyttoresque.api"></a>

# pyttoresque.api

<a id="pyttoresque.api.examples.blink_multisim"></a>

# pyttoresque.api.examples.blink\_multisim

<a id="pyttoresque.analysis"></a>

# pyttoresque.analysis

