// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.module.kotlin.readValue
import io.cratis.arc.concepts.ArcEnum
import io.cratis.arc.concepts.Flags
import java.util.EnumSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Pins what a flags-style enum can and cannot carry over the Arc wire.
 *
 * `@Flags` is generator metadata: it adds the `all<Name>` constant to the generated TypeScript enum and changes
 * nothing about serialization. A JVM enum constant is a single named value, so a combination that no constant
 * declares has nothing to deserialize into, and reading one fails. Arc .NET behaves the same way, because its
 * `EnumConverter` gates reads on `Enum.IsDefined`. A set of enum values is the shape that carries an arbitrary
 * combination.
 */
class ArcObjectMapperFlagsEnumTest {
    private val mapper = ArcObjectMapper.create()

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 4])
    fun `every declared flag value round trips`(value: Int) {
        val permission = FlagPermission.entries.first { entry -> entry.value() == value }

        assertEquals("$value", mapper.writeValueAsString(permission))
        assertEquals(permission, mapper.readValue("$value", FlagPermission::class.java))
    }

    @Test
    fun `a combination no constant declares is rejected and names the value and the type`() {
        val exception = assertThrows(JsonMappingException::class.java) {
            mapper.readValue("3", FlagPermission::class.java)
        }

        assertTrue(exception.message.orEmpty().contains("3"))
        assertTrue(exception.message.orEmpty().contains(FlagPermission::class.java.name))
    }

    @Test
    fun `the Flags annotation changes nothing about serialization`() {
        assertEquals(
            mapper.writeValueAsString(UnflaggedPermission.Write),
            mapper.writeValueAsString(FlagPermission.Write)
        )
        assertThrows(JsonMappingException::class.java) {
            mapper.readValue("3", UnflaggedPermission::class.java)
        }
    }

    @Test
    fun `a declared combination constant carries that combination`() {
        assertEquals("3", mapper.writeValueAsString(DeclaredCombinationPermission.ReadWrite))
        assertEquals(
            DeclaredCombinationPermission.ReadWrite,
            mapper.readValue("3", DeclaredCombinationPermission::class.java)
        )
    }

    @Test
    fun `a set of flags carries an arbitrary combination as an array of wire values`() {
        val value = FlagPermissionHolder(EnumSet.of(FlagPermission.Read, FlagPermission.Write))

        val json = mapper.writeValueAsString(value)

        assertEquals("""{"permissions":[1,2]}""", json)
        assertEquals(setOf(FlagPermission.Read, FlagPermission.Write), mapper.readValue<FlagPermissionHolder>(json).permissions)
    }

    @Test
    fun `an empty set of flags is retained rather than omitted`() {
        assertEquals("""{"permissions":[]}""", mapper.writeValueAsString(FlagPermissionHolder(emptySet())))
    }

    @Test
    fun `a set of flags rejects an element no constant declares`() {
        assertThrows(JsonMappingException::class.java) {
            mapper.readValue<FlagPermissionHolder>("""{"permissions":[3]}""")
        }
    }
}

@Flags
enum class FlagPermission(private val wireValue: Int) : ArcEnum {
    None(0),
    Read(1),
    Write(2),
    Execute(4);

    override fun value(): Int = wireValue
}

enum class UnflaggedPermission(private val wireValue: Int) : ArcEnum {
    None(0),
    Read(1),
    Write(2),
    Execute(4);

    override fun value(): Int = wireValue
}

@Flags
enum class DeclaredCombinationPermission(private val wireValue: Int) : ArcEnum {
    None(0),
    Read(1),
    Write(2),
    ReadWrite(3);

    override fun value(): Int = wireValue
}

data class FlagPermissionHolder(val permissions: Set<FlagPermission>)
