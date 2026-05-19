<!--
SPDX-FileCopyrightText: 2022 Pepijn de Vos

SPDX-License-Identifier: MPL-2.0
-->

# Development Guide

This document contains instructions for developers and maintainers working on the Mosaic codebase.

## Development Setup

### Prerequisites
- Node.js and npm
- Java 21 (for Shadow CLJS)
- Python 3.8+ with pip
- (Optional) CouchDB instance for view deployment

### Initial Setup

1. **Install dependencies:**
   ```bash
   npm install
   ```

2. **Set up Python virtual environment:**
   ```bash
   python -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   pip install marimo build
   ```

## Development Workflow

### Frontend Development

**Development server with auto-reload:**
```bash
npx shadow-cljs watch frontend
```
This starts a development server at http://localhost:8080 with live code reloading.

### Complete Release Build

**Build everything (frontend, marimo WASM, CouchDB views):**
```bash
npx shadow-cljs clj-run nyancad.mosaic.build/release
```

This performs a full production build including:
- Frontend compilation (ClojureScript modules)
- Marimo notebook WASM export
- CouchDB view compilation
- CouchDB view deployment (requires environment variables)

### CouchDB Deployment

The release build automatically deploys CouchDB views if these environment variables are set:
```bash
export COUCHDB_URL=https://api.nyancad.com
export COUCHDB_ADMIN_USER=admin
export COUCHDB_ADMIN_PASS=your_password
```

### VS Code Extension

Install the Mosaic JS dependencies locally first:
```bash
cd frontend/mosaic
npm install --workspaces=false
```

Inside GDSFactory+, the canonical build entrypoints are:
```bash
npx moon run mosaic:compile-vscode
npx moon run mosaic:watch-vscode
```

**Build the extension (compiles ClojureScript + copies CSS/icons):**
```bash
npx shadow-cljs clj-run nyancad.mosaic.build/release-vscode
```

**F5 (Run Extension)** automatically starts `shadow-cljs watch vscode-webview vscode-ext` and launches the Extension Development Host with live JS reloading. However, it does **not** copy CSS. If you changed CSS, manually copy it:
```bash
cp public/css/style.css vscode-ext/out/style.css
```

**Package as .vsix:**
```bash
npx shadow-cljs clj-run nyancad.mosaic.build/package-vscode
```

## Python Package Development

### nyancad (Client Library)

**Development installation:**
```bash
cd python/nyancad
pip install -e .
```

**Build wheel:**
```bash
cd python/nyancad
python -m build
```

### nyancad-server (Server Application)

**Development installation:**
```bash
cd python/nyancad-server
pip install -e .
```

**Run development server:**
```bash
nyancad-server
```

**Build wheel:**
```bash
cd python/nyancad-server
python -m build
```

## Project Structure

- `src/main/nyancad/mosaic/` - ClojureScript frontend modules
- `python/nyancad/` - Python client library with anywidget integration
- `python/nyancad-server/` - ASGI server with marimo integration
- `src/marimo/` - Marimo notebook source
- `src/bash/` - Build and deployment scripts
- `public/` - Static assets and compiled output

## CI/CD

The project uses GitHub Actions for continuous integration. See `.github/workflows/main.yml` for the complete build pipeline.

On tagged releases, the workflow automatically:
- Builds both Python packages
- Publishes to PyPI
- Deploys nyancad-server to production

## Git Subrepo (upstream sync)

This directory is vendored from [NyanCAD/Mosaic](https://github.com/NyanCAD/Mosaic)
using [git subrepo](https://github.com/ingydotnet/git-subrepo). The tracking state
lives in `.gitrepo`.

### Known issue: squash merges invalidate parent hash

Because the containing repository squash-merges all PRs, the `.gitrepo` `parent`
field (which records the local commit of the last sync) can become a dangling hash.
This is a known git-subrepo limitation
([#617](https://github.com/ingydotnet/git-subrepo/issues/617),
[e7411ba](https://github.com/ingydotnet/git-subrepo/commit/e7411ba4ee2a2d154f370dad2408237ccf49e7ba)).

**Symptoms:** `git subrepo push` or `git subrepo branch` fails with:
```
fatal: not a valid object name: ''
```
or:
```
The last sync point ... is not an ancestor.
```

**Fix:** Find the correct parent and update `.gitrepo`:
```bash
# Check if parent is stale
git merge-base --is-ancestor $(git config -f frontend/mosaic/.gitrepo subrepo.parent) HEAD
# If that fails (exit code 1), find the correct parent:
PARENT=$(git log -1 -G "commit =" --format="%H" frontend/mosaic/.gitrepo)
PARENT=$(git log -1 --format="%H" "$PARENT"^)
# Update .gitrepo
git config -f frontend/mosaic/.gitrepo subrepo.parent "$PARENT"
git add frontend/mosaic/.gitrepo
git commit -m "fix(mosaic): update .gitrepo parent after squash-merge"
```

Note: the heuristic above finds the parent of the last commit that touched the
`commit =` line in `.gitrepo`. If multiple already-synced commits were squash-merged
after that point, you may need to manually identify the correct parent — the last
local commit whose mosaic changes are already present on upstream `main`.

### Pushing changes upstream

```bash
git subrepo push frontend/mosaic           # direct push to main
git subrepo push frontend/mosaic -b <name> # push to named branch for PR
```

### Pulling upstream changes

```bash
git subrepo pull frontend/mosaic
```

## Policy on AI

We highly value code quality as well as developer productivity.
Therefore we welcome AI co-authored code, and hold it to the same high standard as human written code.
In practice this means a lot of time is spent in the planning phase, going back and forth on the details to the point anyone could write the final code, after which it is manually reviewed and tested.
Code written in such a fashion will be marked in the git commit with `Co-Authored-By: [model and version]` and remains the sole responsibility of the human author.
