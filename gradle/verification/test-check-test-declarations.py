#!/usr/bin/env python3
# Copyright (c) Cratis. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.

"""Executable checker fixtures compiled with the repository's real Jupiter annotations."""

import argparse
from collections import Counter
import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest

sys.dont_write_bytecode = True
HOME = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("checker", HOME / "check-test-declarations.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class DeclarationCheckerTests(unittest.TestCase):
    def test_valid_parameterized_repeated_template_and_inherited_concrete_methods(self):
        counts, failures = checker.check(ARGS.javap, [CLASSES / "fixtures/valid"])
        self.assertEqual(Counter(dict.fromkeys(checker.ANNOTATIONS, 1)), counts)
        self.assertEqual([], failures)
        self.assertTrue((CLASSES / "fixtures/valid/FirstChild.class").is_file())
        self.assertTrue((CLASSES / "fixtures/valid/SecondChild.class").is_file())

    def test_invalid_return_and_private_static_abstract_method_flags(self):
        counts, failures = checker.check(ARGS.javap, [CLASSES / "fixtures/invalid"])
        self.assertEqual(7, sum(counts.values()))
        self.assertEqual(7, len(failures))
        for method in ("nonVoid", "privateMethod", "staticMethod", "abstractMethod",
                       "parameterized", "repeated", "template"):
            self.assertEqual(1, sum(f" {method}(" in failure for failure in failures), method)

    def test_factory_nonvoid_return_is_excluded_despite_annotation_string_decoy(self):
        counts, failures = checker.check(ARGS.javap, [CLASSES / "fixtures/factory"])
        self.assertEqual(Counter(), counts)
        self.assertEqual([], failures)

    def test_missing_directory_is_an_inspection_error(self):
        with self.assertRaisesRegex(ValueError, "does not exist"):
            checker.check(ARGS.javap, [ARGS.work_dir / "missing"])

    def test_corrupt_candidate_is_an_inspection_error(self):
        corrupt = ARGS.work_dir / "corrupt"
        corrupt.mkdir(exist_ok=True)
        (corrupt / "Broken.class").write_bytes(b"not a class Lorg/junit/jupiter/api/Test;")
        result = subprocess.run(
            [sys.executable, str(HOME / "check-test-declarations.py"), "--javap",
             str(ARGS.javap), str(corrupt)], capture_output=True, text=True
        )
        self.assertEqual(2, result.returncode)
        self.assertIn("Test declaration inspection failed", result.stderr)

    def test_missing_javap_is_an_inspection_error(self):
        with self.assertRaises(OSError):
            checker.check(ARGS.work_dir / "missing-javap", [CLASSES])

    def test_unrecognized_javap_output_is_an_inspection_error(self):
        with self.assertRaisesRegex(ValueError, "unrecognized"):
            checker.inspect("not javap output")
        with self.assertRaisesRegex(ValueError, "descriptor/flags"):
            checker.inspect("Classfile broken.class\n{\n  void broken();\n}\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--javac", required=True, type=Path)
    parser.add_argument("--javap", required=True, type=Path)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--work-dir", required=True, type=Path)
    ARGS = parser.parse_args()
    CLASSES = ARGS.work_dir / "classes"
    CLASSES.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [str(ARGS.javac), "--release", "17", "-Xlint:all", "-Werror", "-classpath", ARGS.classpath,
         "-d", str(CLASSES), *map(str, sorted((HOME / "fixtures").glob("*.java")))], check=True
    )
    unittest.main(argv=[sys.argv[0]], verbosity=2)
