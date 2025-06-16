import marimo

__generated_with = "0.13.15"
app = marimo.App()


@app.cell
def _():
    import marimo as mo
    return (mo,)


@app.cell
async def _():
    import micropip

    # Install the nyancad package from local wheel
    await micropip.install("http://localhost:8080/wheels/nyancad-0.1.0-py3-none-any.whl")

    from nyancad import SchematicReader
    return (SchematicReader,)


@app.cell
def _(SchematicReader, mo):
    # Create the schematic reader widget
    reader = mo.ui.anywidget(SchematicReader())
    reader
    return (reader,)


@app.cell
def _(reader):
    reader.schematic_data
    return


if __name__ == "__main__":
    app.run()
