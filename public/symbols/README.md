# Predefined photonic symbol artwork

`catalog.json` contains 151 symbols. The original 111 SVG objects are extracted from the supplied
OmniGraffle exports: **Fibre Optics**, **Integrated Optics**, and
**Sources & Detectors**. The first two sheets carry the attribution **JWS 2012**.
Each entry retains its source sheet, group ID, attribution, original vector
geometry, colors, and the internal definitions it references. Credits are
excluded from the selectable objects. Names describe the supplied drawings;
the artwork does not define component connectivity or physical ports.

Extraction uses the exported SVG's top-level drawing groups and their measured
SVG bounds, with three source units of padding. Internal gradient/marker
definitions are copied recursively; inherited layer presentation is retained.
The accompanying `.gstencil` documents were inspected to confirm independent
graphics grouping. All three sheets and the individual-object contact sheets
were reviewed visually. The catalog is bundled during Mosaic asset staging;
the application never reads a Downloads folder.

Source SVG SHA-256 checksums:

- Fibre Optics.svg: `1a4fe2c0fa47cbd4718041baf251978fe14941a46b96a3d9a62515fe64d325a5`
- Integrated Optics.svg: `2c9fe0f67b4168e07ef32fe6d824c2abd1663d79cc5267f32bb661c246eebae3`
- Sources & Detectors.svg: `fd10d5a3c255f3e6a8f9c30917df13efff3ac01de4772cafd639135aed69f027`


The original GDSFactory+ starter drawings add **Analog CMOS** (12), **MEMS**
(8), **Quantum RF** (8), and **RF** (12). They use conventional schematic
primitives for transistors, analog blocks, mechanical elements, Josephson
circuits, resonators and microwave signal-chain blocks. They are original
vector artwork by GDSFactory+ contributors (2026), with theme-aware
`currentColor` styling; no third-party symbol library was copied. These are
artwork choices, not pin maps, PDK registrations or simulation models.

The picker sorts every category and search result alphabetically by name,
ignoring case, with stable IDs resolving equal names.
