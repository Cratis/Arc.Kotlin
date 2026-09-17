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
            "ARCKSP0110",
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
            "ARCKSP0303",
            "ARCKSP0304",
            "ARCKSP0305",
            "ARCKSP0306",
            "ARCKSP0307",
            "ARCKSP0308",
            "ARCKSP0309",
            "ARCKSP0311",
            "ARCKSP0400"
        ).forEach { code -> assertTrue("[$code]" in result.messages, "Missing $code in:\n${result.messages}") }
        for (language in listOf("Kotlin", "Java")) {
            assertTrue("[ARCKSP0308] Fluent validator 'io.cratis.arc.contracts.negative.Invalid${language}FluentBody': allow only direct fluent" in result.messages, result.messages)
            assertTrue("[ARCKSP0309] Fluent validator 'io.cratis.arc.contracts.negative.Invalid${language}FluentRule': member 'name', call 'creditCard'" in result.messages, result.messages)
        }
        for (owner in listOf("ComputedFluentMemberRules", "ComputedFluentRecordRules")) {
            assertTrue("[ARCKSP0309] Fluent validator 'io.cratis.arc.contracts.negative.$owner': member 'name' is computed" in result.messages, result.messages)
        }
        assertTrue("[ARCKSP0311] @IgnoreValidation requires an instance property, field, record accessor or bean getter; move the annotation to a supported member edge." in result.messages, result.messages)
        assertTrue("[ARCKSP0311] Ambiguous @IgnoreValidation member 'io.cratis.arc.contracts.negative.HiddenIgnoredField.name' hides inherited state" in result.messages, result.messages)
        assertTrue("[ARCKSP0311] @IgnoreValidation accessor 'io.cratis.arc.contracts.negative.UnbackedIgnoredGetter.getValue' has no supported declared wire member" in result.messages, result.messages)
        assertTrue("class mapping may be shadowed" in result.messages, result.messages)
        assertTrue("Fluent matches requires nonempty classes and escaped literal closing brackets" in result.messages, result.messages)
        for (owner in listOf("ComputedBodyInput", "ComputedBodyChild")) {
            assertTrue("[ARCKSP0300] Artifact/property 'io.cratis.arc.contracts.negative.$owner.calculated' is computed command input; " +
                "use a backed property, @JsonIgnore, or a separate output model." in result.messages, result.messages)
        }
        assertTrue("[ARCKSP0300] Artifact/property 'io.cratis.arc.contracts.negative.JavaComputedBodyChild.javaCalculated' is computed command input; " +
            "use a backed property, @JsonIgnore, or a separate output model." in result.messages, result.messages)
        assertTrue("[ARCKSP0106] Command 'io.cratis.arc.contracts.negative.DuplicateBodyKeys' declares multiple @CommandKey properties: first, second. Exactly one is supported." in result.messages, result.messages)
        assertTrue("[ARCKSP0300] Artifact/property 'io.cratis.arc.contracts.negative.RenamedBodyInput.value' has body-property Jackson names or access that metadata cannot represent; use the default Arc wire name and symmetric access, or a separate wire model." in result.messages, result.messages)
        assertTrue("[ARCKSP0201] Query 'io.cratis.arc.contracts.negative.InaccessibleQueryCompanion.find' must be declared in a public companion object; make the companion public." in result.messages, result.messages)
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
        derivedTypeIdDiagnostics().forEach { message ->
            assertTrue("[ARCKSP0303] $message" in result.messages, "Missing ARCKSP0303 message '$message' in:\n${result.messages}")
        }
        derivedTypeTargetDiagnostics().forEach { message ->
            assertTrue("[ARCKSP0304] $message" in result.messages, "Missing ARCKSP0304 message '$message' in:\n${result.messages}")
        }
        exportedTypeTargetDiagnostics().forEach { message ->
            assertTrue("[ARCKSP0306] $message" in result.messages, "Missing ARCKSP0306 message '$message' in:\n${result.messages}")
        }
        for (name in listOf("sealedIdentity", "starredIdentity", "erasedIdentity", "genericIdentity", "hiddenIdentity", "GenericIdentityProvider", "genericProviderReturn", "InvalidJavaIdentityProviders.genericProviderReturn",
            "InvalidJavaIdentityProviders.GenericProvider", "InvalidJavaIdentityProviders.hiddenIdentity", "InvalidJavaIdentityProviders.projectedIdentity",
            "InvalidJavaIdentityProviders.rawIdentity", "InvalidJavaIdentityProviders.wildcardIdentity",
            "InvalidJavaIdentityProviders.erasedIdentity", "InvalidJavaIdentityProviders.genericIdentity")) {
            assertTrue("[ARCKSP0307] Identity provider declaration 'io.cratis.arc.contracts.negative.$name' " +
                "has no supported concrete public top-level details class" in result.messages, result.messages)
        }
        assertTrue("unsupportedIdentity' has an unsupported details graph" in result.messages, result.messages)
        assertTrue("@CommandEventStreamId with a blank or control-character value" in result.messages, result.messages)
        assertTrue("@CommandEventSubject with a blank or control-character value" in result.messages, result.messages)
        assertTrue("cannot declare @CommandEventStreamId and implement CommandEventStreamIdProvider" in result.messages, result.messages)
        assertTrue("cannot declare @CommandEventSubject and implement CommandEventSubjectProvider" in result.messages, result.messages)
        assertTrue("OverloadedJavaQueries' has overloaded query name 'find'" in result.messages, result.messages)
        assertTrue("star projections are unsupported" in result.messages, result.messages)
        assertTrue(unsupportedJavaArrayDiagnostic() in result.messages, result.messages)
        listOf("covariant", "contravariant", "starred").forEach { method ->
            assertTrue("[ARCKSP0300] Artifact/property 'io.cratis.arc.contracts.negative.ProjectedArrayQueries.$method.ids' value path 'element': raw and wildcard arguments are unsupported; star projections are unsupported; variant generic arguments are unsupported." in result.messages, result.messages)
        }
        assertTrue("[ARCKSP0300] Artifact/property 'io.cratis.arc.contracts.negative.ProjectedArrayQueries.nullableEntries.ids' value path 'element': nullable sequence elements are unsupported." in result.messages, result.messages)
        listOf("wildcard", "raw").forEach { method ->
            assertTrue("[ARCKSP0300] 'io.cratis.arc.contracts.negative.GenericArrayQueries.$method.ids' uses an unsupported nested or generic collection element shape." in result.messages, result.messages)
        }
        assertTrue("[ARCKSP0201] Query 'io.cratis.arc.contracts.negative.GenericArrayQueries.parameter' must not declare type parameters." in result.messages, result.messages)
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
        for (owner in listOf("NullableListProperty", "NullableCollectionProperty", "NullableArrayProperty",
            "NullableSequenceModel", "NullableSequenceView", "NullableSequenceReadModel", "NullableJavaSequenceProperty")) {
            val message = "[ARCKSP0300] Artifact/property 'io.cratis.arc.contracts.negative.$owner.values' value path 'value[]': " +
                "nullable sequence elements are unsupported; declare nonnullable elements, for example List<T> or List<T>?."
            assertTrue(message in result.messages, result.messages)
        }
        nullableResponseDiagnostics().forEach { message ->
            assertTrue("[ARCKSP0105] $message" in result.messages, "Missing ARCKSP0105 message '$message' in:\n${result.messages}")
        }
        concretePolymorphicPropertyDiagnostics().forEach { message ->
            assertTrue("[ARCKSP0305] $message" in result.messages, "Missing ARCKSP0305 message '$message' in:\n${result.messages}")
        }
        assertTrue("move handling to a public instance handle function" in result.messages, result.messages)
        assertTrue("requires 0 <= min <= max" in result.messages, result.messages)
        assertTrue("not exactly representable by a JavaScript number" in result.messages, result.messages)
        assertTrue("regular expression that is not portable to JavaScript" in result.messages, result.messages)
        assertTrue("contradictory numeric bounds" in result.messages, result.messages)
        assertTrue("contradictory length bounds" in result.messages, result.messages)
        // New negative fixtures: @Range on non-numeric, @Length on non-string/collection, @Digits with integer < 1
        assertTrue(
            "@org.hibernate.validator.constraints.Range on 'io.cratis.arc.contracts.negative.RangeOnNonNumeric.value' requires a numeric value" in result.messages,
            result.messages
        )
        assertTrue(
            "@org.hibernate.validator.constraints.Length on 'io.cratis.arc.contracts.negative.LengthOnNonString.value' requires a string, collection, or array" in result.messages,
            result.messages
        )
        assertTrue(
            "@jakarta.validation.constraints.Digits on 'io.cratis.arc.contracts.negative.DigitsZeroInteger.value' requires integer >= 1" in result.messages,
            result.messages
        )
        // Java counterparts
        assertTrue(
            "@org.hibernate.validator.constraints.Range on 'io.cratis.arc.contracts.negative.JavaRangeOnNonNumeric.value' requires a numeric value" in result.messages,
            result.messages
        )
        assertTrue(
            "@org.hibernate.validator.constraints.Length on 'io.cratis.arc.contracts.negative.JavaLengthOnNonString.value' requires a string, collection, or array" in result.messages,
            result.messages
        )
        assertTrue(
            "@jakarta.validation.constraints.Digits on 'io.cratis.arc.contracts.negative.JavaDigitsZeroInteger.value' requires integer >= 1" in result.messages,
            result.messages
        )
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

    @Test
    fun `concrete polymorphic Kotlin and Java properties report exact property-use diagnostics`() {
        val fixtureRoot = Path.of(System.getProperty("arc.contractNegativeFixtures"))
        val sources = Files.walk(fixtureRoot).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { path -> path.name.startsWith("Concrete") || path.name == "Nullable.java" }
                .sorted()
                .map { path ->
                    val source = Files.readString(path)
                    if (path.extension == "kt") SourceFile.kotlin(path.name, source) else SourceFile.java(path.name, source)
                }.toList()
        }
        val result = compile(sources)
        val expected = concretePolymorphicPropertyDiagnostics()

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        expected.forEach { message ->
            assertTrue("[ARCKSP0305] $message" in result.messages, "Missing '$message' in:\n${result.messages}")
        }
        assertEquals(expected.size, Regex("\\[ARCKSP0305]").findAll(result.messages).count(), result.messages)
    }

    @Test
    fun `concrete Java sealed base property reports exact property-use diagnostic`() {
        val fixtureRoot = Path.of(System.getProperty("arc.contractNegativeFixtures"))
            .resolve("java/io/cratis/arc/contracts/negative")
        val sources = listOf("ConcreteJavaSealedBase", "ConcreteJavaSealedLeaf", "ConcreteJavaSealedCommand")
            .map { name -> SourceFile.java("$name.java", Files.readString(fixtureRoot.resolve("$name.java"))) }
        // Prove the fixture is valid Java 17 and its sealed base is instantiable without Arc processing.
        val javaResult = KotlinCompilation().apply {
            this.sources = sources
            inheritClassPath = true
            jvmTarget = "17"
            messageOutputStream = System.out
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, javaResult.exitCode, javaResult.messages)
        val base = javaResult.classLoader.loadClass("io.cratis.arc.contracts.negative.ConcreteJavaSealedBase")
        assertTrue(base.isSealed)
        assertEquals(base, base.getDeclaredConstructor().newInstance().javaClass)

        val result = compile(sources)
        val expected = "[ARCKSP0305] Artifact/property 'io.cratis.arc.contracts.negative.ConcreteJavaSealedCommand.value' " +
            "declares concrete polymorphic base 'io.cratis.arc.contracts.negative.ConcreteJavaSealedBase' " +
            "with visible @DerivedType descendants; declare an interface or abstract base instead."
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(expected in result.messages, result.messages)
        assertEquals(1, Regex("\\[ARCKSP0305]").findAll(result.messages).count(), result.messages)
    }

    @Test
    fun `Java object arrays retain the existing unsupported variant element diagnostic`() {
        val fixtureRoot = Path.of(System.getProperty("arc.contractNegativeFixtures"))
            .resolve("java/io/cratis/arc/contracts/negative")
        val sources = listOf("ConcreteJavaPropertyBase.java", "UnsupportedConcreteJavaPropertyArrayCommand.java")
            .map { name -> SourceFile.java(name, Files.readString(fixtureRoot.resolve(name))) }
        val result = compile(sources)

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(unsupportedJavaArrayDiagnostic() in result.messages, result.messages)
        assertEquals(1, Regex("\\[ARCKSP0300]").findAll(result.messages).count(), result.messages)
        assertEquals(0, Regex("\\[ARCKSP0305]").findAll(result.messages).count(), result.messages)
    }

    private fun unsupportedJavaArrayDiagnostic(): String =
        "[ARCKSP0300] Artifact/property 'io.cratis.arc.contracts.negative.UnsupportedConcreteJavaPropertyArrayCommand.array' " +
            "value path 'element': raw and wildcard arguments are unsupported; star projections are unsupported; " +
            "variant generic arguments are unsupported."

    private fun concretePolymorphicPropertyDiagnostics(): List<String> {
        val packageName = "io.cratis.arc.contracts.negative"
        val uses = listOf(
            "ConcretePropertyCommand.direct" to "ConcretePropertyBase",
            "ConcretePropertyCommand.nullable" to "ConcretePropertyBase",
            "ConcretePropertyCommand.list" to "ConcretePropertyBase",
            "ConcretePropertyCommand.array" to "ConcretePropertyBase",
            "ConcretePropertyCommand.nullableElements" to "ConcretePropertyBase",
            "ConcretePropertyCommand.transitive" to "TransitivePropertyBase",
            "ConcretePropertyCommand.annotatedIntermediate" to "ConcretePropertyIntermediate",
            "ConcretePropertyNestedDto.value" to "ConcretePropertyBase",
            "ConcretePropertyReadModel.value" to "ConcretePropertyBase",
            "ConcreteJavaPropertyCommand.direct" to "ConcreteJavaPropertyBase",
            "ConcreteJavaPropertyCommand.nullable" to "ConcreteJavaPropertyBase",
            "ConcreteJavaPropertyCommand.list" to "ConcreteJavaPropertyBase",
            "ConcreteJavaPropertyCommand.annotatedIntermediate" to "ConcreteJavaPropertyIntermediate",
            "ConcreteJavaPropertyCommand.transitive" to "TransitivePropertyBase",
            "ConcreteJavaPropertyView.value" to "ConcreteJavaPropertyBase",
            "ConcreteJavaPropertyView.values" to "ConcreteJavaPropertyBase",
            "ConcreteJavaPropertyReadModel.value" to "ConcreteJavaPropertyBase",
            "ConcreteJavaSealedCommand.value" to "ConcreteJavaSealedBase"
        )
        return uses.map { (property, base) ->
            "Artifact/property '$packageName.$property' declares concrete polymorphic base '$packageName.$base' " +
                "with visible @DerivedType descendants; declare an interface or abstract base instead."
        }
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

    private fun derivedTypeIdDiagnostics(): List<String> = listOf(
        "Derived type 'io.cratis.arc.contracts.negative.BlankDerivedTypeIdShape' must declare a nonblank @DerivedType id.",
        "Derived type 'io.cratis.arc.contracts.negative.BlankDerivedTypeIdJavaShape' must declare a nonblank @DerivedType id."
    )

    private fun derivedTypeTargetDiagnostics(): List<String> = listOf(
        "Interface 'io.cratis.arc.contracts.negative.AnnotatedDerivedTypeInterface' cannot carry @DerivedType; " +
            "annotate concrete implementations.",
        "Interface 'io.cratis.arc.contracts.negative.AnnotatedDerivedTypeJavaInterface' cannot carry @DerivedType; " +
            "annotate concrete implementations."
    )

    private fun exportedTypeTargetDiagnostics(): List<String> = listOf(
        "Exported type 'io.cratis.arc.contracts.negative.AbstractExportedType' must not be abstract; " +
            "export the concrete derived types instead.",
        "Exported type 'io.cratis.arc.contracts.negative.AbstractJavaExportedType' must not be abstract; " +
            "export the concrete derived types instead.",
        "Exported type 'io.cratis.arc.contracts.negative.GenericExportedType' must not declare type parameters; " +
            "a generic definition has no single shape to generate.",
        "Exported type 'io.cratis.arc.contracts.negative.InternalExportedType' must be public so generated clients can use it.",
        "@ExportedType types must be top-level; nested and local types are not supported."
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
        // Java sealed fixtures require Java 17 in this embedded compilation, independently of the test task's target.
        jvmTarget = "17"
        symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
        val index = workingDir.resolve("fluent-index.json").apply { parentFile.mkdirs(); writeText("{\"formatVersion\":1,\"modules\":[]}") }
        kspProcessorOptions = mutableMapOf("arc.moduleName" to "NegativeContracts", FluentValidationMetadata.OPTION to index.toURI().toASCIIString())
        kspWithCompilation = true
        messageOutputStream = System.out
    }.compile()
}
