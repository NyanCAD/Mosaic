---
layout: default
title: Library Manager Overview
---
![image](https://user-images.githubusercontent.com/168609/185373778-b81ae298-7875-4e28-ad4f-ea2d347fc42c.png)

The library manager is where you create, manage, and organize interfaces, schematics and models. It is structured in 3 panels, on the left you connect to a workspace and browse interfaces grouped under categories, in the middle panel you see all the models and schematics that belong to that interface. And to the right you see all the properties of the schematic/model (top) and the interface (bottom).

In Mosaic, and interface defines the symbol and ports of a group of models/schematics that can be easily swapped out. This allows for easy adjustment of testbenches, design reuse, and testing different topologies. In Mosaic, all MOSFETs from different PDKs have the same interface, allowing to easily adapt a design to a new technology.

# Workspaces

![image](https://user-images.githubusercontent.com/168609/177536472-0b0f6dfe-5ab5-43ca-a2ad-03df61bcae8f.png)

By default Mosaic connects to the local "schematics" database, which is synchronized with a public Cloudant database. In the bottom left it is possible to connect to a different workspace.

The name of the workspace refers to a database that is stored inside your browser or desktop installation. You can choose any arbitrary name you want. If no URL is specified, your designs are only stored locally.

The URL field specifies a CouchDB database, which is used to synchronize your workspace. This can be used to collaborate with other people or store your designs in the cloud.

Note that only the local database in the desktop installation is accessible to the simulator, when using the in-browser storage of the online editor, a synchronization URL has to be provided to be able to simulate your designs.

**Unstable** Currently to access a private cloud database the database credentials have to be provided in the URL in the form of `https://username:password@theurl`, which will be changed in the future for increased security. In a desktop installation a more secure way would be to set up replication in the local CouchDB, and leave the sync URL empty in Mosaic.

# Interface Categories

![image](https://user-images.githubusercontent.com/168609/177535593-e83b15a9-adfb-4fcb-abce-0330597acf07.png)

On the left is a list with categories, which can be unfolded to reveal the interfaces inside them. Categories can be arbitrarily nested, and interfaces can be in multiple categories. The "Everything" category shows all the interfaces as you might expect.

Categories belong to schematics/models rather than interfaces, so when an interface is selected, the middle area only shows the models and schematics belonging to that interface that also belong to the current category. Selecting an interface in the "Everything" category shows all its models and schematics.

**Unstable** Categories are currently assigned in the schematic properties on the right, but in the future could be drag & dropped.

An interface can be renamed in a manner similar to file names, by first selecting them, and then clicking on the text again. Deleting an interface is done by selecting "delete" from the context menu.

# Adding new designs

![image](https://user-images.githubusercontent.com/168609/177538640-a1e05880-ed18-43a7-88d2-88c1cc621caf.png)

At the top, there are two buttons to add new interfaces, schematics, and models. The "Add schematic" button can be unfolded to reveal the "Add SPICE model" button. All buttons will open a modal dialog to prompt for the name of the new interface/schematic/model.

Adding a new interface adds an entry to the "Everything" category with the provided name. The interface names of built-in elements have special meaning and schematics/models under them will show up in the editor as models of these elements. The special interfaces are

* `resistor`
* `capacitor`
* `inductor`
* `diode`
* `vsource`
* `isource`
* `nmos`
* `pmos`
* `npn`
* `pnp`

Once an interface has been created, you can add schematics and models to them. A schematic can be opened with Mosaic, a SPICE model only takes a template for its declaration and use.

# Schematics & Models
![image](https://user-images.githubusercontent.com/168609/177541613-0b1022f8-0c9e-4a6a-b2d5-847d50fbbe24.png)

In the middle of the screen is a list of all the models and schematics of the currently selected interface that belong to the currently selected category.

To edit a schematic, double-click on it, press the "edit" link in the right panel, or select "edit" in the context menu.

A schematic or model can be renamed in a manner similar to file names, by first selecting them, and then clicking on the text again. Deleting an interface is done by selecting "delete" from the context menu.

# Schematic & Model properties
![image](https://user-images.githubusercontent.com/168609/185373657-9c1290bb-63ba-4ad2-af31-6155d6a26260.png)

On the top right the properties of the selected schematic or model are shown. For both types, the categories the items belongs to can be entered here. Categories are separated by a comma, and subcategories are created by a slash.

A SPICE model is defined by several fields:
* The simulator dialect that is being edited
* a reference template that is used every time the model is inserted into the generated SPICE netlist
* a declaration template is provided that is only used once to define the model. This can be either a literal spice model or an include/library statement. Declaration templates are unique across models, so if the same include is used in multiple models, it is only put in the spice netlist once.
* the spice vectors that this model exposes (gm, vdsat, etc.), these can then be selected in the simulator for saving and plotting.

**Unstable** in future versions the template language could be more extensive to for example print individual ports and properties.

**Unstable** the intention is that for schematics, a preview of the schematic is shown here, but this is not yet fully implemented.

# Interface properties
![image](https://user-images.githubusercontent.com/168609/177543509-85c77d27-77f5-4b51-ae3e-f6d13c98aa5e.png)

**Unstable** editing interfaces is currently not very intuitive, and needs to be completely redesigned.

At the top the width and height of the interface is set. This excludes a perimeter for ports. All the built-in interfaces are 1x1 and ignore these settings.

Next a grid of checkboxes is shown, that indicate on which grid cells a port needs to be drawn which a wire can connect to.
For each checked box, a text field appears that allows naming the port. This name should correspond to a port element inside the corresponding schematics.

And finally, an image URL can be provided for a custom icon. Note that each grid cell is 50x50 pixels. If no icon URL is provided, a generic box with pins is drawn.