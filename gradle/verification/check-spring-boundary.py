#!/usr/bin/env python3
# Copyright (c) Cratis. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.

"""Check compiled compiler/build-tool linkage with the JDK's class-file dependency reader.

jdeps includes bytecode, descriptors, generic signatures, exceptions and annotations (not
just public API); constant-pool descriptor inspection also covers JDK17 jdeps omissions
such as invisible/type annotations, class type bounds and Kotlin metadata. Roots are EVERY
artifacts/metadata/json class and EVERY KSP/Gradle main class, including synthetic/nested classes. Follow references through ALL supplied
local classes, not just those packages. External classes are resolved on the production
classpaths and their superclass/interface/class-signature closure is checked, failing
closed on missing definitions. Do not recursively inspect external member bodies or
unused member signatures: those can belong to optional features. This is not proof of
arbitrary external implementation safety. Symbolic dotted strings, dynamic reflection
and resource contents are outside this static check. Tool compile AND runtime classpath
contents are separately checked for Spring definitions, including file dependencies.
"""

import argparse
from collections import deque
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile

PROTECTED = tuple(f"io.cratis.arc.{name}." for name in ("artifacts", "metadata", "json"))
EDGE = re.compile(r"^\s+(\S+)\s+->\s+(\S+)\s+.+$")
DESCRIPTOR_TYPE = re.compile(r"L([\w$/]+(?:\.[\w$]+)*)")


def descriptor_references(path):
    """Supplement jdeps17's omissions: invisible/type annotations and class type bounds.

    Conservatively inspect descriptor-shaped UTF8 constants, including Kotlin metadata.
    This can also reject a descriptor-looking literal, intentionally; ordinary dotted
    symbolic names used to recognize Spring consumer declarations remain allowed.
    JDK jdeps remains responsible for full class-file validation and bytecode edges.
    """
    data = path.read_bytes()
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError(f"Invalid class file: {path}")
    count = struct.unpack_from(">H", data, 8)[0]
    index, offset = 1, 10
    references = set()
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            size = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            text = data[offset:offset + size].decode("utf-8", errors="replace")
            for match in DESCRIPTOR_TYPE.finditer(text):
                name = match[1]
                if "/" in name:
                    references.add(name.replace(".", "$").replace("/", "."))
            offset += size
        elif tag in sizes:
            offset += sizes[tag]
            if tag in (5, 6):
                index += 1
        else:
            raise ValueError(f"Unknown constant-pool tag {tag}: {path}")
        index += 1
    return references


def class_signature_references(signature):
    """Parse JVMS 4.7.9.1 class signatures, including nested generic owner types."""
    position = 0
    references = set()

    def type_signature():
        nonlocal position
        kind = signature[position]
        position += 1
        if kind in "+-[":
            type_signature()
        elif kind in "*BCDFIJSZ":
            return
        elif kind == "T":
            position = signature.index(";", position) + 1
        elif kind == "L":
            name = ""
            while True:
                start = position
                while signature[position] not in "<.;":
                    position += 1
                name += signature[start:position]
                references.add(name.replace("/", "."))
                if signature[position] == "<":
                    position += 1
                    while signature[position] != ">":
                        type_signature()
                    position += 1
                if signature[position] == ";":
                    position += 1
                    break
                if signature[position] != ".":
                    raise ValueError(f"Invalid class signature: {signature}")
                name += "$"
                position += 1
        else:
            raise ValueError(f"Invalid class signature: {signature}")

    if signature.startswith("<"):
        position += 1
        while signature[position] != ">":
            position = signature.index(":", position) + 1
            if signature[position] != ":":
                type_signature()
            while signature[position] == ":":
                position += 1
                type_signature()
        position += 1
    while position < len(signature):
        type_signature()
    return references


