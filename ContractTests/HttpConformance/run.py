#!/usr/bin/env python3
# Copyright (c) Cratis. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.
"""Explicit, bounded three-host HTTP proof. No sibling checkout or external database is used."""

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import signal
import threading

from contract import exercise

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
CAPTURE = REPO / "GradlePlugin/src/test/resources/differential/capture"
sys.path.insert(0, str(CAPTURE))
import capture  # Shared checked-package and safe-output helpers, not copied framework source.


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def prepare_dotnet(work):
    source = work / "dotnet-source"
    source.mkdir()
    for name in ("HttpFixture.csproj", "Program.cs", "packages.lock.json"):
        shutil.copyfile(HERE / "DotNet" / name, source / name)
    for name in ("global.json", "NuGet.Config"):
        shutil.copyfile(CAPTURE / name, source / name)
    env = {key: os.environ[key] for key in ("HOME", "PATH", "SystemRoot") if key in os.environ}
    env.update(DOTNET_CLI_TELEMETRY_OPTOUT="1", DOTNET_NOLOGO="1", DOTNET_MULTILEVEL_LOOKUP="0",
               DOTNET_CLI_HOME=str(work / "dotnet-home"), NUGET_PACKAGES=str(Path.home() / ".nuget/packages"),
               DOTNET_CLI_WORKLOAD_UPDATE_NOTIFY_DISABLE="1", TMPDIR=str(work), TZ="UTC",
               ASPNETCORE_ENVIRONMENT="Production")
    dotnet = shutil.which("dotnet")
    if not dotnet:
        raise ValueError("Pinned .NET SDK must be installed; no skip or automatic fallback")
    sdk = json.loads((CAPTURE / "global.json").read_text())["sdk"]["version"]
    runtime = json.loads((CAPTURE / "tool.lock.json").read_text())["runtime"]
    if capture.run([dotnet, "--version"], source, env) != sdk:
        raise ValueError("Resolved .NET SDK differs from pin")
    runtimes = capture.run([dotnet, "--list-runtimes"], source, env)
    for framework in ("Microsoft.NETCore.App", "Microsoft.AspNetCore.App"):
        if len(re.findall(r"^" + re.escape(framework + " " + runtime) + r" \[.+\]$", runtimes, re.M)) != 1:
            raise ValueError(f"Pinned {framework} runtime {runtime} is required")
    properties = ["-p:ImportDirectoryBuildProps=false", "-p:ImportDirectoryBuildTargets=false",
                  "-p:ImportDirectoryPackagesProps=false", "-p:UseSharedCompilation=false"]
    capture.run([dotnet, "restore", source / "HttpFixture.csproj", "--locked-mode", "--configfile",
                 source / "NuGet.Config", *properties], source, env)
    if (source / "packages.lock.json").read_bytes() != (HERE / "DotNet/packages.lock.json").read_bytes():
        raise ValueError("HTTP fixture restore changed its package lock")
    assets = json.loads((source / "obj/project.assets.json").read_text())
    lock = json.loads((HERE / "DotNet/packages.lock.json").read_text())["dependencies"]["net10.0"]
    if set(assets["libraries"]) != {f"{name}/{data['resolved']}" for name, data in lock.items()} or any(
            data["type"] != "package" for data in assets["libraries"].values()):
        raise ValueError("Unexpected non-package dependency or changed package graph")
    if [Path(path) for path in assets["packageFolders"]] != [Path(env["NUGET_PACKAGES"])]:
        raise ValueError("Unexpected NuGet package directory")
    if set(assets["project"]["restore"]["sources"]) != {"https://api.nuget.org/v3/index.json"}:
        raise ValueError("Unexpected NuGet source")
    hashes = json.loads((HERE / "DotNet/packages.sha256.json").read_text())
    archives = set()
    for name, dependency in lock.items():
        version = dependency["resolved"].lower()
        relative = f"{name.lower()}/{version}/{name.lower()}.{version}.nupkg"
        archives.add(relative)
        capture.verify_package(Path(env["NUGET_PACKAGES"]) / relative, hashes[relative])
    if archives != set(hashes):
        raise ValueError("Package checksum inventory differs from restored graph")
    publish = work / "dotnet-publish"
    capture.run([dotnet, "publish", source / "HttpFixture.csproj", "--no-restore", "-c", "Release",
                 "-o", publish, "-v", "quiet", *properties], source, env)
    runtime_config = json.loads((publish / "Arc.Kotlin.HttpConformance.runtimeconfig.json").read_text())["runtimeOptions"]
    if {entry['name']: entry['version'] for entry in runtime_config['frameworks']} != {
            'Microsoft.NETCore.App': runtime, 'Microsoft.AspNetCore.App': runtime} or runtime_config['rollForward'] != 'Disable':
        raise ValueError("Published HTTP app framework/runtime policy differs from pins")
    return [dotnet, "exec", "--fx-version", runtime, "--roll-forward", "Disable",
            str(publish / "Arc.Kotlin.HttpConformance.dll")], env, sdk, runtime


