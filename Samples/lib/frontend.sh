#!/usr/bin/env bash
# Shared by the sample run scripts — regenerates the TypeScript proxies from the sample that is
# about to run, syncs them into the shared frontend, and starts Vite against the backend's port.
#
# The proxies are build output, never tracked. Copying them in on every run is what makes the
# frontend impossible to drift from the backend it talks to: change a command, restart, and the
# client that compiled a moment ago is the one the server actually serves.
#
# Source this file; it is not meant to be executed directly.

ARC_FRONTEND_PID=""

require_node() {
    if ! command -v npm &>/dev/null; then
        echo "Error: 'npm' not found in PATH." >&2
        echo "Install Node.js 22 or newer, or pass --no-frontend to run only the backend." >&2
        exit 1
    fi
}

# sync_sample_proxies <repo-root> <gradle-project-path> <sample-build-dir>
sync_sample_proxies() {
    local repo_root="$1"
    local gradle_path="$2"
    local sample_dir="$3"
    local frontend_dir="$repo_root/Samples/Frontend"

    echo "▶  Generating TypeScript proxies for $gradle_path..."
    (cd "$repo_root" && ./gradlew "$gradle_path:generateArcProxies" --no-configuration-cache -q)

    rm -rf "$frontend_dir/src/generated"
    mkdir -p "$frontend_dir/src/generated"
    cp -R "$sample_dir/build/generated/arc-proxies/." "$frontend_dir/src/generated/"
    echo "✓  Proxies synced into Samples/Frontend/src/generated"
}

# start_frontend <repo-root> <backend-url> <frontend-port>
start_frontend() {
    local repo_root="$1"
    local backend_url="$2"
    local frontend_port="$3"
    local frontend_dir="$repo_root/Samples/Frontend"

    require_node
    if [[ ! -d "$frontend_dir/node_modules" ]]; then
        echo "▶  Installing frontend dependencies..."
        (cd "$frontend_dir" && npm ci --ignore-scripts >/dev/null)
    fi

    echo "▶  Starting the frontend on http://localhost:$frontend_port"
    (cd "$frontend_dir" && ARC_BACKEND="$backend_url" ARC_FRONTEND_PORT="$frontend_port" npm run dev --silent) &
    ARC_FRONTEND_PID=$!
}

stop_frontend() {
    if [[ -n "$ARC_FRONTEND_PID" ]]; then
        kill "$ARC_FRONTEND_PID" &>/dev/null || true
        ARC_FRONTEND_PID=""
    fi
}
