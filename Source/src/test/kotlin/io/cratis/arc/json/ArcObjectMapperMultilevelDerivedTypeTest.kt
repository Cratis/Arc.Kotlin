// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.DatabindException
import tools.jackson.databind.exc.UnrecognizedPropertyException
import tools.jackson.databind.json.JsonMapper
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Runtime-only fixtures: generated modules retain their separate KSP hierarchy restrictions. */
internal class ArcObjectMapperMultilevelDerivedTypeTest {
    private val mapper = ArcObjectMapper.create(registry())

    @Test
    fun `root selects exact middle with inherited properties and nullable child`() {
        val value = mapper.readValue(middleJson("\"child\":null"), Root::class.java)

        assertEquals(Middle::class.java, value.javaClass)
        assertEquals("root-value", value.inherited)
        assertEquals("middle-value", (value as Middle).middle)
        assertNull(value.child)
        assertTrue(value.children.isEmpty())
    }

    @Test
    fun `root selects exact leaf with inherited middle and leaf properties`() {
        assertLeaf(mapper.readValue(LEAF_JSON, Root::class.java))
    }

    @Test
    fun `middle selects exact leaf with inherited middle and leaf properties`() {
        assertLeaf(mapper.readValue(LEAF_JSON, Middle::class.java))
    }

    @Test
    fun `middle cannot select itself merely because root registers it`() {
        val exception = assertThrows(DatabindException::class.java) {
            mapper.readValue(middleJson(), Middle::class.java)
        }

        assertTrue(exception.originalMessage.contains("Unknown derived type identifier 'middle'"))
    }

    @Test
    fun `middle can select itself only with explicit self registration`() {
        val explicit = registry().apply { register(Middle::class.java, Middle::class.java) }
        val configured = ArcObjectMapper.create(explicit)
        val value = configured.readValue(middleJson("\"child\":$LEAF_JSON"), Middle::class.java)

        assertEquals(Middle::class.java, value.javaClass)
        assertEquals("root-value", value.inherited)
        assertEquals("middle-value", value.middle)
        assertLeaf(requireNotNull(value.child))
    }

