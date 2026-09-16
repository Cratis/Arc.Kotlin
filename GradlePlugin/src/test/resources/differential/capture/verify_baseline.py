#!/usr/bin/env python3
# Copyright (c) Cratis. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.
"""Offline consistency checks linking the reviewed seven-file baseline to its capture inputs.

A receipt is not an attestation: someone who can replace the checker, inputs and receipt can forge it.
Normal checking reads files only. Recording a candidate requires a complete raw/normalized capture;
it writes JSON to stdout for explicit review, never into the checked-in baseline.
"""

import argparse
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

import capture

RECEIPT_KIND = "reviewed-arc-proxy-capture-consistency"
SNAPSHOT_NORMALIZATIONS = [
    'Removed the generator wall-clock "Time:" header field, which the JVM generator does not emit.'
]
FIXTURE_PREFIX = "Arc/Kotlin/Differential/Fixture/"
FIXTURE_FILES = {FIXTURE_PREFIX + name + ".ts" for name in
                 ("Address", "AllOrders", "OrderById", "OrderKind", "OrderView", "PlaceOrder", "index")}
HASH = re.compile(r"[0-9A-F]{64}\Z")


def require_equal(expected, actual, label):
    # JSON type-sensitive comparison: true and 1, or 1 and 1.0, are not equivalent schema values.
    if json.dumps(expected, sort_keys=True) != json.dumps(actual, sort_keys=True):
        raise ValueError(f"{label} mismatch")


def load_json(path):
    path = capture.no_symlinks(path)
    if not path.is_file() or path.stat().st_size > 2_000_000:
        raise ValueError(f"Missing or oversized JSON file: {path}")

    def pairs(entries):
        result = {}
        for key, value in entries:
            if key in result:
                raise ValueError(f"Duplicate JSON key: {key}")
            result[key] = value
        return result

    def constant(value):
        raise ValueError(f"Invalid JSON constant: {value}")

    result = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=pairs, parse_constant=constant)
    if not isinstance(result, dict):
        raise ValueError(f"Expected JSON object: {path}")
    return result


def hash_inventory(values, expected_paths, label):
    if not isinstance(values, dict) or set(values) != set(expected_paths):
        raise ValueError(f"{label} path inventory mismatch")
    if any(not isinstance(value, str) or not HASH.fullmatch(value) for value in values.values()):
        raise ValueError(f"{label} must contain uppercase SHA-256 strings")
    return values


def current_inputs(source):
    source = capture.no_symlinks(source)
    result = {}
    for name in capture.INPUTS:
        path = capture.no_symlinks(source / name)
        if not path.is_file():
            raise ValueError(f"Missing capture input: {name}")
        result[name] = capture.sha256(path.read_bytes())
    return result


def pinned_metadata(source):
    sdk = load_json(source / "global.json")["sdk"]
    require_equal("disable", sdk["rollForward"], "SDK roll-forward policy")
    require_equal(False, sdk["allowPrerelease"], "SDK prerelease policy")
    tool = load_json(source / "tool.lock.json")
    require_equal("Cratis.Arc.ProxyGenerator", tool["package"], "Tool identity")
    hash_inventory({"tool": tool["sha256"]}, {"tool"}, "Tool checksum")
    project = ET.fromstring((source / "Fixture.csproj").read_bytes())
    packages = [entry.get("Version") for entry in project.iter("PackageReference") if entry.get("Include") == "Cratis.Arc"]
    if len(packages) != 1 or not re.fullmatch(r"\[[0-9]+\.[0-9]+\.[0-9]+\]", packages[0] or ""):
        raise ValueError("Fixture must pin exactly one exact Cratis.Arc version")
    runtime = tool["runtime"]
    require_equal([runtime], [entry.text for entry in project.iter("RuntimeFrameworkVersion")], "Fixture runtime pin")
    require_equal(["Disable"], [entry.text for entry in project.iter("RollForward")], "Fixture runtime roll-forward")
    frameworks = {"Microsoft.NETCore.App": runtime, "Microsoft.AspNetCore.App": runtime}
    return {
        "packages": {"Cratis.Arc": packages[0][1:-1], tool["package"]: tool["version"]},
        "dotnetSdk": sdk["version"],
        "dotnetRuntimes": frameworks,
        "generatorObservedRuntimes": frameworks,
        "runtimeSelection": f"dotnet exec --fx-version {runtime} --roll-forward Disable",
        "toolArchiveSha256": tool["sha256"],
        "lockedRestore": True,
        "dependencyArchiveAndPayloadHashesVerified": True,
        "generatorArguments": ["Arc.Kotlin.Differential.Fixture.dll", "raw", "0"],
        "normalization": capture.NORMALIZATION,
    }


