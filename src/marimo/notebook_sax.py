# /// script
# [tool.marimo.display]
# theme = "system"
# ///

import marimo

__generated_with = "0.23.6"
app = marimo.App()


@app.cell(hide_code=True)
def _(
    S,
    hv,
    input_port,
    jnp,
    np,
    output_port,
    processing_mode,
    sim_ports,
    wl_array,
    x_mode_toggle,
):
    _C = 299.792458

    _in_sel = input_port.value
    _out_sel = output_port.value
    _proc = processing_mode.value
    _x_mode = x_mode_toggle.value

    _pairs = []
    for _pi in sim_ports:
        if _in_sel not in ("All", _pi):
            continue
        for _po in sim_ports:
            if _out_sel not in ("All", _po):
                continue
            try:
                _ = S[_pi, _po]
                _pairs.append((_pi, _po))
            except (KeyError, IndexError):
                pass

    if _x_mode == "frequency":
        _x = _C / np.array(wl_array)
        _xlabel = "Frequency (THz)"
    else:
        _x = np.array(wl_array)
        _xlabel = "Wavelength (um)"

    _ylabel_map = {
        "amplitude": "Amplitude |S|",
        "power": "Power |S|²",
        "pow_db": "Power (dB)",
        "phase": "Phase (rad)",
    }
    _ylabel = _ylabel_map.get(_proc, _proc)

    _curves = {}
    for _pi, _po in _pairs:
        _raw = jnp.array(S[_pi, _po])
        if _proc == "amplitude":
            _y = np.array(jnp.abs(_raw))
        elif _proc == "power":
            _y = np.array(jnp.abs(_raw) ** 2)
        elif _proc == "pow_db":
            _y = np.array(10 * jnp.log10(jnp.clip(jnp.abs(_raw) ** 2, 1e-12, None)))
        elif _proc == "phase":
            _y = np.unwrap(np.array(jnp.angle(_raw)))
        else:
            _y = np.array(jnp.abs(_raw))

        _label = f"{_pi} → {_po}"
        _curves[_label] = hv.Curve(
            (_x, _y), kdims=[_xlabel], vdims=[_ylabel], label=_label
        )

    if _curves:
        _opts = {"responsive": True, "height": 500}
        if _x_mode == "frequency":
            _opts["invert_xaxis"] = True
        plot = hv.NdOverlay(_curves).opts(hv.opts.Curve(**_opts))
    else:
        plot = hv.Div("<p><em>No port pairs selected.</em></p>")

    plot
    return


@app.cell(hide_code=True)
def _(circuit_ports, mo, sim_ports, widget_state):
    _all_option = ["All"]
    _port_options = _all_option + sim_ports

    _stored_in = widget_state.get("input_port")
    _in_value = _stored_in if _stored_in in _port_options else "All"

    _stored_out = widget_state.get("output_port")
    _out_value = _stored_out if _stored_out in _port_options else "All"

    input_port = mo.ui.dropdown(
        options=_port_options,
        value=_in_value,
        label="Input port",
    )
    output_port = mo.ui.dropdown(
        options=_port_options,
        value=_out_value,
        label="Output port",
    )
    processing_mode = mo.ui.dropdown(
        options=["amplitude", "power", "pow_db", "phase"],
        value=widget_state.get("processing", "pow_db"),
        label="Processing",
    )

    _no_ports = len(circuit_ports) < 2
    no_ports_warning = (
        mo.callout(
            mo.md(
                "**Insufficient ports** — add at least two ports to the schematic "
                "for S-parameter simulation."
            ),
            kind="warn",
        )
        if _no_ports
        else None
    )

    mo.vstack(
        [
            item
            for item in [no_ports_warning, input_port, output_port, processing_mode]
            if item is not None
        ]
    )
    return input_port, output_port, processing_mode


