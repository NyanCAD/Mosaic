# User flow

This page describes a very basic flow of designing and simulating a simple design.

The first step is to open or creat a new schematic in the library manager. Here the user can see all the existing designs they have access to, and add new ones.

![library manager](High-Fidelity/Library%20Manager.png)

After creating or opening a schematic the user is in the main editor window. There they can place components and wires.
They can also use other schematics in the same library.

(note that tabs are only used in a possible desktop app, the web app will use native browser tabs)

![main editer](High-Fidelity/Main%20Editor%20–%20Tabs.png)

Once the user has drawn their schematic they will want to test it, so they press the simulation button on the toolbar, which brings up the simulation window.
On the first page they select the type of simulation they want to configure, and the parameters for that simulation.
They can also open the simulation in a new tab.

![simulation window](High-Fidelity/My%20Notebook%20-%20Simulation%20Selection.png)

After running the simulation, in this step the user selects the signals from the simulator to plot.
An hierarchical list is shown on the side, which displays all the components and their signals.
Once selected, a simple plot is shown with the selected signals.

![simulation window](High-Fidelity/My%20Notebook%20-%20Plot%20and%20Code.png)

After completing the simulation, the user can go back to modify the schematic, run a different simulation, or export the simulation.

Exporting a simulation will generate Python code in a Jupyter notebook. This is the recomended way to run more complex simulations.
The user can for example export multiple long-running simulations, set up complex measurements and parameter sweeps, and then modify the output to their liking.
