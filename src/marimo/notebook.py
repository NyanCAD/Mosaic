import marimo

__generated_with = "0.13.15"
app = marimo.App()


@app.cell
def _():
    import marimo as mo
    import pandas as pd
    import numpy as np
    from nyancad.anywidget import schematic_bridge
    from nyancad.netlist import inspice_netlist
    from nyancad.plot import timeplot, active_traces
    from InSpice import Simulator
    from holoviews.streams import Pipe
    return (
        Pipe,
        Simulator,
        active_traces,
        inspice_netlist,
        mo,
        np,
        pd,
        schematic_bridge,
        timeplot,
    )


@app.cell
def _(df):
    # Generate cleaner column-oriented code
    data_dict = df.to_dict('list')
    print("df = pd.DataFrame(")
    for key, value in data_dict.items():
        print(f"    {repr(key)}: {repr(value)},")
    print(")")
    return


@app.cell
def _(cols, data, timeplot):
    timeplot([data, cols])
    return


@app.cell
def _(analysis, mo):
    vectors = mo.ui.multiselect(
        options=[*analysis.nodes.keys(), *analysis.branches.keys()], label="Vectors"
    )
    vectors
    return (vectors,)


@app.cell
def _(analysis, data, np, pd, vectors):
    df = pd.DataFrame()
    for vec in vectors.options.values():
        df[vec] = np.array(analysis[vec])
        df["index"] = np.array(analysis[vec].abscissa)
    data.update(data=df)
    return (df,)


@app.cell
def _(Pipe, active_traces):
    cols = active_traces()
    data = Pipe(data=[])
    return cols, data


@app.cell
def _(cols, vectors):
    cols.update(cols=vectors.value)
    return


@app.cell(hide_code=True)
def _(mo, spice):
    # Create all widgets directly using circuit data
    element_names = list(spice.element_names)
    node_names = list(spice.node_names)

    # Operating Point Analysis UI
    op_back_annotate = mo.ui.checkbox(label="Back-annotate", value=True)

    # DC Analysis UI
    dc_source = mo.ui.dropdown(element_names, label="Source Name")
    dc_start = mo.ui.number(label="Start Value", value=0, step=0.1)
    dc_stop = mo.ui.number(label="Stop Value", value=5, step=0.1)
    dc_step = mo.ui.number(label="Step Value", value=0.1, step=0.01)

    # AC Analysis UI
    ac_variation = mo.ui.dropdown(["dec", "oct", "lin"], value="dec", label="Point Spacing")
    ac_points = mo.ui.number(label="Number of Points", value=10, step=1)
    ac_start_freq = mo.ui.number(label="Start Frequency (Hz)", value=1, step=1)
    ac_stop_freq = mo.ui.number(label="Stop Frequency (Hz)", value=1e6, step=1000)

    # Transient Analysis UI
    tran_step = mo.ui.number(label="Step Time (s)", value=1e-5, step=1e-6)
    tran_start = mo.ui.number(label="Start Time (s)", value=0, step=1e-6)
    tran_stop = mo.ui.number(label="End Time (s)", value=1e-3, step=1e-4)
    tran_max = mo.ui.number(label="Max Time Step (s)", value=1e-4, step=1e-5)
    tran_uic = mo.ui.checkbox(label="Use Initial Conditions", value=False)

    # Pole-Zero Analysis UI
    pz_node1 = mo.ui.dropdown(node_names, label="Input Node 1")
    pz_node2 = mo.ui.dropdown(node_names, label="Input Node 2")
    pz_node3 = mo.ui.dropdown(node_names, label="Output Node 1")
    pz_node4 = mo.ui.dropdown(node_names, label="Output Node 2")
    pz_tf_type = mo.ui.dropdown(["vol", "cur"], value="vol", label="Transfer Function Type")
    pz_pz_type = mo.ui.dropdown(["pol", "zer", "pz"], value="pz", label="Analysis Type")

    # Noise Analysis UI
    noise_output = mo.ui.dropdown(node_names, label="Output Node")
    noise_ref = mo.ui.dropdown(node_names, label="Reference Node")
    noise_src = mo.ui.dropdown(element_names, label="Input Source")
    noise_variation = mo.ui.dropdown(["dec", "oct", "lin"], value="dec", label="Point Spacing")
    noise_points = mo.ui.number(label="Number of Points", value=10, step=1)
    noise_start_freq = mo.ui.number(label="Start Frequency (Hz)", value=10, step=1)
    noise_stop_freq = mo.ui.number(label="Stop Frequency (Hz)", value=1e5, step=1000)
    noise_summary = mo.ui.number(label="Points per Summary", value=1, step=1)

    # Distortion Analysis UI
    disto_variation = mo.ui.dropdown(["dec", "oct", "lin"], value="dec", label="Point Spacing")
    disto_points = mo.ui.number(label="Number of Points", value=10, step=1)
    disto_start_freq = mo.ui.number(label="Start Frequency (Hz)", value=100, step=1)
    disto_stop_freq = mo.ui.number(label="Stop Frequency (Hz)", value=1e4, step=1000)
    disto_f2overf1 = mo.ui.number(label="F2/F1 Ratio (optional)", value=0.9, step=0.1)
    disto_spectral = mo.ui.checkbox(label="Spectral Analysis", value=False)

    # Transfer Function Analysis UI
    tf_output = mo.ui.text(label="Output Variable", value="v(out)")
    tf_input = mo.ui.dropdown(element_names, label="Input Source")

    # DC Sensitivity Analysis UI
    dc_sens_output = mo.ui.text(label="Output Variable", value="v(out)")

    # AC Sensitivity Analysis UI
    ac_sens_output = mo.ui.text(label="Output Variable", value="v(out)")
    ac_sens_variation = mo.ui.dropdown(["dec", "oct", "lin"], value="dec", label="Point Spacing")
    ac_sens_points = mo.ui.number(label="Number of Points", value=10, step=1)
    ac_sens_start_freq = mo.ui.number(label="Start Frequency (Hz)", value=100, step=1)
    ac_sens_stop_freq = mo.ui.number(label="Stop Frequency (Hz)", value=1e5, step=1000)

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
    })

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