@app.cell(hide_code=True)
def _(mo, widget_state):
    wl_min = mo.ui.number(
        label="Min wavelength (um)",
        value=widget_state["wl_min"],
        start=0.1,
        stop=10.0,
        step=0.001,
    )
    wl_max = mo.ui.number(
        label="Max wavelength (um)",
        value=widget_state["wl_max"],
        start=0.1,
        stop=10.0,
        step=0.001,
    )
    wl_num = mo.ui.number(
        label="Number of points",
        value=widget_state["wl_num"],
        start=10,
        stop=10000,
        step=10,
    )
    x_mode_toggle = mo.ui.dropdown(
        options=["wavelength", "frequency"],
        value=widget_state["x_mode"],
        label="X-axis",
    )

    mo.vstack([wl_min, wl_max, wl_num, x_mode_toggle])
    return wl_max, wl_min, wl_num, x_mode_toggle


@app.cell
def _():
    widget_state = {
        "wl_min": 1.5,
        "wl_max": 1.6,
        "wl_num": 1000,
        "x_mode": "wavelength",
        "processing": "pow_db",
        "input_port": None,
        "output_port": None,
        "model_overrides": {},
        "param_overrides": {},
    }
    return (widget_state,)


@app.cell
def _():
    import holoviews as hv
    import jax.numpy as jnp
    import marimo as mo
    import numpy as np
    import sax
    from nyancad.netlist import recursive_kfnetlist_from_nyancad, resolve_sax_netlist
    from nyancad.watch import file_schematic, watch_project_dir
    from sax.parsers.kfnetlist import parse_kfnetlist_recursive

    hv.extension("bokeh")
    return (
        file_schematic,
        hv,
        jnp,
        mo,
        np,
        parse_kfnetlist_recursive,
        resolve_sax_netlist,
        sax,
        recursive_kfnetlist_from_nyancad,
        watch_project_dir,
    )


@app.cell
def _(mo, watch_project_dir):
    _args = mo.cli_args()
    if "project" in _args:
        project = watch_project_dir(_args["project"])
    else:
        project = watch_project_dir(".")
    return (project,)


@app.cell
def _(mo):
    _args = mo.cli_args()
    if "schem" in _args:
        schem_file = mo.md(f"**Schematic:** {_args['schem']}")
    else:
        schem_file = mo.ui.file_browser(
            filetypes=[".nyancir"], multiple=False, label="Select Schematic"
        )
    schem_file
    return (schem_file,)


@app.cell
async def _(file_schematic, mo, project, schem_file):
    _args = mo.cli_args()
    if "schem" in _args:
        schem_name = _args["schem"]
    else:
        schem_name = schem_file.path(index=0).stem
    schem_data = await file_schematic(project, schem_name)
    return schem_data, schem_name


@app.cell
def _(mo, project):
    import tomllib
    from pathlib import Path

    _pdk_name = None
    _pdk_models = {}
    _pdk_cells = None
    _error = None

    try:
        _pyproject = Path(project) / "pyproject.toml"
        with open(_pyproject, "rb") as _f:
            _config = tomllib.load(_f)
        _pdk_name = _config["tool"]["gdsfactoryplus"]["pdk"]["name"]

        _parts = _pdk_name.rsplit(".", 1)
        _module = __import__(_pdk_name, fromlist=[_parts[-1]])

        _pdk = getattr(_module, "PDK", None)
        _cells_mod = getattr(_module, "cells", None)
        if _pdk is None:
            _error = f"PDK module `{_pdk_name}` has no `PDK` attribute."
        else:
            _pdk.activate()
            _pdk_models = _pdk.models
            _pdk_cells = _cells_mod
    except FileNotFoundError:
        _error = "No `pyproject.toml` found in project directory."
    except KeyError:
        _error = "Missing `[tool.gdsfactoryplus.pdk] name` in pyproject.toml."
    except ImportError as exc:
        _error = f"Cannot import PDK `{_pdk_name}`: {exc}"

    mo.stop(
        _error is not None,
        mo.callout(mo.md(f"**PDK setup failed**\n\n{_error}"), kind="danger"),
    )

    pdk_models = _pdk_models
    pdk_cells = _pdk_cells
    return pdk_cells, pdk_models


