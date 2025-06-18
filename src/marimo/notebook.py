import marimo

__generated_with = "0.13.15"
app = marimo.App()


@app.cell
def _():
    import marimo as mo
    return


@app.cell
async def _():
    import micropip

    # Install the nyancad package from local wheel
    await micropip.install(
        "http://localhost:8080/wheels/nyancad-0.1.0-py3-none-any.whl"
    )

    from nyancad.anywidget import schematic_bridge
    from nyancad.netlist import spice_netlist
    return schematic_bridge, spice_netlist


@app.cell
def _(schematic_bridge):
    # Create the schematic reader widget
    reader = schematic_bridge()
    reader
    return (reader,)


@app.cell
def _(reader, spice_netlist):
    spice = spice_netlist(reader.name, reader.schematic_data)
    return (spice,)


@app.cell
def _(reader, spice):
    reader.simulation_data = {"spice": spice}
    return


if __name__ == "__main__":
    app.run()
