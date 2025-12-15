---
layout: default
title: Getting Started Instructions
---

# Installation
## Windows
1. Download windows-installer from the [release](https://github.com/NyanCAD/Mosaic/releases) page
2. unzip it (right click, extract all)
3. Run the installer
4. Windows will show a dialog that it protected your PC, click "more info" and "run anyway" (pinky promise I'm not trying to hack your PC)  
   ![image](https://user-images.githubusercontent.com/168609/167245218-8a42cb91-d2d7-45d9-be38-298d057184b0.png)
5. Keep mashing "next" as usual  
   ![image](https://user-images.githubusercontent.com/168609/167245521-e7ee4411-bcba-4dba-9ff8-64b9a9729ed5.png)
6. Launch Mosaic from the start menu  
  ![stertmenu](https://user-images.githubusercontent.com/168609/167245702-8400dbe3-3aa9-4ba3-98bd-d532f5ae849d.png)
7. Wait for the app to start, which will take a while, open a browser window, a terminal, and some firewall prompts. Allow the firewall access and do not close the terminal. (hopefully this will be improved in the future)  
   ![image](https://user-images.githubusercontent.com/168609/167246691-5929b731-5e48-4790-85c1-2dd1e1ff27e8.png)

Note: Closing the browser does not close the app. Currently the only way to close the app is from JupyterLab (http://localhost:8888/lab under File, Shut Down). If you closed the window and would like to reopen it, don't use the start icon, just navigate to http://localhost:8888/mosaic/

## Mac/Linux

1. Download the installer for your platform from the [release](https://github.com/NyanCAD/Mosaic/releases) page, and extract it.
2. Open a terminal in the extracted folder and (using the relevant file name)
```
chmod +x Mosaic-0.5.6-Linux-x86_64.sh
./Mosaic-0.5.6-Linux-x86_64.sh
```
Then follow the instructions, and activate the newly installed environment. (this step depends on your shell, and if you chose to initialize conda)
Then start the app using `jupyter mosaic`.

## Anaconda
Install [miniconda](https://docs.conda.io/en/latest/miniconda.html) and run

```
conda create -n mosaic -c pepijndevos -c pyviz -c conda-forge couchdb pyttoresque jupyterlab-mosaic ngspicesimserver
conda activate mosaic
jupyter mosaic
```

Note: pyttoresque and jupyterlab-mosaic are also available from pypi, if you want to bring your own simserver and database.

# Setup

1. By default you are in the "schematics" workspace, which is synchronized to the cloud. You can change the name and leave the URL field empty for an offline, non-synchronized workspace.
   ![image](https://user-images.githubusercontent.com/168609/177536472-0b0f6dfe-5ab5-43ca-a2ad-03df61bcae8f.png)  
2. Use the "+ Add interface" button to add a "npn" interface, and select it in the side bar
2. Use the arrow on the "+ Add schematic" button to "+ Add SPICE model" and name it "BF199"  
   ![image](https://user-images.githubusercontent.com/168609/177538640-a1e05880-ed18-43a7-88d2-88c1cc621caf.png)
2. Set the reference template to `Q{name} {ports} {properties}` and the declaration template to `.MODEL BF199 NPN(IS=4.031E-16 NF=0.9847 ISE=9.187E-17 NE=1.24 BF=122.5  IKF=0.065 VAF=135 NR=0.991 ISC=4.1E-13 NC=1.37 BR=5.036 IKR=0.04  VAR=8 RB=16 IRB=0.0004 RBM=8 RE=0.402 RC=5 XTB=0 EG=1.11 XTI=3 CJE=2.258E-12 VJE=0.444 MJE=0.136 TF=2.92E-10 XTF=8 VTF=8 ITF=0.14 PTF=20 CJC=9.333E-13 VJC=0.2488 MJC=0.1974 XCJC=0.86 TR=3.5E-08 CJS = 0 VJS=0.75 MJS=0.333 FC=0.9001)`
2. Use the "+ Add interface" button to add a interface for your project, and select it in the side bar  
   ![image](https://user-images.githubusercontent.com/168609/177953960-75821829-b661-4df9-afbd-f130288f6e24.png)
3. Use the "+ Add schematic" button to add one or more designs to your interface  
   ![image](https://user-images.githubusercontent.com/168609/177954064-3f660ce1-191d-4c98-92cc-075327ebc804.png)
3. Double-click a schematic to open it in the editor

Note: Mosaic allows collaborative editing by synchronizing the local CouchDB to that of someone else. The "schematics" database is set up to synchronize to a public cloud database. An adventurous user could experiment with the URL field of the library properties to sync up their database with that of their team mate. The local database settings can be accessed at http://localhost:5984/_utils/ (admin/admin) and needs to be configured with `httpd` `bind` `0.0.0.0` to be accessible from the outside.

# Editing
![image](https://user-images.githubusercontent.com/168609/167248804-cd57b497-0413-4c84-ae47-b93b2c9e7224.png)

You are now in the main editor where you can draw your design. Hovering the mouse over a tool will tell you what it does and what keyboard shortcut it has.

* Left mouse button is used for placing elements and wires
* Right mouse button cancels an action (as does escape)
* Middle mouse button pans the view (or space bar + left mouse)
* Scrolling zooms in an out
* Elements can be selected from the left side bar (or with their shortcut, [R]esistor, [V]oltage source, etc.)
* Wires can be placed with the pencil tool [W]
* Selected elements can be [S]pun, [F]lipped, or [del]eted with the buttons at the top or their respective shortcuts.
* cut/copy/paste and undo/redo works as usual

**Do not forget to add a [G]round node to your design**

To add a `BF199` BJT, press B or long-press the transistor icon, and select the NPN transistor.
After placing it in your schematic, click on it to select the model in the right sidebar.

![image](https://user-images.githubusercontent.com/168609/167249214-40baf1fc-da33-4b4a-bdfd-dfd0025fd9cb.png)  
![image](https://user-images.githubusercontent.com/168609/167249240-940753af-c230-42ee-851f-5aef287e0b5c.png)

# Simulation

1. Click the "Open simulator" icon in the top right to start the simulation UI. (the other button opens JupyterLab, for the adventurous)  
   ![image](https://user-images.githubusercontent.com/168609/167249523-e5dd16bd-24ad-4449-810e-9001f7bda699.png)
2. Another set of terminal windows and firewall messages will pop up. Allow as before.  
   ![image](https://user-images.githubusercontent.com/168609/167249616-968ffe14-d9e7-4ca8-9ec7-bc91a98ba228.png)
3. Select the type of simulation you want to run, and press "simulate"  
   ![image](https://user-images.githubusercontent.com/168609/167250176-f7f0b179-9bad-4467-b6db-6da39d648505.png)
4. Use the probe tool in the schematic editor to select nets to plot, or select them directly in the simulator.
