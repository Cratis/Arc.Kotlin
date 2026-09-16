// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.TypeDescriptor
import io.cratis.arc.metadata.ValidationRuleDescriptor
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class SharedValidationGraphTest {
    private val rule = ValidationRuleDescriptor("notEmpty")
    private val shared = SharedValidatorDescriptor("test.Rules", "test.Person", listOf(SharedValidationMember("name", "java.lang.String", listOf(rule))))
    private val person = TypeDescriptor("Person", "test.Person", properties = listOf(PropertyDescriptor("name", "kotlin.String", validationRules = listOf(rule))))

    @Test
    fun `shared graph activates containing models without requiring Jakarta Valid`() {
        val owner = TypeDescriptor("Owner", "test.Owner", properties = listOf(PropertyDescriptor("person", "test.Person")))
        val graph = SharedValidationGraph(MergedArcArtifacts(emptyList(), emptyList(), listOf(owner, person), emptyList(), sharedValidators = listOf(shared)))
        assertTrue(graph.contains("test.Owner"))
        assertTrue(graph.contains("test.Person"))
    }

    @Test
    fun `shared cycles and polymorphic edges fail while annotation only graphs keep old acceptance`() {
        val cycle = TypeDescriptor("Person", "test.Person", properties = listOf(PropertyDescriptor("child", "test.Person")))
        val polymorphic = TypeDescriptor("Person", "test.Person", properties = person.properties, baseTypeName = "test.Base")
        for (type in listOf(cycle, polymorphic)) {
            assertThrows(GradleException::class.java) { SharedValidationGraph(MergedArcArtifacts(emptyList(), emptyList(), listOf(type), emptyList(), sharedValidators = listOf(shared))) }
            SharedValidationGraph(MergedArcArtifacts(emptyList(), emptyList(), listOf(type), emptyList()))
        }
    }

    @Test
    fun `float shared comparisons fail at the transport graph boundary`() {
        val numeric = SharedValidatorDescriptor("test.FloatRules", "test.Person", listOf(
            SharedValidationMember("name", "float", listOf(ValidationRuleDescriptor("greaterThan", listOf(1))))))
        assertThrows(GradleException::class.java) {
            SharedValidationGraph(MergedArcArtifacts(emptyList(), emptyList(), listOf(person), emptyList(), sharedValidators = listOf(numeric)))
        }
    }

    @Test
    fun `opaque inputs cannot hide active shared rules`() {
        val command = CommandDescriptor("Create", "test.Create", properties = listOf(PropertyDescriptor("input", "kotlin.Any")))
        assertThrows(GradleException::class.java) { SharedValidationGraph(MergedArcArtifacts(listOf(command), emptyList(), listOf(person), emptyList(), sharedValidators = listOf(shared))) }
    }

    @Test
    fun `structural duplicates conjoin exact rules preserve messages and reject incompatible properties`() {
        val second = TypeDescriptor("Person", "test.Person", properties = listOf(PropertyDescriptor("name", "kotlin.String",
            validationRules = listOf(rule, ValidationRuleDescriptor("maxLength", listOf(5)), ValidationRuleDescriptor("notEmpty", message = "other")))))
        fun merge(type: TypeDescriptor) = ArcManifestDiscovery.merge(listOf(
            DiscoveredArcManifest("a", ArcArtifactManifest("A", types = listOf(person))),
            DiscoveredArcManifest("b", ArcArtifactManifest("B", types = listOf(type)))))
        assertEquals(3, merge(second).types.single().properties.single().validationRules.size)
        assertThrows(GradleException::class.java) { merge(TypeDescriptor("Person", "test.Person", properties = listOf(PropertyDescriptor("name", "kotlin.Int")))) }
    }
}
