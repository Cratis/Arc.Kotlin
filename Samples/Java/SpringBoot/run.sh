#!/usr/bin/env bash
# Run the Arc.Kotlin Java Spring Boot task-board sample.
#
# Usage:
#   ./run.sh
#
# No external dependencies are needed: the sample uses a bounded in-memory repository.
# See README.md in this directory for the routes to try once it is running.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# shellcheck source=../../lib/jdk17.sh
source "$SCRIPT_DIR/../../lib/jdk17.sh"

require_jdk17

echo "▶  Running the Java Spring Boot sample on http://localhost:8080"
echo ""
cd "$REPO_ROOT"
exec ./gradlew :Samples:Java:SpringBoot:bootRun
