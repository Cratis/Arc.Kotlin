#!/usr/bin/env bash
# Run the Java Spring Boot sample. A thin wrapper over Samples/run.sh, which runs every sample.
#
#   ./run.sh                      # in memory, with the frontend
#   ./run.sh --database postgres  # store the task board in PostgreSQL (Docker)
#   ./run.sh --no-frontend        # backend only, for curl
#
# See ../../run.sh --help for every option.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/../../run.sh" --language java "$@"
