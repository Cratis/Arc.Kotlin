# Arc KSP code generation

This module contains Arc's KSP processor for reflection-free command handlers, query performers, artifact metadata, and manifests.

Compile-time validation rejects artifact and proxy shapes that generated JVM code cannot invoke safely. Every diagnostic has a stable `ARCKSP` code; see [DIAGNOSTICS.md](DIAGNOSTICS.md).
