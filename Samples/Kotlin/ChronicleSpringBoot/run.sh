#!/usr/bin/env bash
# Run the Kotlin + Chronicle sample. A thin wrapper over Samples/run.sh, which runs every sample.
#
#   ./run.sh                 # starts the pinned kernel with Docker and the backend (no frontend)
#   ./run.sh --no-frontend   # also runs the backend only, for curl
#
# See ../../run.sh --help for every option.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/../../run.sh" --language kotlin --chronicle "$@"
