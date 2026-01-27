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

## Policy on AI

We highly value code quality as well as developer productivity.
Therefore we welcome AI co-authored code, and hold it to the same high standard as human written code.
In practice this means a lot of time is spent in the planning phase, going back and forth on the details to the point anyone could write the final code, after which it is manually reviewed and tested.
Code written in such a fashion will be marked in the git commit with `Co-Authored-By: [model and version]` and remains the sole responsibility of the human author.
