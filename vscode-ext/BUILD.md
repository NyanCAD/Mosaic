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
- **Node.js 18+**
- **npm**

## Setup

From the repository root:

```sh
# Install root dependencies (shadow-cljs, Reagent deps, etc.)
npm install

# Install extension-specific dependencies
cd vscode-ext && npm install && cd ..
```

## Build Commands

All commands run from the **repository root**.

### Development (watch mode)

```sh
npm run vscode:watch
```

This starts shadow-cljs in watch mode for both `:vscode-webview` and `:vscode-ext`
targets, with hot-reload support. To test the extension, open the `vscode-ext/`
folder in VSCode and press **F5** to launch the Extension Development Host.

### Release build

```sh
npm run vscode:build
```

This runs `shadow-cljs release` for both targets with `:simple` optimizations
(extension host) and copies webview assets (CSS, icons, fonts) into `vscode-ext/out/`.

### Package as VSIX

```sh
npm run vscode:package
```

This performs a release build and then runs `vsce package` inside `vscode-ext/`
to produce a `.vsix` file that can be installed in VSCode or published to the
marketplace.

### Via shadow-cljs REPL (Clojure build API)

```sh
npx shadow-cljs clj-run nyancad.mosaic.build/release-vscode   # compile + copy assets
npx shadow-cljs clj-run nyancad.mosaic.build/package-vscode    # compile + copy + vsce package
```

## Output Layout

After a successful build, `vscode-ext/` contains:

```
vscode-ext/
  package.json          # Extension manifest
  .vscodeignore         # Files excluded from VSIX
  out/
    main.js             # Extension host (node-library)
    common.js           # Webview: shared ClojureScript modules
    editor.js           # Webview: schematic editor
    style.css           # Webview: copied from public/css/
    icons/              # Webview: cursor icons
    fonts/              # Webview: web fonts (if present)
```

## Testing Locally

1. Build in watch mode: `npm run vscode:watch`
2. Open `vscode-ext/` as the workspace root in VSCode
3. Press **F5** to launch the Extension Development Host
4. Create or open a `.nyancir` file — the Mosaic schematic editor should appear

For a pre-built test:

1. Build and package: `npm run vscode:package`
2. Install: `code --install-extension vscode-ext/mosaic-schematic-0.0.1.vsix`

## File Format

The `.nyancir` file is a JSON document where each top-level key is a device ID
and the value describes the device (type, position, transform, properties).
The extension uses `jsonc-parser` for incremental JSON edits so that VSCode's
undo/redo and text diff work natively with the schematic data.

## Troubleshooting

- **`vsce` not found**: Run `npm install` inside `vscode-ext/`.
- **Java not found**: shadow-cljs requires a JDK. Install OpenJDK 11+.
- **Webview blank**: Check that `vscode-ext/out/style.css` exists. Run `npm run vscode:assets`.
- **Type warnings during compile**: The `:vscode-ext` target uses `:simple` optimizations;
  add `^js` type hints to interop calls on VSCode API objects.
