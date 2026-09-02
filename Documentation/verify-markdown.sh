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
npx markdownlint-cli2 "Documentation/**/*.md" || LINT_EXIT_CODE=$?

SNIPPET_EXIT_CODE=0
python3 Documentation/validate-doc-snippets.py || SNIPPET_EXIT_CODE=$?

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
LINK_OUTPUT=$(npx linkinator "Documentation/**/*.md" --markdown --recurse --verbosity error 2>&1) || LINK_EXIT_CODE=$?
echo "$LINK_OUTPUT"
LINK_COUNT=$(echo "$LINK_OUTPUT" | grep -oiE "scanned [0-9]+ links" | grep -oE "[0-9]+" | head -1 || true)
if [ -z "$LINK_COUNT" ] || [ "$LINK_COUNT" -eq 0 ]; then
    echo "Link verification scanned 0 links; the checker is not effective."
    LINK_EXIT_CODE=1
fi

if [ "$LINT_EXIT_CODE" -eq 0 ] && [ "$SNIPPET_EXIT_CODE" -eq 0 ] && \
   [ "$TOC_EXIT_CODE" -eq 0 ] && [ "$LINK_EXIT_CODE" -eq 0 ]; then
    echo "All documentation checks passed."
    exit 0
fi

echo "Documentation checks failed: lint=$LINT_EXIT_CODE snippets=$SNIPPET_EXIT_CODE toc=$TOC_EXIT_CODE links=$LINK_EXIT_CODE"
exit 1
