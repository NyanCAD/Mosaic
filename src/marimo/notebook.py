import marimo

__generated_with = "0.13.15"
app = marimo.App()


@app.cell(hide_code=True)
def _(bodeplot, df, hv, simtabs, sweepplot, timeplot):
    _analysis_type = simtabs.value

    if _analysis_type == "dc":
        plot = sweepplot(df).opts(responsive=True, height=500)
    elif _analysis_type == "tran":
        plot = timeplot(df).opts(responsive=True, height=500)
    elif _analysis_type == "ac":
        plot = bodeplot(df).opts(
            hv.opts.Curve(responsive=True, height=200)
        )
    else:
        plot = df

    plot
    return


@app.cell(hide_code=True)
def _(analysis, mo, widget_state):
    _options = [*analysis.nodes.keys(), *analysis.branches.keys()]
    vectors = mo.ui.multiselect(
        options=_options,
        label="Vectors",
        value=[v for v in widget_state["selected_vectors"] or _options if v in _options],
    )
    vectors
    return (vectors,)


@app.cell(hide_code=True)
def _(mo, spice, widget_state):
    # Create all widgets using circuit data and persisted state
    element_names = list(spice.element_names)
    node_names = list(spice.node_names)

    # Helper function to get dropdown value with fallback
    def get_dropdown_value(stored_value, options):
        if stored_value is not None and stored_value in options:
            return stored_value
        return options[0] if options else None

    # Operating Point Analysis UI
    op_back_annotate = mo.ui.checkbox(label="Back-annotate", value=widget_state["op_back_annotate"])

    # DC Analysis UI
    dc_source = mo.ui.dropdown(element_names, label="Source Name", 
                               value=get_dropdown_value(widget_state["dc_source"], element_names))
    dc_start = mo.ui.number(label="Start Value", value=widget_state["dc_start"], step=0.1)
    dc_stop = mo.ui.number(label="Stop Value", value=widget_state["dc_stop"], step=0.1)
    dc_step = mo.ui.number(label="Step Value", value=widget_state["dc_step"], step=0.01)

    # AC Analysis UI
    ac_variation = mo.ui.dropdown(["dec", "oct", "lin"], value=widget_state["ac_variation"], label="Point Spacing")
    ac_points = mo.ui.number(label="Number of Points", value=widget_state["ac_points"], step=1)
    ac_start_freq = mo.ui.number(label="Start Frequency (Hz)", value=widget_state["ac_start_freq"], step=1)
    ac_stop_freq = mo.ui.number(label="Stop Frequency (Hz)", value=widget_state["ac_stop_freq"], step=1000)

    # Transient Analysis UI
    tran_step = mo.ui.number(label="Step Time (s)", value=widget_state["tran_step"], step=1e-6)
    tran_start = mo.ui.number(label="Start Time (s)", value=widget_state["tran_start"], step=1e-6)
    tran_stop = mo.ui.number(label="End Time (s)", value=widget_state["tran_stop"], step=1e-4)
    tran_max = mo.ui.number(label="Max Time Step (s)", value=widget_state["tran_max"], step=1e-5)
    tran_uic = mo.ui.checkbox(label="Use Initial Conditions", value=widget_state["tran_uic"])

    # Pole-Zero Analysis UI
    pz_node1 = mo.ui.dropdown(node_names, label="Input Node 1", 
                              value=get_dropdown_value(widget_state["pz_node1"], node_names))
    pz_node2 = mo.ui.dropdown(node_names, label="Input Node 2", 
                              value=get_dropdown_value(widget_state["pz_node2"], node_names))
    pz_node3 = mo.ui.dropdown(node_names, label="Output Node 1", 
                              value=get_dropdown_value(widget_state["pz_node3"], node_names))
    pz_node4 = mo.ui.dropdown(node_names, label="Output Node 2", 
                              value=get_dropdown_value(widget_state["pz_node4"], node_names))
    pz_tf_type = mo.ui.dropdown(["vol", "cur"], value=widget_state["pz_tf_type"], label="Transfer Function Type")
    pz_pz_type = mo.ui.dropdown(["pol", "zer", "pz"], value=widget_state["pz_pz_type"], label="Analysis Type")

    # Noise Analysis UI
    noise_output = mo.ui.dropdown(node_names, label="Output Node", 
                                  value=get_dropdown_value(widget_state["noise_output"], node_names))
    noise_ref = mo.ui.dropdown(node_names, label="Reference Node", 
                               value=get_dropdown_value(widget_state["noise_ref"], node_names))
    noise_src = mo.ui.dropdown(element_names, label="Input Source", 
                               value=get_dropdown_value(widget_state["noise_src"], element_names))
    noise_variation = mo.ui.dropdown(["dec", "oct", "lin"], value=widget_state["noise_variation"], label="Point Spacing")
    noise_points = mo.ui.number(label="Number of Points", value=widget_state["noise_points"], step=1)
    noise_start_freq = mo.ui.number(label="Start Frequency (Hz)", value=widget_state["noise_start_freq"], step=1)
    noise_stop_freq = mo.ui.number(label="Stop Frequency (Hz)", value=widget_state["noise_stop_freq"], step=1000)
    noise_summary = mo.ui.number(label="Points per Summary", value=widget_state["noise_summary"], step=1)

    # Distortion Analysis UI
    disto_variation = mo.ui.dropdown(["dec", "oct", "lin"], value=widget_state["disto_variation"], label="Point Spacing")
    disto_points = mo.ui.number(label="Number of Points", value=widget_state["disto_points"], step=1)
    disto_start_freq = mo.ui.number(label="Start Frequency (Hz)", value=widget_state["disto_start_freq"], step=1)
    disto_stop_freq = mo.ui.number(label="Stop Frequency (Hz)", value=widget_state["disto_stop_freq"], step=1000)
    disto_f2overf1 = mo.ui.number(label="F2/F1 Ratio (optional)", value=widget_state["disto_f2overf1"], step=0.1)
    disto_spectral = mo.ui.checkbox(label="Spectral Analysis", value=widget_state["disto_spectral"])

    # Transfer Function Analysis UI
    tf_output = mo.ui.text(label="Output Variable", value=widget_state["tf_output"])
    tf_input = mo.ui.dropdown(element_names, label="Input Source", 
                              value=get_dropdown_value(widget_state["tf_input"], element_names))

    # DC Sensitivity Analysis UI
    dc_sens_output = mo.ui.text(label="Output Variable", value=widget_state["dc_sens_output"])

    # AC Sensitivity Analysis UI
    ac_sens_output = mo.ui.text(label="Output Variable", value=widget_state["ac_sens_output"])
    ac_sens_variation = mo.ui.dropdown(["dec", "oct", "lin"], value=widget_state["ac_sens_variation"], label="Point Spacing")
    ac_sens_points = mo.ui.number(label="Number of Points", value=widget_state["ac_sens_points"], step=1)
    ac_sens_start_freq = mo.ui.number(label="Start Frequency (Hz)", value=widget_state["ac_sens_start_freq"], step=1)
    ac_sens_stop_freq = mo.ui.number(label="Stop Frequency (Hz)", value=widget_state["ac_sens_stop_freq"], step=1000)

    # Create the tabbed interface for all analysis types
    simtabs = mo.ui.tabs({
        "op": mo.vstack([
            mo.md("**Operating Point Analysis**"),
            mo.md("Find the DC operating point with capacitors open and inductors shorted."),
            op_back_annotate
        ]),

        "dc": mo.vstack([
            mo.md("**DC Sweep Analysis**"),
            mo.md("Compute DC operating point while sweeping independent sources."),
            dc_source,
            dc_start,
            dc_stop,
            dc_step
        ]),

        "ac": mo.vstack([
            mo.md("**AC Small-Signal Analysis**"),
            mo.md("Perform small-signal AC analysis with linearized devices."),
            ac_variation,
            ac_points,
            ac_start_freq,
            ac_stop_freq
        ]),

        "tran": mo.vstack([
            mo.md("**Transient Analysis**"),
            mo.md("Perform non-linear time-domain simulation."),
            tran_step,
            tran_start,
            tran_stop,
            tran_max,
            tran_uic
        ]),

        "pz": mo.vstack([
            mo.md("**Pole-Zero Analysis**"),
            mo.md("Compute poles and zeros of the transfer function."),
            mo.md("*Input Nodes*"),
            pz_node1,
            pz_node2,
            mo.md("*Output Nodes*"),
            pz_node3,
            pz_node4,
            mo.md("*Analysis Options*"),
            pz_tf_type,
            pz_pz_type
        ]),

        "noise": mo.vstack([
            mo.md("**Noise Analysis**"),
            mo.md("Perform stochastic noise analysis at the DC operating point."),
            mo.md("*Signal Configuration*"),
            noise_output,
            noise_ref,
            noise_src,
            mo.md("*Frequency Sweep*"),
            noise_variation,
            noise_points,
            noise_start_freq,
            noise_stop_freq,
            mo.md("*Output Options*"),
            noise_summary
        ]),

        "disto": mo.vstack([
            mo.md("**Distortion Analysis**"),
            mo.md("Analyze harmonic or spectral distortion in the circuit."),
            mo.md("*Frequency Sweep*"),
            disto_variation,
            disto_points,
            disto_start_freq,
            disto_stop_freq,
            mo.md("*Analysis Options*"),
            disto_f2overf1,
            disto_spectral
        ]),

        "tf": mo.vstack([
            mo.md("**Transfer Function Analysis**"),
            mo.md("Compute DC small-signal transfer function, input and output resistance."),
            tf_output,
            tf_input
        ]),

        "dc_sens": mo.vstack([
            mo.md("**DC Sensitivity Analysis**"),
            mo.md("Compute sensitivity of DC operating point to device parameters."),
            dc_sens_output
        ]),

        "ac_sens": mo.vstack([
            mo.md("**AC Sensitivity Analysis**"),
            mo.md("Compute sensitivity of AC values to device parameters."),
            mo.md("*Output Variable*"),
            ac_sens_output,
            mo.md("*Frequency Sweep*"),
            ac_sens_variation,
            ac_sens_points,
            ac_sens_start_freq,
            ac_sens_stop_freq
        ])
    },
        value=widget_state["active_tab"])

    simtabs
    return (
        ac_points,
        ac_sens_output,
        ac_sens_points,
        ac_sens_start_freq,
        ac_sens_stop_freq,
        ac_sens_variation,
        ac_start_freq,
        ac_stop_freq,
        ac_variation,
        dc_sens_output,
        dc_source,
        dc_start,
        dc_step,
        dc_stop,
        disto_f2overf1,
        disto_points,
        disto_spectral,
        disto_start_freq,
        disto_stop_freq,
        disto_variation,
        noise_output,
        noise_points,
        noise_ref,
        noise_src,
        noise_start_freq,
        noise_stop_freq,
        noise_summary,
        noise_variation,
        op_back_annotate,
        pz_node1,
        pz_node2,
        pz_node3,
        pz_node4,
        pz_pz_type,
        pz_tf_type,
        simtabs,
        tf_input,
        tf_output,
        tran_max,
        tran_start,
        tran_step,
        tran_stop,
        tran_uic,
    )


