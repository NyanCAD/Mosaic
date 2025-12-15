---
layout: default
title: Annotations
---
![image](https://user-images.githubusercontent.com/168609/185366098-182c4a2c-c016-4aa5-bebb-972c623baa33.png)

A schematic is not just a tool to make a simulator understand your intention, it is also a tool to document your intentions to other designers including your future self. Furthermore, real-time back-annotations from the simulator can be used as an interactive design tool.

Mosaic contains several types of annotations.

# Net labels

![image](https://user-images.githubusercontent.com/168609/185367113-e6306f82-752c-4593-8a0e-21af54f65928.png)

These are basic text labels that indicate the name and functionality of a net, and there are 4 types

1. A port to indicate an input or output of the circuit
2. A power supply symbol
3. A ground symbol
4. A simple net name

As far as the circuit is concerned they all carry the same meaning. The net name will be used in the simulator. They will be connected to the subcircuit connection with the same name set in the [library manager](Library-Manager-Overview.html). For example, an interface with a connection called "output" will connect with the schematic net called "output" as indicated by a label on the net.

![image](https://user-images.githubusercontent.com/168609/185370012-b7c587ce-b39a-49d9-a139-8d7770e3f9e6.png)
![image](https://user-images.githubusercontent.com/168609/185370346-bb4789aa-518c-4972-aafe-42e9a0afa567.png)

# Text blocks

These are just free-form text areas that carry no meaning to the simulator, they are purely for human consumption. You can use them to explain or draw attention to certain things, and clarify design intent. Every device comes with a text block that shows its name and some other things by default, but they can also be placed as a stand-alone component.

Their real power is that they are live templates that can display circuit properties and simulation results, which will update in real time when you change a parameter or run a simulation that is configured to back-annotate results.

## Available data

Annotations have access to `schem` which is a map of device names. For example, the resistance of `R1` can be access using `schem.R1.props.resistance`. Additionally, in the device-specific templates, `this` points to the current device.

The other major item is `res` which is a map of simulations, which in turns contain vectors. For example, the NgSpice operating current of R1 can be obtained with `res.Operating Point.@rr1[i].0`. (operating point results are a vector of length 1)

It is also possible to print entire maps and vectors, so if you're unsure of the exact key, you can just start from `res` or `schem` and narrow it down from there.

## Syntax

The syntax is a subset of [PEP 3101](https://peps.python.org/pep-3101/).

* Interpolation is denoted with `&#123;...&#125;`
* Literal braces can be inserted with `&#123;&#123;`
* Nested maps are accessed with dot notation `foo.bar.baz`, (they are not evaluated as Python expressions)
* It supports float and exponential notation with precision `:.2f` `:.5e`

## Custom annotations

A notebook can call `pyttoresque.netlist.SchematicService.save_simulation` with additional data to make it available inside the schematic editor.

https://github.com/NyanCAD/Pyttoresque-templates/blob/main/ac_annotate.ipynb is an example of a notebook that runs a real-time operating point and AC analysis, and also annotates the bandwidth of the filter. Here it is in action:

![output](https://user-images.githubusercontent.com/168609/185380622-c0be5d37-5a35-4f43-8261-f67d2f7d4baa.gif)
