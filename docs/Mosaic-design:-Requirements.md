---
layout: default
title: "Mosaic Design: Requirements"
---
The basics
* Draw schematics
* Simulate them
* display results
* automate it

How?

* **Short feedback loops**: Feedback in everything for a designer. Electrical Rule Check and Back-annotation of simulation results at a minimum, real-time feedback when possible.
* **Discoverable functionality**: Experienced designers work with one hand on the mouse and one on the keyboard, but new users should not hit a brick wall until they read the manual.
* **Deep hierarchy**: PCB schematics have maybe 1 or 2 levels of hierarchy, complex ICs have dozens.
* **Alternate cell views**: Whole system simulations depends on swapping out behavioral models and pre/post layout implementations during the design.
* **Avoid accidental changes**: You don't want to accidentally disconnect things while dragging the view around. Counterpoint, when you try to drag a device, this should not be frustrating.
* **Aids for clear schematics**: Make it effortless to label, align, and clean things.
* **Goal-oriented simulation**: "Gain Bandwidth Product" rather than "AC simulation", "1dB compression point" rather than "DC sweep".
* **Edit on the client, simulate on the server**: Simulation is very resource intensive, and PDKs are hard to manage and shrouded in NDAs, but remote desktop provides a laggy experience.
* **Extension API and marketplace**: Allow users to extend the software for unforeseen uses.
* **Library of components and testbenches**: Don't reinvent everything from scratch, compose useful components and tesbenches that produce relevant figures.
* **Gm/Id**: Modern IC design is moving to a design methodology based on Gm/Id rather than overdrive voltage. Encourage and enable this.

# Architecture suggestion