@app.cell(hide_code=True)
def _(mo):
    simname = mo.ui.dropdown(["ngspice-shared", "ngspice-subprocess", "xyce-serial", "xyce-parallel"], value="ngspice-shared", label="Simulation engine")
    simname
    return (simname,)


@app.cell
def _():
    import marimo as mo
    import pandas as pd
    import numpy as np
    import holoviews as hv
    from nyancad.anywidget import schematic_bridge
    from nyancad.netlist import inspice_netlist
    from nyancad.plot import timeplot, sweepplot, bodeplot
    from InSpice import Simulator
    return (
        Simulator,
        bodeplot,
        hv,
        inspice_netlist,
        mo,
        np,
        pd,
        schematic_bridge,
        sweepplot,
        timeplot,
    )


@app.cell
def _(schematic_bridge):
    # Create the schematic reader widget
    reader = schematic_bridge()
    reader
    return (reader,)


@app.cell
def _(inspice_netlist, reader):
    spice = inspice_netlist(reader.name, reader.schematic_data)
    print(spice)
    return (spice,)


@app.cell
def _(
    Simulator,
    ac_points,
    ac_sens_output,
    ac_sens_points,
    ac_sens_start_freq,
    ac_sens_stop_freq,
    ac_sens_variation,
    ac_start_freq,
    ac_stop_freq,
    ac_variation,
    dc_sens_output,
    dc_source,
    dc_start,
    dc_step,
    dc_stop,
    disto_f2overf1,
    disto_points,
    disto_spectral,
    disto_start_freq,
    disto_stop_freq,
    disto_variation,
    noise_output,
    noise_points,
    noise_ref,
    noise_src,
    noise_start_freq,
    noise_stop_freq,
    noise_summary,
    noise_variation,
    pz_node1,
    pz_node2,
    pz_node3,
    pz_node4,
    pz_pz_type,
    pz_tf_type,
    simname,
    simtabs,
    spice,
    tf_input,
    tf_output,
    tran_max,
    tran_start,
    tran_step,
    tran_stop,
    tran_uic,
):
    # Run simulation with direct widget access
    _simulator = Simulator.factory(simulator=simname.value)
    _simulation = _simulator.simulation(spice)

    _analysis_type = simtabs.value
    print(f"Running {_analysis_type} analysis...")

    if _analysis_type == "op":
        analysis = _simulation.operating_point()
    elif _analysis_type == "dc":
        analysis = _simulation.dc(**{dc_source.value: slice(dc_start.value, dc_stop.value, dc_step.value)})
    elif _analysis_type == "ac":
        analysis = _simulation.ac(
            variation=ac_variation.value,
            number_of_points=int(ac_points.value),
            start_frequency=ac_start_freq.value,
            stop_frequency=ac_stop_freq.value
        )
    elif _analysis_type == "tran":
        analysis = _simulation.transient(
            step_time=tran_step.value,
            end_time=tran_stop.value,
            start_time=tran_start.value,
            max_time=tran_max.value if tran_max.value > 0 else None,
            use_initial_condition=tran_uic.value
        )
    elif _analysis_type == "pz":
        analysis = _simulation.polezero(
            node1=pz_node1.value,
            node2=pz_node2.value,
            node3=pz_node3.value,
            node4=pz_node4.value,
            tf_type=pz_tf_type.value,
            pz_type=pz_pz_type.value
        )
    elif _analysis_type == "noise":
        analysis = _simulation.noise(
            output_node=noise_output.value,
            ref_node=noise_ref.value,
            src=noise_src.value,
            variation=noise_variation.value,
            points=int(noise_points.value),
            start_frequency=noise_start_freq.value,
            stop_frequency=noise_stop_freq.value,
            points_per_summary=int(noise_summary.value) if noise_summary.value > 0 else None
        )
    elif _analysis_type == "disto":
        analysis = _simulation.distortion(
            variation=disto_variation.value,
            points=int(disto_points.value),
            start_frequency=disto_start_freq.value,
            stop_frequency=disto_stop_freq.value,
            f2overf1=disto_f2overf1.value if disto_spectral.value else None
        )
    elif _analysis_type == "tf":
        analysis = _simulation.transfer_function(
            outvar=tf_output.value,
            insrc=tf_input.value
        )
    elif _analysis_type == "dc_sens":
        analysis = _simulation.dc_sensitivity(
            output_variable=dc_sens_output.value
        )
    elif _analysis_type == "ac_sens":
        analysis = _simulation.ac_sensitivity(
            output_variable=ac_sens_output.value,
            variation=ac_sens_variation.value,
            number_of_points=int(ac_sens_points.value),
            start_frequency=ac_sens_start_freq.value,
            stop_frequency=ac_sens_stop_freq.value
        )

    print("Analysis completed successfully!")
    return (analysis,)


