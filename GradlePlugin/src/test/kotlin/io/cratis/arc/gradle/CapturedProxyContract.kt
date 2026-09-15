// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.json.JsonMapper

/**
 * Bounded seven-file capture contract, not raw command/query output equality or a TypeScript compiler.
 * `actualRoot` is an isolated output root containing the mapped relative paths, not a basename index.
 * Only Address and OrderKind receive expected-header-only preparation followed by full byte comparison.
 * Other artifacts are parsed independently against literals below, never against JVM-derived expectations.
 * Comments/formatting are outside that parsed contract. Imports may use type syntax/grouping; properties
 * may be reordered. Only captured AllOrders has the sorting/paging scaffold: the JVM List fixture has
 * neither capability. Neither side's source bytes are normalized for comparison.
 */
internal object CapturedProxyContract {
    private const val capturedPackage = "Arc/Kotlin/Differential/Fixture"
    private const val actualPackage = "arc/kotlin/differential/fixture"
    private const val capturedNamespace = "Arc.Kotlin.Differential.Fixture"
    private const val actualNamespace = "arc.kotlin.differential.fixture"
    private val sources = linkedMapOf(
        "Address.ts" to "Address", "AllOrders.ts" to "OrderView", "OrderById.ts" to "OrderView",
        "OrderKind.ts" to "OrderKind", "OrderView.ts" to "OrderView", "PlaceOrder.ts" to "PlaceOrder",
        "index.ts" to null
    )
    private val orderFields = linkedMapOf(
        "id" to "Guid", "customer" to "string", "kind" to "OrderKind",
        "requestedDate" to "DateOnly", "requestedTime" to "TimeOnly", "shipTo" to "Address"
    )
    private val commandFields = linkedMapOf(
        "id" to "Guid", "customer" to "string", "quantity" to "number", "express" to "boolean",
        "requestedDate" to "DateOnly", "requestedTime" to "TimeOnly", "placedAt" to "Date",
        "kind" to "OrderKind", "shipTo" to "Address", "tags" to "string[]"
    )
    private val constructors = mapOf(
        "Guid" to "Guid", "string" to "String", "number" to "Number", "boolean" to "Boolean",
        "DateOnly" to "DateOnly", "TimeOnly" to "TimeOnly", "Date" to "Date", "OrderKind" to "Number",
        "Address" to "Address", "string[]" to "String"
    )

    fun verify(captured: File, actualRoot: File) {
        val declared = sources.keys.map { "$capturedPackage/$it" }.sorted()
        assertEquals(declared + "capture-manifest.json", files(captured), "Captured relative file set")
        val manifest = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .readTree(captured.resolve("capture-manifest.json"))
        assertTrue(manifest.path("formatVersion").isIntegralNumber, "Integral capture manifest format required")
        assertEquals(1, manifest.path("formatVersion").intValue(), "Capture manifest format")
        val hashes = manifest.path("files")
        assertTrue(hashes.isObject, "Capture manifest files must be an object")
        assertEquals(declared, hashes.properties().map { it.key }.sorted(), "Declared capture paths")
        declared.forEach { path ->
            assertTrue(hashes.path(path).isString, "String checksum required: $path")
            assertEquals(sha256(captured.resolve(path).readBytes()), hashes.path(path).stringValue(), "Capture checksum: $path")
        }
        // Mapping is expected-side, explicit, one-to-one, and retains the complete relative path.
        val mapping = declared.associateWith { actualPackage + it.removePrefix(capturedPackage) }
        assertEquals(mapping.values.sorted(), files(actualRoot), "JVM relative file set")
        sources.forEach { (name, source) ->
            val expected = captured.resolve("$capturedPackage/$name").readText()
            val actualFile = actualRoot.resolve(mapping.getValue("$capturedPackage/$name"))
            val actual = actualFile.readText()
            if (source != null) {
                verifyHeader(expected, "$capturedNamespace.$source", "captured/$name")
                verifyHeader(actual, "$actualNamespace.$source", "JVM/$name")
            }
            if (name == "Address.ts" || name == "OrderKind.ts") {
                // Replace only the already-validated first-line identity, never arbitrary body text.
                val prepared = expected.substringBefore('\n').replace(
                    "Source: $capturedNamespace.$source.", "Source: $actualNamespace.$source."
                ) + "\n" + expected.substringAfter('\n')
                assertArrayEquals(prepared.toByteArray(Charsets.UTF_8), actualFile.readBytes(), name)
            } else {
                verifyContract(name, expected, capturedSide = true)
                verifyContract(name, actual, capturedSide = false)
            }
        }
    }

