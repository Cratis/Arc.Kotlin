// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.TypeDescriptor
import io.cratis.arc.metadata.ValidationRuleDescriptor
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ArcFluentValidationMetadataDiscoveryTest {
    private val resource = "META-INF/cratis/arc-fluent-validation/Inventory.json"
    private val service = "META-INF/services/io.cratis.arc.artifacts.ArcArtifactModule"

    @Test
    fun `directory and jar inventories are canonical and never initialize application bytecode`() {
        val directory = fixture()
        val jar = jar(directory)
        val first = ArcFluentValidationMetadataDiscovery.extract(listOf(directory), listOf(directory))
        val second = ArcFluentValidationMetadataDiscovery.extract(listOf(jar, directory), listOf(directory, jar))
        assertEquals(first.toList(), second.toList())
        assertTrue(String(first).contains("fixture.Rules"))
        assertTrue(String(first).contains("\"arguments\":[5]"))
    }

    @Test
    fun `missing resource and missing bytecode fail closed`() {
        val directory = fixture()
        val withoutMetadata = jar(directory) { it != resource }
        assertTrue(assertThrows(GradleException::class.java) { ArcFluentValidationMetadataDiscovery.inventory(listOf(withoutMetadata)) }.message!!.contains("unindexed=[fixture.Rules]"))
        val withoutClass = jar(directory) { it != "fixture/Rules.class" }
        assertTrue(assertThrows(GradleException::class.java) { ArcFluentValidationMetadataDiscovery.inventory(listOf(withoutClass)) }.message!!.contains("missing bytecode=[fixture.Rules]"))
    }

    @Test
    fun `missing runtime module or service registration fails closed`() {
        val directory = fixture()
        for (missing in listOf(service, "io/cratis/arc/generated/InventoryArcArtifactModule.class")) {
            val jar = jar(directory) { it != missing }
            assertTrue(assertThrows(GradleException::class.java) { ArcFluentValidationMetadataDiscovery.inventory(listOf(jar)) }.message!!.contains("runtime artifact module or ServiceLoader"))
        }
    }

    @Test
    fun `runtime only and compile only fluent dependencies both reject`() {
        val directory = fixture()
        assertTrue(assertThrows(GradleException::class.java) { ArcFluentValidationMetadataDiscovery.extract(emptyList(), listOf(directory)) }.message!!.contains("runtime-only=[fixture.Rules]"))
        assertTrue(assertThrows(GradleException::class.java) { ArcFluentValidationMetadataDiscovery.extract(listOf(directory), emptyList()) }.message!!.contains("compile-only=[fixture.Rules]"))
    }

    @Test
    fun `conflicting declarations and compile runtime drift reject`() {
        val first = fixture()
        val second = fixture()
        second.resolve(resource).writeText(document(6))
        assertTrue(assertThrows(GradleException::class.java) { ArcFluentValidationMetadataDiscovery.inventory(listOf(first, second)) }.message!!.contains("conflicting fluent declaration"))
        assertTrue(assertThrows(GradleException::class.java) { ArcFluentValidationMetadataDiscovery.extract(listOf(first), listOf(second)) }.message!!.contains("Compile/runtime fluent metadata differs"))
    }

    @Test
    fun `strict format fields vocabulary arguments and duplicate JSON keys reject`() {
        val directory = fixture()
        val original = document(5)
        val mutations = listOf(
            original.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            original.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
            original.replace("\"modelTypeName\"", "\"unknownField\""),
            original.replace("\"maxLength\"", "\"creditCard\""),
            original.replace("[5]", "[\"5\"]"),
            original.replace("[5]", "[-1]"),
            original.replace("\"moduleName\":\"Inventory\"", "\"moduleName\":\"Other\"")
        )
        for (mutation in mutations) {
            directory.resolve(resource).writeText(mutation)
            assertThrows(GradleException::class.java, { ArcFluentValidationMetadataDiscovery.inventory(listOf(directory)) }, mutation)
        }
    }

    @Test
    fun `manual generator boundary refuses manifests that missed dependency rules`() {
        val directory = fixture()
        fun manifest(rules: List<ValidationRuleDescriptor>) {
            val type = TypeDescriptor("Person", "fixture.Person", properties = listOf(PropertyDescriptor("name", "kotlin.String", validationRules = rules)))
            directory.resolve("META-INF/cratis/arc/Consumer.json").apply {
                parentFile.mkdirs(); writeText(ArcObjectMapper.create().writeValueAsString(ArcArtifactManifest("Consumer", types = listOf(type))))
            }
        }
        manifest(emptyList())
        val scope = directory.resolve("META-INF/cratis/arc-fluent-validation-scope/Consumer.json").apply { parentFile.mkdirs() }
        scope.writeText("""{"formatVersion":1,"moduleName":"Consumer","indexed":false,"validators":[]}""")
        assertTrue(assertThrows(GradleException::class.java) { ArcManifestDiscovery.discover(listOf(directory), "Consumer") }.message!!.contains("no complete verified root scope"))
        scope.writeText("""{"formatVersion":1,"moduleName":"Consumer","indexed":true,"validators":[]}""")
        assertTrue(assertThrows(GradleException::class.java) { ArcManifestDiscovery.discover(listOf(directory), "Consumer") }.message!!.contains("Missing declarations: [fixture.Rules]"))
        scope.writeText("""{"formatVersion":1,"moduleName":"Consumer","indexed":true,"validators":["fixture.Rules"]}""")
        assertTrue(assertThrows(GradleException::class.java) { ArcManifestDiscovery.discover(listOf(directory), "Consumer") }.message!!.contains("missing from"))
        manifest(listOf(ValidationRuleDescriptor("maxLength", listOf(5))))
        assertEquals(1, ArcManifestDiscovery.discover(listOf(directory), "Consumer").size)
        assertTrue(assertThrows(GradleException::class.java) { ArcManifestDiscovery.discover(listOf(directory)) }.message!!.contains("--module-name"))
    }

    @Test
    fun `stale module bytecode without generated validator linkage rejects`() {
        val directory = fixture(linked = false)
        assertTrue(assertThrows(GradleException::class.java) { ArcFluentValidationMetadataDiscovery.inventory(listOf(directory)) }.message!!.contains("no generated runtime validator linkage"))
    }

    private fun fixture(linked: Boolean = true): File {
        val parent = File(System.getProperty("arc.fluent.evidence") ?: System.getProperty("arc.functional.work")).apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "fluent-inventory-").toFile()
        val source = root.resolve("src")
        val output = root.resolve("classes").apply { mkdirs() }
        val references = if (linked) "private final Class<?> declaration = fixture.Rules.class; private final Class<?> registration = io.cratis.arc.validation.FluentValidatorRegistration.class;" else ""
        val files = mapOf(
            "io/cratis/arc/validation/FluentModelValidator.java" to "package io.cratis.arc.validation; public abstract class FluentModelValidator<T> {}",
            "io/cratis/arc/validation/FluentValidatorRegistration.java" to "package io.cratis.arc.validation; public final class FluentValidatorRegistration {}",
            "io/cratis/arc/artifacts/ArcArtifactModule.java" to "package io.cratis.arc.artifacts; public abstract class ArcArtifactModule {}",
            "io/cratis/arc/generated/InventoryArcArtifactModule.java" to "package io.cratis.arc.generated; public final class InventoryArcArtifactModule extends io.cratis.arc.artifacts.ArcArtifactModule { $references }",
            "io/cratis/arc/generated/ConsumerArcArtifactModule.java" to "package io.cratis.arc.generated; public final class ConsumerArcArtifactModule extends io.cratis.arc.artifacts.ArcArtifactModule { $references }",
            "fixture/Rules.java" to "package fixture; public final class Rules extends io.cratis.arc.validation.FluentModelValidator<Object> { static { if (true) throw new AssertionError(\"APPLICATION LOADED\"); } }"
        ).map { (path, text) -> source.resolve(path).apply { parentFile.mkdirs(); writeText(text) } }
        val compiler = ToolProvider.getSystemJavaCompiler()
        compiler.getStandardFileManager(null, null, null).use { manager ->
            assertTrue(compiler.getTask(null, manager, null, listOf("--release", "17", "-d", output.path), null, manager.getJavaFileObjectsFromFiles(files)).call())
        }
        output.resolve(resource).apply { parentFile.mkdirs(); writeText(document(5)) }
        output.resolve(service).apply { parentFile.mkdirs(); writeText("io.cratis.arc.generated.InventoryArcArtifactModule\nio.cratis.arc.generated.ConsumerArcArtifactModule\n") }
        return output
    }
    private fun document(max: Int) = """{"formatVersion":1,"moduleName":"Inventory","validators":[{"validatorTypeName":"fixture.Rules","modelTypeName":"fixture.Person","members":[{"member":"name","memberTypeName":"java.lang.String","rules":[{"ruleName":"maxLength","arguments":[$max],"message":null}]}]}]}"""
    private fun jar(directory: File, include: (String) -> Boolean = { true }): File {
        val target = Files.createTempFile(directory.parentFile.toPath(), "fixture-", ".jar").toFile()
        JarOutputStream(target.outputStream()).use { output ->
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                val path = file.relativeTo(directory).invariantSeparatorsPath
                if (include(path)) { output.putNextEntry(JarEntry(path)); output.write(file.readBytes()); output.closeEntry() }
            }
        }
        return target
    }
}
