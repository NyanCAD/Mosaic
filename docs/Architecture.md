---
layout: default
title: Architecture
---

![mosaic_arch drawio(1)](https://user-images.githubusercontent.com/168609/178278120-fc9ed037-fdb2-47d6-bcdd-0b12584face6.svg)

The Mosaic application is made up of several components.

* The Mosaic editor itself is a pure frontend web application for editing schematics
   * It stores schematics directly in CouchDB
* Pyttoresque is a Python library that contains all the core functionality for simulation and analysis:
   * Get schematics from CouchDB
   * Run simulations on SimServer and stream back the results (it can start a local SimServer on-demand)
   * A library of analysis and plotting functions
* Jupyter is a web server that hosts
   * JupyterLab, a Python notebook environment
   * The Mosaic editor
   * Jupyter Server Proxy, which hosts
      * Panel, which serves the simulation app "dashboard"
      * CouchDB, which stores all the schematic data

In the desktop installation, the editor, database, and simulation GUI are served by Jupyter, but they can all be hosted separately in a custom [deployment].