@app.cell
def _(simtabs):
    simtabs.value
    return


@app.cell
def _(mo):
    simname = mo.ui.dropdown(["ngspice-shared", "ngspice-subprocess", "xyce-serial", "xyce-parallel"], value="ngspice-shared", label="Simulation engine")
    simname
    return (simname,)


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
    simulator = Simulator.factory(simulator=simname.value)
    simulation = simulator.simulation(spice)

    analysis_type = simtabs.value
    print(f"Running {analysis_type} analysis...")

    if analysis_type == "op":
        analysis = simulation.operating_point()
    elif analysis_type == "dc":
        analysis = simulation.dc(**{dc_source.value: slice(dc_start.value, dc_stop.value, dc_step.value)})
    elif analysis_type == "ac":
        analysis = simulation.ac(
            variation=ac_variation.value,
            number_of_points=int(ac_points.value),
            start_frequency=ac_start_freq.value,
            stop_frequency=ac_stop_freq.value
        )
    elif analysis_type == "tran":
        analysis = simulation.transient(
            step_time=tran_step.value,
            end_time=tran_stop.value,
            start_time=tran_start.value,
            max_time=tran_max.value if tran_max.value > 0 else None,
            use_initial_condition=tran_uic.value
        )
    elif analysis_type == "pz":
        analysis = simulation.polezero(
            node1=pz_node1.value,
            node2=pz_node2.value,
            node3=pz_node3.value,
            node4=pz_node4.value,
            tf_type=pz_tf_type.value,
            pz_type=pz_pz_type.value
        )
    elif analysis_type == "noise":
        analysis = simulation.noise(
            output_node=noise_output.value,
            ref_node=noise_ref.value,
            src=noise_src.value,
            variation=noise_variation.value,
            points=int(noise_points.value),
            start_frequency=noise_start_freq.value,
            stop_frequency=noise_stop_freq.value,
            points_per_summary=int(noise_summary.value) if noise_summary.value > 0 else None
        )
    elif analysis_type == "disto":
        analysis = simulation.distortion(
            variation=disto_variation.value,
            points=int(disto_points.value),
            start_frequency=disto_start_freq.value,
            stop_frequency=disto_stop_freq.value,
            f2overf1=disto_f2overf1.value if disto_spectral.value else None
        )
    elif analysis_type == "tf":
        analysis = simulation.transfer_function(
            outvar=tf_output.value,
            insrc=tf_input.value
        )
    elif analysis_type == "dc_sens":
        analysis = simulation.dc_sensitivity(
            output_variable=dc_sens_output.value
        )
    elif analysis_type == "ac_sens":
        analysis = simulation.ac_sensitivity(
            output_variable=ac_sens_output.value,
            variation=ac_sens_variation.value,
            number_of_points=int(ac_sens_points.value),
            start_frequency=ac_sens_start_freq.value,
            stop_frequency=ac_sens_stop_freq.value
        )

    print("Analysis completed successfully!")
    analysis
    return (analysis,)


if __name__ == "__main__":
    app.run()