    @Test
    fun `leaf registration on middle is not inferred transitively for root`() {
        val explicit = ConcurrentDerivedTypeRegistry().apply {
            register(Root::class.java, Middle::class.java)
            register(Middle::class.java, Leaf::class.java)
        }
        val configured = ArcObjectMapper.create(explicit)

        assertThrows(DatabindException::class.java) { configured.readValue(LEAF_JSON, Root::class.java) }
        assertLeaf(configured.readValue(LEAF_JSON, Middle::class.java))
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"_derivedTypeId\":null}", "{\"_derivedTypeId\":42}",
        "{\"_derivedTypeId\":true}", "{\"_derivedTypeId\":{}}", "{\"_derivedTypeId\":[]}",
        "{\"_derivedTypeId\":\"unknown\"}", "{\"_derivedTypeId\":\"sibling\"}"])
    fun `root rejects missing nontextual unknown and unrelated identifiers`(json: String) {
        val exception = assertThrows(DatabindException::class.java) {
            mapper.readValue(childWithFields(json), Root::class.java)
        }
        assertIdentifierFailure(exception, json)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"_derivedTypeId\":null}", "{\"_derivedTypeId\":42}",
        "{\"_derivedTypeId\":true}", "{\"_derivedTypeId\":{}}", "{\"_derivedTypeId\":[]}",
        "{\"_derivedTypeId\":\"unknown\"}", "{\"_derivedTypeId\":\"sibling\"}"])
    fun `middle rejects missing nontextual unknown and unrelated identifiers`(json: String) {
        val exception = assertThrows(DatabindException::class.java) {
            mapper.readValue(childWithFields(json), Middle::class.java)
        }
        assertIdentifierFailure(exception, json)
    }

    @Test
    fun `resolved middle independently resolves its child and collection elements`() {
        val value = mapper.readValue(
            middleJson("\"child\":$LEAF_JSON,\"children\":[$LEAF_JSON,$LEAF_JSON]"),
            Root::class.java
        )

        assertEquals(Middle::class.java, value.javaClass)
        val middle = value as Middle
        assertLeaf(requireNotNull(middle.child))
        assertEquals(2, middle.children.size)
        middle.children.forEach(::assertLeaf)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"_derivedTypeId\":null}", "{\"_derivedTypeId\":42}",
        "{\"_derivedTypeId\":true}", "{\"_derivedTypeId\":{}}", "{\"_derivedTypeId\":[]}",
        "{\"_derivedTypeId\":\"unknown\"}", "{\"_derivedTypeId\":\"middle\"}",
        "{\"_derivedTypeId\":\"sibling\"}"])
    fun `resolved middle does not bypass the child allowlist`(child: String) {
        val exception = assertThrows(DatabindException::class.java) {
            mapper.readValue(middleJson("\"child\":${childWithFields(child)}"), Root::class.java)
        }

        assertEquals(listOf("child"), exception.path.map { it.propertyName })
        assertIdentifierFailure(exception, child)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"_derivedTypeId\":null}", "{\"_derivedTypeId\":42}",
        "{\"_derivedTypeId\":true}", "{\"_derivedTypeId\":{}}", "{\"_derivedTypeId\":[]}",
        "{\"_derivedTypeId\":\"unknown\"}", "{\"_derivedTypeId\":\"middle\"}",
        "{\"_derivedTypeId\":\"sibling\"}"])
    fun `resolved middle does not bypass the collection element allowlist`(child: String) {
        val exception = assertThrows(DatabindException::class.java) {
            mapper.readValue(middleJson("\"children\":[$LEAF_JSON,${childWithFields(child)}]"), Root::class.java)
        }

        assertEquals(2, exception.path.size)
        assertEquals("children", exception.path[0].propertyName)
        assertEquals(1, exception.path[1].index)
        assertIdentifierFailure(exception, child)
    }

    @Test
    fun `same mapper accepts valid then rejects invalid then accepts valid without leaking bypass state`() {
        val valid = middleJson("\"child\":$LEAF_JSON")
        assertLeaf(requireNotNull((mapper.readValue(valid, Root::class.java) as Middle).child))

        val exception = assertThrows(DatabindException::class.java) {
            mapper.readValue(middleJson("\"child\":${middleJson()}"), Root::class.java)
        }
        assertEquals("child", exception.path.single().propertyName)
        assertThrows(DatabindException::class.java) { mapper.readValue(middleJson(), Middle::class.java) }

        assertLeaf(requireNotNull((mapper.readValue(valid, Root::class.java) as Middle).child))
    }

    @Test
    fun `resolved middle honors renamed and ignored properties`() {
        val value = mapper.readValue(middleJson("\"ignored\":\"injected\""), Root::class.java) as Middle

        assertEquals("middle-value", value.middle)
        assertEquals("private-default", value.ignored)
    }

    @Test
    fun `resolved middle preserves strict unknown property policy`() {
        assertTrue(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
        val exception = assertThrows(UnrecognizedPropertyException::class.java) {
            mapper.readValue(middleJson("\"unexpected\":42"), Root::class.java)
        }

        assertEquals("unexpected", exception.propertyName)
    }

    @Test
    fun `resolved middle preserves explicitly relaxed unknown property policy`() {
        val relaxed = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
        val configured = ArcObjectMapper.configure(relaxed, registry())
        val value = configured.readValue(middleJson("\"unexpected\":42"), Root::class.java) as Middle

        assertFalse(configured.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
        assertEquals("root-value", value.inherited)
        assertEquals("middle-value", value.middle)
    }

    @Test
    fun `serialization retains one unchanged identifier per annotated object and field values`() {
        val leaf = Leaf("leaf-root", "leaf-middle", "leaf-value")
        val json = mapper.writeValueAsString(Middle("root-value", "middle-value", leaf, listOf(leaf)))
        val node = mapper.readTree(json)

        assertEquals(3, Regex("\"_derivedTypeId\"").findAll(json).count())
        assertEquals("middle", node["_derivedTypeId"].stringValue())
        assertEquals("root-value", node["inherited"].stringValue())
        assertEquals("middle-value", node["middle_name"].stringValue())
        assertFalse(node.has("middle"))
        assertFalse(node.has("ignored"))
        listOf(node["child"], node["children"][0]).forEach { child ->
            assertEquals("leaf", child["_derivedTypeId"].stringValue())
            assertEquals("leaf-root", child["inherited"].stringValue())
            assertEquals("leaf-middle", child["middle_name"].stringValue())
            assertEquals("leaf-value", child["leaf"].stringValue())
            assertFalse(child.has("ignored"))
        }
    }

    private fun registry() = ConcurrentDerivedTypeRegistry().apply {
        register(Root::class.java, Middle::class.java)
        register(Root::class.java, Leaf::class.java)
        register(Middle::class.java, Leaf::class.java)
        register(OtherRoot::class.java, Sibling::class.java)
    }

    private fun middleJson(extra: String = ""): String {
        val suffix = if (extra.isEmpty()) "" else ",$extra"
        return """{"_derivedTypeId":"middle","inherited":"root-value","middle_name":"middle-value"$suffix}"""
    }

    // Supply otherwise valid fields so a bypass cannot hide behind missing constructor arguments.
    private fun childWithFields(json: String): String {
        val identifier = json.removeSurrounding("{", "}")
        val suffix = if (identifier.isEmpty()) "" else ",$identifier"
        return """{"inherited":"child-root","middle_name":"child-middle"$suffix}"""
    }

    private fun assertIdentifierFailure(exception: DatabindException, json: String) {
        val identifier = mapper.readTree(json)["_derivedTypeId"]
        val expected = if (identifier?.isString == true) {
            "Unknown derived type identifier '${identifier.stringValue()}'"
        } else {
            "Missing textual _derivedTypeId"
        }
        assertTrue(exception.originalMessage.startsWith(expected), exception.message)
    }

    private fun assertLeaf(value: Root) {
        assertEquals(Leaf::class.java, value.javaClass)
        assertEquals("leaf-root", value.inherited)
        assertEquals("leaf-middle", (value as Leaf).middle)
        assertEquals("leaf-value", value.leaf)
    }

    abstract class Root(val inherited: String)

    @DerivedType("middle")
    open class Middle(
        inherited: String,
        @param:JsonProperty("middle_name") @get:JsonProperty("middle_name") val middle: String,
        val child: Middle? = null,
        val children: List<Middle> = emptyList(),
        @get:JsonIgnore val ignored: String = "private-default"
    ) : Root(inherited)

    @DerivedType("leaf")
    class Leaf(
        inherited: String,
        @JsonProperty("middle_name") middle: String,
        val leaf: String
    ) : Middle(inherited, middle)

    abstract class OtherRoot

    @DerivedType("sibling")
    class Sibling : OtherRoot()

    private companion object {
        const val LEAF_JSON =
            """{"_derivedTypeId":"leaf","inherited":"leaf-root","middle_name":"leaf-middle","leaf":"leaf-value"}"""
    }
}
