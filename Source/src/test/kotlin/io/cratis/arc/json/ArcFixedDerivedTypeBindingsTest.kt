// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import tools.jackson.core.JsonParser
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.DatabindException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.type.ResolvedRecursiveType
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/** Runtime registrations only; this does not expand KSP generic model support. */
internal class ArcFixedDerivedTypeBindingsTest {
    private val mapper = ArcObjectMapper.create(ConcurrentDerivedTypeRegistry().apply {
        listOf(FixedString::class.java, FixedList::class.java, FixedNested::class.java,
            FixedMap::class.java, FixedReference::class.java, FixedArray::class.java,
            FixedNumber::class.java, FixedUnknown::class.java, FixedRecursive::class.java,
            FixedBox::class.java).forEach {
            register(Base::class.java, it)
        }
    })

    @Test
    fun `fixed inherited binding rejects incompatible typed root before returning a value`() {
        val failure = assertThrows(DatabindException::class.java) {
            mapper.readValue(json("string", "\"text\""), object : TypeReference<Base<Payload>>() {})
        }
        assertTrue(failure.originalMessage.contains(FixedString::class.java.name), failure.message)
        assertTrue(failure.originalMessage.contains(Base::class.java.name), failure.message)
        assertTrue(failure.originalMessage.contains(Payload::class.java.name), failure.message)
        assertTrue(failure.originalMessage.contains("base<T>"), failure.message)
    }

    @Test
    fun `nested property validates its declared generic binding`() {
        val failure = assertThrows(DatabindException::class.java) {
            mapper.readValue("""{"value":${json("string", "\"text\"")}}""", Envelope::class.java)
        }
        assertEquals("value", failure.path.single().propertyName)
    }

    @Test
    fun `nested collection element validates its declared generic binding`() {
        val failure = assertThrows(DatabindException::class.java) {
            mapper.readValue("""{"values":[${json("string", "\"text\"")}]}""", Envelope::class.java)
        }
        assertEquals("values", failure.path[0].propertyName)
        assertEquals(0, failure.path[1].index)
    }

    @Test
    fun `fixed list rejects incompatible elements without inspecting payload`() {
        assertThrows(DatabindException::class.java) {
            mapper.readValue(json("list", "[]"), object : TypeReference<Base<List<Payload>>>() {})
        }
    }

    @Test
    fun `differing collection erasures are projected before recursively comparing arguments`() {
        val failure = assertThrows(DatabindException::class.java) {
            mapper.readValue(json("nested", "[[\"text\"]]"),
                object : TypeReference<Base<Collection<Collection<Payload>>>>() {})
        }
        assertTrue(failure.originalMessage.contains("base<T>"), failure.message)
        assertTrue(failure.originalMessage.contains(String::class.java.name), failure.message)
    }

    @Test
    fun `fixed map rejects incompatible values and keys`() {
        assertThrows(DatabindException::class.java) {
            mapper.readValue(json("map", "{}"), object : TypeReference<Base<Map<String, List<Payload>>>>() {})
        }
        assertThrows(DatabindException::class.java) {
            mapper.readValue(json("map", "{}"), object : TypeReference<Base<Map<Payload, List<String>>>>() {})
        }
    }

    @Test
    fun `fixed ordinary generic subtype is projected before comparing bindings`() {
        assertThrows(DatabindException::class.java) {
            mapper.readValue(json("box", "{\"value\":\"text\"}"), object : TypeReference<Base<Box<Payload>>>() {})
        }
        val value = mapper.readValue(json("box", "{\"value\":\"text\"}"),
            object : TypeReference<Base<Box<CharSequence>>>() {})
        assertEquals("text", requireNotNull(value.payload).value.toString())
    }

    @Test
    fun `raw container request still rejects incompatible erasure`() {
        val requested = mapper.typeFactory.constructParametricType(Base::class.java, List::class.java)
        assertThrows(DatabindException::class.java) {
            mapper.readValue<Any>(json("string", "\"text\""), requested)
        }
        val value = mapper.readValue<Base<*>>(json("list", "[\"text\"]"), requested)
        assertEquals(listOf("text"), value.payload)
    }

    @Test
    fun `fixed reference rejects incompatible referenced binding`() {
        assertThrows(DatabindException::class.java) {
            mapper.readValue(json("reference", "\"text\""), object : TypeReference<Base<AtomicReference<Payload>>>() {})
        }
    }

    @Test
    fun `fixed generic array rejects incompatible component binding`() {
        assertThrows(DatabindException::class.java) {
            mapper.readValue(json("array", "[]"), object : TypeReference<Base<Array<List<Payload>>>>() {})
        }
    }

    @Test
    fun `read compatible widening accepts string object interface number and raw requests`() {
        val input = json("string", "\"text\"")
        assertEquals("text", mapper.readValue(input, object : TypeReference<Base<String>>() {}).payload)
        assertEquals("text", mapper.readValue(input, object : TypeReference<Base<Any>>() {}).payload)
        val sequence = mapper.readValue(input, object : TypeReference<Base<CharSequence>>() {}).payload
        assertEquals("text", requireNotNull(sequence).toString())
        assertEquals("text", mapper.readValue(input, Base::class.java).payload)
        val number = mapper.readValue(json("number", "42"), object : TypeReference<Base<Number>>() {}).payload
        assertEquals(42, requireNotNull(number).toInt())
    }

