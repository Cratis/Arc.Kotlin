# Copyright (c) Cratis. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.
"""Offline baseline verifier regressions. Temporary output is supplied by Gradle or lifecycle."""

import copy
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import capture
import verify_baseline as verifier


class BaselineTest(unittest.TestCase):
    def setUp(self):
        managed = os.environ.get("AI_WORK_OUTPUT")
        if not managed:
            self.fail("AI_WORK_OUTPUT must identify an existing test workspace")
        self.workspace = tempfile.TemporaryDirectory(prefix="baseline-", dir=managed)
        self.addCleanup(self.workspace.cleanup)
        self.root = Path(self.workspace.name)
        self.source = self.root / "inputs"
        self.source.mkdir()
        for name in capture.INPUTS:
            shutil.copyfile(capture.HERE / name, self.source / name)
        self.baseline = self.root / "baseline"
        shutil.copytree(capture.HERE.parent / "captured", self.baseline)
        self.receipt_file = self.root / "receipt.json"
        shutil.copyfile(capture.HERE / "baseline-receipt.json", self.receipt_file)
        self.receipt = verifier.load_json(self.receipt_file)

    def write_receipt(self, receipt):
        self.receipt_file.write_text(json.dumps(receipt), encoding="utf-8")

    def check(self):
        return verifier.verify_baseline(self.source, self.baseline, self.receipt_file)

    def full_capture(self):
        # Synthetic test evidence only; real candidate provenance was separately obtained from the pinned tool.
        raw = self.root / "raw"
        stage = self.root / "full"
        raw.mkdir()
        stage.mkdir()
        for name in verifier.FIXTURE_FILES:
            target = raw / name
            target.parent.mkdir(parents=True, exist_ok=True)
            data = (self.baseline / name).read_bytes()
            if not name.endswith("/index.ts"):
                data = data.replace(b". Hash:", b". Time: 2026-09-14T07:42:48.6501150Z. Hash:", 1)
            target.write_bytes(data)
        capture.prepare(raw, stage, verifier.pinned_metadata(self.source), self.source)
        return stage

    def test_original_baseline_checks_without_network_subprocesses_or_writes(self):
        before = capture.inventory(self.root)
        with patch.object(capture.subprocess, "run", side_effect=AssertionError("No process allowed")), \
                patch.object(capture.urllib.request, "urlopen", side_effect=AssertionError("No network allowed")):
            self.check()
        self.assertEqual(before, capture.inventory(self.root))

    def test_every_generation_input_is_bound_including_scripts_and_lockfiles(self):
        for name in capture.INPUTS:
            path = self.source / name
            original = path.read_bytes()
            with self.subTest(input=name):
                path.write_bytes(original + b"\n")
                with self.assertRaisesRegex(ValueError, "Current capture inputs"):
                    self.check()
                path.write_bytes(original)

    def test_missing_input_or_wrong_input_inventory_fails(self):
        file = self.source / "Fixture.cs"
        file.unlink()
        with self.assertRaisesRegex(ValueError, "Missing capture input"):
            self.check()
        shutil.copyfile(capture.HERE / "Fixture.cs", file)
        self.receipt["inputHashes"]["elsewhere.cs"] = self.receipt["inputHashes"].pop("Fixture.cs")
        self.write_receipt(self.receipt)
        with self.assertRaisesRegex(ValueError, "input path inventory"):
            self.check()

    def test_sdk_runtime_package_tool_and_policy_pin_drift_is_rejected(self):
        cases = [
            ("dotnetSdk", "10.0.401"), ("toolArchiveSha256", "0" * 64),
            ("lockedRestore", False), ("lockedRestore", 1),
            ("dependencyArchiveAndPayloadHashesVerified", False),
            ("runtimeSelection", "roll forward"), ("generatorArguments", ["other.dll", "raw", "1"]),
            ("normalization", "trim-every-line"),
            ("packages", {"Cratis.Arc": "0.0.0", "Cratis.Arc.ProxyGenerator": "22.14.0"}),
            ("dotnetRuntimes", {"Microsoft.NETCore.App": "10.0.11"}),
            ("generatorObservedRuntimes", {"Microsoft.NETCore.App": "10.0.12", "Microsoft.AspNetCore.App": "10.0.11"}),
        ]
        for key, value in cases:
            receipt = copy.deepcopy(self.receipt)
            receipt["pins"][key] = value
            self.write_receipt(receipt)
            with self.subTest(pin=key, value=value), self.assertRaisesRegex(ValueError, "Capture pins"):
                self.check()

    def test_same_bytes_under_replaced_current_pins_still_need_new_review(self):
        # Even if somebody refreshes only the file hash, pinned metadata still has to agree.
        file = self.source / "global.json"
        sdk = verifier.load_json(file)
        sdk["sdk"]["version"] = "10.0.401"
        file.write_text(json.dumps(sdk))
        self.receipt["inputHashes"]["global.json"] = capture.sha256(file.read_bytes())
        self.write_receipt(self.receipt)
        with self.assertRaisesRegex(ValueError, "Capture pins"):
            self.check()

    def test_receipt_rejects_ambiguous_json_and_types(self):
        for data in ('{"formatVersion":1,"formatVersion":1}', '{"bad":NaN}', '[]'):
            self.receipt_file.write_text(data)
            with self.subTest(json=data), self.assertRaises(ValueError):
                self.check()
        for field, value in (("formatVersion", True), ("formatVersion", 1.0), ("kind", "other"),
                             ("captureManifestSha256", "not-a-hash")):
            receipt = copy.deepcopy(self.receipt)
            receipt[field] = value
            self.write_receipt(receipt)
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.check()

    def test_snapshot_mutation_cannot_hide_behind_renewed_snapshot_checksums(self):
        name = verifier.FIXTURE_PREFIX + "PlaceOrder.ts"
        file = self.baseline / name
        file.write_bytes(file.read_bytes().replace(b"quantity?: number;", b"quantity?: string;"))
        legacy_path = self.baseline / "capture-manifest.json"
        legacy = verifier.load_json(legacy_path)
        legacy["files"][name] = capture.sha256(file.read_bytes())
        legacy_path.write_text(json.dumps(legacy))
        with self.assertRaisesRegex(ValueError, "Baseline bytes"):
            self.check()

    def test_snapshot_metadata_is_bound_even_when_proxy_bytes_are_unchanged(self):
        file = self.baseline / "capture-manifest.json"
        original = verifier.load_json(file)
        for key, value in (("formatVersion", True), ("undeclared", True), ("dotnetSdk", "10.0.401"), ("packages", {}),
                           ("normalizations", ["strip everything"]), ("files", {})):
            modified = dict(original, **{key: value})
            file.write_text(json.dumps(modified))
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "Snapshot"):
                self.check()

    def test_missing_extra_and_duplicate_basename_paths_are_not_filtered_out(self):
        file = self.baseline / (verifier.FIXTURE_PREFIX + "Address.ts")
        original = file.read_bytes()
        file.unlink()
        with self.assertRaisesRegex(ValueError, "Baseline inventory"):
            self.check()
        file.write_bytes(original)
        for name in ("other/Address.ts", "unrelated.txt"):
            extra = self.baseline / name
            extra.parent.mkdir(parents=True, exist_ok=True)
            extra.write_bytes(original)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Baseline inventory"):
                self.check()
            extra.unlink()

    def test_receipt_inventory_cannot_escape_or_omit_fixture_files(self):
        name = next(iter(self.receipt["normalizedFiles"]))
        for replacement in ("../outside.ts", "/outside.ts", "wrong/Address.ts"):
            receipt = copy.deepcopy(self.receipt)
            receipt["normalizedFiles"][replacement] = receipt["normalizedFiles"].pop(name)
            self.write_receipt(receipt)
            with self.subTest(path=replacement), self.assertRaisesRegex(ValueError, "Normalized fixture path inventory"):
                self.check()

    def test_symlinks_in_inputs_snapshot_and_receipt_fail(self):
        locations = [self.source / "Fixture.cs", self.receipt_file,
                     self.baseline / (verifier.FIXTURE_PREFIX + "Address.ts")]
        for index, target in enumerate(locations):
            original = target.read_bytes()
            saved = self.root / f"saved-{index}"
            target.rename(saved)
            target.symlink_to(saved)
            with self.subTest(path=target), self.assertRaises(ValueError):
                self.check()
            target.unlink()
            target.write_bytes(original)
        alias = self.root / "alias"
        alias.symlink_to(self.source, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "Symlink"):
            verifier.verify_baseline(alias, self.baseline, self.receipt_file)

    def test_candidate_requires_full_current_capture_not_just_snapshot_hashes(self):
        stage = self.full_capture()
        candidate = verifier.candidate_receipt(stage, self.source)
        self.assertEqual(self.receipt["normalizedFiles"], candidate["normalizedFiles"])
        self.assertEqual(self.receipt["inputHashes"], candidate["inputHashes"])
        self.assertEqual(self.receipt["pins"], candidate["pins"])
        with self.assertRaisesRegex(ValueError, "Full capture format"):
            verifier.candidate_receipt(self.baseline, self.source)
        (self.source / "Fixture.cs").write_text("modified")
        with self.assertRaisesRegex(ValueError, "Current capture inputs"):
            verifier.candidate_receipt(stage, self.source)

    def test_candidate_rejects_false_pin_claims_even_with_internally_valid_capture(self):
        stage = self.full_capture()
        manifest_file = stage / "capture-manifest.json"
        original = verifier.load_json(manifest_file)
        for field, value in (("dotnetSdk", "10.0.401"), ("lockedRestore", 1),
                             ("toolArchiveSha256", "0" * 64), ("generatorObservedRuntimes", {})):
            manifest_file.write_text(json.dumps(dict(original, **{field: value})))
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, f"Capture {field}"):
                verifier.candidate_receipt(stage, self.source)

    def test_candidate_rejects_corrupted_raw_content(self):
        stage = self.full_capture()
        file = stage / "raw" / (verifier.FIXTURE_PREFIX + "Address.ts")
        file.write_bytes(file.read_bytes() + b"modified")
        with self.assertRaisesRegex(ValueError, "checksum"):
            verifier.candidate_receipt(stage, self.source)

    def test_candidate_rejects_normalized_tampering_even_after_inventory_hashes_are_renewed(self):
        stage = self.full_capture()
        name = verifier.FIXTURE_PREFIX + "Address.ts"
        file = stage / "normalized" / name
        original = file.read_bytes()
        mutated = original.replace(b"export class Address", b"export interface Address")
        self.assertNotEqual(original, mutated)
        file.write_bytes(mutated)
        manifest_file = stage / "capture-manifest.json"
        manifest = verifier.load_json(manifest_file)
        manifest["files"]["normalized/" + name] = capture.sha256(mutated)
        manifest["normalizedFiles"][name] = capture.sha256(mutated)
        manifest_file.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "Normalization mismatch"):
            verifier.candidate_receipt(stage, self.source)

    def test_candidate_rejects_symlinked_capture_root(self):
        stage = self.full_capture()
        alias = self.root / "capture-alias"
        alias.symlink_to(stage, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "Symlink"):
            verifier.candidate_receipt(alias, self.source)


if __name__ == "__main__":
    unittest.main()
