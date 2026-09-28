#!/usr/bin/env bash
# Shared by Samples/*/ChronicleSpringBoot/run.sh — starts the pinned Chronicle development kernel
# via Docker, waits for it to report healthy, and stops it again when the sample exits.
#
# The samples and ContractTests pin the same Chronicle 19.21.1 development kernel.
# Keep this image aligned with the version documented in both Chronicle sample READMEs.
#
# Source this file; it is not meant to be executed directly.

CHRONICLE_KERNEL_IMAGE="cratis/chronicle:19.21.1-development@sha256:6071390202d556ee0a9262d2993f5bbfde6db9ac893ba6269963e82bb928beb2"

start_chronicle_kernel() {
    local container_name="$1"

    if ! command -v docker &>/dev/null; then
        echo "Error: 'docker' not found in PATH." >&2
        echo "Install Docker to use ./run.sh --chronicle, or start a compatible Chronicle 19.21.1 kernel" >&2
        echo "on localhost:35000 and follow the manual steps in the Kotlin or Java" >&2
        echo "ChronicleSpringBoot sample README (run ./gradlew :Samples:<Language>:ChronicleSpringBoot:bootRun)." >&2
        exit 1
    fi

    docker rm -f "$container_name" &>/dev/null || true

    echo "▶  Starting Chronicle kernel ($CHRONICLE_KERNEL_IMAGE)..."
    docker run -d --rm --name "$container_name" -p 35000:35000 "$CHRONICLE_KERNEL_IMAGE" >/dev/null

    # shellcheck disable=SC2064 # container_name is intentionally expanded now, not at trap time.
    trap "stop_chronicle_kernel '$container_name'" EXIT INT TERM

    echo "Waiting for Chronicle to be ready..."
    for _ in $(seq 1 60); do
        if curl -sk https://localhost:35000/health 2>/dev/null | grep -q "Healthy"; then
            echo "✓  Chronicle → localhost:35000 (gRPC + API, TLS with self-signed dev cert)"
            return 0
        fi
        sleep 1
    done

    echo "Timed out waiting for the Chronicle kernel to become healthy." >&2
    docker logs "$container_name" >&2 || true
    exit 1
}

stop_chronicle_kernel() {
    local container_name="$1"
    echo ""
    echo "▶  Stopping Chronicle kernel..."
    docker stop "$container_name" &>/dev/null || true
}
