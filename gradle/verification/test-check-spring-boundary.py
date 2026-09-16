#!/usr/bin/env python3
# Copyright (c) Cratis. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.

"""Compiled mutations exercising the same CLI used by Gradle; no Spring dependency needed."""

import argparse
import os
from pathlib import Path
import subprocess
import sys
import unittest
import zipfile

CHECKER = Path(__file__).with_name("check-spring-boundary.py")


class SpringBoundaryTest(unittest.TestCase):
    def check_fixture(self, *, root="public class Root {}", helper=None,
                      consumer="public class Consumer {}", expected=None, omit_helper=False,
                      metadata="public class Metadata {}", json="public class Json {}",
                      vendor=None, omit_vendor=False, broken_vendor=False, runtime_vendor=None):
        work = WORK / self.id().split(".")[-1]
        work.mkdir(parents=True, exist_ok=False)
        sources = {
            "io/cratis/arc/artifacts/Root.java": root,
            "io/cratis/arc/metadata/Metadata.java": metadata,
            "io/cratis/arc/json/Json.java": json,
            "io/cratis/arc/runtime/Unrelated.java":
                "public class Unrelated { org.springframework.fixture.SpringType value; }",
            "org/springframework/fixture/SpringType.java": "public class SpringType {}",
            "org/springframework/fixture/Marker.java":
                "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) public @interface Marker {}",
            "org/springframework/fixture/TypeMarker.java":
                "@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) public @interface TypeMarker {}",
            "org/springframework/fixture/SpringFailure.java": "public class SpringFailure extends Exception {}",
        }
        if helper is not None:
            sources["io/cratis/arc/queries/Helper.java"] = helper
        # Compile the dependency independently, then compile Arc against its JARs.
        # Spring is deliberately absent from the check's classpath.
        spring_sources = {key: value for key, value in sources.items() if key.startswith("org/")}
        sources = {key: value for key, value in sources.items() if not key.startswith("org/")}
        spring_jar = compile_jar(work / "spring", spring_sources)
        vendor_jar = compile_jar(work / "vendor", vendor, [spring_jar]) if vendor else None
        paths = []
        for relative, body in sources.items():
            path = work / "src" / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            package = relative.rsplit("/", 1)[0].replace("/", ".")
            path.write_text(f"package {package};\n{body}\n")
            paths.append(path)
        classes = work / "classes"
        subprocess.run([str(JAVAC), "--release", "17", "-cp",
                        os.pathsep.join(map(str, [spring_jar] + ([vendor_jar] if vendor_jar else []))),
                        "-d", str(classes), *map(str, paths)], check=True)
        consumer_path = work / "consumer-src/io/cratis/arc/tool/Consumer.java"
        consumer_path.parent.mkdir(parents=True)
        consumer_path.write_text("package io.cratis.arc.tool;\n" + consumer + "\n")
        consumers = work / "consumers"
        subprocess.run([str(JAVAC), "--release", "17", "-cp",
                        os.pathsep.join(map(str, [classes, spring_jar] + ([vendor_jar] if vendor_jar else []))),
                        "-d", str(consumers), str(consumer_path)], check=True)
        # Deliberately pass only Arc classes; the fake Spring API must NOT be on the check's classpath.
        # Copy preserves the compiled originals as regression evidence, including missing-class cases.
        source = work / "source"
        for path in (classes / "io").rglob("*.class"):
            if omit_helper and path.name == "Helper.class":
                continue
            destination = source / path.relative_to(classes)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(path.read_bytes())
        extra = []
        if vendor_jar and not omit_vendor:
            if broken_vendor:
                corrupt = work / "broken.jar"
                with zipfile.ZipFile(corrupt, "w") as archive:
                    archive.writestr("vendor/Base.class", b"not a class file")
                vendor_jar = corrupt
            extra = ["--classpath", str(vendor_jar)]
        if runtime_vendor:
            extra += ["--tool-classpath", str(compile_jar(work / "runtime-vendor", runtime_vendor, [spring_jar]))]
        result = subprocess.run([sys.executable, "-B", str(CHECKER), "--jdeps", str(JDEPS),
                                 "--source", str(source), "--consumer", str(consumers), *extra],
                                capture_output=True, text=True)
        if expected is None:
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("Spring-free boundary passed", result.stdout)
        else:
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn(expected, result.stderr)
        (work / "result.txt").write_text(f"exit={result.returncode}\n{result.stdout}{result.stderr}")

    def test_symbolic_spring_names_and_unreachable_runtime_are_allowed(self):
        self.check_fixture(consumer='public class Consumer { String name = "org.springframework.data.domain.Page"; }')

    def test_unused_protected_root_is_checked(self):
        self.check_fixture(root="public class Root { org.springframework.fixture.SpringType value; }",
                           expected="io.cratis.arc.artifacts.Root -> org.springframework.fixture.SpringType")

    def test_unused_metadata_root_is_checked(self):
        self.check_fixture(metadata="public class Metadata { org.springframework.fixture.SpringType value; }",
                           expected="io.cratis.arc.metadata.Metadata -> org.springframework.fixture.SpringType")

    def test_unused_json_root_is_checked(self):
        self.check_fixture(json="public class Json { org.springframework.fixture.SpringType value; }",
                           expected="io.cratis.arc.json.Json -> org.springframework.fixture.SpringType")

    def test_safe_transitive_generic_signature_is_allowed(self):
        self.check_fixture(root="public class Root { java.util.List<io.cratis.arc.queries.Helper> values; }",
                           helper="public class Helper { java.util.List<String> values; }")

    def test_indirect_generic_signature_is_rejected(self):
        self.check_fixture(root="public class Root { java.util.List<io.cratis.arc.queries.Helper> values; }",
                           helper="public class Helper { java.util.List<org.springframework.fixture.SpringType> values; }",
                           expected="io.cratis.arc.artifacts.Root -> io.cratis.arc.queries.Helper -> org.springframework.fixture.SpringType")

    def test_consumer_discovers_types_outside_protected_packages(self):
        self.check_fixture(consumer="public class Consumer { io.cratis.arc.queries.Helper value; }",
                           helper="public class Helper { org.springframework.fixture.SpringType value; }",
                           expected="io.cratis.arc.tool.Consumer -> io.cratis.arc.queries.Helper -> org.springframework.fixture.SpringType")

    def test_consumer_generic_signature_is_rejected(self):
        self.check_fixture(consumer="public class Consumer { java.util.List<org.springframework.fixture.SpringType> values; }",
                           expected="io.cratis.arc.tool.Consumer -> org.springframework.fixture.SpringType")

    def test_generic_bound_is_rejected(self):
        self.check_fixture(root="public class Root<T extends org.springframework.fixture.SpringType> {}",
                           expected="org.springframework.fixture.SpringType")

    def test_annotation_is_rejected(self):
        self.check_fixture(root="@org.springframework.fixture.Marker public class Root {}",
                           expected="org.springframework.fixture.Marker")

    def test_class_valued_annotation_argument_is_rejected(self):
        self.check_fixture(root="@Root.Type(org.springframework.fixture.SpringType.class) public class Root { public @interface Type { Class<?> value(); } }",
                           expected="org.springframework.fixture.SpringType")

    def test_type_annotation_is_rejected(self):
        self.check_fixture(root="public class Root { java.util.List<@org.springframework.fixture.TypeMarker String> value; }",
                           expected="org.springframework.fixture.TypeMarker")

    def test_bytecode_only_reference_is_rejected(self):
        self.check_fixture(root="public class Root { Object value() { return new org.springframework.fixture.SpringType(); } }",
                           expected="org.springframework.fixture.SpringType")

    def test_nested_private_class_is_a_root(self):
        self.check_fixture(root="public class Root { private static class Nested { org.springframework.fixture.SpringType value; } }",
                           expected="io.cratis.arc.artifacts.Root$Nested -> org.springframework.fixture.SpringType")

    def test_declared_exception_is_rejected(self):
        self.check_fixture(root="public class Root { void run() throws org.springframework.fixture.SpringFailure {} }",
                           expected="org.springframework.fixture.SpringFailure")

    def test_missing_transitive_local_class_fails_closed(self):
        self.check_fixture(root="public class Root { io.cratis.arc.queries.Helper value; }",
                           helper="public class Helper {}", omit_helper=True,
                           expected="Missing referenced local class")

    def test_external_superclass_chain_is_rejected(self):
        self.check_fixture(root="public class Root extends vendor.Base {}",
                           vendor={"vendor/Base.java": "public class Base extends Parent {}",
                                   "vendor/Parent.java": "public class Parent extends org.springframework.fixture.SpringType {}"},
                           expected="vendor.Base -> vendor.Parent -> org.springframework.fixture.SpringType")

    def test_external_interface_chain_is_rejected(self):
        self.check_fixture(root="public abstract class Root implements vendor.Base {}",
                           vendor={"vendor/Base.java": "public interface Base extends Parent {}",
                                   "vendor/Parent.java": "public interface Parent extends org.springframework.fixture.Marker {}"},
                           expected="vendor.Base -> vendor.Parent -> org.springframework.fixture.Marker")

    def test_external_generic_bound_is_rejected(self):
        self.check_fixture(root="public class Root extends vendor.Base {}",
                           vendor={"vendor/Base.java": "public class Base<T extends org.springframework.fixture.SpringType> {}"},
                           expected="vendor.Base -> org.springframework.fixture.SpringType")

    def test_external_generic_interface_argument_is_rejected(self):
        self.check_fixture(root="public abstract class Root implements vendor.Base {}",
                           vendor={"vendor/Base.java": "public interface Base extends java.util.function.Supplier<org.springframework.fixture.SpringType> {}"},
                           expected="vendor.Base -> org.springframework.fixture.SpringType")

    def test_external_safe_hierarchy_and_optional_internals_are_allowed(self):
        self.check_fixture(root="public class Root extends vendor.Base {}",
                           vendor={"vendor/Base.java": """public class Base<T extends Number>
                               implements java.io.Serializable {
                               public org.springframework.fixture.SpringType optional() { return null; }
                               private org.springframework.fixture.SpringType field;
                               public Object dynamic() { return new org.springframework.fixture.SpringType(); }
                           }"""})

    def test_external_nested_generic_bound_is_rejected(self):
        self.check_fixture(root="public class Root extends vendor.Base {}",
                           vendor={"vendor/Base.java": "public class Base<T extends Container<String>.Inner<Integer>> {}",
                                   "vendor/Container.java": "public class Container<T> { public abstract class Inner<U> implements org.springframework.fixture.Marker {} }"},
                           expected="vendor.Base -> vendor.Container$Inner -> org.springframework.fixture.Marker")

    def test_runtime_variant_cannot_hide_behind_safe_compile_definition(self):
        self.check_fixture(root="public class Root extends vendor.Base {}",
                           vendor={"vendor/Base.java": "public class Base {}"},
                           runtime_vendor={"vendor/Base.java": "public class Base extends org.springframework.fixture.SpringType {}"},
                           expected="vendor.Base -> org.springframework.fixture.SpringType")

    def test_missing_generic_bound_definition_fails_closed(self):
        self.check_fixture(root="public class Root<T extends vendor.Base> {}",
                           vendor={"vendor/Base.java": "public class Base {}"}, omit_vendor=True,
                           expected="Missing external class definition: vendor.Base")

    def test_source_only_descriptor_constant_is_not_mandatory_linkage(self):
        self.check_fixture(root='public class Root { String metadata = "Lkotlin/collections/LinkedHashMap;"; }')

    def test_spring_descriptor_constant_is_rejected_conservatively(self):
        self.check_fixture(root='public class Root { String metadata = "Lorg/springframework/fixture/SpringType;"; }',
                           expected="org.springframework.fixture.SpringType")

    def test_missing_external_definition_fails_closed(self):
        self.check_fixture(root="public class Root extends vendor.Base {}",
                           vendor={"vendor/Base.java": "public class Base {}"}, omit_vendor=True,
                           expected="Missing external class definition: vendor.Base")

    def test_unreadable_external_definition_fails_closed(self):
        self.check_fixture(root="public class Root extends vendor.Base {}",
                           vendor={"vendor/Base.java": "public class Base {}"}, broken_vendor=True,
                           expected="Cannot establish external structure")

    def test_empty_outputs_fail_closed(self):
        work = WORK / "empty"
        work.mkdir()
        result = subprocess.run([sys.executable, "-B", str(CHECKER), "--jdeps", str(JDEPS),
                                 "--source", str(work), "--consumer", str(work)], capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("No compiled classes found", result.stderr)


def compile_jar(work, sources, classpath=()):
    paths = []
    for relative, body in sources.items():
        path = work / "src" / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        package = relative.rsplit("/", 1)[0].replace("/", ".")
        path.write_text(f"package {package};\n{body}\n")
        paths.append(path)
    classes = work / "classes"
    subprocess.run([str(JAVAC), "--release", "17", "-cp", os.pathsep.join(map(str, classpath)),
                    "-d", str(classes), *map(str, paths)], check=True)
    jar = work / "dependency.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        for path in classes.rglob("*.class"):
            archive.write(path, path.relative_to(classes).as_posix())
    return jar


class GradleClasspathTest(unittest.TestCase):
    """Run the actual applied Gradle script in minimal, independent Java projects.

    No production build file is mutated. The isolated build has the same project paths,
    source sets and configurations, and uses actual compiled file/Maven dependency JARs.
    The nested checker regression task is excluded to avoid recursive fixture execution.
    """
    def check_gradle(self, declaration, spring=True, expected=None, module=":CodeGeneration:KSP"):
        work = WORK / self.id().split(".")[-1]
        work.mkdir()
        dependency = compile_jar(work / "dependency", {
            ("org/springframework/fixture/SpringType.java" if spring else "vendor/Safe.java"):
                ("public class SpringType {}" if spring else "public class Safe {}")})
        (work / "settings.gradle").write_text(
            "rootProject.name = 'boundary-fixture'\ninclude ':Source', ':CodeGeneration:KSP', ':GradlePlugin'\n")
        scripts = work / "gradle/verification"
        scripts.mkdir(parents=True)
        for name in ("check-spring-boundary.py", "test-check-spring-boundary.py", "spring-boundary.gradle.kts"):
            (scripts / name).write_bytes(CHECKER.with_name(name).read_bytes())
        # A local Maven repository proves group resolution, with no network dependency.
        artifact = work / "repository/org/springframework/fixture/1"
        artifact.mkdir(parents=True)
        (artifact / "fixture-1.jar").write_bytes(dependency.read_bytes())
        (artifact / "fixture-1.pom").write_text(
            '<project><modelVersion>4.0.0</modelVersion><groupId>org.springframework</groupId>'
            '<artifactId>fixture</artifactId><version>1</version></project>')
        (work / "build.gradle").write_text("""
subprojects {
    apply plugin: 'java'
    repositories { maven { url = rootProject.file('repository') } }
    java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
}
project('%s') { dependencies { %s } }
apply from: 'gradle/verification/spring-boundary.gradle.kts'
""" % (module, declaration.replace("DEPENDENCY", dependency.as_posix())))
        for module in ("Source", "CodeGeneration/KSP", "GradlePlugin"):
            names = ("artifacts/Root", "metadata/Metadata", "json/Json") if module == "Source" else (module.replace("/", "") + "/Consumer",)
            for name in names:
                package, simple = name.rsplit("/", 1)
                path = work / module / "src/main/java/io/cratis/arc" / (name + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"package io.cratis.arc.{package}; public class {simple} {{}}\n")
        command = [str(GRADLE), "-p", str(work), "checkSpringBoundary", "-x", "testSpringBoundaryChecker",
                   "--offline", "--no-daemon", "--no-configuration-cache", "--max-workers=1", "--console=plain"]
        (work / "command.txt").write_text("\n".join(command) + "\n")
        result = subprocess.run(command, capture_output=True, text=True)
        (work / "gradle.log").write_text(result.stdout + result.stderr)
        if expected:
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn(expected, result.stdout + result.stderr)
        else:
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("Spring-free boundary passed", result.stdout)

    def test_compile_only_maven_spring_is_rejected(self):
        self.check_gradle("compileOnly 'org.springframework:fixture:1'", expected="Spring on :CodeGeneration:KSP compileClasspath")

    def test_runtime_only_file_spring_is_rejected(self):
        self.check_gradle("runtimeOnly files('DEPENDENCY')", module=":GradlePlugin",
                          expected="Spring definition on tool compile/runtime classpath")

    def test_compile_only_file_spring_is_rejected(self):
        self.check_gradle("compileOnly files('DEPENDENCY')", expected="Spring definition on tool compile/runtime classpath")

    def test_safe_file_dependency_is_allowed(self):
        self.check_gradle("implementation files('DEPENDENCY')", spring=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--javac", type=Path, required=True)
    parser.add_argument("--jdeps", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--gradle", type=Path, required=True)
    args = parser.parse_args()
    JAVAC, JDEPS, WORK, GRADLE = args.javac, args.jdeps, args.work_dir, args.gradle
    WORK.mkdir(parents=True, exist_ok=False)
    unittest.main(argv=[sys.argv[0]], verbosity=2)
