#!/bin/bash
# SPDX-FileCopyrightText: 2024 Pepijn de Vos
# SPDX-License-Identifier: MPL-2.0

set -e

# Clean the output directory
rm -rf public/docs

# Build Jekyll site from docs folder to public/docs
cd docs
jekyll build
cd ..
