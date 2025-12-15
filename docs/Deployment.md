---
layout: default
title: Deployment
---
The components that make up Mosaic can be deployed in several different ways. See also [Architecture] for a more in-depth look at how the components work together.

# Editor-only

The Mosaic web app is a pure JS application that can store schematics inside the browser in IndexDB/PouchDB, and optionally synchronize with a CouchDB database server. The [online demo](https://nyancad.github.io/Mosaic/app) on the home page is such an installation that talks to a Cloudant instance.

In this scenario it is possible to draw, share, and collaborate on schematics, but there is no simulator available. It is however possible to synchronize with a CouchDB database that can later be used in an environment with a simulator, such as the other options documented below. This allows you to start drawing with zero barrier to entry, and switch to a more elaborate installation later.

Installing the editor this way is as simple as putting the [files](https://github.com/NyanCAD/Mosaic/tree/gh-pages/app) on a webserver.

# Local installation

This method is outlined in the [Getting started instructions]. It basically comes down to installing the following packages

1. CouchDB, for storing and synchronizing schematics
2. Pyttoresque, the Python library that serves Mosaic on Jupyter.
3. NgspiceSimServer, a streaming RPC API to NgSpice.

The way this is done for the [desktop installer](https://github.com/NyanCAD/mosaic-installer) is by using [Constructor](https://github.com/conda/constructor) to create an installer from the relevant Conda packages. It is however also possible to install these components from source, PyPi, and distro packages.

**Unstable** when using CouchDB from a distro package, there is currently no way to use it as the local database in the web app, so PouchDB will be used, with the option to synchronize to the system CouchDB. Configuration options will be provided for this in the future.

# Binder (Hub)

[Binder](https://mybinder.org/) is a cloud service that turns a git repository into a Docker image for running notebooks. It is based on [BinderHub](https://binderhub.readthedocs.io/en/latest/index.html) which you can deploy yourself.

The [Pyttoresque-templates](https://github.com/NyanCAD/Pyttoresque-templates) repository not only contains example notebooks for running simulations, it is also a repository that can be opened in Binder for a full fledged Mosaic installation.

Since Binder does not persist the state of your Docker image, the repository is configured without a local CouchDB installation, relying instead on the same Cloudant instance as the online demo. Trying to open the simulator from the online demo will in fact open the simulator on Binder.

# Custom deployment

An organisation seeking to provide Mosaic to its employees, providing collaboration and sharing while carefully managing access to design files, PDKs and simulators, may want to run Mosaic in various possible hybrid cloud/on-premise/local configurations. Since the web app, database, and simulation server are completely decoupled, any combination is possible.

* You could provide a completely managed [JupyterHub](https://jupyter.org/hub) environment that includes the database and simulation server.
* You could run an on-premise simulation server with NDA-protected PDK files, a hosted Cloudant database, and local Pyttoresque installations.
* You could only run a CouchDB server to synchronize designs between employees who each run their own installations.
* And many more options to suit your needs.