@app.cell
def _(analysis, np, pd, simtabs, vectors):
    _analysis_type = simtabs.value

    if _analysis_type == "dc":
        df = pd.DataFrame(index=np.array(analysis.sweep))
    elif _analysis_type == "tran":
        df = pd.DataFrame(index=np.array(analysis.time))
    elif _analysis_type == "ac":
        df = pd.DataFrame(index=np.array(analysis.frequency))
    else:
        df = pd.DataFrame()

    for vec in vectors.value:
        df[vec] = np.array(analysis[vec])
    return (df,)


@app.cell
def _():
    # Comprehensive widget state dictionary to persist all values across UI rebuilds
    widget_state = {
        # Tab and selection state
        "active_tab": "op",
        "selected_vectors": [],

        # Operating Point
        "op_back_annotate": True,

        # DC Analysis
        "dc_source": None,  # Will be set to first available element
        "dc_start": 0,
        "dc_stop": 5,
        "dc_step": 0.1,

        # AC Analysis  
        "ac_variation": "dec",
        "ac_points": 10,
        "ac_start_freq": 1,
        "ac_stop_freq": 1e6,

        # Transient Analysis
        "tran_step": 1e-5,
        "tran_start": 0,
        "tran_stop": 1e-3,
        "tran_max": 1e-4,
        "tran_uic": False,

        # Pole-Zero Analysis
        "pz_node1": None,
        "pz_node2": None,
        "pz_node3": None,
        "pz_node4": None,
        "pz_tf_type": "vol",
        "pz_pz_type": "pz",

        # Noise Analysis
        "noise_output": None,
        "noise_ref": None,
        "noise_src": None,
        "noise_variation": "dec",
        "noise_points": 10,
        "noise_start_freq": 10,
        "noise_stop_freq": 1e5,
        "noise_summary": 1,

        # Distortion Analysis
        "disto_variation": "dec",
        "disto_points": 10,
        "disto_start_freq": 100,
        "disto_stop_freq": 1e4,
        "disto_f2overf1": 0.9,
        "disto_spectral": False,

        # Transfer Function Analysis
        "tf_output": "v(out)",
        "tf_input": None,

        # DC Sensitivity Analysis
        "dc_sens_output": "v(out)",

        # AC Sensitivity Analysis
        "ac_sens_output": "v(out)",
        "ac_sens_variation": "dec",
        "ac_sens_points": 10,
        "ac_sens_start_freq": 100,
        "ac_sens_stop_freq": 1e5,
    }
    return (widget_state,)


