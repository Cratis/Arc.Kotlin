#!/usr/bin/env bash
# Shared by Samples/*/*/run.sh — makes sure a JDK 17 is on JAVA_HOME/PATH before invoking Gradle.
# The repository's Gradle toolchain targets JDK 17 exactly; see .cratis/ai/rules/project/workspace.md.
#
# Source this file; it is not meant to be executed directly.

require_jdk17() {
    if [[ -n "${JAVA_HOME:-}" ]] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"17\.'; then
        return 0
    fi

    if command -v java &>/dev/null && java -version 2>&1 | grep -q '"17\.'; then
        return 0
    fi

    local candidate=""
    if command -v /usr/libexec/java_home &>/dev/null; then
        candidate="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    fi
    if [[ -z "$candidate" ]]; then
        for path in \
            /opt/homebrew/opt/openjdk@17 \
            /usr/local/opt/openjdk@17 \
            /usr/lib/jvm/temurin-17-jdk-amd64 \
            /usr/lib/jvm/java-17-openjdk-amd64 \
            /usr/lib/jvm/java-17-openjdk
        do
            if [[ -x "$path/bin/java" ]]; then
                candidate="$path"
                break
            fi
        done
    fi

    if [[ -z "$candidate" ]]; then
        echo "Error: JDK 17 is required and could not be found automatically." >&2
        echo "Install a JDK 17 and either put it first on PATH or export JAVA_HOME, then retry." >&2
        exit 1
    fi

    export JAVA_HOME="$candidate"
    export PATH="$JAVA_HOME/bin:$PATH"
}
