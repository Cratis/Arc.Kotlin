#!/usr/bin/env bash
# Run the Java + Chronicle sample. A thin wrapper over Samples/run.sh, which runs every sample.
#
#   ./run.sh                 # starts the pinned Chronicle kernel with Docker, plus the frontend
#   ./run.sh --no-frontend   # backend only, for curl
#
# See ../../run.sh --help for every option.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/../../run.sh" --language java --chronicle "$@"
