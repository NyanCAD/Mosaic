---
layout: default
title: Editor Overview
---
![image](https://user-images.githubusercontent.com/168609/177952948-f6c88c9f-5a53-4473-a347-6fa691e78942.png)

This is the main editing window, typically accessed by opening a schematic from the library manager. The main drawing area is a grid where devices and wires can be placed. At the top are the available tools. On the left are devices that can be placed, and on the right are the properties of the selected device.

**Important**: do not forget to add a ground node to your schematic. Without it the simulator will give mysterious errors. A ground node can be placed by pressing **g** or long-pressing the label/port icon in the left sidebar to reveal the ground and supply port types.

See also: [keyboard shortcuts]

# Editing area

The Mosaic editing area uses a fixed, course grid, which makes it very easy to align elements and avoid accidental errors from things that _look_ connected but aren't.

In the default theme, every element has a backdrop to easily distinguish different types. Passive elements are green, power sources are yellow, subcircuits are grey, N-channel devices are blue, P-channel devices are red, and selected items are purple, with their ports highlighted in red.

![image](https://user-images.githubusercontent.com/168609/177957032-e24ca947-67b5-418e-9387-2974f1bac6ed.png)

Wires are made up of straight segments. Selecting a wire segment gives it a dashed line. Double-clicking a wire segment, selects all connected wires. Hovering over a wire gives it an outline, so its endpoints can be easily identified.

When more than two wire ends meet in the same tile, a dot is shown to indicate a connection. When wire midpoints cross each other, no connection is formed, and no dot is shown. When a port or wire is not connected to anything, a dashed red outline is shown.

![image](https://user-images.githubusercontent.com/168609/177957989-fe30686e-e1e6-42ea-9146-f56667509403.png)

When a device port or wire end meets the midpoint of a wire, the wire is split up in segments. **Unstable** care must be taken when moving devices, split segments remain split, and could potentially cause unexpected connections when trying to cross over the segment endpoints with another wire.

When a wire is drawn "through" a device, that is, a new wire crossing multiple ports of the same device, the wire is split at the ports, and the middle section removed. This allows quickly hooking up passives by drawing wires through them.

![image](https://user-images.githubusercontent.com/168609/177960304-07e17d44-faea-450c-a11d-3101bdab031d.png)

By holding down **ctrl** diagonal wires can be drawn.

# Toolbar
![image](https://user-images.githubusercontent.com/168609/177961838-a573ccb3-e036-499d-a7f7-307da68e85f6.png)

At the top of the screen is a toolbar, divided in three sections.

## Pointer tools

Only one of the following tools can be active at the same time, determining the functionality of the mouse.

1. Pointer tool [**esc**], for selecting and dragging elements
2. **W**ire tool, for drawing wires
3. **E**raser tool, for deleting elements
4. Pan tool [**space**], for panning the view (useful on trackpads and 2-button mice)
5. Probe tool, for plotting voltages in a connected simulator

## Selection tools

These tools act on the selected elements

1. Spin (counter) clockwise
3. Flip vertically/horizontally
5. Delete
6. Cut/Copy/Paste

## Global tools

These tools act on the entire schematic

1. Zoom in/out
3. Undo/Redo

## Other items
![image](https://user-images.githubusercontent.com/168609/178006923-52e0457b-62f4-48df-9da5-1ddffd3f697b.png)

The right side of the toolbar shows the following buttons

1. Open the simulator
2. Open Jupyter Lab
3. Open the library manager
4. **Unstable** take a snapshot (in the future there will be an UI for managing edit history)
5. **Unstable** Theme selector (currently features the default and "classic" theme)

**Unstable** most likely a hamburger menu will be added to house some of these buttons and other planned functionality

The toolbar also shows a little indicator when data synchronization is in progress, followed by a checkmark that will then fade away.

# Devices

![image](https://user-images.githubusercontent.com/168609/177964003-f30c136a-f37e-4481-afcc-c0c5276849e5.png)

The toolbar can be used to select items to place visually, as an alternative to the [keyboard shortcuts]. The selected device is highlighted in dark blue. Devices that have a little arrow can be long-pressed to reveal similar devices grouped under it. 

**Unstable** it will be possible to hide/collapse the device toolbar.

The available devices are

1. port (label, ground, supply, [annotation|annotations])
2. resistor
3. inductor
4. capacitor
5. diode
6. voltage source (current source)
7. NMOS (PMOS, NPN, PNP)
8. subcircuit

# Device properties

![image](https://user-images.githubusercontent.com/168609/177965886-46151938-d905-494b-9e0b-e7d9963d3c28.png)

When one or more devices are selected, its properties are shown to the right.

**Unstable** Currently some basic properties are available per interface, and additional ones can be passed in the "spice" field. Check the [ngspice manual](https://ngspice.sourceforge.io/docs/ngspice-manual.pdf) for the correct syntax.