#!/usr/bin/env bash
# Run an Arc for Kotlin and Java sample end to end — backend, generated proxies, and frontend.
#
# Usage:
#   ./run.sh                                  # Kotlin, in memory, with the frontend
#   ./run.sh --language java                  # the same sample written in Java
#   ./run.sh --database mongodb               # store the task board in MongoDB (Docker)
#   ./run.sh --database postgres              # store the task board in PostgreSQL (Docker)
#   ./run.sh --chronicle                      # the Chronicle-backed sample (Docker)
#   ./run.sh --no-frontend                    # backend only, for curl
#   ./run.sh --sign-in-required               # start signed out, so the anonymous paths are reachable
#
# Options:
#   --language kotlin|java     Which implementation to run. Default: kotlin.
#   --database memory|mongodb|postgres
#                              Where the task board is stored. Default: memory, which needs nothing.
#   --chronicle                Run the Chronicle sample instead, against the pinned kernel.
#   --port <n>                 Backend port. Default: 8080.
#   --frontend-port <n>        Frontend port. Default: 5173.
#   --no-frontend              Skip the frontend entirely.
#   --sign-in-required         Do not treat an unidentified request as the built-in sample user.
#
# Everything this starts is stopped again on exit, including Docker containers.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/jdk17.sh
source "$SCRIPT_DIR/lib/jdk17.sh"
# shellcheck source=lib/database.sh
source "$SCRIPT_DIR/lib/database.sh"
# shellcheck source=lib/frontend.sh
source "$SCRIPT_DIR/lib/frontend.sh"
# shellcheck source=lib/chronicle-kernel.sh
source "$SCRIPT_DIR/lib/chronicle-kernel.sh"

LANGUAGE="kotlin"
DATABASE="memory"
CHRONICLE=false
PORT=8080
FRONTEND_PORT=5173
WITH_FRONTEND=true
DEFAULT_IDENTITY=true

while [[ $# -gt 0 ]]; do
    case "$1" in
        --language) LANGUAGE="${2:?--language needs a value}"; shift 2 ;;
        --database) DATABASE="${2:?--database needs a value}"; shift 2 ;;
        --port) PORT="${2:?--port needs a value}"; shift 2 ;;
        --frontend-port) FRONTEND_PORT="${2:?--frontend-port needs a value}"; shift 2 ;;
        --chronicle) CHRONICLE=true; shift ;;
        --no-frontend) WITH_FRONTEND=false; shift ;;
        --sign-in-required) DEFAULT_IDENTITY=false; shift ;;
        -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

case "$LANGUAGE" in
    kotlin) LANGUAGE_DIRECTORY="Kotlin" ;;
    java) LANGUAGE_DIRECTORY="Java" ;;
    *) echo "Unknown language '$LANGUAGE'. Choose kotlin or java." >&2; exit 1 ;;
esac

if "$CHRONICLE"; then
    MODULE="ChronicleSpringBoot"
    if [[ "$DATABASE" != "memory" ]]; then
        echo "Error: --database applies to the plain Arc sample." >&2
        echo "The Chronicle sample stores its events in the Chronicle kernel, which owns its own storage." >&2
        exit 1
    fi
    # The Chronicle sample is HTTP-driven, exactly as the Arc .NET Chronicle sample is: it exists to
    # show events, reducers and tenancy, and a browser adds nothing to that. The shared frontend is
    # built against the showcase proxies, which this sample does not generate.
    WITH_FRONTEND=false
else
    MODULE="SpringBoot"
fi

GRADLE_PATH=":Samples:$LANGUAGE_DIRECTORY:$MODULE"
SAMPLE_DIRECTORY="$REPO_ROOT/Samples/$LANGUAGE_DIRECTORY/$MODULE"
BACKEND_URL="http://localhost:$PORT"

cleanup() {
    stop_frontend
    stop_sample_database
    if "$CHRONICLE"; then
        stop_chronicle_kernel "arc-samples-chronicle"
    fi
}
trap cleanup EXIT INT TERM

require_jdk17

if "$CHRONICLE"; then
    start_chronicle_kernel "arc-samples-chronicle"
else
    start_sample_database "$DATABASE"
fi

if "$WITH_FRONTEND"; then
    sync_sample_proxies "$REPO_ROOT" "$GRADLE_PATH" "$SAMPLE_DIRECTORY"
    start_frontend "$REPO_ROOT" "$BACKEND_URL" "$FRONTEND_PORT"
fi

APPLICATION_ARGUMENTS="--server.port=$PORT"
if [[ "$DATABASE" != "memory" ]]; then
    APPLICATION_ARGUMENTS="$APPLICATION_ARGUMENTS --spring.profiles.active=$DATABASE"
fi
if ! "$DEFAULT_IDENTITY"; then
    APPLICATION_ARGUMENTS="$APPLICATION_ARGUMENTS --cratis.arc.samples.default-identity=false"
fi
if "$WITH_FRONTEND"; then
    # Vite serves the page from its own port, so every WebSocket handshake the browser makes is
    # cross-origin. Spring refuses those by default and the browser reports it as a socket that never
    # opens rather than a failure, so the dev-server origin has to be named explicitly.
    APPLICATION_ARGUMENTS="$APPLICATION_ARGUMENTS --cratis.arc.observable-queries.allowed-origins=http://localhost:$FRONTEND_PORT"
fi
GRADLE_ARGUMENTS=("--args=$APPLICATION_ARGUMENTS")

echo ""
echo "▶  $LANGUAGE_DIRECTORY sample ($DATABASE store) → $BACKEND_URL"
if "$WITH_FRONTEND"; then
    echo "▶  Frontend → http://localhost:$FRONTEND_PORT"
fi
echo ""

cd "$REPO_ROOT"
./gradlew "$GRADLE_PATH:bootRun" --no-configuration-cache "${GRADLE_ARGUMENTS[@]}"
