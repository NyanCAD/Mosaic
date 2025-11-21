<!--
SPDX-FileCopyrightText: 2022 Pepijn de Vos

SPDX-License-Identifier: MPL-2.0
-->

# Mosaic
A modern schematic entry and simulation program.

https://nyancad.com

This project was funded through the <a href="/PET">NGI0 PET</a> Fund, a fund established by <a href="https://nlnet.nl">NLnet</a> with financial support from the European Commission's <a href="https://ngi.eu">Next Generation Internet</a> programme, under the aegis of DG Communications Networks, Content and Technology under grant agreement N<sup>o</sup> 825310.

## Usage

### Web Application
Visit https://nyancad.com to use Mosaic directly in your browser.

### Server Installation

**Installation:**
```bash
pip install nyancad-server
```

**Run server:**
```bash
nyancad-server
```

Or using pipx (recommended for isolated installation):
```bash
pipx run nyancad-server
```

### Python Library

The `nyancad` package provides integration with Jupyter and marimo notebooks:

```bash
pip install nyancad
```

## Development

For development setup and build instructions, see [DEVELOPMENT.md](DEVELOPMENT.md).