@app.cell
def _(
    ac_points,
    ac_sens_output,
    ac_sens_points,
    ac_sens_start_freq,
    ac_sens_stop_freq,
    ac_sens_variation,
    ac_start_freq,
    ac_stop_freq,
    ac_variation,
    dc_sens_output,
    dc_source,
    dc_start,
    dc_step,
    dc_stop,
    disto_f2overf1,
    disto_points,
    disto_spectral,
    disto_start_freq,
    disto_stop_freq,
    disto_variation,
    noise_output,
    noise_points,
    noise_ref,
    noise_src,
    noise_start_freq,
    noise_stop_freq,
    noise_summary,
    noise_variation,
    op_back_annotate,
    pz_node1,
    pz_node2,
    pz_node3,
    pz_node4,
    pz_pz_type,
    pz_tf_type,
    simtabs,
    tf_input,
    tf_output,
    tran_max,
    tran_start,
    tran_step,
    tran_stop,
    tran_uic,
    vectors,
    widget_state,
):
    # Update widget_state with all current widget values to persist across UI rebuilds
    widget_state["active_tab"] = simtabs.value
    widget_state["selected_vectors"] = vectors.value

    # Operating Point
    widget_state["op_back_annotate"] = op_back_annotate.value

    # DC Analysis
    widget_state["dc_source"] = dc_source.value
    widget_state["dc_start"] = dc_start.value
    widget_state["dc_stop"] = dc_stop.value
    widget_state["dc_step"] = dc_step.value

    # AC Analysis
    widget_state["ac_variation"] = ac_variation.value
    widget_state["ac_points"] = ac_points.value
    widget_state["ac_start_freq"] = ac_start_freq.value
    widget_state["ac_stop_freq"] = ac_stop_freq.value

    # Transient Analysis
    widget_state["tran_step"] = tran_step.value
    widget_state["tran_start"] = tran_start.value
    widget_state["tran_stop"] = tran_stop.value
    widget_state["tran_max"] = tran_max.value
    widget_state["tran_uic"] = tran_uic.value

    # Pole-Zero Analysis
    widget_state["pz_node1"] = pz_node1.value
    widget_state["pz_node2"] = pz_node2.value
    widget_state["pz_node3"] = pz_node3.value
    widget_state["pz_node4"] = pz_node4.value
    widget_state["pz_tf_type"] = pz_tf_type.value
    widget_state["pz_pz_type"] = pz_pz_type.value

    # Noise Analysis
    widget_state["noise_output"] = noise_output.value
    widget_state["noise_ref"] = noise_ref.value
    widget_state["noise_src"] = noise_src.value
    widget_state["noise_variation"] = noise_variation.value
    widget_state["noise_points"] = noise_points.value
    widget_state["noise_start_freq"] = noise_start_freq.value
    widget_state["noise_stop_freq"] = noise_stop_freq.value
    widget_state["noise_summary"] = noise_summary.value

    # Distortion Analysis
    widget_state["disto_variation"] = disto_variation.value
    widget_state["disto_points"] = disto_points.value
    widget_state["disto_start_freq"] = disto_start_freq.value
    widget_state["disto_stop_freq"] = disto_stop_freq.value
    widget_state["disto_f2overf1"] = disto_f2overf1.value
    widget_state["disto_spectral"] = disto_spectral.value

    # Transfer Function Analysis
    widget_state["tf_output"] = tf_output.value
    widget_state["tf_input"] = tf_input.value

    # DC Sensitivity Analysis
    widget_state["dc_sens_output"] = dc_sens_output.value

    # AC Sensitivity Analysis
    widget_state["ac_sens_output"] = ac_sens_output.value
    widget_state["ac_sens_variation"] = ac_sens_variation.value
    widget_state["ac_sens_points"] = ac_sens_points.value
    widget_state["ac_sens_start_freq"] = ac_sens_start_freq.value
    widget_state["ac_sens_stop_freq"] = ac_sens_stop_freq.value
    return


@app.cell
def _(analysis, op_back_annotate, reader, simtabs):
    if simtabs.value == "op" and op_back_annotate.value:
        data = {k: float(v[0]) for k, v in analysis.nodes.items()}
        reader.simulation_data = {"op": data}
    return


if __name__ == "__main__":
    app.run()
