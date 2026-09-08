// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedType
import io.cratis.arc.polymorphism.DerivedTypeRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ArcDerivedTypeDeserializerLifecycleTest {
    @Test
    fun `specialized target retains generic payload and nested collection bindings`() {
        val mapper = ArcObjectMapper.create(registry())
        val value = mapper.readValue(
            payloadJson,
            object : TypeReference<LifecycleBase<LifecyclePayload>>() {}
        ) as LifecycleMiddle<*>

        assertEquals(LifecyclePayload("root"), value.payload)
        assertEquals(listOf(listOf(LifecyclePayload("nested"))), value.nested)
        assertEquals(LifecycleMiddle::class.java, value.javaClass)
    }

    @Test
    fun `sibling properties retain distinct generic bindings`() {
        val mapper = ArcObjectMapper.create(registry())
        val value = mapper.readValue(
            """{"first":$payloadJson,"second":{"_derivedTypeId":"middle","payload":"text","nested":[["entry"]]}}""",
            LifecycleGenericEnvelope::class.java
        )

        assertEquals(LifecyclePayload("root"), (value.first as LifecycleMiddle<*>).payload)
        assertEquals("text", (value.second as LifecycleMiddle<*>).payload)
        assertEquals(listOf(listOf("entry")), value.second.nested)
    }

    @Test
    fun `target bean contextualization honors property annotations without sibling leakage`() {
        val mapper = ArcObjectMapper.create(registry())
        val json = """
            {
                "left":{"_derivedTypeId":"middle","payload":"left","nested":[],"leftOnly":1},
                "right":{"_derivedTypeId":"middle","payload":"right","nested":[],"rightOnly":2}
            }
        """.trimIndent()
        repeat(3) {
            val value = mapper.readValue(json, LifecycleContextualEnvelope::class.java)
            assertEquals("left", (value.left as LifecycleMiddle<*>).payload)
            assertEquals("right", (value.right as LifecycleMiddle<*>).payload)
        }

        val exception = assertThrows(JsonMappingException::class.java) {
            mapper.readValue(json.replace("rightOnly", "leftOnly"), LifecycleContextualEnvelope::class.java)
        }
        assertTrue(exception.message.orEmpty().contains("leftOnly"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cold and warm shared mappers isolate concurrent dispatch and registries`(warm: Boolean) {
        val primary = ArcObjectMapper.create(registry())
        val alternateRegistry = ConcurrentDerivedTypeRegistry().apply {
            register(LifecycleBase::class.java, LifecycleAlternate::class.java)
        }
        val alternate = ArcObjectMapper.create(alternateRegistry)
        if (warm) {
            assertPayload(primary, LifecycleMiddle::class.java)
            assertPayload(alternate, LifecycleAlternate::class.java)
        }
        val executor = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        try {
            val tasks = (0 until 4).map {
                executor.submit {
                    ready.countDown()
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    repeat(20) {
                        assertPayload(primary, LifecycleMiddle::class.java)
                        assertPayload(alternate, LifecycleAlternate::class.java)
                        assertThrows(JsonMappingException::class.java) {
                            primary.readValue(payloadJson.replace("middle", "unknown"), LifecycleBase::class.java)
                        }
                    }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `unrelated registry target is rejected before its deserializer runs`() {
        val calls = AtomicInteger()
        val hostile = object : DerivedTypeRegistry by registry() {
            override fun resolve(baseType: Class<*>, id: String): Class<*> = LifecycleUnrelated::class.java
        }
        val mapper = ArcObjectMapper.create(hostile).registerModule(
            SimpleModule().addDeserializer(LifecycleUnrelated::class.java, object : JsonDeserializer<LifecycleUnrelated>() {
                override fun deserialize(parser: JsonParser, context: DeserializationContext): LifecycleUnrelated {
                    calls.incrementAndGet()
                    parser.skipChildren()
                    return LifecycleUnrelated()
                }
            })
        )

        val exception = assertThrows(JsonMappingException::class.java) {
            mapper.readValue(payloadJson, LifecycleBase::class.java)
        }
        assertTrue(exception.message.orEmpty().contains("not assignable"))
        assertEquals(0, calls.get())
    }

    @Test
    fun `updating reader validates discriminator and replaces rather than mutates`() {
        val mapper = ArcObjectMapper.create(registry())
        val original = LifecycleMiddle("original", emptyList())
        val reader = mapper.readerFor(object : TypeReference<LifecycleBase<String>>() {}).withValueToUpdate(original)
        val replacement = reader.readValue<LifecycleMiddle<String>>(
            """{"_derivedTypeId":"middle","payload":"replacement","nested":[]}"""
        )

        assertNotSame(original, replacement)
        assertEquals("original", original.payload)
        assertEquals("replacement", replacement.payload)
        for (json in listOf(
            """{"payload":"missing","nested":[]}""",
            """{"_derivedTypeId":"unknown","payload":"unknown","nested":[]}"""
        )) {
            assertThrows(JsonMappingException::class.java) { reader.readValue<Any>(json) }
        }
    }

    @Test
    fun `strict merge configuration still rejects updating reads`() {
        val mapper = ArcObjectMapper.configure(
            JsonMapper.builder().disable(MapperFeature.IGNORE_MERGE_FOR_UNMERGEABLE).build(),
            registry()
        )
        val reader = mapper.readerFor(LifecycleBase::class.java)
            .withValueToUpdate(LifecycleMiddle("original", emptyList()))

        val exception = assertThrows(JsonMappingException::class.java) { reader.readValue<Any>(payloadJson) }
        assertTrue(exception.message.orEmpty().contains("cannot be merged"))
    }

    @Test
    fun `native Jackson type metadata cannot bypass the Arc discriminator guard`() {
        val mapper = ArcObjectMapper.create(registry())
            .addMixIn(LifecycleBase::class.java, LifecycleNativeType::class.java)
        for (discriminator in listOf("", "\"_derivedTypeId\":\"middle\",")) {
            val json = """{"nativeKind":"alternate",${discriminator}"payload":"value","nested":[]}"""
            val exception = assertThrows(JsonMappingException::class.java) {
                mapper.readValue(json, LifecycleBase::class.java)
            }
            assertTrue(exception.message.orEmpty().contains("native Jackson type metadata"))
        }
    }

    @Test
    fun `registered concept and enum types retain scalar deserializer precedence`() {
        val registry = ConcurrentDerivedTypeRegistry().apply {
            register(LifecycleConcept::class.java, LifecycleConcept::class.java)
            register(LifecycleEnum::class.java, LifecycleEnum::class.java)
        }
        val mapper = ArcObjectMapper.create(registry)

        assertEquals("scalar", mapper.readValue("\"scalar\"", LifecycleConcept::class.java).value())
        assertEquals(LifecycleEnum.First, mapper.readValue("0", LifecycleEnum::class.java))
    }

    @Test
    fun `registered bases preserve explicit null values`() {
        val mapper = ArcObjectMapper.create(registry())

        assertNull(mapper.readValue("null", LifecycleBase::class.java))
        assertNull(mapper.readValue("null", LifecycleMiddle::class.java))
    }

    @Test
    fun `AS_EMPTY property preserves null instead of creating an unregistered concrete base`() {
        val mapper = ArcObjectMapper.create(emptyValueRegistry())
        val value = mapper.readValue("""{"value":null,"values":[null]}""", LifecycleEmptyEnvelope::class.java)

        assertNull(value.value)
    }

    @Test
    fun `AS_EMPTY collection content preserves null instead of creating an unregistered concrete base`() {
        val mapper = ArcObjectMapper.create(emptyValueRegistry())
        val value = mapper.readValue("""{"value":null,"values":[null]}""", LifecycleEmptyEnvelope::class.java)

        assertEquals(1, value.values.size)
        assertNull(value.values.single())
    }

    @Test
    fun `AS_EMPTY properties still bind explicitly registered leaves`() {
        val mapper = ArcObjectMapper.create(emptyValueRegistry())
        val value = mapper.readValue(
            """{"value":{"_derivedTypeId":"empty-leaf","name":"property"},
                "values":[{"_derivedTypeId":"empty-leaf","name":"element"}]}""",
            LifecycleEmptyEnvelope::class.java
        )

        assertEquals("property", (value.value as LifecycleEmptyLeaf).name)
        assertEquals("element", (value.values.single() as LifecycleEmptyLeaf).name)
    }

    @Test
    fun `AS_EMPTY configuration does not accept objects without a discriminator`() {
        val mapper = ArcObjectMapper.create(emptyValueRegistry())
        for (json in listOf("""{"value":{}}""", """{"values":[{}]}""")) {
            val exception = assertThrows(JsonMappingException::class.java) {
                mapper.readValue(json, LifecycleEmptyEnvelope::class.java)
            }
            assertTrue(exception.message.orEmpty().contains("Missing textual _derivedTypeId"))
        }
        val exception = assertThrows(JsonMappingException::class.java) {
            mapper.readValue("{}", LifecycleEmptyBase::class.java)
        }
        assertTrue(exception.message.orEmpty().contains("Missing textual _derivedTypeId"))
    }

    @Test
    fun `concrete registered base preserves root null`() {
        val mapper = ArcObjectMapper.create(emptyValueRegistry())

        assertNull(mapper.readValue("null", LifecycleEmptyBase::class.java))
    }

    private fun emptyValueRegistry(): ConcurrentDerivedTypeRegistry = ConcurrentDerivedTypeRegistry().apply {
        register(LifecycleEmptyBase::class.java, LifecycleEmptyLeaf::class.java)
    }

    private fun registry(): ConcurrentDerivedTypeRegistry = ConcurrentDerivedTypeRegistry().apply {
        register(LifecycleBase::class.java, LifecycleMiddle::class.java)
        register(LifecycleMiddle::class.java, LifecycleLeaf::class.java)
    }

    private fun assertPayload(mapper: ObjectMapper, expectedType: Class<*>) {
        val value = mapper.readValue(payloadJson, object : TypeReference<LifecycleBase<LifecyclePayload>>() {})
        assertEquals(expectedType, value.javaClass)
        val payload = when (value) {
            is LifecycleMiddle<*> -> value.payload
            is LifecycleAlternate<*> -> value.payload
            else -> throw AssertionError("Unexpected derived type ${value.javaClass.name}")
        }
        assertEquals(LifecyclePayload("root"), payload)
    }

    private val payloadJson = """{"_derivedTypeId":"middle","payload":{"value":"root"},"nested":[[{"value":"nested"}]]}"""
}

abstract class LifecycleBase<T>

@DerivedType("middle")
open class LifecycleMiddle<T>(val payload: T, val nested: List<List<T>>) : LifecycleBase<T>()

@DerivedType("leaf")
class LifecycleLeaf<T>(payload: T, nested: List<List<T>>) : LifecycleMiddle<T>(payload, nested)

@DerivedType("middle")
class LifecycleAlternate<T>(val payload: T, val nested: List<List<T>>) : LifecycleBase<T>()

data class LifecyclePayload(val value: String)

data class LifecycleGenericEnvelope(val first: LifecycleBase<LifecyclePayload>, val second: LifecycleBase<String>)

data class LifecycleContextualEnvelope(
    @field:JsonIgnoreProperties(value = ["leftOnly"])
    val left: LifecycleBase<String>,
    @field:JsonIgnoreProperties(value = ["rightOnly"])
    val right: LifecycleBase<String>
)

class LifecycleUnrelated

open class LifecycleEmptyBase

@DerivedType("empty-leaf")
class LifecycleEmptyLeaf(val name: String) : LifecycleEmptyBase()

class LifecycleEmptyEnvelope {
    @set:JsonSetter(nulls = Nulls.AS_EMPTY)
    var value: LifecycleEmptyBase? = LifecycleEmptyBase()

    @set:JsonSetter(contentNulls = Nulls.AS_EMPTY)
    var values: List<LifecycleEmptyBase?> = listOf(LifecycleEmptyBase())
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "nativeKind")
@JsonSubTypes(JsonSubTypes.Type(value = LifecycleAlternate::class, name = "alternate"))
abstract class LifecycleNativeType

@DerivedType("concept")
class LifecycleConcept(private val rawValue: String) : ConceptAs<String> {
    override fun value(): String = rawValue
}

@DerivedType("enum")
enum class LifecycleEnum {
    First
}
