// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.metadata.ApiEndpointOptions
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ConceptDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.TypeDescriptor
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class ArcProxyTypeMappingTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `type mapping corrects a built-in mapping rather than only adding an unknown one`() {
        val command = CommandDescriptor(
            "SaveSchedule",
            "sample.SaveSchedule",
            listOf(PropertyDescriptor("day", "java.time.LocalDate")),
            location = listOf("sample")
        )
        val output = temporaryDirectory.resolve("corrected")

        generate(
            output,
            MergedArcArtifacts(listOf(command), emptyList(), emptyList(), emptyList()),
            typeMappings = listOf("java.time.LocalDate=string")
        )

        val proxy = Files.readString(output.resolve("SaveSchedule.ts"))
        assertTrue(proxy.contains("day?: string;"), proxy)
        // The built-in mapping would have produced DateOnly and imported it from @cratis/fundamentals.
        assertFalse(proxy.contains("DateOnly"), proxy)
    }

    @Test
    fun `type mapping with an npm package imports the type instead of generating it`() {
        val money = TypeDescriptor(
            "Money",
            "sample.Money",
            listOf("sample"),
            listOf(PropertyDescriptor("amount", "kotlin.Long"))
        )
        val command = CommandDescriptor(
            "Pay",
            "sample.Pay",
            listOf(PropertyDescriptor("total", "sample.Money")),
            location = listOf("sample")
        )
        val output = temporaryDirectory.resolve("external-type")

        generate(
            output,
            MergedArcArtifacts(listOf(command), emptyList(), listOf(money), emptyList()),
            typeMappings = listOf("sample.Money=Money=@acme/models")
        )

        val proxy = Files.readString(output.resolve("Pay.ts"))
        assertTrue(proxy.contains("import { Money } from '@acme/models';"), proxy)
        assertTrue(proxy.contains("total?: Money;"), proxy)
        assertFalse(proxy.contains("from './Money'"), proxy)
        // A mapped type must not also be generated, or a local declaration and an import collide.
        assertFalse(Files.exists(output.resolve("Money.ts")), "mapped types must not be generated")
    }

    @Test
    fun `package mapping imports every model under the package by its simple name`() {
        val types = listOf("Customer", "Address").map { name ->
            TypeDescriptor(name, "shared.model.$name", listOf("shared", "model"), emptyList())
        }
        val command = CommandDescriptor(
            "Register",
            "sample.Register",
            types.map { PropertyDescriptor(it.name.replaceFirstChar(Char::lowercaseChar), it.fullyQualifiedName) },
            location = listOf("sample")
        )
        val output = temporaryDirectory.resolve("external-package")

        generate(
            output,
            MergedArcArtifacts(listOf(command), emptyList(), types, emptyList()),
            packageMappings = mapOf("shared.model" to "@acme/models")
        )

        val proxy = Files.readString(output.resolve("Register.ts"))
        assertTrue(proxy.contains("import { Address, Customer } from '@acme/models';"), proxy)
        types.forEach { type ->
            assertFalse(Files.exists(output.resolve("model/${type.name}.ts")), "${type.name} must not be generated")
        }
    }

    @Test
    fun `longest matching package wins so a nested package overrides a broader one`() {
        val type = TypeDescriptor("Money", "shared.money.Money", listOf("shared", "money"), emptyList())
        val command = CommandDescriptor(
            "Pay",
            "sample.Pay",
            listOf(PropertyDescriptor("total", "shared.money.Money")),
            location = listOf("sample")
        )
        val output = temporaryDirectory.resolve("longest-package")

        generate(
            output,
            MergedArcArtifacts(listOf(command), emptyList(), listOf(type), emptyList()),
            packageMappings = mapOf("shared" to "@acme/base", "shared.money" to "@acme/money")
        )

        val proxy = Files.readString(output.resolve("Pay.ts"))
        assertTrue(proxy.contains("import { Money } from '@acme/money';"), proxy)
        assertFalse(proxy.contains("@acme/base"), proxy)
    }

    @Test
    fun `a concept resolves through its underlying type mapping, matching Arc dotNET ordering`() {
        val concept = ConceptDescriptor("ScheduleDay", "sample.ScheduleDay", "java.time.LocalDate")
        val command = CommandDescriptor(
            "SaveDay",
            "sample.SaveDay",
            listOf(PropertyDescriptor("day", "sample.ScheduleDay")),
            location = listOf("sample")
        )
        val output = temporaryDirectory.resolve("concept-ordering")

        generate(
            output,
            MergedArcArtifacts(listOf(command), emptyList(), emptyList(), emptyList(), concepts = listOf(concept)),
            typeMappings = listOf("java.time.LocalDate=string")
        )

        val proxy = Files.readString(output.resolve("SaveDay.ts"))
        assertTrue(proxy.contains("day?: string;"), proxy)
    }

    @Test
    fun `unusable entries are named in a warning rather than dropped silently`() {
        val warnings = mutableListOf<String>()

        val mappings = ProxyTypeMappings.parseTypeMappings(
            listOf("sample.Ok=Ok", "sample.NoTsType=", "=OrphanTsType", "noSeparator", "   "),
            warnings::add
        )

        assertEquals(setOf("sample.Ok"), mappings.keys)
        assertEquals(4, warnings.size, warnings.toString())
        listOf("sample.NoTsType=", "=OrphanTsType", "noSeparator").forEach { entry ->
            assertTrue(warnings.any { warning -> "'$entry'" in warning }, "no warning named '$entry': $warnings")
        }
    }

    @Test
    fun `the split is bounded so everything after the second separator stays in the npm package`() {
        val warnings = mutableListOf<String>()

        val mappings = ProxyTypeMappings.parseTypeMappings(
            listOf("sample.Packaged=Money=@acme/models", "sample.Extra=Money=@acme/models=trailing"),
            warnings::add
        )

        assertTrue(warnings.isEmpty(), warnings.toString())
        assertEquals("Money", mappings.getValue("sample.Packaged").typeScriptType)
        assertEquals("@acme/models", mappings.getValue("sample.Packaged").npmPackage)
        // Bounded to three parts, so a trailing separator is kept rather than dropped without saying so.
        assertEquals("Money", mappings.getValue("sample.Extra").typeScriptType)
        assertEquals("@acme/models=trailing", mappings.getValue("sample.Extra").npmPackage)
    }

    @Test
    fun `unusable package mappings are named in a warning`() {
        val warnings = mutableListOf<String>()

        val mappings = ProxyTypeMappings.parsePackageMappings(
            listOf("shared=@acme/models", "shared.broken=", "=@acme/orphan", "noSeparator"),
            warnings::add
        )

        assertEquals(mapOf("shared" to "@acme/models"), mappings)
        assertEquals(3, warnings.size, warnings.toString())
    }

    @Test
    fun `invalid mappings fail before touching output including unused entries`() {
        val output = temporaryDirectory.resolve("unsafe")
        Files.createDirectories(output)
        val manual = output.resolve("manual.ts")
        Files.writeString(manual, "// keep this file\n")
        val artifacts = MergedArcArtifacts(emptyList(), emptyList(), emptyList(), emptyList())
        for (entry in listOf(
            "app.Bad=string[]", "app.Bad=X;throw Error()=@acme/models",
            "app.Bad=Money=@acme/models'", "app.Bad=Money=../models", "app.Bad=Money=@acme/../models",
            "app.Bad=unknown", "app.Bad=ImaginaryGlobal", "app.Bad=delete=@acme/models",
            "app.Bad=return=@acme/models", "app.Bad=const=@acme/models"
        )) {
            assertThrows(GradleException::class.java, { generate(output, artifacts, listOf(entry)) }, entry)
            assertEquals("// keep this file\n", Files.readString(manual))
        }
    }

    @Test
    fun `external import collisions include generated scaffolding and global constructors`() {
        val command = CommandDescriptor("Save", "app.Save", listOf(
            PropertyDescriptor("text", "kotlin.String"), PropertyDescriptor("mapped", "shared.Money")
        ))
        val artifacts = MergedArcArtifacts(listOf(command), emptyList(), emptyList(), emptyList())
        for (name in listOf("String", "Command", "PropertyDescriptor", "ISave", "Save", "SaveValidator")) {
            val failure = assertThrows(GradleException::class.java) {
                generate(temporaryDirectory.resolve(name), artifacts, listOf("shared.Money=$name=@acme/models"))
            }
            assertTrue(name in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun `external interface and enum names cannot shadow their global descriptor constructors`() {
        val view = io.cratis.arc.metadata.InterfaceDescriptor("View", "shared.View", emptyList(), emptyList())
        val state = io.cratis.arc.metadata.EnumDescriptor("State", "shared.State")
        for ((type, name) in listOf("shared.View" to "Object", "shared.State" to "Number")) {
            val command = CommandDescriptor("Save", "app.Save", listOf(PropertyDescriptor("value", type)))
            val artifacts = MergedArcArtifacts(listOf(command), emptyList(), emptyList(), listOf(state), listOf(view))
            val failure = assertThrows(GradleException::class.java) {
                generate(temporaryDirectory.resolve(name), artifacts, listOf("$type=$name=@acme/models"))
            }
            assertTrue("shadowed" in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun `mapping addition and removal cleans generated models but preserves manual files`() {
        val money = TypeDescriptor("Money", "shared.Money", listOf("shared"), emptyList())
        val command = CommandDescriptor("Pay", "app.Pay", listOf(PropertyDescriptor("total", "shared.Money")))
        val artifacts = MergedArcArtifacts(listOf(command), emptyList(), listOf(money), emptyList())
        val output = temporaryDirectory.resolve("transitions")
        generate(output, artifacts)
        val manual = output.resolve("manual.ts")
        Files.writeString(manual, "// authored\n")
        assertTrue(Files.exists(output.resolve("Money.ts")))
        generate(output, artifacts, packageMappings = mapOf("shared" to "@acme/models"))
        assertFalse(Files.exists(output.resolve("Money.ts")))
        assertFalse(Files.readString(output.resolve("index.ts")).contains("./Money"))
        assertEquals("// authored\n", Files.readString(manual))
        generate(output, artifacts)
        assertTrue(Files.exists(output.resolve("Money.ts")))
        assertTrue(Files.readString(output.resolve("index.ts")).contains("./Money"))
    }

    private fun generate(
        output: Path,
        artifacts: MergedArcArtifacts,
        typeMappings: List<String> = emptyList(),
        packageMappings: Map<String, String> = emptyMap()
    ) {
        val warnings = mutableListOf<String>()
        TypeScriptProxyGenerator(
            artifacts,
            ProxyGenerationOptions(
                output.toFile(),
                ApiEndpointOptions(),
                true,
                1,
                ProxyTypeMappings.parseTypeMappings(typeMappings, warnings::add),
                ProxyTypeMappings.parsePackageMappings(
                    packageMappings.map { (javaPackage, npmPackage) -> "$javaPackage=$npmPackage" },
                    warnings::add
                )
            )
        ).generate()
        assertTrue(warnings.isEmpty(), "unexpected mapping warnings: $warnings")
    }
}
