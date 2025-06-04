import marimo

__generated_with = "0.9.14"
app = marimo.App(width="medium")


@app.cell
def __():
    import marimo as mo
    return mo,


@app.cell
def __(mo):
    mo.md("# Test Notebook")
    return


@app.cell
def __(mo):
    mo.md("This is a simple test notebook to verify the marimo export functionality.")
    return


if __name__ == "__main__":
    app.run()
