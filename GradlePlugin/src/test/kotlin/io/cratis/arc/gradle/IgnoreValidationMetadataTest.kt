// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.ApiEndpointOptions
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.TypeDescriptor
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.ValidationRuleDescriptor
import java.nio.file.Path
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class IgnoreValidationMetadataTest {
    @TempDir lateinit var directory: Path
    private val mapper = ArcObjectMapper.create()
    private fun member(name: String, type: String = "kotlin.String", ignore: Boolean = false) =
        PropertyDescriptor(name, TypeShapeDescriptor.value(type), false, emptyList(), false, emptyList(), null, ignore)

    @Test
    fun `format 8 requires an explicit boolean on every property and rejects prior format`() {
        val root = directory.resolve("input").toFile()
        val manifest = root.resolve("META-INF/cratis/arc/Input.json").apply { parentFile.mkdirs() }
        val original = mapper.writeValueAsString(ArcArtifactManifest("Input", types = listOf(TypeDescriptor("Model", "fixture.Model", properties = listOf(member("name", ignore = true))))))
        for (mutation in listOf(original.replace("\"formatVersion\":8", "\"formatVersion\":7"),
            original.replace("\"ignoreValidation\":true,", ""),
            original.replace("\"ignoreValidation\":true", "\"ignoreValidation\":null"),
            original.replace("\"ignoreValidation\":true", "\"ignoreValidation\":\"true\""),
            original.replace("\"validateRecursively\":false", "\"validateRecursively\":true"))) {
            assertFalse(original == mutation)
            manifest.writeText(mutation)
            assertThrows(GradleException::class.java) { ArcManifestDiscovery.discover(listOf(root)) }
        }
        manifest.writeText(original)
        val actual = ArcManifestDiscovery.discover(listOf(root)).single().manifest.types.single().properties.single()
        assertTrue(actual.ignoreValidation)
        assertEquals(member("name", ignore = true), actual)
    }

    @Test
    fun `merge rejects disagreement about ignored edge rather than unioning constraints back in`() {
        val ignored = TypeDescriptor("Model", "fixture.Model", properties = listOf(member("name", ignore = true)))
        val active = TypeDescriptor("Model", "fixture.Model", properties = listOf(member("name")))
        assertThrows(GradleException::class.java) { ValidationDescriptorMerge.merge(listOf(ignored, active), TypeDescriptor::class.java, "fixture.Model") }
        assertTrue(ValidationDescriptorMerge.merge(listOf(ignored, ignored), TypeDescriptor::class.java, "fixture.Model").properties.single().ignoreValidation)
    }

    @Test
    fun `shared renderer omits ignored declared rules and automatic recursion but keeps wire fields and active alias`() {
        val properties = listOf(member("ignored", "fixture.Child", true), member("alias", "fixture.Child"), member("name", ignore = true), member("sibling"))
        val child = TypeDescriptor("Child", "fixture.Child", location = listOf("fixture"), properties = listOf(member("name")))
        val model = TypeDescriptor("Model", "fixture.Model", location = listOf("fixture"), properties = properties)
        val command = CommandDescriptor("Run", "fixture.Run", properties = properties, location = listOf("fixture"))
        val rule = ValidationRuleDescriptor("notEmpty")
        val declarations = listOf("fixture.Model", "fixture.Run").map { name ->
            SharedValidatorDescriptor("${name}Rules", name, listOf(SharedValidationMember("name", "java.lang.String", listOf(rule)), SharedValidationMember("sibling", "java.lang.String", listOf(rule))))
        } + SharedValidatorDescriptor("fixture.ChildRules", "fixture.Child", listOf(SharedValidationMember("name", "java.lang.String", listOf(rule))))
        val artifacts = MergedArcArtifacts(listOf(command), emptyList(), listOf(model, child), emptyList(), sharedValidators = declarations)
        val output = directory.resolve("generated").toFile()
        TypeScriptProxyGenerator(artifacts, ProxyGenerationOptions(output, ApiEndpointOptions(), false, 1)).generate()
        for (name in listOf("Model", "Run")) {
            val text = output.resolve("$name.ts").readText()
            assertTrue(text.contains("ignored"), text)
            assertTrue(text.contains("c => c.sibling"), text)
            assertFalse(text.contains("c => c.name"), text)
            assertFalse(text.contains("value.ignored"), text)
            assertTrue(text.contains("value.alias"), text)
        }
        assertTrue(output.resolve("Child.ts").readText().contains("c => c.name"))
    }

    @Test
    fun `ignored self edge does not manufacture shared cycle but active self edge still rejects`() {
        fun artifacts(ignore: Boolean) = MergedArcArtifacts(emptyList(), emptyList(), listOf(
            TypeDescriptor("Model", "fixture.Model", properties = listOf(member("next", "fixture.Model", ignore), member("name")))), emptyList(),
            sharedValidators = listOf(SharedValidatorDescriptor("fixture.Rules", "fixture.Model", listOf(SharedValidationMember("name", "java.lang.String", listOf(ValidationRuleDescriptor("notEmpty")))))))
        assertTrue(SharedValidationGraph(artifacts(true)).contains("fixture.Model"))
        assertThrows(GradleException::class.java) { SharedValidationGraph(artifacts(false)) }
    }
}
