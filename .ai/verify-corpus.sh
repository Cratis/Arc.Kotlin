#!/bin/bash

# Copyright (c) Cratis. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

echo "=========================================="
echo "Arc.Kotlin AI corpus verification"
echo "=========================================="

STRUCTURE_EXIT_CODE=0
python3 - <<'PY' || STRUCTURE_EXIT_CODE=$?
import os
import re
import sys
from pathlib import Path

root = Path(".")
errors = []

# --- adapters must be symlinks into .ai/, never copies or path-only text files ---

adapters = {
    ".claude/CLAUDE.md": ".ai/rules/general.md",
    ".claude/skills": ".ai/skills",
    ".agents/skills": ".ai/skills",
    ".github/skills": ".ai/skills",
    ".github/copilot-instructions.md": ".ai/rules/general.md",
}

for adapter, expected in adapters.items():
    path = root / adapter
    if not path.is_symlink():
        errors.append(f"{adapter}: must be a symlink into .ai/, not a regular file or directory")
        continue
    resolved = (path.parent / os.readlink(path)).resolve()
    if not resolved.exists():
        errors.append(f"{adapter}: dangling symlink -> {os.readlink(path)}")
        continue
    if resolved != (root / expected).resolve():
        errors.append(f"{adapter}: resolves to {resolved}, expected {(root / expected).resolve()}")

# --- no duplicate corpus content outside .ai/ ---

for adapter_root in (".claude", ".agents", ".github"):
    base = root / adapter_root
    if not base.is_dir():
        continue
    for path in base.rglob("*"):
        if path.is_symlink() or path.is_dir():
            continue
        if path.name == "SKILL.md":
            errors.append(f"{path}: skills live only in .ai/skills; adapters must be symlinks")

# --- every skill has valid frontmatter and a matching name ---

skills = sorted((root / ".ai/skills").glob("*/SKILL.md"))
if not skills:
    errors.append(".ai/skills: no SKILL.md files found")

for skill in skills:
    text = skill.read_text(encoding="utf-8")
    match = re.match(r"^---\n(.*?)\n---\n", text, re.DOTALL)
    if not match:
        errors.append(f"{skill}: missing YAML frontmatter delimited by ---")
        continue
    fields = dict(
        re.findall(r"^([A-Za-z0-9_-]+):\s*(.+?)\s*$", match.group(1), re.MULTILINE)
    )
    for required in ("name", "description"):
        if required not in fields:
            errors.append(f"{skill}: frontmatter is missing '{required}'")
    name = fields.get("name")
    if name and name != skill.parent.name:
        errors.append(f"{skill}: frontmatter name '{name}' does not match directory '{skill.parent.name}'")
    if name and not re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", name):
        errors.append(f"{skill}: frontmatter name '{name}' is not kebab-case")

# --- every relative link inside the corpus resolves ---

link_pattern = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
checked_links = 0

for markdown in sorted(root.glob(".ai/**/*.md")):
    for target in link_pattern.findall(markdown.read_text(encoding="utf-8")):
        target = target.split("#", 1)[0].strip()
        if not target or target.startswith(("http://", "https://", "mailto:")):
            continue
        checked_links += 1
        resolved = (markdown.parent / target).resolve()
        if not resolved.exists():
            errors.append(f"{markdown}: broken relative link -> {target}")

if checked_links == 0:
    errors.append("link verification checked 0 relative links; the check is not effective")

# --- rule index in general.md must cover every rule file ---

general = root / ".ai/rules/general.md"
if general.exists():
    general_text = general.read_text(encoding="utf-8")
    for rule in sorted((root / ".ai/rules").glob("*.md")):
        if rule.name == "general.md":
            continue
        if f"({rule.name})" not in general_text and f"(./{rule.name})" not in general_text:
            errors.append(f".ai/rules/general.md: does not link to {rule.name}")
    for skill in skills:
        if skill.parent.name not in general_text:
            errors.append(f".ai/rules/general.md: does not mention skill {skill.parent.name}")
else:
    errors.append(".ai/rules/general.md: missing")

# --- work records must never live in the corpus ---

for markdown in sorted(root.glob(".ai/**/*.md")):
    lowered = markdown.name.lower()
    if any(token in lowered for token in ("handover", "session", "plan-", "status-board", "continuation")):
        errors.append(f"{markdown}: looks like a work record; those belong in .ai-work/")

for error in errors:
    print(f"error: {error}")

print(f"Checked {len(adapters)} adapters, {len(skills)} skills, {checked_links} relative links.")
sys.exit(1 if errors else 0)
PY

LINT_EXIT_CODE=0
if command -v npx >/dev/null 2>&1; then
    npx markdownlint-cli2 ".ai/**/*.md" || LINT_EXIT_CODE=$?
else
    echo "Error: npx is not installed. Install Node.js and npm."
    LINT_EXIT_CODE=1
fi

if [ "$STRUCTURE_EXIT_CODE" -eq 0 ] && [ "$LINT_EXIT_CODE" -eq 0 ]; then
    echo "All AI corpus checks passed."
    exit 0
fi

echo "AI corpus checks failed: structure=$STRUCTURE_EXIT_CODE lint=$LINT_EXIT_CODE"
exit 1