    private fun files(root: File): List<String> {
        assertTrue(root.isDirectory, "Missing output root: $root")
        assertTrue(!Files.isSymbolicLink(root.toPath()), "Symbolic output root: $root")
        return root.walkTopDown().onEnter { directory ->
            assertTrue(!Files.isSymbolicLink(directory.toPath()), "Symbolic directory: $directory")
            true
        }.onEach { file ->
            assertTrue(!Files.isSymbolicLink(file.toPath()), "Symbolic capture/output entry: $file")
        }.filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02X".format(it) }

    private fun verifyHeader(text: String, source: String, label: String) {
        val body = text.substringAfter('\n', "")
        assertEquals("// @generated by Cratis. Source: $source. Hash: ${sha256(body.toByteArray(Charsets.UTF_8))}",
            text.substringBefore('\n'), "$label source identity and body checksum")
    }

    private fun verifyContract(name: String, text: String, capturedSide: Boolean) {
        val expected = when (name) {
            "PlaceOrder.ts" -> command()
            "OrderView.ts" -> model()
            "AllOrders.ts" -> query(collection = true, capturedSide = capturedSide)
            "OrderById.ts" -> query(collection = false, capturedSide = capturedSide)
            "index.ts" -> sources.keys.filter { it != "index.ts" }.joinToString("\n") { "export * from './${it.removeSuffix(".ts")}';" }
            else -> error("No literal oracle for $name")
        }
        val label = "${if (capturedSide) "captured" else "JVM"}/$name contract"
        assertEquals(parse(expected), parse(text), label)
    }

    private fun model(): String = """
        import { field, DateOnly, Guid, TimeOnly } from '@cratis/fundamentals';
        import { Address } from './Address';
        import { OrderKind } from './OrderKind';
        export class OrderView {
            ${orderFields.map { (name, type) -> "@field(${constructors.getValue(type)}) $name!: $type;" }.joinToString("\n")}
        }
    """

    private fun command(): String = """
        import { Command } from '@cratis/arc/commands';
        import { useCommand, SetCommandValues, ClearCommandValues } from '@cratis/arc.react/commands';
        import { PropertyDescriptor } from '@cratis/arc/reflection';
        import { Address } from './Address';
        import { OrderKind } from './OrderKind';
        import { Guid, DateOnly, TimeOnly } from '@cratis/fundamentals';
        export interface IPlaceOrder {
            ${commandFields.map { (name, type) -> "$name?: $type;" }.joinToString("\n")}
        }
        export class PlaceOrder extends Command<IPlaceOrder, Guid> implements IPlaceOrder {
            readonly route: string = '/api/arc/kotlin/differential/fixture/place-order';
            readonly treatWarningsAsErrors: boolean = false;
            readonly roles: string[] = [];
            readonly propertyDescriptors: PropertyDescriptor[] = [
                ${commandFields.map { (name, type) -> "new PropertyDescriptor('$name', ${constructors.getValue(type)}, false)," }.joinToString("\n")}
            ];
            ${commandFields.map { (name, type) -> "private _$name!: $type;" }.joinToString("\n")}
            constructor() { super(Guid, false); }
            get requestParameters(): string[] { return []; }
            ${commandFields.map { (name, type) -> """
                get $name(): $type { return this._$name; }
                set $name(value: $type) { this._$name = value; this.propertyChanged('$name'); }
            """ }.joinToString("\n")}
            static use(initialValues?: IPlaceOrder): [PlaceOrder, SetCommandValues<IPlaceOrder>, ClearCommandValues] {
                return useCommand<PlaceOrder, IPlaceOrder>(PlaceOrder, initialValues);
            }
        }
    """

    private fun query(collection: Boolean, capturedSide: Boolean): String {
        val name = if (collection) "AllOrders" else "OrderById"
        val model = if (collection) "OrderView[]" else "OrderView"
        val parameters = if (collection) "" else ", OrderByIdParameters"
        val namespace = if (capturedSide) capturedNamespace else actualNamespace
        val method = if (capturedSide) name else if (collection) "allOrders" else "orderById"
        val pageable = collection && capturedSide
        val sortingImports = if (pageable) ", Sorting, Paging" else ""
        val pagingImports = if (pageable) ", useQueryWithPaging, useSuspenseQueryWithPaging, SetPage, SetPageSize" else ""
        val sortingType = if (!collection || pageable) ", SetSorting" else ""
        val args = if (!collection) "args?: OrderByIdParameters" else if (pageable) "sorting?: Sorting" else ""
        val invocationArgs = if (!collection) "args" else if (pageable) "undefined, sorting" else "undefined"
        return """
            import { QueryFor, QueryResultWithState$sortingImports } from '@cratis/arc/queries';
            import { useQuery, useSuspenseQuery, PerformQuery$sortingType$pagingImports, QueryWhen } from '@cratis/arc.react/queries';
            import { ParameterDescriptor } from '@cratis/arc/reflection';
            import { OrderView } from './OrderView';
            ${if (!collection) "import { Guid } from '@cratis/fundamentals'; export interface OrderByIdParameters { id: Guid; }" else ""}
            ${if (pageable) "class AllOrdersSortBy { constructor(readonly query: AllOrders) {} } class AllOrdersSortByWithoutQuery {}" else ""}
            export class $name extends QueryFor<$model$parameters> {
                readonly route: string = '/api/arc/kotlin/differential/fixture/${if (collection) "all-orders" else "order-by-id"}';
                readonly queryName: string = '$namespace.OrderView.$method';
                readonly treatWarningsAsErrors: boolean = false;
                readonly roles: string[] = [];
                readonly defaultValue: $model = ${if (collection) "[]" else "{} as any"};
                ${if (pageable) "private readonly _sortBy: AllOrdersSortBy; private static readonly _sortBy: AllOrdersSortByWithoutQuery = new AllOrdersSortByWithoutQuery();" else ""}
                constructor() { super(OrderView, $collection); ${if (pageable) "this._sortBy = new AllOrdersSortBy(this);" else ""} }
                get requiredRequestParameters(): string[] { return [${if (!collection) "'id'," else ""}]; }
                readonly parameterDescriptors: ParameterDescriptor[] = [${if (!collection) "new ParameterDescriptor('id', Guid, false)," else ""}];
                ${if (!collection) "id!: Guid;" else ""}
                ${if (pageable) "get sortBy(): AllOrdersSortBy { return this._sortBy; } static get sortBy(): AllOrdersSortByWithoutQuery { return this._sortBy; }" else ""}
                ${listOf("use" to "useQuery", "useSuspense" to "useSuspenseQuery").joinToString("\n") { (hook, call) -> """
                    static $hook($args): [QueryResultWithState<$model>, PerformQuery${if (!collection) "<OrderByIdParameters>" else ""}$sortingType] {
                        ${if (collection && !pageable) "const [result, perform] = $call<$model, $name$parameters>($name, $invocationArgs); return [result, perform];" else "return $call<$model, $name$parameters>($name, $invocationArgs);"}
                    }
                """ }}
                ${if (pageable) listOf("useWithPaging" to "useQueryWithPaging", "useSuspenseWithPaging" to "useSuspenseQueryWithPaging").joinToString("\n") { (hook, call) -> """
                    static $hook(pageSize: number, sorting?: Sorting): [QueryResultWithState<OrderView[]>, PerformQuery, SetSorting, SetPage, SetPageSize] {
                        return $call<OrderView[], AllOrders>(AllOrders, new Paging(0, pageSize), undefined, sorting);
                    }
                """ } else ""}
                static when(condition: boolean): QueryWhen<$name, $model$parameters> {
                    return new QueryWhen<$name, $model$parameters>($name, condition);
                }
            }
        """
    }

    private data class Parsed(val imports: List<String>, val declarations: Map<String, List<String>>)

    /** Small fail-closed grammar for these fixtures, not substring assertions or a general TS AST. */
    private fun parse(text: String): Parsed {
        val tokens = tokens(text)
        val imports = mutableListOf<String>()
        val declarations = linkedMapOf<String, List<String>>()
        var cursor = 0
        while (cursor < tokens.size) {
            if (tokens[cursor] == "import") {
                val end = tokens.indexOfFrom(";", cursor)
                val statement = tokens.subList(cursor, end)
                val open = statement.indexOf("{")
                val close = statement.indexOf("}")
                assertTrue(open in 1..2 && close > open, "Unsupported import: $statement")
                assertTrue(statement.take(open) == listOf("import") || statement.take(open) == listOf("import", "type"),
                    "Unsupported import prefix: $statement")
                assertEquals(listOf("from", statement.last()), statement.drop(close + 1), "Import source")
                statement.subList(open + 1, close).filter { it != "," && it != "type" }.forEach {
                    imports += "${statement.last()}:$it"
                }
                cursor = end + 1
            } else if (tokens.getOrNull(cursor + 1) == "*") {
                val end = tokens.indexOfFrom(";", cursor)
                val key = tokens.subList(cursor, end + 1).joinToString(" ")
                assertTrue(declarations.put(key, emptyList()) == null, "Duplicate export: $key")
                cursor = end + 1
            } else {
                val open = tokens.indexOfFrom("{", cursor)
                val close = closing(tokens, open, "{", "}")
                val key = tokens.subList(cursor, open).joinToString(" ")
                val members = members(tokens.subList(open + 1, close))
                assertTrue(declarations.put(key, members) == null, "Duplicate declaration: $key")
                cursor = close + 1
            }
        }
        assertEquals(imports.size, imports.distinct().size, "Duplicate imported bindings")
        return Parsed(imports.sorted(), declarations)
    }

    private fun members(body: List<String>): List<String> {
        val result = mutableListOf<String>()
        var cursor = 0
        while (cursor < body.size) {
            val start = cursor
            var parentheses = 0
            var brackets = 0
            var initializer = false
            while (cursor < body.size) {
                val token = body[cursor++]
                when (token) {
                    "(" -> parentheses++
                    ")" -> parentheses--
                    "[" -> brackets++
                    "]" -> brackets--
                    "=" -> if (parentheses == 0 && brackets == 0) initializer = true
                    "{" -> {
                        cursor = closing(body, cursor - 1, "{", "}") + 1
                        if (!initializer && parentheses == 0 && brackets == 0) break
                    }
                    ";" -> if (parentheses == 0 && brackets == 0) break
                }
            }
            val member = body.subList(start, cursor)
            // Only descriptor entry ordering is non-semantic inside a member. Keep each entry and its multiplicity.
            if (member.take(2) == listOf("readonly", "propertyDescriptors")) {
                val equals = member.indexOf("=")
                assertTrue(equals > 0, "Missing descriptor initializer")
                val entries = member.subList(equals + 2, member.size - 2)
                val descriptors = mutableListOf<String>()
                var index = 0
                while (index < entries.size) {
                    assertEquals(listOf("new", "PropertyDescriptor", "("), entries.drop(index).take(3))
                    val end = closing(entries, index + 2, "(", ")")
                    descriptors += entries.subList(index, end + 1).joinToString(" ")
                    assertEquals(",", entries.getOrNull(end + 1), "Descriptor separator")
                    index = end + 2
                }
                result += member.take(equals + 2).joinToString(" ") + descriptors.sorted().joinToString(" | ") + "] ;"
            } else {
                result += member.joinToString(" ")
            }
        }
        assertEquals(result.size, result.distinct().size, "Duplicate members")
        return result.sorted()
    }

    private fun closing(tokens: List<String>, start: Int, open: String, close: String): Int {
        var depth = 0
        for (index in start until tokens.size) {
            if (tokens[index] == open) depth++
            if (tokens[index] == close) {
                depth--
                if (depth == 0) return index
            }
        }
        throw AssertionError("Unclosed $open at $start")
    }

    private fun List<String>.indexOfFrom(value: String, start: Int): Int =
        (start until size).firstOrNull { this[it] == value } ?: throw AssertionError("Missing $value after token $start")

    private val lexical = Regex("""\s+|//[^\n]*|/\*[\s\S]*?\*/|'[^'\r\n]*'|"[^"\r\n]*"|[A-Za-z_$][A-Za-z0-9_$]*|[0-9]+|[{}()\[\];:,.?!<>=@*]""")

    private fun tokens(text: String): List<String> {
        val result = mutableListOf<String>()
        var cursor = 0
        lexical.findAll(text).forEach { match ->
            assertEquals(cursor, match.range.first, "Unsupported TS syntax at $cursor")
            cursor = match.range.last + 1
            val value = match.value
            if (!value.first().isWhitespace() && !value.startsWith("//") && !value.startsWith("/*")) {
                result += value
            }
        }
        assertEquals(text.length, cursor, "Unparsed TS suffix")
        return result
    }
}