@contextmanager
def termination_guard():
    def terminate(signum, frame):
        raise InterruptedError("Conformance runner received SIGTERM")
    previous = signal.signal(signal.SIGTERM, terminate)
    try:
        yield
    finally:
        signal.signal(signal.SIGTERM, previous)


def java_environment(work):
    env = {key: os.environ[key] for key in ("HOME", "PATH", "SystemRoot") if key in os.environ}
    env.update(TMPDIR=str(work), TZ="UTC", LANG="C.UTF-8", LC_ALL="C.UTF-8")
    return env


@contextmanager
def server(command, work, log, dotnet=False, env=None, runtime=None, evidence=None,
           startup_timeout=90, total_timeout=180, max_log_bytes=4_000_000):
    """Bound logs and wall-clock execution for the whole child lifetime, including HTTP exchanges."""
    with log.open("wb") as output:
        process = subprocess.Popen(command, cwd=work, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        stop = threading.Event()
        failures = []
        failure_lock = threading.Lock()
        deadline = time.monotonic() + total_timeout

        def terminate_child():
            if process.poll() is None:
                try:
                    process.terminate()
                except ProcessLookupError:
                    pass

        def fail(error):
            with failure_lock:
                if not failures:
                    failures.append(error)
            terminate_child()

        def drain():
            written = 0
            try:
                while chunk := process.stdout.read1(4096):
                    remaining = max_log_bytes - written
                    output.write(chunk[:max(0, remaining)])
                    output.flush()
                    written += len(chunk)
                    if written > max_log_bytes:
                        fail(RuntimeError(f"Host log exceeded bound: {log}"))
                        break
            except (OSError, ValueError) as error:
                if not stop.is_set():
                    fail(error)

        def supervise():
            while not stop.wait(0.02):
                if time.monotonic() >= deadline:
                    fail(TimeoutError(f"Host execution exceeded deadline: {log}"))
                    # SIGTERM alone cannot bound a child that ignores it while its client awaits a response.
                    if process.poll() is None:
                        try:
                            process.kill()
                        except ProcessLookupError:
                            pass
                    return

        reader = threading.Thread(target=drain, name="conformance-log", daemon=True)
        watchdog = threading.Thread(target=supervise, name="conformance-deadline", daemon=True)
        reader.start()
        watchdog.start()
        try:
            startup = time.monotonic() + startup_timeout
            while True:
                if failures:
                    raise failures[0]
                if process.poll() is not None:
                    raise RuntimeError(f"Host exited ({process.returncode}); see {log}")
                with log.open("rb") as recorded:
                    text = recorded.read(max_log_bytes + 1).decode(errors="replace")
                origin = None
                if dotnet:
                    records = [json.loads(line) for line in text.splitlines(keepends=True)
                               if line.endswith('\n') and line.startswith('{"kind":"http-conformance-ready"')]
                    if len(records) > 1:
                        raise RuntimeError("Duplicate host readiness record")
                    if records:
                        ready = records[0]
                        if ready['runtime'] != runtime or Path(ready['coreRuntimeDirectory']).parts[-2:] != ('Microsoft.NETCore.App', runtime) or \
                                Path(ready['aspNetCoreAssembly']).parts[-3:-1] != ('Microsoft.AspNetCore.App', runtime):
                            raise RuntimeError("Loaded framework assemblies differ from runtime pins")
                        if evidence is not None:
                            evidence.update(ready)
                        origin = ready['baseUrl']
                else:
                    ports = re.findall(r"Tomcat started on port (\d+)", text)
                    if len(set(ports)) > 1:
                        raise RuntimeError("Ambiguous host listening port")
                    if ports:
                        origin = "http://127.0.0.1:" + ports[0]
                if origin is not None:
                    yield origin
                    if failures:
                        raise failures[0]
                    break
                if time.monotonic() >= startup:
                    raise TimeoutError(f"Host startup timed out; see {log}")
                stop.wait(0.02)
        finally:
            stop.set()
            terminate_child()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
            reader.join(timeout=5)
            watchdog.join(timeout=5)
            process.stdout.close()
            if reader.is_alive() or watchdog.is_alive():
                raise RuntimeError("Conformance supervisor did not join")


def run(kotlin_jar, java_jar, java, output):
    output = capture.destination(output, REPO / ".ai-work")
    managed = os.environ.get("AI_WORK_OUTPUT")
    if not managed:
        raise ValueError("Run through lifecycle or set its owned AI_WORK_OUTPUT")
    managed = capture.no_symlinks(managed)
    if not managed.is_dir() or not managed.is_relative_to(REPO / ".ai-work"):
        raise ValueError("AI_WORK_OUTPUT must be inside this repository's .ai-work")
    allowed = [managed]
    if os.environ.get("AI_WORK_KEEP"):
        allowed.append(capture.no_symlinks(os.environ["AI_WORK_KEEP"]))
    elif managed.name == "output":
        # A lifecycle start supplies named siblings; require their reviewed task owner manifest layout.
        allowed.append(managed.parent / "keep")
    if not any(output.is_relative_to(root) for root in allowed):
        raise ValueError("Evidence must be inside this task's output or keep directory")
    for jar in (kotlin_jar, java_jar):
        if not jar.is_file():
            raise ValueError(f"Missing sample boot jar: {jar}")
    work = Path(tempfile.mkdtemp(prefix="http-conformance-", dir=managed))
    command, env, sdk, runtime = prepare_dotnet(work)
    jvm_env = java_environment(work)
    java_version = capture.run([java, "-version"], work, jvm_env)
    if not re.search(r'version "17[.\"]', java_version):
        raise ValueError("This fixture requires the supported Java 17 host")
    result = {"formatVersion": 1, "dotnetSdk": sdk, "dotnetRuntime": runtime, "cratisArcVersion": "22.14.0",
              "javaVersion": java_version, "javaExecutableSha256": digest(Path(java)),
              "dotnetCommand": command[:6],
              "sampleJarHashes": {"kotlin": digest(kotlin_jar), "java": digest(java_jar)},
              "inputHashes": {str(file.relative_to(REPO)): digest(file) for file in
                              [HERE / "run.py", HERE / "contract.py", *sorted((HERE / "DotNet").glob('*')),
                               *(CAPTURE / name for name in ("global.json", "NuGet.Config", "tool.lock.json", "capture.py"))]},
              "hosts": {}}
    stage = work / "result"
    stage.mkdir()
    for label, cmd, host_env in [("dotnet", command, env),
                                 ("kotlin", [java, "-Duser.timezone=UTC", f"-Djava.io.tmpdir={work}", "-jar", str(kotlin_jar), "--server.address=127.0.0.1", "--server.port=0", "--spring.config.location=classpath:/application.properties"], jvm_env),
                                 ("java", [java, "-Duser.timezone=UTC", f"-Djava.io.tmpdir={work}", "-jar", str(java_jar), "--server.address=127.0.0.1", "--server.port=0", "--spring.config.location=classpath:/application.properties"], jvm_env)]:
        exchanges = []
        evidence = {}
        try:
            with server(cmd, work, stage / f"{label}.log", dotnet=label == "dotnet", env=host_env,
                        runtime=runtime, evidence=evidence) as origin:
                cases = exercise(origin, exchanges)
                result["hosts"][label] = {"passed": cases, "exchanges": exchanges, "runtimeEvidence": evidence}
        finally:
            (work / f"{label}-exchanges.json").write_text(json.dumps(exchanges, indent=2) + "\n")
    expected = result["hosts"]["dotnet"]["passed"]
    if len(expected) != 9 or any(host["passed"] != expected for host in result["hosts"].values()):
        raise AssertionError("All three hosts must complete the same nine cases")
    (stage / "results.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    capture.publish(stage, output, REPO / ".ai-work")
    print(f"PASS: 9 selected HTTP cases on each of .NET, Kotlin and Java; evidence: {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kotlin-jar", required=True, type=Path)
    parser.add_argument("--java-jar", required=True, type=Path)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not args.java:
        parser.error("Java 17 executable is required")
    try:
        with termination_guard():
            run(args.kotlin_jar.resolve(), args.java_jar.resolve(), args.java, args.output)
    except subprocess.CalledProcessError as error:
        print(error.stdout.decode('utf-8', errors='replace') if error.stdout else str(error), file=sys.stderr)
        sys.exit(1)
    except (AssertionError, ValueError, OSError, RuntimeError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        print(f'HTTP conformance failed: {error}', file=sys.stderr)
        sys.exit(1)
