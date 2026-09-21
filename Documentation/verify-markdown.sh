#!/bin/bash

# Copyright (c) Cratis. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

echo "=========================================="
echo "Arc.Kotlin documentation verification"
echo "=========================================="

if ! command -v npx >/dev/null 2>&1; then
    echo "Error: npx is not installed. Install Node.js and npm."
    exit 1
fi

LINT_EXIT_CODE=0
npx markdownlint-cli2 "Documentation/**/*.{md,mdx}" || LINT_EXIT_CODE=$?

AUTHORING_EXIT_CODE=0
node Documentation/verify-authoring.mjs || AUTHORING_EXIT_CODE=$?

SNIPPET_EXIT_CODE=0
python3 Documentation/validate-doc-snippets.py || SNIPPET_EXIT_CODE=$?

# Compiles the shared-docs Kotlin snippets against the real modules. Exits 2 - never 0 - when no
# JVM toolchain is available, so "could not compile" can never be read as "compiled clean".
# Exit 2 means the snippets could not be compiled because no JVM toolchain was
# found, which is different from a snippet being wrong. A documentation-only
# change should still be verifiable without a JDK, so that is a warning here and
# a failure under --strict-toolchains, which is what CI passes. This mirrors how
# the documentation site treats a blocked client validator.
STRICT_TOOLCHAINS=0
for argument in "$@"; do
    [ "$argument" = "--strict-toolchains" ] && STRICT_TOOLCHAINS=1
done

CLIENT_SNIPPET_EXIT_CODE=0
python3 Documentation/validate-client-snippets.py || CLIENT_SNIPPET_EXIT_CODE=$?

if [ "$CLIENT_SNIPPET_EXIT_CODE" -eq 2 ]; then
    if [ "$STRICT_TOOLCHAINS" -eq 1 ]; then
        echo "Client snippet compilation was blocked by a missing JVM toolchain, and --strict-toolchains was requested."
    else
        echo "WARNING: client snippets were NOT compiled - no JVM toolchain. Install a JDK 17 toolchain to verify them locally; CI runs this with --strict-toolchains."
        CLIENT_SNIPPET_EXIT_CODE=0
    fi
fi

TOC_EXIT_CODE=0
python3 - <<'PY' || TOC_EXIT_CODE=$?
from pathlib import Path
import re

root = Path("Documentation")
count = 0
for toc in root.rglob("toc.yml"):
    for href in re.findall(r"^\s*href:\s*([^#\s]+)", toc.read_text(), re.MULTILINE):
        count += 1
        target = (toc.parent / href).resolve()
        if not target.is_file():
            raise SystemExit(f"Missing toc target: {toc}: {href}")
if count == 0:
    raise SystemExit("TOC verification checked 0 href values")
print(f"Verified {count} toc href targets.")
PY

LINK_EXIT_CODE=0
# These pages are published alongside the C# implementation's, so they link to it
# with site-absolute paths. Those resolve only on the aggregated documentation
# site and are verified when it is built. linkinator serves the scanned files
# from a local web server, so skipping what falls outside /Documentation/ skips
# exactly those paths and nothing else. The pattern must never match the crawl
# root itself, or this check silently becomes a no-op.
SITE_ABSOLUTE_LINKS='^https?://(localhost|127\.0\.0\.1):[0-9]+/(?!Documentation/)'

LINK_OUTPUT=$(npx linkinator "Documentation/**/*.{md,mdx}" --markdown --recurse --verbosity error --skip "$SITE_ABSOLUTE_LINKS" 2>&1) || LINK_EXIT_CODE=$?
echo "$LINK_OUTPUT"
LINK_COUNT=$(echo "$LINK_OUTPUT" | grep -oiE "scanned [0-9]+ links" | grep -oE "[0-9]+" | head -1 || true)
if [ -z "$LINK_COUNT" ] || [ "$LINK_COUNT" -eq 0 ]; then
    echo "Link verification scanned 0 links; the checker is not effective."
    LINK_EXIT_CODE=1
fi

if [ "$LINT_EXIT_CODE" -eq 0 ] && [ "$AUTHORING_EXIT_CODE" -eq 0 ] && [ "$SNIPPET_EXIT_CODE" -eq 0 ] && \
   [ "$CLIENT_SNIPPET_EXIT_CODE" -eq 0 ] && [ "$TOC_EXIT_CODE" -eq 0 ] && [ "$LINK_EXIT_CODE" -eq 0 ]; then
    echo "All documentation checks passed."
    exit 0
fi

echo "Documentation checks failed: lint=$LINT_EXIT_CODE authoring=$AUTHORING_EXIT_CODE snippets=$SNIPPET_EXIT_CODE client-snippets=$CLIENT_SNIPPET_EXIT_CODE toc=$TOC_EXIT_CODE links=$LINK_EXIT_CODE"
exit 1
