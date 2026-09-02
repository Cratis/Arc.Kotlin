// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspProcessorOptions
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.streams.toList
import org.junit.jupiter.api.Assertions.assertEquals
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorNegativeCompilationTest {
    @Test
    fun `invalid Kotlin and Java contract fixtures report stable Arc diagnostics`() {
        val fixtureRoot = Path.of(System.getProperty("arc.contractNegativeFixtures"))
        val sourceFiles = Files.walk(fixtureRoot).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { path -> path.extension == "kt" || path.extension == "java" }
                .sorted()
                .map { path ->
                    val source = Files.readString(path)
                    if (path.extension == "kt") SourceFile.kotlin(path.name, source) else SourceFile.java(path.name, source)
                }
                .toList()
        }
        assertTrue(sourceFiles.isNotEmpty(), "ContractTests negative fixtures must be present")

        val result = compile(sourceFiles)

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        listOf(
            "ARCKSP0101",
            "ARCKSP0102",
            "ARCKSP0103",
            "ARCKSP0104",
            "ARCKSP0105",
            "ARCKSP0106",
            "ARCKSP0107",
            "ARCKSP0108",
            "ARCKSP0109",
            "ARCKSP0200",
            "ARCKSP0201",
            "ARCKSP0202",
            "ARCKSP0203",
            "ARCKSP0204",
            "ARCKSP0205",
            "ARCKSP0206",
            "ARCKSP0208",
            "ARCKSP0209",
            "ARCKSP0210",
            "ARCKSP0300",
            "ARCKSP0301",
            "ARCKSP0400"
        ).forEach { code -> assertTrue("[$code]" in result.messages, "Missing $code in:\n${result.messages}") }
        assertTrue("annotate it with @FromServices" in result.messages, result.messages)
        queryInfrastructureDiagnostics().forEach { message ->
            assertTrue("[ARCKSP0208] $message" in result.messages, "Missing ARCKSP0208 message '$message' in:\n${result.messages}")
        }
        queryDefaultDiagnostics().forEach { message ->
            assertTrue("[ARCKSP0209] $message" in result.messages, "Missing ARCKSP0209 message '$message' in:\n${result.messages}")
        }
        springDataDiagnostics().forEach { message ->
            assertTrue("[ARCKSP0210] $message" in result.messages, "Missing ARCKSP0210 message '$message' in:\n${result.messages}")
        }
        assertTrue("OverloadedJavaQueries' has overloaded query name 'find'" in result.messages, result.messages)
        assertTrue("star projections are unsupported" in result.messages, result.messages)
        listOf(
            "InvalidMapShapes.kt",
            "value path 'value.key': map keys must be nonnullable String",
            "value path 'value': nullable map values and sequence elements are unsupported",
            "value path 'value[]': nullable map values and sequence elements are unsupported",
            "not a runtime-safe JavaScript primitive",
            "unresolvable and type-parameter arguments are unsupported",
            "value path 'conceptValue' has unsupported non-scalar underlying type",
            "query, observable, and service parameters cannot use maps",
            "query and observable return maps are unsupported",
            "uses a top-level map; command response maps are unsupported",
            "maps are supported only for artifact properties"
        ).forEach { message -> assertTrue(message in result.messages, "Missing '$message' in:\n${result.messages}") }
        exactMapDiagnostics().forEach { message ->
            assertTrue("[ARCKSP0300] $message" in result.messages, "Missing ARCKSP0300 message '$message' in:\n${result.messages}")
        }
        nullableResponseDiagnostics().forEach { message ->
            assertTrue("[ARCKSP0105] $message" in result.messages, "Missing ARCKSP0105 message '$message' in:\n${result.messages}")
        }
        assertTrue("move handling to a public instance handle function" in result.messages, result.messages)
        assertTrue("requires 0 <= min <= max" in result.messages, result.messages)
        assertTrue("not exactly representable by a JavaScript number" in result.messages, result.messages)
        assertTrue("regular expression that is not portable to JavaScript" in result.messages, result.messages)
        assertTrue("contradictory numeric bounds" in result.messages, result.messages)
        assertTrue("contradictory length bounds" in result.messages, result.messages)
        assertTrue(
            "Jakarta constraints declared directly on static Java query parameter" in result.messages,
            result.messages
        )
        assertTrue("implements ConceptAs<T>, but its underlying generic type cannot be resolved" in result.messages, result.messages)
        assertTrue("AmbiguousKotlinResponseCommand.handle" in result.messages, result.messages)
        assertTrue("AmbiguousJavaResponseCommand.handle" in result.messages, result.messages)
        assertTrue(
            result.messages.indexOf("FirstAmbiguousKotlinResponse") <
                result.messages.indexOf("SecondAmbiguousKotlinResponse"),
            result.messages
        )
        assertTrue(
            result.messages.indexOf("FirstAmbiguousJavaResponse") <
                result.messages.indexOf("SecondAmbiguousJavaResponse"),
            result.messages
        )
    }

    @Test
    fun `explicitly nullable response nodes report exact command response diagnostics`() {
        val fixture = Path.of(System.getProperty("arc.contractNegativeFixtures"))
            .resolve("kotlin/io/cratis/arc/contracts/negative/NullableCommandResponseElements.kt")
        val result = compile(listOf(SourceFile.kotlin(fixture.name, Files.readString(fixture))))
        val expected = nullableResponseDiagnostics()

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        expected.forEach { message ->
            assertTrue("[ARCKSP0105] $message" in result.messages, "Missing ARCKSP0105 message '$message' in:\n${result.messages}")
        }
        assertEquals(expected.size, Regex("\\[ARCKSP0105]").findAll(result.messages).count(), result.messages)
    }

    @Test
    fun `command-like type without annotation produces a warning without failing compilation`() {
        val result = compile(
            listOf(
                SourceFile.kotlin(
                    "MissingAnnotation.kt",
                    """
                    package io.cratis.arc.contracts.negative

                    public data class MissingAnnotation(public val value: String) {
                        public fun handle(): String = value.uppercase()
                    }
                    """.trimIndent()
                )
            )
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue("[ARCKSP0100]" in result.messages, result.messages)
        assertTrue("missing @Command" in result.messages, result.messages)
    }

    private fun exactMapDiagnostics(): List<String> = listOf(
        "Artifact/property 'io.cratis.arc.contracts.negative.NullableJavaMapKeyCommand.values' value path " +
            "'value.key': map keys must be nonnullable String.",
        "Artifact/property 'io.cratis.arc.contracts.negative.NullableJavaMapValueCommand.values' value path " +
            "'value': nullable map values and sequence elements are unsupported.",
        "Artifact/property 'io.cratis.arc.contracts.negative.NullableJavaMapListElementCommand.values' value path " +
            "'value[]': nullable map values and sequence elements are unsupported.",
        "Artifact/property 'io.cratis.arc.contracts.negative.NullableNestedJavaMapValueCommand.values' value path " +
            "'value.value': nullable map values and sequence elements are unsupported.",
        "Artifact/property 'io.cratis.arc.contracts.negative.UnsafeFloatMapLeaf.values' value path 'value': " +
            "map value leaf 'kotlin.Float' is not a runtime-safe JavaScript primitive; model, concept, enum, UUID, " +
            "temporal, and derived leaves are unsupported.",
        "Artifact/property 'io.cratis.arc.contracts.negative.UnsafeDoubleMapLeaf.values' value path 'value': " +
            "map value leaf 'kotlin.Double' is not a runtime-safe JavaScript primitive; model, concept, enum, UUID, " +
            "temporal, and derived leaves are unsupported.",
        "Artifact/property 'io.cratis.arc.contracts.negative.PrimitiveFloatJavaMapCommand.values' value path " +
            "'value[]': map value leaf 'java.lang.Float' is not a runtime-safe JavaScript primitive; model, concept, " +
            "enum, UUID, temporal, and derived leaves are unsupported.",
        "Artifact/property 'io.cratis.arc.contracts.negative.PrimitiveDoubleJavaMapCommand.values' value path " +
            "'value[]': map value leaf 'java.lang.Double' is not a runtime-safe JavaScript primitive; model, concept, " +
            "enum, UUID, temporal, and derived leaves are unsupported.",
        "Artifact/property 'io.cratis.arc.contracts.negative.BoxedFloatJavaMapCommand.values' value path 'value': " +
            "map value leaf 'java.lang.Float' is not a runtime-safe JavaScript primitive; model, concept, enum, UUID, " +
            "temporal, and derived leaves are unsupported.",
        "Artifact/property 'io.cratis.arc.contracts.negative.BoxedDoubleJavaMapCommand.values' value path 'value': " +
            "map value leaf 'java.lang.Double' is not a runtime-safe JavaScript primitive; model, concept, enum, UUID, " +
            "temporal, and derived leaves are unsupported."
    )

    private fun queryInfrastructureDiagnostics(): List<String> = listOf(
        "Query infrastructure parameter 'io.cratis.arc.contracts.negative.NullableInfrastructureReadModel.invalid.request' " +
            "must use the exact non-null type 'io.cratis.arc.queries.QueryRequest'.",
        "Query infrastructure parameter 'io.cratis.arc.contracts.negative.ServiceInfrastructureReadModel.invalid.request' " +
            "must not be annotated @FromServices; its value is supplied by the query execution context.",
        "Query 'io.cratis.arc.contracts.negative.DuplicateInfrastructureReadModel.invalid' declares more than one " +
            "QUERY_CONTEXT infrastructure parameter; each infrastructure source may appear at most once.",
        "Query parameter 'io.cratis.arc.contracts.negative.GenericInfrastructureLookalikeReadModel.invalid.request' uses " +
            "infrastructure-like type 'io.cratis.arc.contracts.negative.QueryRequest'; only exact non-null " +
            "'io.cratis.arc.queries.QueryRequest' and 'io.cratis.arc.queries.QueryContext' types are supported as query " +
            "infrastructure parameters.",
        "Query parameter 'io.cratis.arc.contracts.negative.InfrastructureLookalikeReadModel.invalid.context' uses " +
            "infrastructure-like type 'io.cratis.arc.contracts.negative.QueryContext'; only exact non-null " +
            "'io.cratis.arc.queries.QueryRequest' and 'io.cratis.arc.queries.QueryContext' types are supported as query " +
            "infrastructure parameters.",
        "Query 'io.cratis.arc.contracts.negative.GenericInfrastructureReadModel.invalid' must not use a generic " +
            "infrastructure parameter bounded by 'io.cratis.arc.queries.QueryRequest'; only exact non-generic query " +
            "infrastructure parameter types are supported.",
        "Query 'io.cratis.arc.contracts.negative.DuplicateJavaQueryInfrastructure.invalid' declares more than one " +
            "QUERY_REQUEST infrastructure parameter; each infrastructure source may appear at most once.",
        "Query infrastructure parameter 'io.cratis.arc.contracts.negative.NullableJavaQueryInfrastructure.invalid.request' " +
            "must use the exact non-null type 'io.cratis.arc.queries.QueryRequest'.",
        "Query parameter 'io.cratis.arc.contracts.negative.SubtypeJavaQueryInfrastructure.invalid.request' uses " +
            "infrastructure-like type 'io.cratis.arc.contracts.negative.QueryRequestSubtype'; only exact non-null " +
            "'io.cratis.arc.queries.QueryRequest' and 'io.cratis.arc.queries.QueryContext' types are supported as query " +
            "infrastructure parameters."
    )

    private fun springDataDiagnostics(): List<String> = listOf(
        "Query host adapter parameter 'io.cratis.arc.contracts.negative.NullablePageableReadModel.invalid.pageable' " +
            "must use the exact non-null type 'org.springframework.data.domain.Pageable'.",
        "Kotlin query host adapter parameter " +
            "'io.cratis.arc.contracts.negative.DefaultedSortReadModel.invalid.sort' must not declare a default value; " +
            "Arc always creates it from the query request.",
        "Query host adapter parameter 'io.cratis.arc.contracts.negative.ServicePageableReadModel.invalid.pageable' " +
            "must not be annotated @FromServices; its value is supplied from the query request.",
        "Query 'io.cratis.arc.contracts.negative.DuplicateSortReadModel.invalid' declares more than one SORT host " +
            "adapter parameter; each host adapter kind may appear at most once.",
        "Query parameter 'io.cratis.arc.contracts.negative.PageableSubtypeReadModel.invalid.pageable' uses " +
            "host-adapter-like type 'io.cratis.arc.contracts.negative.PageableSubtype'; only exact non-null " +
            "'org.springframework.data.domain.Pageable' and 'org.springframework.data.domain.Sort' host adapter " +
            "parameters are supported.",
        "Query 'io.cratis.arc.contracts.negative.GenericPageableReadModel.invalid' must not use a generic host adapter " +
            "parameter bounded by 'org.springframework.data.domain.Pageable'; only exact non-generic " +
            "'org.springframework.data.domain.Pageable' and 'org.springframework.data.domain.Sort' parameters are supported.",
        "Observable query 'io.cratis.arc.contracts.negative.ObservableSpringPageReadModel.invalid' must not return " +
            "'org.springframework.data.domain.Page<T>'",
        "Observable query 'io.cratis.arc.contracts.negative.ObservableJavaSpringPage.invalid' must not return " +
            "'org.springframework.data.domain.Page<T>'",
        "Query 'io.cratis.arc.contracts.negative.SpringPageSubtypeReadModel.invalid' returns Spring Data Page subtype " +
            "'io.cratis.arc.contracts.negative.UnsupportedSpringPageSubtype'",
        "Query 'io.cratis.arc.contracts.negative.InvalidJavaSpringDataReturns.wildcard' must return exact invariant " +
            "'org.springframework.data.domain.Page<T>'",
        "Query 'io.cratis.arc.contracts.negative.InvalidJavaSpringDataReturns.nullable' must return the exact non-null " +
            "type 'org.springframework.data.domain.Page<T>'.",
        "Query 'io.cratis.arc.contracts.negative.PageableCollectionReadModel.invalid' uses Pageable or Sort host " +
            "adapters but does not return exact 'org.springframework.data.domain.Page<T>'; provider-owned adapters " +
            "require a Page return to prevent Arc from sorting or paging the result again.",
        "Query 'io.cratis.arc.contracts.negative.SortCollectionReadModel.invalid' uses Pageable or Sort host adapters " +
            "but does not return exact 'org.springframework.data.domain.Page<T>'; provider-owned adapters require a " +
            "Page return to prevent Arc from sorting or paging the result again.",
        "Query client parameter 'io.cratis.arc.contracts.negative.ReservedPageReadModel.invalid.page' conflicts with " +
            "reserved paging or sorting control 'page'; use another parameter name.",
        "Query client parameter 'io.cratis.arc.contracts.negative.ReservedPageSizeReadModel.invalid.pageSize' conflicts " +
            "with reserved paging or sorting control 'pageSize'; use another parameter name.",
        "Query client parameter 'io.cratis.arc.contracts.negative.ReservedSortByReadModel.invalid.sortBy' conflicts with " +
            "reserved paging or sorting control 'sortBy'; use another parameter name.",
        "Query client parameter 'io.cratis.arc.contracts.negative.ReservedSortDirectionReadModel.invalid.sortDirection' " +
            "conflicts with reserved paging or sorting control 'sortDirection'; use another parameter name."
    )

    private fun queryDefaultDiagnostics(): List<String> = listOf(
        "Kotlin query parameter default 'io.cratis.arc.contracts.negative.DefaultedInfrastructureReadModel.invalid.context' " +
            "is unsupported because QUERY_REQUEST and QUERY_CONTEXT infrastructure parameters must always be supplied by Arc.",
        "Kotlin query parameter default 'io.cratis.arc.contracts.negative.DefaultedRequestReadModel.invalid.request' is " +
            "unsupported because QUERY_REQUEST and QUERY_CONTEXT infrastructure parameters must always be supplied by Arc.",
        "Kotlin query parameter default 'io.cratis.arc.contracts.negative.DefaultedServiceReadModel.invalid.dependency' is " +
            "unsupported because service parameters must always be supplied by Arc.",
        "Kotlin query parameter defaults on 'io.cratis.arc.contracts.negative.ExcessDefaultedClientsReadModel.invalid' " +
            "are unsupported because 7 defaulted client parameters require more than the maximum 6 (64 invocation branches)."
    )

    private fun nullableResponseDiagnostics(): List<String> = listOf(
        "Handler 'io.cratis.arc.contracts.negative.NullableTopLevelClientCommand.handle' has explicitly nullable " +
            "response type 'io.cratis.arc.contracts.negative.NullableTopLevelClient?'; nullable response nodes are " +
            "unsupported until command response metadata can preserve branch nullability.",
        "Handler 'io.cratis.arc.contracts.negative.NullablePairMemberCommand.handle' has explicitly nullable response " +
            "type 'io.cratis.arc.contracts.negative.NullablePairMember?'; nullable response nodes are unsupported until " +
            "command response metadata can preserve branch nullability.",
        "Handler 'io.cratis.arc.contracts.negative.NullableTripleMemberCommand.handle' has explicitly nullable response " +
            "type 'io.cratis.arc.contracts.negative.NullableTripleMember?'; nullable response nodes are unsupported until " +
            "command response metadata can preserve branch nullability.",
        "Handler 'io.cratis.arc.contracts.negative.NullableArcOneOfShapeCommand.handle' has explicitly nullable response " +
            "type 'io.cratis.arc.contracts.negative.NullableArcOneOfShapeMember?'; nullable response nodes are unsupported " +
            "until command response metadata can preserve branch nullability.",
        "Handler 'io.cratis.arc.contracts.negative.NullableArcOneOfMemberCommand.handle' has explicitly nullable response " +
            "type 'io.cratis.arc.commands.ArcOneOf<io.cratis.arc.contracts.negative.NullableArcOneOfMember>?'; nullable " +
            "response nodes are unsupported until command response metadata can preserve branch nullability.",
        "Handler 'io.cratis.arc.contracts.negative.NullableCommandResultPayloadCommand.handle' has explicitly nullable " +
            "response type 'io.cratis.arc.contracts.negative.NullableCommandResultPayload?'; nullable response nodes are " +
            "unsupported until command response metadata can preserve branch nullability.",
        "Handler 'io.cratis.arc.contracts.negative.NullableCommandResultMemberCommand.handle' has explicitly nullable " +
            "response type 'io.cratis.arc.results.CommandResult<io.cratis.arc.contracts.negative.NullableCommandResultMember>?'; " +
            "nullable response nodes are unsupported until command response metadata can preserve branch nullability.",
        "Handler 'io.cratis.arc.contracts.negative.NullableClientCollectionCommand.handle' has nullable response element " +
            "type 'io.cratis.arc.contracts.negative.NullableClientCollectionElement?'; nullable response elements are " +
            "unsupported until command response metadata can preserve element nullability.",
        "Handler 'io.cratis.arc.contracts.negative.NullableHandledCollectionCommand.handle' has nullable response element " +
            "type 'io.cratis.arc.results.ValidationResult?'; nullable response elements are unsupported until command " +
            "response metadata can preserve element nullability.",
        "Handler 'io.cratis.arc.contracts.negative.NullableClientArrayCommand.handle' has nullable response element type " +
            "'io.cratis.arc.contracts.negative.NullableClientArrayElement?'; nullable response elements are unsupported " +
            "until command response metadata can preserve element nullability."
    )

    private fun compile(sources: List<SourceFile>): JvmCompilationResult = KotlinCompilation().apply {
        useKsp2()
        this.sources = sources
        inheritClassPath = true
        symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
        kspProcessorOptions = mutableMapOf("arc.moduleName" to "NegativeContracts")
        kspWithCompilation = true
        messageOutputStream = System.out
    }.compile()
}
