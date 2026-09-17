#!/usr/bin/env bash
# Run the Arc.Kotlin Kotlin + Chronicle task-board sample.
#
# Usage:
#   ./run.sh [--no-docker]
#
# By default this starts the pinned Chronicle development kernel with Docker, waits for it to
# report healthy, runs the sample, and stops the container again on exit (Ctrl-C or normal exit).
#
# Options:
#   --no-docker   Skip starting Chronicle. Use this when a compatible kernel (18.4.0+) is already
#                 running on localhost:35000.
#
# The sample's event store uses an in-memory sink, so no database is needed either way.
# See README.md in this directory for the routes to try, including the required
# 'x-cratis-tenant-id' header.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# shellcheck source=../../lib/jdk17.sh
source "$SCRIPT_DIR/../../lib/jdk17.sh"
# shellcheck source=../../lib/chronicle-kernel.sh
source "$SCRIPT_DIR/../../lib/chronicle-kernel.sh"

USE_DOCKER=true
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-docker) USE_DOCKER=false; shift ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

require_jdk17

if "$USE_DOCKER"; then
    start_chronicle_kernel "arc-kotlin-chronicle-sample-kernel"
fi

echo "▶  Running the Kotlin + Chronicle sample on http://localhost:8080"
echo ""
cd "$REPO_ROOT"
./gradlew :Samples:Kotlin:ChronicleSpringBoot:bootRun
