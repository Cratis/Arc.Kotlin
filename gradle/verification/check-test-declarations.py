#!/usr/bin/env python3
# Copyright (c) Cratis. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.

"""Check direct standard Jupiter method declarations, not general test discovery.

Only supplied ordinary test class directories are scanned. A byte prefilter avoids
javap for helper classes; javap inspects metadata without loading/initializing the
application. Composed annotations, class eligibility and runtime discovery are not
proved here. TestFactory has a different return contract and is not checked.
"""

import argparse
from collections import Counter
from pathlib import Path
import re
import subprocess
import sys


ANNOTATIONS = (
    "org.junit.jupiter.api.Test",
    "org.junit.jupiter.params.ParameterizedTest",
    "org.junit.jupiter.api.RepeatedTest",
    "org.junit.jupiter.api.TestTemplate",
)
MARKERS = tuple(name.replace(".", "/").encode() + b";" for name in ANNOTATIONS)
FORBIDDEN = {"ACC_PRIVATE", "ACC_STATIC", "ACC_ABSTRACT"}


def inspect(text):
    """Read the bounded JDK17 javap member/annotation format; fail closed on errors."""
    if not text.startswith("Classfile ") or "\n{\n" not in text or "\n}\n" not in text:
        raise ValueError("unrecognized javap class output")
    members = text.split("\n{\n", 1)[1].split("\n}\n", 1)[0]
    counts = Counter()
    failures = []
    for member in re.split(r"(?=^  \S)", members, flags=re.MULTILINE):
        if not member.strip():
            continue
        header = member.splitlines()[0].strip()
        descriptor = re.search(r"^    descriptor: (.+)$", member, re.MULTILINE)
        flags = re.search(r"^    flags: \(0x[0-9a-fA-F]+\)(.*)$", member, re.MULTILINE)
        if descriptor is None or flags is None:
            raise ValueError(f"missing member descriptor/flags: {header}")
        if not descriptor[1].startswith("("):
            continue  # Fields do not declare test methods.
        annotations = set()
        for block in re.findall(
            r"^    RuntimeVisibleAnnotations:\n((?:^      .*\n|^\s*$\n)*)",
            member + "\n", re.MULTILINE
        ):
            annotations.update(re.findall(r"^        ([\w.]+)(?:\(|$)", block, re.MULTILINE))
        matched = annotations.intersection(ANNOTATIONS)
        counts.update(matched)
        if matched:
            invalid_flags = FORBIDDEN.intersection(flags[1].replace(",", "").split())
            if not descriptor[1].endswith(")V") or invalid_flags:
                failures.append(
                    f"{header} descriptor={descriptor[1]} flags={flags[1].strip()} "
                    f"annotations={','.join(sorted(matched))}: "
                    "test methods must return void and must not be private, static or abstract; "
                    "use an explicit Unit return type for Kotlin runBlocking tests"
                )
    return counts, failures


def check(javap, directories):
    version = subprocess.run([str(javap), "-version"], check=True, capture_output=True, text=True)
    if not re.match(r"17(?:\.|$)", version.stdout.strip()):
        raise ValueError(f"JDK17 javap required, got {version.stdout.strip()!r}")
    classes = []
    for directory in directories:
        if not directory.is_dir():
            raise ValueError(f"test class directory does not exist: {directory}")
        classes.extend(directory.rglob("*.class"))
    classes = sorted(set(classes))
    candidates = [path for path in classes if any(marker in path.read_bytes() for marker in MARKERS)]
    counts = Counter()
    failures = []
    for start in range(0, len(candidates), 32):
        batch = candidates[start:start + 32]
        result = subprocess.run(
            [str(javap), "-J-Duser.language=en", "-J-Duser.country=US", "-p", "-v", *map(str, batch)],
            check=True, capture_output=True, text=True
        )
        if result.stderr.strip():
            raise ValueError(f"javap inspection diagnostic: {result.stderr}")
        outputs = re.split(r"(?=^Classfile )", result.stdout, flags=re.MULTILINE)
        outputs = [output for output in outputs if output.strip()]
        if len(outputs) != len(batch):
            raise ValueError("javap did not return exactly one class result per input")
        for path, output in zip(batch, outputs):
            found, invalid = inspect(output)
            counts.update(found)
            failures.extend(f"{path}: {failure}" for failure in invalid)
    print(f"Scanned {len(classes)} class files; inspected {len(candidates)} byte-prefiltered classes")
    for name in ANNOTATIONS:
        print(f"{name}: {counts[name]} direct method declarations")
    for failure in failures:
        print(failure, file=sys.stderr)
    print(f"Invalid standard Jupiter method declarations: {len(failures)}")
    return counts, failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--javap", required=True, type=Path)
    parser.add_argument("directories", nargs="*", type=Path)
    args = parser.parse_args()
    try:
        _, failures = check(args.javap, args.directories)
        return 1 if failures else 0
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"Test declaration inspection failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