@app.cell
def _(
    mo,
    parse_kfnetlist_recursive,
    pdk_cells,
    resolve_sax_netlist,
    schem_data,
    schem_name,
    recursive_kfnetlist_from_nyancad,
):
    _error = None
    _recnet = {}
    _circuit_ports = []
    _leaf_components = []

    try:
        _kf_netlists = recursive_kfnetlist_from_nyancad(schem_name, schem_data)
        _recnet = parse_kfnetlist_recursive(_kf_netlists)
        _leaf_components = resolve_sax_netlist(_recnet, pdk_cells)

        _top = _recnet.get(schem_name, {})
        _ports_section = _top.get("ports", {})
        _circuit_ports = sorted(_ports_section.keys())
    except Exception as exc:
        _error = str(exc)

    mo.stop(
        _error is not None,
        mo.callout(
            mo.md(f"**Netlist build failed**\n\n```\n{_error}\n```"), kind="danger"
        ),
    )

    recnet = _recnet
    circuit_ports = _circuit_ports
    leaf_components = _leaf_components
    return circuit_ports, leaf_components, recnet


@app.cell(hide_code=True)
def _(leaf_components, mo, pdk_models, widget_state):
    _available = sorted(pdk_models.keys())
    _stored = widget_state.get("model_overrides", {})

    model_dropdowns = {}
    for _comp in leaf_components:
        _default = _stored.get(_comp, _comp if _comp in _available else None)
        model_dropdowns[_comp] = mo.ui.dropdown(
            options=_available,
            value=_default,
            label=_comp,
        )

    _param_stored = widget_state.get("param_overrides", {})
    param_inputs = {
        k: mo.ui.text(value=str(v), label=k) for k, v in _param_stored.items()
    }

    _items = [mo.md("**Model Assignment**"), *model_dropdowns.values()]
    if param_inputs:
        _items.extend([mo.md("**Parameter Overrides**"), *param_inputs.values()])

    mo.vstack(_items)
    return model_dropdowns, param_inputs


@app.cell
def _(
    jnp,
    model_dropdowns,
    param_inputs,
    pdk_models,
    recnet,
    sax,
    wl_max,
    wl_min,
    wl_num,
):
    _models = dict(pdk_models)
    for _comp, _dropdown in model_dropdowns.items():
        _sel = _dropdown.value
        if _sel is not None and _sel in _models:
            _models[_comp] = _models[_sel]

    wl_array = jnp.linspace(wl_min.value, wl_max.value, int(wl_num.value))

    _params = {}
    for _k, _input in param_inputs.items():
        _v = _input.value
        try:
            _params[_k] = float(_v)
        except (ValueError, TypeError):
            _params[_k] = _v

    _circuit_fn, _info = sax.circuit(
        recnet,
        models=_models,
        ignore_impossible_connections=False,
    )
    S = _circuit_fn(wl=wl_array, **_params)
    sim_ports = sorted(sax.get_ports(S))
    return S, sim_ports, wl_array


@app.cell
def _(
    input_port,
    model_dropdowns,
    output_port,
    param_inputs,
    processing_mode,
    widget_state,
    wl_max,
    wl_min,
    wl_num,
    x_mode_toggle,
):
    widget_state["wl_min"] = wl_min.value
    widget_state["wl_max"] = wl_max.value
    widget_state["wl_num"] = wl_num.value
    widget_state["x_mode"] = x_mode_toggle.value
    widget_state["processing"] = processing_mode.value
    widget_state["input_port"] = input_port.value
    widget_state["output_port"] = output_port.value
    widget_state["model_overrides"] = {
        comp: dropdown.value for comp, dropdown in model_dropdowns.items()
    }
    widget_state["param_overrides"] = {
        key: text.value for key, text in param_inputs.items()
    }
    return


if __name__ == "__main__":
    app.run()
