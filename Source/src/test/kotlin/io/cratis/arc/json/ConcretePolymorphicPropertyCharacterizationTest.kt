// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import com.fasterxml.jackson.databind.JsonMappingException
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ConcretePolymorphicPropertyCharacterizationTest {
    @Test
    fun `concrete base instance writes no discriminator but registered base requires one on read`() {
        val registry = ConcurrentDerivedTypeRegistry()
        registry.register(ConcretePropertyBase::class.java, ConcretePropertyLeaf::class.java)
        val mapper = ArcObjectMapper.create(registry)

        val json = mapper.writeValueAsString(ConcretePropertyHolder(ConcretePropertyBase()))

        assertEquals("""{"value":{"name":"base"}}""", json)
        assertFalse(mapper.readTree(json)["value"].has("_derivedTypeId"))
        val exception = assertThrows(JsonMappingException::class.java) {
            mapper.readValue(json, ConcretePropertyHolder::class.java)
        }
        assertTrue(
            exception.originalMessage.contains("Missing textual _derivedTypeId for ${ConcretePropertyBase::class.java.name}"),
            exception.message
        )
        assertEquals("value", exception.path.single().fieldName)
    }
}

public open class ConcretePropertyBase(public val name: String = "base")

@DerivedType("concrete-property-leaf")
public class ConcretePropertyLeaf : ConcretePropertyBase()

public data class ConcretePropertyHolder(public val value: ConcretePropertyBase)
