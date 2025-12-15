---
layout: default
title: Simulator Overview
---
The simulator user interface is a [Panel](https://panel.holoviz.org/) application that uses the [Python API] to run basic simulations and plot their results.

The intention is for the simulator app to remain relatively simple, and use the same [Python API] in [Jupyter Lab](https://jupyter.org/) to create more advanced simulations, analysis, automation, and all that good stuff that would make the interface overly complex.

# Edit

![image](https://user-images.githubusercontent.com/168609/185372214-2f8026cb-0380-477a-b8a1-d4a2f40797fd.png)

This is the main window to configure a simulation. In the sidebar the type of simulation can be selected, which currently offers

1. Operating point
2. Transient simulation
3. AC simulation
4. DC sweep
5. Noise simulation
6. **Unstable** FFT simulation (a transient simulation with FFT plotting)

The fields of each simulation correspond directly with the fields listed in section 15.3 of the [NgSpice manual](https://ngspice.sourceforge.io/docs/ngspice-manual.pdf)

The vectors field allows you to specify which vectors are saved. If you select nothing, NgSpice will save all node voltages and some branch currents. The available vectors are populated from those configured in the [library manager|library manager overview].

The "rerun on change" checkbox will watch the database for changes, and rerun the simulation if any are detected. For small designs and short simulations it can be very helpful to instantly see the result of a change, but for longer simulations it's best to manually rerun the simulation when needed.

The "Back annotate" checkbox will push simulation results back to the database, where they can be picked up from the schematic editor in [annotations]. The intended use is for operating point analysis and small sweeps. It is possible but strongly discouraged to use this setting on transient analysis, which will potentially store millions of points in the database.

At the bottom you can click "Setup" to change the connection to the database and simulator, and "Simulate" to run the simulation.

# Simulate

![image](https://user-images.githubusercontent.com/168609/178224200-fb8c77ad-24ca-479d-b443-5f0bf80ad864.png)

The plot window uses [Holoviews](https://holoviews.org/) and [Datashader](https://datashader.org/) to display high-performance plots of millions of points that stream data from the simulator in real-time. The type of plot is automatically selected per simulation type.

On the left is a list of checkboxes for all the saved vectors in this simulation. Checking/unchecking them will add/remove them from the plot in the middle of the screen.

At this point, if you use the probe tool in the schematic editor to click on a wire, its voltage should be plotted. **Unstable** it is not yet possible to plot device currents this way.

At the bottom is a collapsed terminal window that displays simulator logs. If the simulation doesn't work, this is the first place to check.

Below that are an "Edit" button to go back to the simulation editor, and a "Simulate" button to rerun the current simulation.

# Setup
![image](https://user-images.githubusercontent.com/168609/178220059-2045f9e7-8dec-4520-beaf-5933eaf30cc8.png)

Pressing "Setup" from the edit page brings you to the page where you can configure the schematic and simulation server.

When you click the simulate button from the schematic editor, the schematic and database are set to the one used by the schematic, so you normally would not need to change it. But if you are running the Panel app by itself, the first two fields configure the ID of the schematic in the database, and the database URL.

The next fields concern the simulation server. By default a `localhost` NgSpice server is chosen, which should work for a standard desktop installation, as well as running on Binder. In a custom deployment this could be configured to connect to a simulator on another host.

**Unstable** Currently Xyce support is only partially implemented, and other commercial simulators can be supported as customer demand arises.

**Unstable** The last field allows you to insert arbitrary SPICE commands to configure the simulator. For example to set the temperature or customize solver options.

At the bottom you can click "Edit" to return to the main edit window.