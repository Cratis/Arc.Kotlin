// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.DatabindException

internal class ArcObjectMapperBodyPropertyTest {
    private val mapper = ArcObjectMapper.create()

    @Test
    fun `mutable backed final and private setter body state accepts supplied wire values`() {
        val value = mapper.readValue(
            """{"constructorValue":"supplied","mutableValue":"mutable-input","backedValue":"final-input","privateSetterValue":"private-input"}""",
            BodyPropertyState::class.java
        )
        assertEquals("supplied", value.constructorValue)
        assertEquals("mutable-input", value.mutableValue)
        assertEquals("final-input", value.backedValue)
        assertEquals("private-input", value.privateSetterValue)
        val tree = mapper.readTree(mapper.writeValueAsString(value))
        assertEquals("mutable-input", tree["mutableValue"].stringValue())
        assertEquals("final-input", tree["backedValue"].stringValue())
        assertEquals("private-input", tree["privateSetterValue"].stringValue())
    }

    @Test
    fun `computed scalar is serialized but cannot accept a supplied wire value`() {
        val tree = mapper.readTree(mapper.writeValueAsString(BodyPropertyState("original")))
        assertEquals("original-computed", tree["computedValue"].stringValue())
        assertThrows(DatabindException::class.java) {
            mapper.readValue("""{"constructorValue":"original","computedValue":"supplied"}""", BodyPropertyState::class.java)
        }
    }

    @Test
    fun `explicit Jackson body names and asymmetric access differ from the default symmetric contract`() {
        val value = mapper.readValue(
            """{"first":"first","wire_value":"renamed-input","readOnly":"attack","writeOnly":"write-input"}""",
            AnnotatedBodyState::class.java
        )
        assertEquals("renamed-input", value.renamed)
        assertEquals("read-default", value.readOnly)
        assertEquals("write-input", value.writeOnly)
        val tree = mapper.readTree(mapper.writeValueAsString(value))
        assertEquals(setOf("first", "wire_value", "readOnly"), tree.propertyNames().asSequence().toSet())
        assertEquals("renamed-input", tree["wire_value"].stringValue())
        assertEquals("read-default", tree["readOnly"].stringValue())
    }

    @Test
    fun `explicit read write access accepts and serializes supplied backed body values`() {
        val value = mapper.readValue("""{"first":"first","getter":"getter-input","field":"field-input"}""",
            SymmetricBodyState::class.java)
        assertEquals("getter-input", value.getter)
        assertEquals("field-input", value.field)
        val tree = mapper.readTree(mapper.writeValueAsString(value))
        assertEquals(setOf("first", "getter", "field"), tree.propertyNames().asSequence().toSet())
        assertEquals("getter-input", tree["getter"].stringValue())
        assertEquals("field-input", tree["field"].stringValue())
    }

    @Test
    fun `nonpublic and ignored state is absent and ignored supplied values do not change state`() {
        val value = mapper.readValue(
            """{"constructorValue":"original","ignoredField":"attack","ignoredGetter":"attack"}""",
            BodyPropertyState::class.java
        )
        assertEquals("field-secret", value.ignoredField)
        assertEquals("getter-secret", value.ignoredGetter)
        val tree = mapper.readTree(mapper.writeValueAsString(value))
        for (name in listOf("hiddenValue", "protectedValue", "ignoredField", "ignoredGetter")) {
            assertFalse(tree.has(name), name)
        }
        assertThrows(DatabindException::class.java) {
            mapper.readValue("""{"constructorValue":"original","hiddenValue":"attack"}""", BodyPropertyState::class.java)
        }
    }
}

internal class SymmetricBodyState(val first: String) {
    @get:JsonProperty(access = JsonProperty.Access.READ_WRITE) var getter: String = "getter-default"
    @field:JsonProperty(access = JsonProperty.Access.READ_WRITE) var field: String = "field-default"
}

internal class AnnotatedBodyState(val first: String) {
    @get:JsonProperty("wire_value") var renamed: String = "renamed-default"
    @get:JsonProperty(access = JsonProperty.Access.READ_ONLY) var readOnly: String = "read-default"
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) var writeOnly: String = "write-default"
}

internal class BodyPropertyState(val constructorValue: String) {
    var mutableValue: String = "mutable-default"
    val backedValue: String = "final-default"
    var privateSetterValue: String = "private-default"
        private set
    val computedValue: String get() = "$constructorValue-computed"
    private val hiddenValue: String = "hidden-secret"
    protected val protectedValue: String = "protected-secret"
    @field:JsonIgnore var ignoredField: String = "field-secret"
    @get:JsonIgnore var ignoredGetter: String = "getter-secret"
}