def external_structure(data):
    """Read only mandatory class structure, not optional external implementation edges.

    JVMS 4: constant pool, superclass, interfaces, and the class Signature attribute.
    Class signatures include parameter bounds and generic supertype arguments. Member
    descriptors/signatures are checked when local code references those types, not by
    recursively expanding every unused API of every external library.
    """
    offset = 0

    def take(size):
        nonlocal offset
        value = data[offset:offset + size]
        if len(value) != size:
            raise ValueError("Truncated external class file")
        offset += size
        return value

    def u2():
        return struct.unpack(">H", take(2))[0]

    if take(4) != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid external class file")
    take(4)  # minor, major
    count = u2()
    pool = [None] * count
    index = 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = take(1)[0]
        if tag == 1:
            pool[index] = take(u2()).decode("utf-8", errors="replace")
        elif tag == 7:
            pool[index] = u2()
        elif tag in sizes:
            take(sizes[tag])
            if tag in (5, 6):
                index += 1
        else:
            raise ValueError(f"Unknown external constant-pool tag {tag}")
        index += 1

    def class_name(index):
        return pool[pool[index]].replace("/", ".")

    take(4)  # access_flags, this_class
    parent = u2()
    references = {class_name(parent)} if parent else set()
    references.update(class_name(u2()) for _ in range(u2()))

    def attributes():
        for _ in range(u2()):
            name = pool[u2()]
            size = struct.unpack(">I", take(4))[0]
            yield name, take(size)

    for _ in range(2):  # fields, methods: deliberately not implementation/API recursion
        for _ in range(u2()):
            take(6)
            list(attributes())
    for name, value in attributes():
        if name == "Signature":
            signature = pool[struct.unpack(">H", value)[0]]
            references.update(class_signature_references(signature))
    if offset != len(data):
        raise ValueError("Trailing external class-file data")
    return references


class Classpath:
    """Index JARs/directories without expanding external bytecode graphs with jdeps.

    Honor Java 17 multi-release selection. Tool content checks inspect all versions,
    conservatively rejecting a Spring definition even in an inactive JAR version.
    JDK classes are terminal only when present in this JDK's module inventory.
    """
    def __init__(self, jdeps, paths, tool_paths):
        self.classes = {}
        self.jdk = set()
        self.archives = []
        jmods = Path(jdeps).resolve().parent.parent / "jmods"
        if not jmods.is_dir():
            raise ValueError(f"Cannot establish JDK class inventory: {jmods}")
        for module in jmods.glob("*.jmod"):
            with zipfile.ZipFile(module) as archive:
                self.jdk.update(name[8:-6].replace("/", ".") for name in archive.namelist()
                                if name.startswith("classes/") and name.endswith(".class"))
        if "java.lang.Object" not in self.jdk:
            raise ValueError("JDK class inventory omits java.lang.Object")
        tools = {Path(path).resolve() for path in tool_paths}
        for path in dict.fromkeys(Path(path).resolve() for path in [*paths, *tool_paths]):
            if path.is_dir():
                entries = {entry.relative_to(path).as_posix(): entry for entry in path.rglob("*.class")}
                read = lambda entry: entry.read_bytes()
            elif path.is_file() and zipfile.is_zipfile(path):
                archive = zipfile.ZipFile(path)
                self.archives.append(archive)
                entries = {name: name for name in archive.namelist() if name.endswith(".class")}
                read = archive.read
            else:
                raise ValueError(f"Cannot inspect classpath entry: {path}")
            multi_release = False
            if path.is_file():
                try:
                    manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
                    multi_release = bool(re.search(r"(?im)^Multi-Release: true\s*$", manifest))
                except KeyError:
                    pass
            selected = {}
            for entry, location in entries.items():
                version, name = 0, entry
                match = re.fullmatch(r"META-INF/versions/(\d+)/(.*)", entry)
                if match:
                    version, name = int(match[1]), match[2]
                if path in tools and name.startswith("org/springframework/"):
                    raise ValueError(f"Spring definition on tool compile/runtime classpath: {path}!/{entry}")
                if version and (not multi_release or version > 17):
                    continue
                if name.startswith("META-INF/") or name == "module-info.class":
                    continue
                if name not in selected or version > selected[name][0]:
                    selected[name] = (version, location)
            for name, (_, location) in selected.items():
                # Compile and runtime configurations can select different definitions.
                # Check every reachable variant rather than letting their union's order
                # hide a mandatory superclass/signature behind a safe compile-time copy.
                self.classes.setdefault(name[:-6].replace("/", "."), []).append((read, location))

    def references(self, name):
        if name in self.jdk:
            return set()
        if name not in self.classes:
            raise ValueError(f"Missing external class definition: {name}")
        references = set()
        for read, location in self.classes[name]:
            references.update(external_structure(read(location)))
        return references

    def close(self):
        for archive in self.archives:
            archive.close()


def inventory(directories):
    names = set()
    for directory in directories:
        root = Path(directory)
        if not root.is_dir():
            raise ValueError(f"Missing compiled class directory: {root}")
        for path in root.rglob("*.class"):
            name = path.relative_to(root).as_posix()[:-6].replace("/", ".")
            if name in names:
                raise ValueError(f"Duplicate compiled local class: {name}")
            names.add(name)
    if not names:
        raise ValueError("No compiled classes found")
    return names