While maybe somewhat controversial in some circles, I think an Electron/web app is the right way forward, since it can provide a zero-install, corss-platform, responsive interface while providing a natural separation of the server proccess. I have [verified](http://pepijndevos.nl/2021/05/05/evaluating-gui-toolkits.html) that web technologies can smoothly pan/zoom through 5K mosfets and plot 10M datapoints, so performance should be no concern.

This allows the frontend to be written in modern UI frameworks such as React, while the backend can be written in Python to expose scripting functionality. I suggest using Jupyter Lab for providing language kernels and an interactive simulation and plotting interface.

# Inspiration

* [Analog Designer Toolbox](https://www.master-micro.com/analog-designers-toolbox)
* [Bret Victor: Inventing on Principle](https://www.youtube.com/watch?v=PUv66718DII&t=1406s)
* [Rich Hickey: Simple Made easy](https://www.infoq.com/presentations/Simple-Made-Easy/)
* [Blender: search](https://docs.blender.org/manual/en/latest/interface/controls/templates/operator_search.html)
* [VS Code: Extension API](https://code.visualstudio.com/api)
* [Figma](https://www.figma.com)
* [TRIZ](https://en.wikipedia.org/wiki/TRIZ)

# Excerpts from interview notes

Experience uses rely heavily on keyboard shortcuts:

> Uses the layout editor with one hand on the mouse, one hand on the keyboard. Most common actions like zooming, measuring, drawing rectangles have single key commands.  Open source tools often focus on “cute” icons and boxes. Experienced users don’t use them.

> When I press “I” instance should pop up, when I press ctrl-e I should go up the hierarchy. These are important things.

IC deisgn is very conservative and risk averse:

> The program has not changed in the past 30 years, so people are very used to it.

> EDA hasn’t been disrupted for 30 years. Still cadence. Why? If a car doesn’t have a driving wheel, how many people will want to use it?

X11 forwarding or remote desktop:

> All the mouse tricks don’t work if you’re in a different window manager

> Efabless uses remote desktop, this should go away. There is no reason to use remote desktop.

> Remote desktop can be very laggy. A webapp is very interesting, and doesn’t exist in IC design at all.

Setup & management:

> Usually in a group of 10 you need an 11th CAD engineer to manage the installation.

> The most tedious thing is to manage your library of models.

> If you point to the wrong libraries you get wrong results. If the schematic tool could show you which symbol is using which library model that would be very useful.

More than just network topology

> Custom IC design is graphic, and has to be tidy, it has to convey an idea. Schematic contains artisanship.

Accidental changes

> if you accidentally press something, especially M(ove), it will move something and then it’s just… gone. So save often, and keep lots of versions.

Version control

> some aspects of the tool are overpriced. Example: waveform viewer, nicer backannotating. Version control for layouts

> because of NDA they don’t use git(hub?)

Partitioning the system

> these systems are reaching a size where it becomes infeasible to simulate the full system at the transistor level. But you can’t do fully digital simulation because the PLL oscillator needs some analog performance metrics like jitter. Partitioning your design is critical for this approach. He relied heavily on experience colleagues for advice.

> Full-system simulation is something thet needs to be improved. Ngspice is not up to the task, maybe Xyce. Spectre is very fast, but even then a full chip takes very long, maybe days. For a simple analog chip, only test parts of the design, and do very limited top-level simulations to verify everything is connected. For more complex chips, write behavioral verilog-a simulations that are 100x faster. Art of system engineering is designing the interfaces so you can verify the interfaces.

Sharing knowledge

> Hard to find guidance with vendor tools. Comes down to experience and colleagues. Community is very closed. Typical solution is to go ask the vendor. I’d like to be able to google something. Lots of “built in” knowledge you can’t see from a schematic.

> With sky130 there will be all these things on github that you can use, but trouble is you never really know how good they are. So you’re going to want to test it. Say there are three opamps you kinda want to just load them up and see. This one doesn’t perform etc. Even a problem with opencores digital stuff, it’s free but you still have to verify it works.

Extensibility

> Firefox and and Atom did something different: allow extensions, enabling unforeseen uses. Extension marketplace.

Guided design

> When you start to work with a new process you need to understand what the characteristics are like. Guided characterization of a transistor, pick technology, give parameters, get characterization plots such as transconductor efficiency, intrinsic gain, transit frequency.

> Guided design is needed, I like what you are doing [gesturing at my .gif of real time operating point annotation]

> Try to walk in the steps of the designer and make that easy. If a designer wants to characterize a process, make that easy. Give some figures of merit. 

Reusable components/tests

> If you look into the literature there is a finite number of topologies. So you can have a library of premade subsystems.

> When I start analog design I have to select a topology, usually based on experience.

> When making a comparator, you need to show the comparator meets the relevant specs for a comparator, offset, delay, etc.

> When you make an opamp, you kinda want to test the same thing always, You want to test the gain, test the stability, PSSR, textbook things. But every time you spend so much time building you testbench. Feels like a waste.

> Common functions like FFT (samples, coherence) and SNDR are hard to get right, and no reusable blocks exist.

Simulation automation

> When you run the simulation, you use python+matplotlib or Octave to generate plots. He used Octave from the first project, but he’s migrating back to Python because it’s a lot more versatile.

> Started working on sky130 and found “holes” in the tools, and turned to python to write some functions, which over time grew into a library, which became a bit of a house of cards.

> Not everyone has a software background. There is a divide between analog and digital designers, you probably saw it, people focused on analog tend not to have the automation aspect.

> Traditional analog design more clicky things than cody things, but he thinks that should change. Features like unit test should really come more into analog design.

> Instead of monte-carlo, measure sensitivity and use statistical variations.

> The idea to have a complete testbench with all the specs you care about, that was important.
It generates like a dashboard, or a datasheet.

> Post-layout simulation is actually unproductive. Specify the parasitics in the schematic. If you don’t meet the goal after parasitic extraction, you don’t even have to simulate.

Feedback

> Seems like a small thing, but virtuoso can do back annotation, which is really useful to see that your transistors are in the correct operating region. Even in cadence it’s a few steps to do this.

Hierarchy

> Looked into KiCad in the beginning, but the lack of hierarchy killed it. In a complex IC you can have up to 100 levels. Hierarchy is your friend because you can reuse stuff across designs.

Gm/Id

> He does DC calculations very carefully to make sure all the transistors are in the right operating region. For AC simulation it gets very complicated, so you have to omit a lot of details. He uses gm/Id or gm/Vo for AC. For DC he uses a simple quadratic model. For AC he only gets the limits. He plots several key parameters and uses those to estimate some AC things, which can easily be 30% off.

> Analog designer toolbox: gm/id methodology
