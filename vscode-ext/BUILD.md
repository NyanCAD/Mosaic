<!--
SPDX-FileCopyrightText: 2022 Pepijn de Vos

SPDX-License-Identifier: MPL-2.0
-->

# Building the Mosaic VSCode Extension

The Mosaic VSCode extension provides a custom editor for `.nyancir` schematic files.
It is built from ClojureScript using shadow-cljs, producing two compilation targets
that are packaged together into a VSIX bundle.

## Architecture

The extension consists of two shadow-cljs build targets:

| Target | Type | Output | Purpose |
|--------|------|--------|---------|
| `:vscode-ext` | `:node-library` | `vscode-ext/out/main.js` | Extension host — registers the `CustomTextEditorProvider`, manages document lifecycle |
| `:vscode-webview` | `:browser` | `vscode-ext/out/` | Webview frontend — the schematic editor UI, compiled with `:vscode` reader feature |

The `:vscode` reader conditional (`#?(:vscode ...)`) switches code paths that differ
between browser and VSCode environments. For example, the editor uses `JsAtom`
(backed by `jsonc-parser`) instead of PouchDB for state management in the webview.

### Key source files

- `src/main/nyancad/mosaic/extension.cljs` — Extension host entry point (`activate`/`deactivate`)
- `src/main/nyancad/mosaic/jsatom.cljs` — Reactive atom backed by VSCode text document buffer
- `src/main/nyancad/mosaic/editor.cljc` — Schematic editor (shared via reader conditionals)
- `src/main/nyancad/mosaic/common.cljc` — Shared utilities (shared via reader conditionals)

## Prerequisites

- **Java 11+** (for shadow-cljs / ClojureScript compiler)
- **Node.js 18+** and **npm**

On macOS: `brew install node openjdk`

## Setup

From the repository root:

```sh
npm install
```

This installs shadow-cljs, the JS dependencies used by the ClojureScript code,
and `@vscode/vsce` for packaging. The extension's own `jsonc-parser` dependency
is resolved from the root `node_modules` during compilation.

## Build Commands

All commands run from the **repository root** via shadow-cljs.

### Development (watch mode)

```sh
npx shadow-cljs watch vscode-webview vscode-ext
```

This starts shadow-cljs in watch mode for both targets with hot-reload support.
To test, open the `vscode-ext/` folder in VSCode and press **F5** to launch
the Extension Development Host. You need to manually copy assets once first
(see release build below), or just run a full release build first.

### Release build

```sh
npx shadow-cljs clj-run nyancad.mosaic.build/release-vscode
```

This compiles both targets with `:simple` optimizations and copies webview
assets (CSS, icons, fonts) from `public/css/` into `vscode-ext/out/`.

### Package as VSIX

```sh
npx shadow-cljs clj-run nyancad.mosaic.build/package-vscode
```

This performs a release build and then runs `vsce package` to produce a `.vsix`
file in `vscode-ext/` that can be installed in VSCode or published to the
marketplace.

## Output Layout

After a successful build, `vscode-ext/` contains:

```
vscode-ext/
  package.json          # Extension manifest
  .vscodeignore         # Files excluded from VSIX
  out/
    main.js             # Extension host (node-library)
    editor.js           # Webview: schematic editor (single bundle)
    style.css           # Webview: copied from public/css/
    icons/              # Webview: cursor icons
    fonts/              # Webview: web fonts (if present)
```

## Testing Locally

1. Build: `npx shadow-cljs clj-run nyancad.mosaic.build/release-vscode`
2. Open `vscode-ext/` as the workspace root in VSCode
3. Press **F5** to launch the Extension Development Host
4. Create or open a `.nyancir` file — the Mosaic schematic editor should appear

To install a packaged build:

```sh
npx shadow-cljs clj-run nyancad.mosaic.build/package-vscode
code --install-extension vscode-ext/mosaic-schematic-*.vsix
```

## File Format

The `.nyancir` file is a JSON document where each top-level key is a device ID
and the value describes the device (type, position, transform, properties).
The extension uses `jsonc-parser` for incremental JSON edits so that VSCode's
undo/redo and text diff work natively with the schematic data.

## Troubleshooting

- **Java not found**: shadow-cljs requires a JDK. Install OpenJDK 11+.
- **Webview blank**: Check that `vscode-ext/out/style.css` exists. The release
  build copies it automatically; for watch mode you need one release build first.
- **Type warnings during compile**: The `:vscode-ext` target uses `:simple`
  optimizations; add `^js` type hints to interop calls on VSCode API objects.