def dependencies(jdeps, directories, local, external):
    version = subprocess.run([str(jdeps), "--version"], text=True, capture_output=True, check=True)
    if not re.match(r"17(?:\.|$)", version.stdout.strip()):
        raise ValueError(f"JDK17 jdeps required, got {version.stdout.strip()!r}")
    result = subprocess.run(
        [str(jdeps), "--multi-release", "17", "-verbose:class", "-filter:none",
         *map(str, directories)], text=True, capture_output=True, check=True,
    )
    if result.stderr.strip():
        raise ValueError(f"jdeps diagnostic: {result.stderr}")
    graph = {name: set() for name in local}
    seen = set()
    for line in result.stdout.splitlines():
        match = EDGE.match(line)
        if match and match[1] in local:
            seen.add(match[1])
            graph[match[1]].add(match[2])
    # Fail closed if the tool output changes or a class was silently omitted.
    if local - seen:
        raise ValueError(f"jdeps omitted local classes: {sorted(local - seen)}\n{result.stdout}")
    for directory in directories:
        for path in Path(directory).rglob("*.class"):
            name = path.relative_to(directory).as_posix()[:-6].replace("/", ".")
            # Actual class structure is mandatory, including bounds omitted by jdeps17.
            graph[name].update(external_structure(path.read_bytes()))
            # Kotlin metadata also spells source-only aliases (e.g. kotlin.collections.LinkedHashMap)
            # as descriptors. They have no JVM definition. Do not confuse these symbolic
            # constants with mandatory linkage; actual descriptors/bytecode above still
            # fail closed. Always reject Spring constants and missing Arc-local references.
            graph[name].update(reference for reference in descriptor_references(path)
                               if reference.startswith(("org.springframework.", "io.cratis.arc."))
                               or reference in external.classes or reference in external.jdk)
    return graph


def check(jdeps, source_dirs, consumer_dirs, classpath=(), tool_classpath=()):
    source = inventory(source_dirs)
    consumers = inventory(consumer_dirs)
    if source & consumers:
        raise ValueError(f"Source/consumer class collision: {sorted(source & consumers)}")
    local = source | consumers
    roots = {name for name in source if name.startswith(PROTECTED)} | consumers
    for prefix in PROTECTED:
        if not any(name.startswith(prefix) for name in source):
            raise ValueError(f"No compiled roots for {prefix}")
    external = Classpath(jdeps, classpath, tool_classpath)
    graph = dependencies(jdeps, source_dirs + consumer_dirs, local, external)
    paths = {name: [name] for name in sorted(roots)}
    pending = deque(sorted(roots))
    violations = []
    try:
        while pending:
            owner = pending.popleft()
            try:
                references = graph[owner] if owner in local else external.references(owner)
            except (ValueError, IndexError, TypeError, struct.error) as error:
                raise ValueError(f"Cannot establish external structure: {' -> '.join(paths[owner])}: {error}") from error
            for target in sorted(references):
                path = paths[owner] + [target]
                if target.startswith("org.springframework."):
                    violations.append(" -> ".join(path))
                elif target.startswith("io.cratis.arc.") and target not in local:
                    raise ValueError(f"Missing referenced local class: {' -> '.join(path)}")
                elif target not in paths:
                    paths[target] = path
                    pending.append(target)
    finally:
        external.close()
    if violations:
        raise ValueError("Spring linkage crosses the compiler/Gradle boundary:\n" + "\n".join(violations))
    return len(roots), len(paths), len(local)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdeps", required=True, type=Path)
    parser.add_argument("--source", action="append", required=True, type=Path)
    parser.add_argument("--consumer", action="append", required=True, type=Path)
    parser.add_argument("--classpath", action="append", default=[], type=Path)
    parser.add_argument("--tool-classpath", action="append", default=[], type=Path)
    args = parser.parse_args()
    try:
        roots, reached, total = check(args.jdeps, args.source, args.consumer, args.classpath, args.tool_classpath)
    except (OSError, ValueError, IndexError, struct.error, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError):
            print(error.stdout, error.stderr, file=sys.stderr)
        return 1
    print(f"Spring-free boundary passed: {roots} roots, {reached} reachable local/external classes, {total} inventoried classes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
