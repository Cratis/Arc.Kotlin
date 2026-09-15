# Copyright (c) Cratis. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.
"""Run with python3 -B -m unittest discover -s <capture-directory> -v, through lifecycle."""

import base64
import hashlib
import json
import os
from pathlib import Path
import tempfile
import subprocess
import unittest
import zipfile
from unittest.mock import patch

import capture

BODY = b"export class Test {}\n\n// A body Time: 2026-01-01T00:00:00.0000000Z is not a header.\n"


def generated(body=BODY, time=b"2026-09-14T07:42:48.6501150Z"):
    return (capture.MARKER + b". Source: A.B.Test. Time: " + time + b". Hash: "
            + capture.sha256(body).encode() + b"\n" + body)


class CaptureTest(unittest.TestCase):
    def setUp(self):
        managed = os.environ.get("AI_WORK_OUTPUT")
        if not managed:
            self.fail("Tests require lifecycle AI_WORK_OUTPUT, not system temporary files")
        # Deliberately do not recursively remove failure evidence. Lifecycle owns this directory.
        self.root = Path(tempfile.mkdtemp(prefix="unit-", dir=managed))

    def raw(self):
        raw = self.root / "raw"
        raw.mkdir()
        (raw / "Test.ts").write_bytes(generated())
        (raw / "index.ts").write_bytes(b"export * from './Test';\n")
        return raw

    def prepared(self):
        stage = self.root / "stage"
        stage.mkdir()
        capture.prepare(self.raw(), stage, {"dotnetSdk": "test"}, capture.HERE)
        return stage

    def test_fixed_checksum_vectors_and_corruption(self):
        capture.require_checksum(b"abc", "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD")
        expected = base64.b64encode(hashlib.sha512(b"abc").digest()).decode()
        capture.require_checksum(b"abc", expected, "sha512")
        for data, checksum, algorithm in ((b"abd", capture.sha256(b"abc"), "sha256"),
                                           (b"abd", expected, "sha512"),
                                           (b"abc", "not-a-checksum", "sha256")):
            with self.subTest(algorithm=algorithm, checksum=checksum), self.assertRaises(ValueError):
                capture.require_checksum(data, checksum, algorithm)

    def test_normalization_changes_only_first_line_timestamp(self):
        expected = capture.MARKER + b". Source: A.B.Test. Hash: " + capture.sha256(BODY).encode() + b"\n" + BODY
        self.assertEqual(expected, capture.normalize(generated(), Path("Test.ts")))
        self.assertEqual(expected, capture.normalize(generated(time=b"2025-01-01T00:00:00.0000000Z"), Path("Test.ts")))

    def test_header_parser_fails_closed_on_shape_and_body_mutations(self):
        good = generated()
        mutations = [b"\n" + good, b"prefix " + good, good.replace(b". Time:", b"! Time:"),
                     good.replace(b"6501150Z", b"650115Z"), good.replace(b"6501150Z", b"6501150+00:00"),
                     good.replace(b"2026-09-14", b"2026-02-30"), good.replace(b". Hash:", b". Other:"),
                     good.replace(b"\n", b"\r\n"), good.replace(BODY, BODY + b" "),
                     good + generated(), good.replace(b"A.B.Test", b""), b"\xef\xbb\xbf" + good,
                     capture.normalize(good, Path("Test.ts")), good.replace(b"export class", b"\xffxport class")]
        for mutation in mutations:
            with self.subTest(mutation=mutation[:120]), self.assertRaises(ValueError):
                capture.normalize(mutation, Path("Test.ts"))

    def test_index_is_exact_headerless_export_grammar(self):
        data = b"export * from './Test';\nexport * from './Other';\n"
        self.assertEqual(data, capture.normalize(data, Path("nested/index.ts")))
        for mutation in (b"", b"// manual\n" + data, data + b"\n", data.replace(b"'", b'"'), generated()):
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                capture.normalize(mutation, Path("index.ts"))
        with self.assertRaises(ValueError):
            capture.normalize(data, Path("Test.ts"))

    def test_refuses_every_existing_destination_including_owned_or_empty(self):
        for name in ("manual", "empty", "owned"):
            target = self.root / name
            target.mkdir()
            if name == "manual":
                (target / "precious.txt").write_text("preserve me")
            if name == "owned":
                (target / "capture-manifest.json").write_text('{"owner":"arc-kotlin-dotnet-capture"}')
            before = capture.inventory(target)
            with self.assertRaises(ValueError):
                capture.destination(target, self.root)
            self.assertEqual(before, capture.inventory(target))

    def test_refuses_root_escape_missing_parent_and_symlinks(self):
        for target in (self.root, self.root / ".." / "escape", self.root / "missing" / "child"):
            with self.subTest(target=target), self.assertRaises(ValueError):
                capture.destination(target, self.root)
        for name, target in (("link", self.root / "missing"), ("parent-link", self.root)):
            link = self.root / name
            link.symlink_to(target)
            self.addCleanup(link.unlink)
            with self.assertRaises(ValueError):
                capture.destination(link, self.root)
            with self.assertRaises(ValueError):
                capture.destination(link / "child", self.root)

    def test_verifier_detects_missing_extra_changed_and_symlink_files(self):
        stage = self.prepared()
        file = stage / "normalized/Test.ts"
        original = file.read_bytes()
        file.write_bytes(original + b" ")
        with self.assertRaisesRegex(ValueError, "checksum"):
            capture.verify_capture(stage)
        file.unlink()
        with self.assertRaisesRegex(ValueError, "checksum"):
            capture.verify_capture(stage)
        file.write_bytes(original)
        extra = stage / "extra.txt"
        extra.write_text("manual")
        with self.assertRaisesRegex(ValueError, "checksum"):
            capture.verify_capture(stage)
        extra.unlink()
        file.unlink()
        file.symlink_to(stage / "raw/Test.ts")
        self.addCleanup(file.unlink)
        with self.assertRaisesRegex(ValueError, "Non-regular"):
            capture.verify_capture(stage)

    def test_recomputed_file_hash_does_not_hide_normalization_change(self):
        stage = self.prepared()
        file = stage / "normalized/Test.ts"
        file.write_bytes(file.read_bytes().replace(b"export class", b"export interface"))
        manifest_file = stage / "capture-manifest.json"
        manifest = json.loads(manifest_file.read_text())
        manifest["files"]["normalized/Test.ts"] = capture.sha256(file.read_bytes())
        manifest["normalizedFiles"]["Test.ts"] = capture.sha256(file.read_bytes())
        manifest_file.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "Normalization mismatch"):
            capture.verify_capture(stage)

    def test_invalid_raw_or_io_failure_never_publishes_destination(self):
        raw = self.raw()
        stage = self.root / "stage"
        stage.mkdir()
        output = self.root / "capture"
        (raw / "ZBad.ts").write_text("manual")
        with self.assertRaises(ValueError):
            capture.prepare(raw, stage, {}, capture.HERE)
            capture.publish(stage, output, self.root)
        self.assertFalse(output.exists())
        (raw / "ZBad.ts").unlink()
        with patch.object(capture.shutil, "copyfile", side_effect=OSError("injected disk failure")):
            with self.assertRaises(OSError):
                capture.prepare(raw, stage, {}, capture.HERE)
                capture.publish(stage, output, self.root)
        self.assertFalse(output.exists())

    def test_success_is_complete_and_cannot_replace_even_empty_raced_directory(self):
        stage = self.prepared()
        output = self.root / "capture"
        # Simulate a competitor creating an empty destination after the last Python check.
        real_destination = capture.destination
        def race(path, root):
            result = real_destination(path, root)
            result.mkdir()
            return result
        with patch.object(capture, "destination", side_effect=race):
            with self.assertRaises(FileExistsError):
                capture.publish(stage, output, self.root)
        self.assertTrue(stage.is_dir())
        self.assertEqual([], list(output.iterdir()))
        output.rmdir()
        capture.publish(stage, output, self.root)
        self.assertFalse(stage.exists())
        capture.verify_capture(output)
        before = capture.inventory(output)
        with self.assertRaises(ValueError):
            capture.publish(self.root / "nonexistent", output, self.root)
        self.assertEqual(before, capture.inventory(output))

    def test_prepare_rejects_empty_unexpected_and_symlink_output(self):
        raw = self.root / "raw"
        raw.mkdir()
        stage = self.root / "stage"
        stage.mkdir()
        with self.assertRaises(ValueError):
            capture.prepare(raw, stage, {}, capture.HERE)
        (raw / "file.txt").write_text("not TypeScript")
        with self.assertRaises(ValueError):
            capture.prepare(raw, stage, {}, capture.HERE)
        (raw / "file.txt").unlink()
        (raw / "Test.ts").symlink_to(capture.HERE / "Fixture.cs")
        self.addCleanup((raw / "Test.ts").unlink)
        with self.assertRaises(ValueError):
            capture.prepare(raw, stage, {}, capture.HERE)

    def test_subprocess_failure_and_timeout_leave_no_published_output(self):
        output = self.root / "capture"
        failures = (subprocess.CalledProcessError(1, "dotnet", output=b"failed"),
                    subprocess.TimeoutExpired("dotnet", 180))
        for error in failures:
            with self.subTest(error=error), patch.object(capture, "run", side_effect=error):
                with patch.object(capture.shutil, "which", return_value="dotnet"):
                    with self.assertRaises(type(error)):
                        capture.capture(output)
            self.assertFalse(output.exists())

    def test_generator_runtime_resolution_must_match_both_exact_pins(self):
        log = ("  Runtime assemblies: /dotnet/shared/Microsoft.NETCore.App/10.0.11\n"
               "  AspNetCoreApp assemblies: /dotnet/shared/Microsoft.AspNetCore.App/10.0.11\n")
        self.assertEqual({"Microsoft.NETCore.App": "10.0.11", "Microsoft.AspNetCore.App": "10.0.11"},
                         capture.runtime_evidence(log, "10.0.11"))
        for mutation in ("", log + log, log.replace("10.0.11", "10.0.12", 1),
                         log.replace("AspNetCoreApp", "Unknown")):
            with self.subTest(log=mutation), self.assertRaises(ValueError):
                capture.runtime_evidence(mutation, "10.0.11")

    def test_package_archive_and_extracted_payload_are_both_verified(self):
        package = self.root / "test.nupkg"
        with zipfile.ZipFile(package, "w") as archive:
            archive.writestr("Test.nuspec", "metadata")
            archive.writestr("lib/Test.dll", b"payload")
            archive.writestr("[Content_Types].xml", "container metadata")
        (self.root / "test.nuspec").write_text("metadata")
        (self.root / "lib").mkdir()
        payload = self.root / "lib/Test.dll"
        payload.write_bytes(b"payload")
        checksum = capture.sha256(package.read_bytes())
        capture.verify_package(package, checksum)
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            capture.verify_package(package, "0" * 64)
        payload.write_bytes(b"corrupt cache")
        with self.assertRaisesRegex(ValueError, "payload mismatch"):
            capture.verify_package(package, checksum)
        payload.unlink()
        with self.assertRaisesRegex(ValueError, "payload mismatch"):
            capture.verify_package(package, checksum)

    def test_tool_extraction_rejects_traversal_and_missing_payload(self):
        package = self.root / "tool.nupkg"
        with zipfile.ZipFile(package, "w") as archive:
            archive.writestr("tools/net10.0/any/../escape", b"bad")
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            capture.extract_tool(package, self.root / "tools")
        self.assertFalse((self.root / "escape").exists())
        with zipfile.ZipFile(package, "w") as archive:
            archive.writestr("tools/net8.0/any/Test.dll", b"wrong framework")
        with self.assertRaisesRegex(ValueError, "Missing"):
            capture.extract_tool(package, self.root / "tools")


if __name__ == "__main__":
    unittest.main()
