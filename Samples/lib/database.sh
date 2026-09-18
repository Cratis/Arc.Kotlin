#!/usr/bin/env bash
# Shared by the sample run scripts — starts the database a --database option asks for and stops it
# again when the sample exits.
#
# Images are pinned so a sample run is reproducible, and every container is started with --rm and a
# fixed name so a previous interrupted run cannot leave one behind.
#
# Source this file; it is not meant to be executed directly.

MONGODB_IMAGE="mongo:8.2@sha256:e0ce8c35124d4a9f9785532d1f268f39e9728ffa1cb38f46fa482436424c4bd3"
POSTGRES_IMAGE="postgres:18.1-alpine@sha256:aa6eb304ddb6dd26df23d05db4e5cb05af8951cda3e0dc57731b771e0ef4ab29"

ARC_SAMPLE_DATABASE_CONTAINER=""

require_docker() {
    if ! command -v docker &>/dev/null; then
        echo "Error: 'docker' not found in PATH." >&2
        echo "Install Docker, or run with --database memory (the default), which needs nothing." >&2
        exit 1
    fi
}

stop_sample_database() {
    if [[ -n "$ARC_SAMPLE_DATABASE_CONTAINER" ]]; then
        echo ""
        echo "▶  Stopping $ARC_SAMPLE_DATABASE_CONTAINER..."
        docker stop "$ARC_SAMPLE_DATABASE_CONTAINER" &>/dev/null || true
        ARC_SAMPLE_DATABASE_CONTAINER=""
    fi
}

# Reuses a database already listening on the port instead of failing to bind to it. A developer who
# already runs MongoDB or PostgreSQL locally should not have to stop it to try a sample.
port_in_use() {
    nc -z localhost "$1" &>/dev/null
}

start_mongodb() {
    local container_name="arc-samples-mongodb"
    if port_in_use 27017; then
        echo "✓  Reusing the MongoDB already listening on localhost:27017"
        echo "   Set ARC_SAMPLE_MONGODB_URI to point somewhere else."
        return 0
    fi
    require_docker
    docker rm -f "$container_name" &>/dev/null || true

    echo "▶  Starting MongoDB ($MONGODB_IMAGE)..."
    docker run -d --rm --name "$container_name" -p 27017:27017 "$MONGODB_IMAGE" >/dev/null
    ARC_SAMPLE_DATABASE_CONTAINER="$container_name"

    echo "Waiting for MongoDB to accept connections..."
    for _ in $(seq 1 60); do
        if docker exec "$container_name" mongosh --quiet --eval 'db.runCommand({ ping: 1 }).ok' 2>/dev/null | grep -q '^1$'; then
            echo "✓  MongoDB → mongodb://localhost:27017/arc-samples"
            return 0
        fi
        sleep 1
    done

    echo "Timed out waiting for MongoDB." >&2
    docker logs "$container_name" >&2 || true
    exit 1
}

start_postgres() {
    local container_name="arc-samples-postgres"
    if port_in_use 5432; then
        echo "✓  Reusing the PostgreSQL already listening on localhost:5432"
        echo "   Set ARC_SAMPLE_POSTGRES_URL, ARC_SAMPLE_POSTGRES_USER and ARC_SAMPLE_POSTGRES_PASSWORD"
        echo "   when its credentials or database name differ from arc/arc/arc-samples."
        return 0
    fi
    require_docker
    docker rm -f "$container_name" &>/dev/null || true

    echo "▶  Starting PostgreSQL ($POSTGRES_IMAGE)..."
    docker run -d --rm --name "$container_name" \
        -e POSTGRES_USER=arc \
        -e POSTGRES_PASSWORD=arc \
        -e POSTGRES_DB=arc-samples \
        -p 5432:5432 "$POSTGRES_IMAGE" >/dev/null
    ARC_SAMPLE_DATABASE_CONTAINER="$container_name"

    echo "Waiting for PostgreSQL to accept connections..."
    for _ in $(seq 1 60); do
        if docker exec "$container_name" pg_isready -U arc -d arc-samples &>/dev/null; then
            echo "✓  PostgreSQL → jdbc:postgresql://localhost:5432/arc-samples"
            return 0
        fi
        sleep 1
    done

    echo "Timed out waiting for PostgreSQL." >&2
    docker logs "$container_name" >&2 || true
    exit 1
}

# Starts whatever the chosen database needs and echoes the Spring profile to activate.
# Prints nothing for the in-memory default, which needs no profile and no container.
start_sample_database() {
    case "$1" in
        memory) ;;
        mongodb) start_mongodb ;;
        postgres) start_postgres ;;
        *)
            echo "Unknown database '$1'. Choose memory, mongodb or postgres." >&2
            exit 1
            ;;
    esac
}