def candidate_receipt(root, source):
    """Validate full capture bytes and current pins before emitting a review candidate, never a snapshot."""
    root = capture.no_symlinks(root)
    manifest = load_json(root / "capture-manifest.json")
    require_equal(2, manifest["formatVersion"], "Full capture format")
    capture.verify_capture(root)  # Verifies inventory, raw body hashes and exact timestamp-only normalization.
    inputs = current_inputs(source)
    require_equal(inputs, manifest["inputHashes"], "Current capture inputs")
    pins = pinned_metadata(source)
    for key, expected in pins.items():
        require_equal(expected, manifest[key], f"Capture {key}")
    files = hash_inventory(manifest["normalizedFiles"], FIXTURE_FILES, "Normalized fixture")
    return {
        "formatVersion": 1,
        "kind": RECEIPT_KIND,
        "inputHashes": inputs,
        "pins": pins,
        "normalizedFiles": files,
        # The raw capture remains local. This digest names the reviewed evidence; checking a receipt
        # without that raw capture cannot re-prove that an external tool actually ran.
        "captureManifestSha256": capture.sha256((root / "capture-manifest.json").read_bytes()),
    }


def verify_baseline(source, baseline, receipt_file):
    """No subprocesses, network, SDK, or writes. Does not consult any .ai-work capture."""
    receipt = load_json(receipt_file)
    require_equal(sorted(("formatVersion", "kind", "inputHashes", "pins", "normalizedFiles", "captureManifestSha256")),
                  sorted(receipt), "Receipt schema")
    require_equal(1, receipt["formatVersion"], "Receipt format")
    require_equal(RECEIPT_KIND, receipt["kind"], "Receipt kind")
    hash_inventory({"manifest": receipt["captureManifestSha256"]}, {"manifest"}, "Capture evidence digest")
    hash_inventory(receipt["inputHashes"], capture.INPUTS, "Capture input")
    require_equal(current_inputs(source), receipt["inputHashes"], "Current capture inputs")
    pins = pinned_metadata(source)
    require_equal(pins, receipt["pins"], "Capture pins")
    files = hash_inventory(receipt["normalizedFiles"], FIXTURE_FILES, "Normalized fixture")
    baseline = capture.no_symlinks(baseline)
    if not baseline.is_dir():
        raise ValueError("Missing baseline directory")
    actual = capture.inventory(baseline)
    require_equal(sorted(FIXTURE_FILES | {"capture-manifest.json"}), sorted(actual), "Baseline inventory")
    require_equal(files, {key: value for key, value in actual.items() if key != "capture-manifest.json"}, "Baseline bytes")
    legacy = load_json(baseline / "capture-manifest.json")
    require_equal(sorted(("dotnetSdk", "files", "formatVersion", "normalizations", "packages")), sorted(legacy), "Snapshot schema")
    require_equal(1, legacy["formatVersion"], "Snapshot format")
    require_equal(files, legacy["files"], "Snapshot inventory/checksums")
    require_equal(pins["packages"], legacy["packages"], "Snapshot packages")
    require_equal(pins["dotnetSdk"], legacy["dotnetSdk"], "Snapshot SDK")
    require_equal(SNAPSHOT_NORMALIZATIONS, legacy["normalizations"], "Snapshot normalization")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="check the reviewed baseline offline")
    mode.add_argument("--candidate", type=Path, help="emit a candidate receipt from a verified current full capture")
    parser.add_argument("--inputs", type=Path, default=capture.HERE)
    parser.add_argument("--baseline", type=Path, default=capture.HERE.parent / "captured")
    parser.add_argument("--receipt", type=Path, default=capture.HERE / "baseline-receipt.json")
    args = parser.parse_args()
    try:
        if args.check:
            verify_baseline(args.inputs, args.baseline, args.receipt)
            print("Verified seven captured proxies against reviewed receipt, current inputs and SDK/runtime/package pins (offline)")
        else:
            print(json.dumps(candidate_receipt(args.candidate, args.inputs), indent=2, sort_keys=True))
    except (ValueError, KeyError, TypeError, OSError, ET.ParseError) as error:
        print(f"Baseline verification refused: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
