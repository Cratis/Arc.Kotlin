// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.metadata.ApiEndpointOptions
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.InterfaceDescriptor
import io.cratis.arc.metadata.TypeDescriptor
import io.cratis.arc.queries.QueryTransportType
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.gradle.api.GradleException

internal class QuerySortHelperTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest
    @EnumSource(QueryTransportType::class)
    fun `sort helpers select returned row properties rather than query arguments`(transport: QueryTransportType) {
        val parent = TypeDescriptor("Parent", "data.Parent", listOf("data"), listOf(
            PropertyDescriptor("id", "kotlin.String"), PropertyDescriptor("name", "kotlin.String")
        ))
        val row = TypeDescriptor("Row", "data.Row", listOf("data"), listOf(
            PropertyDescriptor("name", "kotlin.String"), PropertyDescriptor("query", "kotlin.String")
        ), baseTypeName = parent.fullyQualifiedName)
        val declaringModel = TypeDescriptor("QueryRoot", "app.QueryRoot", listOf("app"), listOf(PropertyDescriptor("wrongOwner", "kotlin.Int")))
        val query = QueryDescriptor("search", declaringModel.fullyQualifiedName, row.fullyQualifiedName,
            listOf(ParameterDescriptor("filter", "kotlin.String")), transport = transport, isEnumerable = true,
            supportsPaging = true, supportsSorting = true)
        val text = generate(query, listOf(row, parent, declaringModel))
        for (name in listOf("id", "name", "query")) {
            assertTrue("get $name(): SortingActions" in text, text)
        }
        assertFalse("get filter(): SortingActions" in text, text)
        assertFalse("get wrongOwner(): SortingActions" in text, text)
        assertEquals(2, Regex("get name\\(\\): SortingActions").findAll(text).count(), text)
        assertTrue("filter: string;" in text, "Request parameter must remain intact\n$text")
        assertTrue("new ParameterDescriptor('filter', String, false)" in text, text)
        assertFalse("constructor(readonly query: Search)" in text, "Owner parameter must not conflict with the row's query field")
    }

    @ParameterizedTest
    @EnumSource(QueryTransportType::class)
    fun `parameterless sortable queries receive row helpers but disabled capabilities stay disabled`(transport: QueryTransportType) {
        val row = TypeDescriptor("Row", "data.Row", listOf("data"), listOf(PropertyDescriptor("value", "kotlin.String")))
        for (enabled in listOf(true, false)) {
            val query = QueryDescriptor("all", "app.QueryRoot", row.fullyQualifiedName, transport = transport,
                isEnumerable = true, supportsSorting = enabled, supportsPaging = false)
            val text = generate(query, listOf(row), enabled.toString())
            assertEquals(enabled, "get value(): SortingActions" in text, text)
            assertEquals(enabled, "get sortBy()" in text, text)
            assertEquals(enabled, "class AllSortBy" in text, text)
            assertEquals(enabled, "SortingActions" in text, text)
            assertFalse("static useWithPaging(" in text, text)
        }
    }

    @Test
    fun `scalar and empty row metadata never invent helpers from request arguments`() {
        for (type in listOf("kotlin.String", "data.Empty")) {
            val query = QueryDescriptor("all", "app.QueryRoot", type, listOf(ParameterDescriptor("filter", "kotlin.String")),
                isEnumerable = true, supportsSorting = true)
            val types = if (type == "data.Empty") listOf(TypeDescriptor("Empty", type, listOf("data"))) else emptyList()
            val text = generate(query, types, type)
            assertFalse("get filter(): SortingActions" in text, text)
            assertFalse("SortingActionsForQuery" in text, text)
        }
    }

    @Test
    fun `cyclic return model inheritance fails before output writes`() {
        val left = TypeDescriptor("Left", "data.Left", listOf("data"), baseTypeName = "data.Right")
        val right = TypeDescriptor("Right", "data.Right", listOf("data"), baseTypeName = "data.Left")
        val query = QueryDescriptor("all", "app.QueryRoot", left.fullyQualifiedName, isEnumerable = true, supportsSorting = true)
        assertThrows(GradleException::class.java) { generate(query, listOf(left, right)) }
        assertFalse(Files.exists(directory.resolve("out")), "No partial output may be written")
    }

    @ParameterizedTest
    @EnumSource(QueryTransportType::class)
    fun `interface helpers use represented fields and reject illegal constructor accessors`(transport: QueryTransportType) {
        val query = QueryDescriptor("all", "app.QueryRoot", "data.View", isEnumerable = true, supportsSorting = true, transport = transport)
        val valid = InterfaceDescriptor("View", "data.View", listOf("data"), listOf(PropertyDescriptor("name", "kotlin.String")))
        val text = generate(query, emptyList(), "valid", listOf(valid))
        assertTrue("get name(): SortingActions" in text, text)
        val invalid = InterfaceDescriptor("View", "data.View", listOf("data"), listOf(PropertyDescriptor("constructor", "kotlin.String")))
        val failure = assertThrows(GradleException::class.java) { generate(query, emptyList(), "invalid", listOf(invalid)) }
        assertTrue("constructor" in failure.message.orEmpty(), failure.message)
        assertFalse(Files.exists(directory.resolve("invalid")))
    }

    @ParameterizedTest
    @EnumSource(QueryTransportType::class)
    fun `non enumerable queries never gain named or paging helpers from capability flags`(transport: QueryTransportType) {
        val row = TypeDescriptor("Row", "data.Row", listOf("data"), listOf(PropertyDescriptor("name", "kotlin.String")))
        val query = QueryDescriptor("single", "app.QueryRoot", row.fullyQualifiedName, transport = transport,
            supportsSorting = true, supportsPaging = true, isEnumerable = false)
        val text = generate(query, listOf(row))
        assertFalse("SortBy" in text, text)
        assertFalse("SortingActions" in text, text)
        assertFalse("static useWithPaging" in text, text)
    }

    @Test
    fun `helper order is stable across shuffled metadata and multilevel inheritance`() {
        fun model(name: String, fields: List<String>, base: String? = null) = TypeDescriptor(name, "data.$name", listOf("data"),
            fields.map { PropertyDescriptor(it, "kotlin.String") }, baseTypeName = base?.let { "data.$it" })
        val first = listOf(model("Grand", listOf("name", "base")), model("Parent", listOf("name", "id"), "Grand"),
            model("Row", listOf("name", "child"), "Parent"))
        val second = listOf(model("Row", listOf("child", "name"), "Parent"), model("Parent", listOf("id", "name"), "Grand"),
            model("Grand", listOf("base", "name")))
        val query = QueryDescriptor("all", "app.QueryRoot", "data.Row", isEnumerable = true, supportsSorting = true)
        val a = generate(query, first, "first")
        val b = generate(query, second, "second")
        val helpersA = a.substringAfter("class AllSortBy {").substringBefore("export class All extends")
        val helpersB = b.substringAfter("class AllSortBy {").substringBefore("export class All extends")
        assertEquals(helpersA, helpersB)
        assertEquals(listOf("base", "child", "id", "name"),
            Regex("get ([A-Za-z]+)\\(\\): SortingActionsForQuery").findAll(helpersA).map { it.groupValues[1] }.toList())
    }

    private fun generate(query: QueryDescriptor, types: List<TypeDescriptor>, subdirectory: String = "out",
        interfaces: List<InterfaceDescriptor> = emptyList()): String {
        val output = directory.resolve(subdirectory)
        TypeScriptProxyGenerator(MergedArcArtifacts(emptyList(), listOf(query), types, emptyList(), interfaces),
            ProxyGenerationOptions(output.toFile(), ApiEndpointOptions(), true, 1)).generate()
        return Files.readString(output.resolve("${query.name.replaceFirstChar(Char::uppercaseChar)}.ts"))
    }
}