    @Test
    fun `nested collection map reference and array widening remains readable`() {
        val nested = mapper.readValue(json("nested", "[[\"text\"]]"),
            object : TypeReference<Base<Collection<Collection<Any>>>>() {})
        assertEquals("text", requireNotNull(nested.payload).first().first())
        val map = mapper.readValue(json("map", "{\"key\":[\"text\"]}"),
            object : TypeReference<Base<Map<CharSequence, Collection<Any>>>>() {})
        assertEquals("text", requireNotNull(map.payload).values.first().first())
        val reference = mapper.readValue(json("reference", "\"text\""),
            object : TypeReference<Base<AtomicReference<CharSequence>>>() {})
        assertEquals("text", requireNotNull(reference.payload).get().toString())
        val array = mapper.readValue(json("array", "[[\"text\"]]"),
            object : TypeReference<Base<Array<Collection<CharSequence>>>>() {})
        assertEquals("text", requireNotNull(array.payload).first().first().toString())
    }

    @Test
    fun `null base and compatible nullable payloads remain null`() {
        assertNull(mapper.readValue("null", object : TypeReference<Base<Payload>>() {}))
        assertNull(mapper.readValue(json("string", "null"), object : TypeReference<Base<String>>() {}).payload)
        assertNull(mapper.readValue("""{"value":null,"values":[null]}""", Envelope::class.java).value)
    }

    @Test
    fun `null payload does not erase a definite metadata contradiction`() {
        assertThrows(DatabindException::class.java) {
            mapper.readValue(json("string", "null"), object : TypeReference<Base<Payload>>() {})
        }
    }

    @Test
    fun `unknown actual arguments cannot establish a contradiction`() {
        // Jackson represents the star projection as Object, indistinguishable from an unresolved binding.
        val value = mapper.readValue(json("unknown", "[]"), object : TypeReference<Base<List<Payload>>>() {})
        assertTrue(requireNotNull(value.payload).isEmpty())
        val unconstrained = mapper.readValue(json("list", "[\"text\"]"), object : TypeReference<Base<List<*>>>() {})
        assertEquals("text", requireNotNull(unconstrained.payload).single())
    }

    @Test
    fun `recursive generic collection metadata terminates on real Jackson types`() {
        val recursive = mapper.constructType(RecursiveList::class.java)
        val reference = recursive.contentType as ResolvedRecursiveType
        assertSame(recursive, reference.selfReferencedType)
        // The metadata is cyclic even with an empty payload; nested recursive arrays are not bindable by Jackson.
        val value = mapper.readValue(json("recursive", "[]"), object : TypeReference<Base<RecursiveList>>() {})
        assertTrue(requireNotNull(value.payload).isEmpty())
    }

    @Test
    fun `definite contradiction is rejected before custom target delegate lookup or invocation`() {
        var invocations = 0
        var resolutions = 0
        val configuredMapper = (mapper as JsonMapper).rebuild().addModule(SimpleModule().addDeserializer(FixedString::class.java,
            object : ValueDeserializer<FixedString>() {
                override fun resolve(context: DeserializationContext) { resolutions++ }

                override fun deserialize(parser: JsonParser, context: DeserializationContext): FixedString {
                    invocations++
                    parser.skipChildren()
                    return FixedString().apply { payload = "custom" }
                }
            })).build()
        assertThrows(DatabindException::class.java) {
            configuredMapper.readValue(json("string", "\"text\""), object : TypeReference<Base<Payload>>() {})
        }
        assertEquals(0, invocations)
        assertEquals(0, resolutions)
        val valid = configuredMapper.readValue(json("string", "\"text\""), object : TypeReference<Base<String>>() {})
        assertEquals("custom", valid.payload)
        assertEquals(1, invocations)
        assertEquals(1, resolutions)
    }

    private fun json(id: String, payload: String): String = """{"_derivedTypeId":"$id","payload":$payload}"""

    abstract class Base<T> { var payload: T? = null }
    class Payload(val name: String)
    class Envelope(val value: Base<Payload>? = null, val values: List<Base<Payload>?> = emptyList())
    @DerivedType("string") class FixedString : Base<String>()
    @DerivedType("list") class FixedList : Base<List<String>>()
    @DerivedType("nested") class FixedNested : Base<ArrayList<ArrayList<String>>>()
    @DerivedType("map") class FixedMap : Base<LinkedHashMap<String, ArrayList<String>>>()
    @DerivedType("reference") class FixedReference : Base<AtomicReference<String>>()
    @DerivedType("array") class FixedArray : Base<Array<List<String>>>()
    @DerivedType("number") class FixedNumber : Base<Int>()
    @DerivedType("unknown") class FixedUnknown : Base<List<*>>()
    @DerivedType("recursive") class FixedRecursive : Base<RecursiveList>()
    @DerivedType("box") class FixedBox : Base<StringBox>()
    open class Box<T> { var value: T? = null }
    class StringBox : Box<String>()
    class RecursiveList : ArrayList<RecursiveList>()
}
