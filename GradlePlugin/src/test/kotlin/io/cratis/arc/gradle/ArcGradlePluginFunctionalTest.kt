// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Real local GAVs and production plugin JARs; public transitive downloads make this intentionally not fully hermetic. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArcGradlePluginFunctionalTest {
    private val version = property("version")
    private val repository = File(property("repository"))
    private val pluginJar = File(property("pluginJar"))
    private val pluginClasspath = property("pluginClasspath").split(File.pathSeparator).map(::File)
    private val work = Files.createTempDirectory(File(property("work")).apply { mkdirs() }.toPath(), "consumer-").toFile()
    private val home = work.resolve("home").apply { mkdirs() }
    private val cache = work.resolve("gradle-home").apply { mkdirs() }
    private val unavailable = "0.0.0-functional-unavailable"
    private val header = "// Copyright (c) Cratis. All rights reserved.\n" +
        "// Licensed under the MIT license. See LICENSE file in the project root for full license information.\n\n"

    @Test
    fun `default dependency version resolves real GAVs and incremental generation removes stale artifacts`() {
        val consumer = fixture("default")
        assertFalse(consumer.resolve("build").exists())
        verifyGenerated(consumer, run(consumer), false)
        val coldArtifacts = generatedArtifacts(consumer)
        write(consumer, "src/main/kotlin/fixture/Extra.kt", """
            package fixture
            @io.cratis.arc.artifacts.Command
            public data class Extra(public val value: String) { public fun handle(): String = value }
        """.trimIndent())
        verifyGenerated(consumer, run(consumer), true, incremental = true)
        assertTrue(consumer.resolve("src/main/kotlin/fixture/Extra.kt").delete())
        // Deliberately no clean or forced rerun between builds.
        verifyGenerated(consumer, run(consumer), false, incremental = true)
        assertEquals(coldArtifacts, generatedArtifacts(consumer), "Removal must restore metadata, module, handlers, and proxies")
        assertFalse(consumer.resolve("build/generated/proxies/Extra.ts").exists())
        assertFalse(consumer.resolve("build/generated/ksp/main/kotlin").walkTopDown()
            .any { it.isFile && it.readText().contains("fixture.Extra") })
    }

    @Test
    fun `JVM proxies are compared against reproducibly captured Arc dotNET output`() {
        val captured = File(property("capturedDifferential"))
        val consumer = fixture("dotnet-capture-differential")
        // Remove only this newly created test consumer's seeded sources, before producing any output.
        assertTrue(consumer.resolve("src/main/kotlin/fixture").deleteRecursively())
        assertTrue(consumer.resolve("src/main/java/fixture").deleteRecursively())
        // The capture runs the .NET generator with segmentsToSkip 0; align the JVM side so routes and output
        // directories are compared like for like rather than differing by harness configuration.
        consumer.resolve("build.gradle").appendText(
            "\ncratisArc { endpoints { segmentsToSkip.set(0) }\nproxies { segmentsToSkip.set(0) } }\n"
        )
        write(consumer, "src/main/kotlin/arc/kotlin/differential/fixture/Fixture.kt", """
            package arc.kotlin.differential.fixture

            import io.cratis.arc.artifacts.Command
            import io.cratis.arc.artifacts.ReadModel
            import java.time.LocalDate
            import java.time.LocalTime
            import java.time.OffsetDateTime
            import java.util.UUID

            public enum class OrderKind { Standard, Express }

            public data class Address(public val street: String, public val city: String)

            @Command
            public data class PlaceOrder(
                public val id: UUID,
                public val customer: String,
                public val quantity: Int,
                public val express: Boolean,
                public val requestedDate: LocalDate,
                public val requestedTime: LocalTime,
                public val placedAt: OffsetDateTime,
                public val kind: OrderKind,
                public val shipTo: Address,
                public val tags: List<String>
            ) {
                public fun handle(): UUID = id
            }

            @ReadModel
            public data class OrderView(
                public val id: UUID,
                public val customer: String,
                public val kind: OrderKind,
                public val requestedDate: LocalDate,
                public val requestedTime: LocalTime,
                public val shipTo: Address
            ) {
                public companion object {
                    @JvmStatic public fun allOrders(): List<OrderView> = emptyList()
                    @JvmStatic public fun orderById(id: UUID): OrderView =
                        OrderView(id, "", OrderKind.Standard, LocalDate.of(1, 1, 1), LocalTime.MIDNIGHT, Address("", ""))
                }
            }
        """.trimIndent())

        val generation = runner(consumer, "generateArcProxies").build()
        assertEquals(TaskOutcome.SUCCESS, generation.task(":generateArcProxies")?.outcome, generation.output)

        CapturedProxyContract.verify(captured, consumer.resolve("build/generated/proxies"))
    }

    @Test
    fun `configured type and package mappings import external types instead of generating them`() {
        val consumer = fixture(
            "external-mappings",
            options = """
                proxies {
                    mapType('java.time.Duration', 'string')
                    mapType('fixture.shared.Money', 'Money', '@acme/models')
                    mapPackage('fixture.external', '@acme/external')
                }
            """.trimIndent()
        )
        write(consumer, "src/main/kotlin/fixture/mappings/Mappings.kt", """
            package fixture.mappings
            import fixture.external.Region
            import fixture.shared.Money
            @io.cratis.arc.artifacts.Command
            public data class Pay(
                public val total: Money,
                public val region: Region,
                public val elapsed: java.time.Duration
            ) {
                public fun handle(): String = total.amount.toString()
            }
        """.trimIndent())
        write(consumer, "src/main/kotlin/fixture/shared/Money.kt", """
            package fixture.shared
            public data class Money(public val amount: Long)
        """.trimIndent())
        write(consumer, "src/main/kotlin/fixture/external/Region.kt", """
            package fixture.external
            public data class Region(public val code: String)
        """.trimIndent())

        val generation = runner(consumer, "generateArcProxies").build()
        assertEquals(TaskOutcome.SUCCESS, generation.task(":generateArcProxies")?.outcome, generation.output)

        val command = consumer.resolve("build/generated/proxies/mappings/Pay.ts").readText()
        assertTrue(command.contains("import { Money } from '@acme/models';"), command)
        assertTrue(command.contains("import { Region } from '@acme/external';"), command)
        assertTrue(command.contains("elapsed?: string;"), command)
        assertFalse(command.contains("from './Money'"), command)
        assertFalse(command.contains("from '../shared/Money'"), command)
        // A mapped type is answered by configuration, so it must not also be generated.
        assertFalse(consumer.resolve("build/generated/proxies/shared/Money.ts").exists())
        assertFalse(consumer.resolve("build/generated/proxies/external/Region.ts").exists())
    }

    @Test
    fun `unusable proxy mappings are reported as warnings rather than dropped silently`() {
        val consumer = fixture(
            "unusable-mappings",
            options = "proxies { typeMappings.add('fixture.Broken='); packageMappings.put('fixture.Empty', '  ') }"
        )
        write(consumer, "src/main/kotlin/fixture/warn/Warn.kt", """
            package fixture.warn
            @io.cratis.arc.artifacts.Command
            public data class Warn(public val value: String) {
                public fun handle(): String = value
            }
        """.trimIndent())

        val generation = runner(consumer, "generateArcProxies").build()

        assertEquals(TaskOutcome.SUCCESS, generation.task(":generateArcProxies")?.outcome, generation.output)
        assertTrue(generation.output.contains("Ignoring unusable Arc proxy type mapping 'fixture.Broken='"), generation.output)
        // The entry is quoted verbatim, so the whitespace-only value stays visible in the warning.
        assertTrue(
            generation.output.contains("Ignoring unusable Arc proxy package mapping 'fixture.Empty="),
            generation.output
        )
        assertTrue(consumer.resolve("build/generated/proxies/warn/Warn.ts").exists())
    }

    @Test
    fun `native Kotlin and Java Map suffix models flow from KSP through discovery to ordinary proxies`() {
        val consumer = fixture("map-models")
        write(consumer, "src/main/kotlin/fixture/kotlinmaps/HeatMap.kt", """
            package fixture.kotlinmaps
            @io.cratis.arc.artifacts.ReadModel
            public data class HeatMap(public val value: String) {
                public companion object {
                    @JvmStatic public fun hottest(): HeatMap = HeatMap("query-kotlin")
                }
            }
            @io.cratis.arc.artifacts.Command
            public data class SaveHeatMap(public val map: HeatMap) {
                public fun handle(): String = map.value
            }
        """.trimIndent())
        write(consumer, "src/main/java/fixture/javamaps/RoadMap.java", """
            package fixture.javamaps;
            @io.cratis.arc.artifacts.ReadModel
            public record RoadMap(String value) {
                public static RoadMap shortest() { return new RoadMap("query-java"); }
            }
        """.trimIndent())
        write(consumer, "src/main/java/fixture/javamaps/SaveRoadMap.java", """
            package fixture.javamaps;
            @io.cratis.arc.artifacts.Command
            public record SaveRoadMap(RoadMap map) {
                public String handle() { return map.value(); }
            }
        """.trimIndent())
        write(consumer, "src/main/java/fixture/MapModelProbe.java", mapModelProbe())
        consumer.resolve("build.gradle").appendText("""

            tasks.register('mapModelProbe', JavaExec) {
                dependsOn('classes')
                classpath = sourceSets.main.runtimeClasspath
                mainClass.set('fixture.MapModelProbe')
            }
        """.trimIndent())
        // Prove generated Java/Kotlin invocations before rendering, so a renderer RED is not a compilation failure.
        val invocation = runner(consumer, "mapModelProbe").build()
        for (task in listOf("kspKotlin", "compileKotlin", "compileJava", "mapModelProbe")) {
            assertEquals(TaskOutcome.SUCCESS, invocation.task(":$task")?.outcome, invocation.output)
        }
        assertTrue(invocation.output.contains("MAP_MODEL_INVOCATIONS_VERIFIED"), invocation.output)
        val discovered = ArcManifestDiscovery.discover(listOf(consumer.resolve("build/resources/main")))
        assertEquals(listOf("Consumer"), discovered.map { it.manifest.moduleName })
        val artifacts = ArcManifestDiscovery.merge(discovered)
        for ((packageName, modelName, queryName) in listOf(
            Triple("kotlinmaps", "HeatMap", "hottest"), Triple("javamaps", "RoadMap", "shortest")
        )) {
            val modelType = "fixture.$packageName.$modelName"
            assertTrue(artifacts.types.any { it.fullyQualifiedName == modelType })
            val property = artifacts.commands.single { it.name == "Save$modelName" }.properties.single()
            assertEquals(io.cratis.arc.metadata.TypeShapeKind.VALUE, property.shape.kind)
            assertEquals(modelType, property.typeName)
            val query = artifacts.queries.single { it.name == queryName }
            assertEquals(io.cratis.arc.metadata.TypeShapeKind.VALUE, query.returnShape.kind)
            assertEquals(modelType, query.returnTypeName)
        }

        val generation = runner(consumer, "generateArcProxies").build()
        assertEquals(TaskOutcome.SUCCESS, generation.task(":generateArcProxies")?.outcome, generation.output)
        for ((packageName, modelName, queryName) in listOf(
            Triple("kotlinmaps", "HeatMap", "Hottest"), Triple("javamaps", "RoadMap", "Shortest")
        )) {
            val proxies = consumer.resolve("build/generated/proxies/$packageName")
            val model = proxies.resolve("$modelName.ts").readText()
            assertTrue(model.contains("export class $modelName"), model)
            assertTrue(model.contains("value!: string;"), model)
            val command = proxies.resolve("Save$modelName.ts").readText()
            assertTrue(command.contains("import { $modelName } from './$modelName';"), command)
            assertTrue(command.contains("new PropertyDescriptor('map', $modelName, false)"), command)
            val query = proxies.resolve("$queryName.ts").readText()
            assertTrue(query.contains("import { $modelName } from './$modelName';"), query)
            assertTrue(query.contains("extends QueryFor<$modelName>"), query)
            assertTrue(query.contains("super($modelName, false);"), query)
            for (proxy in listOf(model, command, query)) {
                assertFalse(proxy.contains("Record<string,"), proxy)
                assertFalse(proxy.contains("sanitizeArcStringMap"), proxy)
            }
        }
        val firstPass = generatedArtifacts(consumer)
        val secondPass = runner(consumer, "generateArcProxies", "--rerun-tasks").build()
        assertEquals(TaskOutcome.SUCCESS, secondPass.task(":generateArcProxies")?.outcome, secondPass.output)
        assertEquals(firstPass, generatedArtifacts(consumer), "Native manifest, invokers, and Map-suffix proxies must be byte stable")
    }

    @Test
    fun `native body properties survive discovery and incremental add remove and invalid correction`() {
        val consumer = fixture("body-properties")
        val path = "src/main/kotlin/fixture/bodies/BodyInput.kt"
        val source = """
            package fixture.bodies
            @io.cratis.arc.artifacts.Command
            public class BodyInput(public val zulu: String, public val alpha: String) {
                @io.cratis.arc.artifacts.CommandKey public var id: String = "body-key"
                @get:jakarta.validation.constraints.Size(min = 2, max = 30)
                public var title: String = "title"
                    private set
                public val backed: String = "backed"
                @get:com.fasterxml.jackson.annotation.JsonIgnore public val secret: String get() = zulu
                // incremental body
                public fun handle(): String = id + title + backed
            }
            public class JavaBodyModel(public val first: String) {
                public val calculated: String = first
            }
        """.trimIndent()
        write(consumer, path, source)
        val importedPath = "src/main/kotlin/fixture/imported/ImportedBody.kt"
        val importedSource = """
            package fixture.imported
            import com.fasterxml.jackson.annotation.JsonProperty
            public class ImportedBody(public val first: String) {
                @get:JsonProperty(access = JsonProperty.Access.READ_WRITE)
                public var body: String = first
            }
            public class ImportedOutput(public val first: String) {
                @field:JsonProperty(access = JsonProperty.Access.READ_WRITE)
                public var body: String = first
                public val computed: String get() = first + body
            }
        """.trimIndent()
        write(consumer, importedPath, importedSource)
        write(consumer, "src/main/java/fixture/bodies/JavaBodyInput.java", """
            package fixture.bodies;
            import java.time.LocalDate;
            import fixture.imported.ImportedBody;
            import fixture.imported.ImportedOutput;
            @io.cratis.arc.artifacts.Command
            public record JavaBodyInput(JavaBodyModel value, LocalDate date, ImportedBody imported) {
                public ImportedOutput handle() { return new ImportedOutput(imported.getBody()); }
            }
        """.trimIndent())
        consumer.resolve("build.gradle").appendText("""

            tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
                compilerOptions.allWarningsAsErrors.set(true)
            }
            tasks.withType(JavaCompile).configureEach {
                options.compilerArgs.addAll(['-Xlint:all', '-Werror'])
            }
        """.trimIndent())
        val initial = runner(consumer, "generateArcProxies").build()
        assertEquals(TaskOutcome.SUCCESS, initial.task(":generateArcProxies")?.outcome, initial.output)
        fun properties(): List<io.cratis.arc.metadata.PropertyDescriptor> = ArcManifestDiscovery.merge(
            ArcManifestDiscovery.discover(listOf(consumer.resolve("build/resources/main")))
        ).commands.single { it.name == "BodyInput" }.properties
        assertEquals(listOf("zulu", "alpha", "backed", "id", "title"), properties().map { it.name })
        assertTrue(properties().single { it.name == "id" }.isCommandKey)
        assertEquals(listOf("length"), properties().single { it.name == "title" }.validationRules.map { it.ruleName })
        val proxy = consumer.resolve("build/generated/proxies/bodies/BodyInput.ts")
        assertTrue(proxy.readText().contains("new PropertyDescriptor('id', String, false)"))
        assertTrue(proxy.readText().contains("this.ruleFor(c => c.title).length(2, 30);"))
        assertFalse(proxy.readText().contains("secret"))
        val importedManifest = ArcManifestDiscovery.merge(
            ArcManifestDiscovery.discover(listOf(consumer.resolve("build/resources/main")))
        )
        val javaProperties = importedManifest.commands.single { it.name == "JavaBodyInput" }.properties
        assertEquals(listOf("value", "date", "imported"), javaProperties.map { it.name })
        assertEquals("java.time.LocalDate", javaProperties.single { it.name == "date" }.typeName)
        assertEquals("fixture.imported.ImportedBody", javaProperties.single { it.name == "imported" }.typeName)
        assertEquals(listOf("first", "body"), importedManifest.types.single { it.name == "ImportedBody" }.properties.map { it.name })
        assertEquals(listOf("first", "body", "computed"), importedManifest.types.single { it.name == "ImportedOutput" }.properties.map { it.name })
        assertTrue(consumer.resolve("build/generated/proxies/bodies/JavaBodyInput.ts").readText()
            .contains("new PropertyDescriptor('date', DateOnly, false)"))
        assertTrue(consumer.resolve("build/generated/proxies/imported/ImportedBody.ts").readText()
            .contains("@field(String)\n    body!: string;"))
        assertTrue(consumer.resolve("build/generated/proxies/imported/ImportedOutput.ts").readText()
            .contains("@field(String)\n    body!: string;"))
        val first = generatedArtifacts(consumer)
        assertTrue(first.values.any { it.contains("typedCommand.id") }, "Generated key access must use the body member")
        write(consumer, path, source.replace("// incremental body", "public var extra: String = \"extra\""))
        val added = runner(consumer, "generateArcProxies").build()
        assertEquals(TaskOutcome.SUCCESS, added.task(":kspKotlin")?.outcome, added.output)
        assertEquals(listOf("zulu", "alpha", "backed", "extra", "id", "title"), properties().map { it.name })
        assertTrue(proxy.readText().contains("new PropertyDescriptor('extra', String, false)"))
        write(consumer, path, source)
        val removed = runner(consumer, "generateArcProxies").build()
        assertEquals(TaskOutcome.SUCCESS, removed.task(":kspKotlin")?.outcome, removed.output)
        assertEquals(first, generatedArtifacts(consumer), "Removing body state must restore every generated byte")
        val invalid = source.replace("// incremental body", "public val calculated: String get() = zulu")
        write(consumer, path, invalid)
        val failure = runner(consumer, "generateArcProxies").buildAndFail()
        val line = (header + invalid).lineSequence().indexOfFirst { it.contains("public val calculated") } + 1
        assertTrue(failure.output.contains("BodyInput.kt:$line:"), failure.output)
        assertTrue(failure.output.contains("[ARCKSP0300] Artifact/property 'fixture.bodies.BodyInput.calculated' is computed command input; use a backed property, @JsonIgnore, or a separate output model."), failure.output)
        assertFalse(failure.output.contains("[ARCKSP9999]"), failure.output)
        println(failure.output)
        write(consumer, path, source)
        val recovered = runner(consumer, "generateArcProxies").build()
        assertEquals(TaskOutcome.SUCCESS, recovered.task(":kspKotlin")?.outcome, recovered.output)
        assertEquals(first, generatedArtifacts(consumer), "Invalid-to-valid recovery must be deterministic without clean")
        val invalidGraph = source.replace("public val calculated: String = first", "public val calculated: String get() = first")
        write(consumer, path, invalidGraph)
        val graphFailure = runner(consumer, "generateArcProxies").buildAndFail()
        val graphLine = (header + invalidGraph).lineSequence().indexOfFirst { it.contains("public val calculated") } + 1
        assertTrue(graphFailure.output.contains("BodyInput.kt:$graphLine:"), graphFailure.output)
        assertTrue(graphFailure.output.contains("[ARCKSP0300] Artifact/property 'fixture.bodies.JavaBodyModel.calculated' is computed command input; use a backed property, @JsonIgnore, or a separate output model."), graphFailure.output)
        assertFalse(graphFailure.output.contains("[ARCKSP9999]"), graphFailure.output)
        println(graphFailure.output)
        write(consumer, path, source)
        val graphRecovered = runner(consumer, "generateArcProxies").build()
        assertEquals(TaskOutcome.SUCCESS, graphRecovered.task(":kspKotlin")?.outcome, graphRecovered.output)
        assertEquals(first, generatedArtifacts(consumer), "Java-root graph correction must restore every generated byte")
        val invalidImported = importedSource.replaceFirst("public var body: String = first", "public val body: String get() = first")
        write(consumer, importedPath, invalidImported)
        val importedFailure = runner(consumer, "generateArcProxies").buildAndFail()
        val importedLine = (header + invalidImported).lineSequence().indexOfFirst { it.contains("public val body") } + 1
        assertTrue(importedFailure.output.contains("ImportedBody.kt:$importedLine:"), importedFailure.output)
        assertTrue(importedFailure.output.contains("[ARCKSP0300] Artifact/property 'fixture.imported.ImportedBody.body' is computed command input; use a backed property, @JsonIgnore, or a separate output model."), importedFailure.output)
        assertFalse(importedFailure.output.contains("unresolvable Java type"), importedFailure.output)
        assertFalse(importedFailure.output.contains("[ARCKSP9999]"), importedFailure.output)
        println(importedFailure.output)
        write(consumer, importedPath, importedSource)
        val importedRecovered = runner(consumer, "generateArcProxies").build()
        assertEquals(TaskOutcome.SUCCESS, importedRecovered.task(":kspKotlin")?.outcome, importedRecovered.output)
        assertEquals(first, generatedArtifacts(consumer), "Imported child correction must restore every generated byte")
    }

    @Test
    fun `native nullable sequence correction preserves locations discovery and deterministic recovered artifacts`() {
        val consumer = fixture("nullable-sequences")
        val kotlinPath = "src/main/kotlin/fixture/sequences/Sequences.kt"
        val javaPath = "src/main/java/fixture/sequences/JavaSequences.java"
        val kotlin = """
            package fixture.sequences
            public interface SequenceView { public val values: Collection<String>? }
            public data class SequenceModel(public val values: Array<String>?)
            @io.cratis.arc.artifacts.ReadModel
            public data class SequenceReadModel(public val values: List<String>?) {
                public companion object { @JvmStatic public fun findSequences(): SequenceReadModel = SequenceReadModel(null) }
            }
            @io.cratis.arc.artifacts.Command
            public data class Sequences(
                public val list: List<String>?,
                public val collection: Collection<String>?,
                public val array: Array<String>?,
                public val view: SequenceView,
                public val model: SequenceModel
            ) { public fun handle() { } }
        """.trimIndent()
        val java = """
            package fixture.sequences;
            @io.cratis.arc.artifacts.Command
            public record JavaSequences(java.util.List<String> values) { public void handle() { } }
        """.trimIndent()
        write(consumer, kotlinPath, kotlin)
        write(consumer, javaPath, java)
        write(consumer, "src/main/java/fixture/sequences/Nullable.java", """
            package fixture.sequences;
            @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
            public @interface Nullable { }
        """.trimIndent())
        consumer.resolve("build.gradle").appendText("""

            tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
                compilerOptions.allWarningsAsErrors.set(true)
            }
            tasks.withType(JavaCompile).configureEach {
                options.compilerArgs.addAll(['-Xlint:all', '-Werror'])
            }
        """.trimIndent())
        val initial = runner(consumer, "generateArcProxies").build()
        assertEquals(TaskOutcome.SUCCESS, initial.task(":generateArcProxies")?.outcome, initial.output)
        val discovered = ArcManifestDiscovery.discover(listOf(consumer.resolve("build/resources/main")))
        assertEquals(listOf("Consumer"), discovered.map { it.manifest.moduleName })
        val artifacts = ArcManifestDiscovery.merge(discovered)
        val command = artifacts.commands.single { it.name == "Sequences" }
        for ((name, kind) in listOf("list" to io.cratis.arc.metadata.SequenceKind.LIST,
            "collection" to io.cratis.arc.metadata.SequenceKind.COLLECTION, "array" to io.cratis.arc.metadata.SequenceKind.ARRAY)) {
            val shape = command.properties.single { it.name == name }.shape
            assertEquals(kind, shape.sequenceKind)
            assertTrue(shape.nullable)
            assertEquals(false, shape.elementShape?.nullable)
        }
        val javaShape = artifacts.commands.single { it.name == "JavaSequences" }.properties.single().shape
        assertEquals(io.cratis.arc.metadata.SequenceKind.LIST, javaShape.sequenceKind)
        assertEquals(false, javaShape.elementShape?.nullable)
        for (name in listOf("SequenceModel", "SequenceReadModel")) {
            val shape = artifacts.types.single { it.name == name }.properties.single().shape
            assertTrue(shape.nullable)
            assertEquals(false, shape.elementShape?.nullable)
        }
        val view = artifacts.interfaces.single { it.name == "SequenceView" }.properties.single().shape
        assertTrue(view.nullable)
        assertEquals(false, view.elementShape?.nullable)
        for (name in listOf("SequenceModel", "SequenceReadModel", "SequenceView")) {
            val text = consumer.resolve("build/generated/proxies/sequences/$name.ts").readText()
            assertTrue(text.contains("values?: string[];"), text)
            assertFalse(text.contains("(string | null)[]"), text)
        }
        val firstPass = generatedArtifacts(consumer)
        for ((path, valid, invalid, member) in listOf(
            listOf(kotlinPath, kotlin, kotlin.replace("List<String>?", "List<String?>?"), "SequenceReadModel.values"),
            listOf(javaPath, java, java.replace("List<String>", "List<@Nullable String>"), "JavaSequences.values")
        )) {
            write(consumer, path, invalid)
            val failure = runner(consumer, "generateArcProxies").buildAndFail()
            assertEquals(TaskOutcome.FAILED, failure.task(":kspKotlin")?.outcome, failure.output)
            val diagnostic = "[ARCKSP0300] Artifact/property 'fixture.sequences.$member' value path 'value[]': " +
                "nullable sequence elements are unsupported; declare nonnullable elements, for example List<T> or List<T>?."
            assertTrue(failure.output.contains(diagnostic), failure.output)
            val file = consumer.resolve(path)
            val line = file.readLines().indexOfFirst { it.contains(if (path == kotlinPath) "data class SequenceReadModel" else "record JavaSequences") } + 1
            assertTrue(failure.output.contains("${file.name}:$line:"), failure.output)
            assertFalse(failure.output.contains("[ARCKSP9999]"), failure.output)
            assertFalse(failure.output.contains("Unexpected exception"), failure.output)
            assertFalse(failure.output.contains("IllegalStateException"), failure.output)
            write(consumer, path, valid)
            val recovered = runner(consumer, "generateArcProxies").build()
            assertEquals(TaskOutcome.SUCCESS, recovered.task(":kspKotlin")?.outcome, recovered.output)
            // Identical recovered bytes let Gradle correctly reuse the last successful proxy generation.
            assertTrue(recovered.task(":generateArcProxies")?.outcome in listOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE), recovered.output)
            assertEquals(firstPass, generatedArtifacts(consumer), "Correction without clean must restore identical artifacts")
            assertEquals(discovered.single().manifest.moduleName,
                ArcManifestDiscovery.discover(listOf(consumer.resolve("build/resources/main"))).single().manifest.moduleName)
        }
        println("NULLABLE_SEQUENCE_NATIVE_PROXIES ${consumer.resolve("build/generated/proxies").absolutePath}")
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3])
    fun `native implicit Kotlin visibility preserves explicit identity invocations metadata and proxy bytes`(variant: Int) {
        var expected: Map<String, String>? = null
        val variants = listOf("public " to "public ", "" to "public ", "public " to "", "" to "")
        for (index in listOf(0, variant)) {
            val visibility = variants[index]
            val (classes, members) = visibility
            val consumer = fixture("visibility-$variant-$index")
            write(consumer, "src/main/kotlin/fixture/Fixtures.kt", """
                package fixture
                ${classes}interface Named { ${members}val value: String }
                ${classes}class Child(${members}val first: String) { ${members}var last: String = "last" }
                @io.cratis.arc.artifacts.Command
                ${classes}class Input(${members}override val value: String) : Named {
                    @io.cratis.arc.artifacts.CommandKey ${members}var id: String = "key"
                    @get:jakarta.validation.constraints.Size(min = 2, max = 30)
                    ${members}var title: String = "title"
                        private set
                    @field:jakarta.validation.Valid ${members}var child: Child = Child("first")
                    private val secret: String = "secret"
                    internal val internalState: String = "internal"
                    protected val protectedState: String = "protected"
                    ${members}fun provide(): String = value + title
                    ${members}fun handle(provided: String): String = provided + id + child.last
                }
                @io.cratis.arc.artifacts.ReadModel
                ${classes}data class View(${members}override val value: String) : Named {
                    ${members}companion object {
                        @JvmStatic ${members}fun all(): List<View> = listOf(View("value"))
                        private fun helper(): String = "unrelated"
                    }
                }
                @io.cratis.arc.artifacts.ReadModel
                ${classes}class NoQueries(${members}val value: String) {
                    private companion object { fun helper(): String = "unrelated" }
                }
            """.trimIndent())
            write(consumer, "src/main/java/fixture/VisibilityProbe.java", """
                package fixture;
                import io.cratis.arc.artifacts.ArcArtifactModule;
                import io.cratis.arc.authorization.ArcPrincipal;
                import io.cratis.arc.commands.CommandExecutionOptions;
                import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry;
                import io.cratis.arc.commands.DefaultCommandPipeline;
                import io.cratis.arc.commands.ServiceResolver;
                import io.cratis.arc.java.JavaAsyncScope;
                import java.util.ServiceLoader;
                import java.util.UUID;
                import java.util.concurrent.Executors;
                import java.util.concurrent.TimeUnit;
                public final class VisibilityProbe {
                    public static void main(String[] args) throws Exception {
                        var module = ServiceLoader.load(ArcArtifactModule.class).iterator().next();
                        var handler = module.getCommandHandlers().stream().filter(h -> h.getCommandType().equals(Input.class)).findFirst().orElseThrow();
                        var input = new Input("kotlin");
                        input.setId("supplied");
                        if (!"supplied".equals(handler.resolveCommandKey(input))) throw new AssertionError("Implicit key access");
                        var registry = new ConcurrentCommandHandlerRegistry();
                        registry.register(handler);
                        ServiceResolver services = new ServiceResolver() {
                            @Override public <T> T resolve(Class<T> type) { return null; }
                        };
                        var executor = Executors.newSingleThreadExecutor();
                        try (var scope = JavaAsyncScope.owningExecutorService(executor)) {
                            var result = scope.commands(new DefaultCommandPipeline(registry)).execute(input,
                                new CommandExecutionOptions(UUID.randomUUID(), new ArcPrincipal(), services))
                                .toCompletableFuture().get(5, TimeUnit.SECONDS);
                            if (!result.isSuccess() || !"kotlintitlesuppliedlast".equals(result.getResponse())) {
                                throw new AssertionError("Implicit provide and handle response: " + result.getResponse());
                            }
                        } finally {
                            executor.shutdownNow();
                            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("Executor cleanup");
                        }
                        System.out.println("VISIBILITY_INVOCATIONS_VERIFIED");
                    }
                }
            """.trimIndent())
            consumer.resolve("build.gradle").appendText("""

                tasks.register('visibilityProbe', JavaExec) {
                    dependsOn('classes')
                    classpath = sourceSets.main.runtimeClasspath
                    mainClass.set('fixture.VisibilityProbe')
                }
            """.trimIndent())
            // No explicit-api mode or visibility rewriting: these are ordinary consumer compilations.
            val result = visibilityRunner(consumer, "generateArcProxies", "runtimeProbe", "visibilityProbe").build()
            assertTrue(result.output.contains("VISIBILITY_INVOCATIONS_VERIFIED"), result.output)
            verifyGenerated(consumer, result, false)
            val artifacts = ArcManifestDiscovery.merge(ArcManifestDiscovery.discover(listOf(consumer.resolve("build/resources/main"))))
            val properties = artifacts.commands.single { it.name == "Input" }.properties
            assertEquals(listOf("value", "child", "id", "title"), properties.map { it.name })
            assertTrue(properties.single { it.name == "id" }.isCommandKey)
            assertTrue(properties.single { it.name == "child" }.validateRecursively)
            assertEquals(listOf("length"), properties.single { it.name == "title" }.validationRules.map { it.ruleName })
            assertEquals(listOf("first", "last"), artifacts.types.single { it.name == "Child" }.properties.map { it.name })
            assertEquals(listOf("value"), artifacts.interfaces.single().properties.map { it.name })
            val proxy = consumer.resolve("build/generated/proxies/Input.ts").readText()
            assertTrue(proxy.contains("new PropertyDescriptor('id', String, false)"), proxy)
            assertTrue(proxy.contains("this.ruleFor(c => c.title).length(2, 30);"), proxy)
            assertFalse(proxy.contains("secret"), proxy)
            assertFalse(proxy.contains("internalState"), proxy)
            val generated = generatedArtifacts(consumer)
            assertTrue(generated.values.any { it.contains("typedCommand.id") })
            if (expected == null) expected = generated else assertEquals(expected, generated,
                "Explicit and implicit same-identity consumers must produce identical invocation, metadata, manifest and proxy bytes")
        }
    }

    @Test
    fun `native nonpublic Kotlin and Java controls fail at source without aggregate artifacts`() {
        val kotlin = buildList {
            for (visibility in listOf("private", "internal")) {
                add(Triple("@io.cratis.arc.artifacts.Command $visibility class Bad { fun handle() {} }", "ARCKSP0101", "Command 'fixture.Bad' must be public"))
                add(Triple("@io.cratis.arc.artifacts.ReadModel $visibility class Bad", "ARCKSP0200", "Read model 'fixture.Bad' must be public"))
                add(Triple("@io.cratis.arc.artifacts.ReadModel class Bad { $visibility companion object { fun all(): Bad = Bad() } }", "ARCKSP0201", "Query 'fixture.Bad.all' must be declared in a public companion object"))
            }
            for (visibility in listOf("private", "internal", "protected")) {
                add(Triple("@io.cratis.arc.artifacts.Command class Bad { $visibility fun handle() {} }", "ARCKSP0102", "Handler 'fixture.Bad.handle' must be public"))
                add(Triple("@io.cratis.arc.artifacts.Command class Bad { $visibility fun provide(): String = \"v\"; fun handle(value: String) {} }", "ARCKSP0103", "Provide method 'fixture.Bad.provide' must be public"))
                add(Triple("@io.cratis.arc.artifacts.ReadModel class Bad { companion object { $visibility fun all(): Bad = Bad() } }", "ARCKSP0201", "Query 'fixture.Bad.all' must be public"))
            }
            add(Triple("class Outer { @io.cratis.arc.artifacts.Command class Bad { fun handle() {} } }", "ARCKSP0101", "nested commands are not supported"))
            add(Triple("class Outer { @io.cratis.arc.artifacts.ReadModel class Bad }", "ARCKSP0200", "nested read models are not supported"))
            add(Triple("class Outer { class Child(val value: String) }; @io.cratis.arc.artifacts.Command class Bad(val child: Outer.Child) { fun handle() {} }", "ARCKSP0300", "nested"))
            add(Triple("@io.cratis.arc.artifacts.Command class Bad(val value: String) { val computed: String get() = value; fun handle() {} }", "ARCKSP0300", "Artifact/property 'fixture.Bad.computed' is computed command input"))
        }
        val java = buildList {
            add(Triple("@io.cratis.arc.artifacts.Command class Bad { public void handle() {} }", "ARCKSP0101", "Command 'fixture.Bad' must be public"))
            add(Triple("@io.cratis.arc.artifacts.ReadModel class Bad { public static Bad all() { return new Bad(); } }", "ARCKSP0200", "Read model 'fixture.Bad' must be public"))
            for (visibility in listOf("", "private", "protected")) {
                add(Triple("@io.cratis.arc.artifacts.Command public class Bad { $visibility void handle() {} }", "ARCKSP0102", "Handler 'fixture.Bad.handle' must be public"))
                add(Triple("@io.cratis.arc.artifacts.Command public class Bad { $visibility String provide() { return \"v\"; } public void handle(String value) {} }", "ARCKSP0103", "Provide method 'fixture.Bad.provide' must be public"))
                add(Triple("@io.cratis.arc.artifacts.ReadModel public class Bad { $visibility static Bad all() { return new Bad(); } }", "ARCKSP0201", "Java query 'fixture.Bad.all' must be public"))
            }
        }
        for ((language, cases) in listOf("kotlin" to kotlin, "java" to java)) {
            for ((index, case) in cases.withIndex()) {
                val (source, code, message) = case
                val consumer = fixture("visibility-negative-$language-$index")
                val fileName = if (language == "kotlin") "Bad.kt" else "Bad.java"
                write(consumer, "src/main/$language/fixture/$fileName", "package fixture;\n$source")
                val result = visibilityRunner(consumer, "kspKotlin").buildAndFail()
                assertEquals(TaskOutcome.FAILED, result.task(":kspKotlin")?.outcome, result.output)
                assertTrue(result.output.contains("[$code]"), result.output)
                assertTrue(result.output.contains(message), result.output)
                assertTrue(result.output.contains("$fileName:5:"), result.output)
                assertFalse(result.output.contains("[ARCKSP9999]"), result.output)
                assertFalse(result.output.contains("Unexpected exception"), result.output)
                val generated = consumer.resolve("build/generated/ksp/main").walkTopDown().filter(File::isFile).toList()
                assertFalse(generated.any { it.extension == "json" || it.name.contains("ArcArtifactModule") || it.name.contains("ArcArtifactMetadata") })
            }
        }
    }

    private fun visibilityRunner(consumer: File, vararg tasks: String): GradleRunner = runner(consumer, *tasks)
        .withEnvironment(System.getenv())
        .withArguments(*tasks, "--max-workers=2", "--no-configuration-cache", "--console=plain", "--stacktrace",
            "--gradle-user-home", requireNotNull(System.getProperty("arc.onboarding.gradleUserHome")))

    @Test
    fun `explicit dependency version resolves actual fixture version`() {
        val consumer = fixture("explicit", "dependencyVersion.set('$unavailable'); dependencyVersion.set('$version')")
        verifyGenerated(consumer, run(consumer), false)
    }

    @Test
    fun `unavailable explicit dependency version fails resolution without fallback`() {
        val consumer = fixture("unavailable", "dependencyVersion.set('$unavailable')", expectedVersion = unavailable)
        val result = runner(consumer, "verifyDependencyResolution").buildAndFail()
        assertTrue(result.output.contains("DECLARATIONS_VERIFIED $unavailable"), result.output)
        assertTrue(result.output.contains("Could not find io.cratis:arc:$unavailable"), result.output)
        assertTrue(result.output.contains("Could not find io.cratis:arc-ksp:$unavailable"), result.output)
        assertFalse(consumer.resolve("build/generated/ksp/main/resources/META-INF/cratis/arc/Consumer.json").exists())
    }

    @Test
    fun `explicit consumer coordinates are preserved despite unavailable managed default`() {
        val consumer = fixture("preserved", "dependencyVersion.set('$unavailable')", """
            dependencies {
                implementation 'io.cratis:arc:$version'
                ksp 'io.cratis:arc-ksp:$version'
            }
        """.trimIndent(), runtimeScope = "implementation", suppliedProcessor = true)
        verifyGenerated(consumer, run(consumer), false)
    }

    @Test
    fun `pre-applied Kotlin and KSP use managed processor with final DSL values`() {
        val consumer = fixture("pre-applied", "dependencyVersion.set('$unavailable'); dependencyVersion.set('$version')",
            preApplied = "id 'org.jetbrains.kotlin.jvm'; id 'com.google.devtools.ksp'")
        verifyGenerated(consumer, run(consumer), false)
    }

    @Test
    fun `dependency management opt out adds no Arc declarations or resolved dependencies`() {
        val consumer = fixture("opt-out", "manageDependencies.set(false); dependencyVersion.set('')", managed = false)
        val result = runner(consumer, "verifyDependencyResolution").build()
        assertTrue(result.output.contains("RESOLVED_DEPENDENCIES_VERIFIED $version"), result.output)
    }

    @Test
    fun `supplied implementation runtime is preserved`() = verifySuppliedScope("implementation")

    @Test
    fun `supplied api runtime is preserved`() = verifySuppliedScope("api")

    @Test
    fun `supplied compileOnly runtime is preserved without runtime promotion`() = verifySuppliedScope("compileOnly")

    @Test
    fun `supplied runtimeOnly runtime is preserved without compile promotion`() = verifySuppliedScope("runtimeOnly")

    @Test
    fun `blank module name remains invalid`() = verifyInvalid("moduleName.set('')", "cratisArc.moduleName cannot be blank.")

    @Test
    fun `blank managed version remains invalid`() =
        verifyInvalid("dependencyVersion.set('')", "cratisArc.dependencyVersion cannot be blank.")

    @Test
    fun `negative endpoint and proxy options remain invalid`() {
        verifyInvalid("endpoints { segmentsToSkip.set(-1) }", "cratisArc.endpoints.segmentsToSkip cannot be negative.")
        verifyInvalid("proxies { segmentsToSkip.set(-1) }", "cratisArc.proxies.segmentsToSkip cannot be negative.")
    }

    private fun verifyInvalid(options: String, message: String) {
        val consumer = fixture("invalid-${message.substringBefore(" cannot")}")
        consumer.resolve("build.gradle").appendText("\ncratisArc { $options }\n")
        val result = runner(consumer, "help").buildAndFail()
        assertTrue(result.output.contains(message), result.output)
    }

    private fun verifySuppliedScope(scope: String) {
        val consumer = fixture("supplied-$scope", "dependencyVersion.set('$unavailable')", """
            dependencies {
                $scope 'io.cratis:arc:$version'
                ksp 'io.cratis:arc-ksp:$version'
            }
        """.trimIndent(), runtimeScope = scope, suppliedProcessor = true, preApplied = "id 'java-library'")
        val result = runner(consumer, "verifyDependencyResolution").build()
        assertTrue(result.output.contains("RESOLVED_DEPENDENCIES_VERIFIED $version"), result.output)
    }

    private fun fixture(
        name: String,
        options: String = "",
        dependencies: String = "",
        expectedVersion: String = version,
        runtimeScope: String? = null,
        suppliedProcessor: Boolean = false,
        managed: Boolean = true,
        preApplied: String = ""
    ): File {
        assertTrue(pluginJar.isFile && pluginJar.extension == "jar")
        assertTrue(pluginClasspath.contains(pluginJar))
        assertTrue(pluginClasspath.all { it.isFile && it.extension == "jar" }, pluginClasspath.toString())
        assertFalse(pluginClasspath.any { it.name.contains("junit", true) || it.name.contains("kct", true) })
        JarFile(pluginJar).use { assertEquals(version, it.manifest.mainAttributes.getValue("Implementation-Version")) }
        for (artifact in listOf("arc", "arc-ksp")) {
            val gav = repository.resolve("io/cratis/$artifact/$version/$artifact-$version")
            assertTrue(File("$gav.jar").isFile)
            val pom = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("$gav.pom"))
            val root = pom.documentElement
            fun coordinate(name: String): String = (0 until root.childNodes.length)
                .map { root.childNodes.item(it) }.single { it.nodeName == name }.textContent
            assertEquals("io.cratis", coordinate("groupId"))
            assertEquals(artifact, coordinate("artifactId"))
            assertEquals(version, coordinate("version"))
        }
        val consumer = work.resolve(name).apply { mkdirs() }
        write(consumer, "settings.gradle", "rootProject.name = 'consumer'")
        consumer.resolve("gradle.properties").writeText(
            "org.gradle.jvmargs=-Xmx512m -Duser.home=${home.absolutePath}\n" +
                "org.gradle.daemon=false\norg.gradle.daemon.idletimeout=1000\n" +
                "kotlin.compiler.execution.strategy=in-process\n"
        )
        write(consumer, "build.gradle", """
            plugins { $preApplied; id 'io.cratis.arc' }
            repositories {
                exclusiveContent {
                    forRepository {
                        maven {
                            url = uri('${repository.toURI()}')
                            metadataSources { mavenPom(); artifact() }
                        }
                    }
                    filter { includeModule('io.cratis', 'arc'); includeModule('io.cratis', 'arc-ksp') }
                }
                mavenCentral()
            }
            cratisArc {
                moduleName.set('Consumer')
                $options
                endpoints { segmentsToSkip.set(1) }
                proxies { outputDirectory.set(layout.buildDirectory.dir('generated/proxies')); segmentsToSkip.set(1) }
            }
            $dependencies
            configurations.create('processorResolution') {
                canBeConsumed = false
                canBeResolved = true
                extendsFrom(configurations.ksp)
            }
            tasks.register('verifyDependencyResolution') {
                doLast {
                    assert cratisArc.manageDependencies.get() == $managed
                    def origin = new File(io.cratis.arc.gradle.ArcGradlePlugin.protectionDomain.codeSource.location.toURI())
                    assert origin.isFile() && origin.name.endsWith('.jar')
                    assert io.cratis.arc.gradle.ArcGradlePlugin.package.implementationVersion == '$version'
                    new java.util.jar.JarFile(origin).withCloseable { jar ->
                        assert jar.manifest.mainAttributes.getValue('Implementation-Version') == '$version'
                    }
                    println "PLUGIN_ORIGIN_JAR " + origin
                    def declarations = configurations.collectMany { configuration ->
                        configuration.dependencies.findAll { it.group == 'io.cratis' && it.name in ['arc', 'arc-ksp'] }
                            .collect { configuration.name + ':' + it.group + ':' + it.name + ':' + it.version }
                    }.sort()
                    println 'ALL_ARC_DECLARATIONS ' + declarations
                    assert declarations == ${if (managed) "['${runtimeScope ?: "arcManagedRuntime"}:io.cratis:arc:$expectedVersion', '${if (suppliedProcessor) "ksp" else "arcManagedProcessor"}:io.cratis:arc-ksp:$expectedVersion'].sort()" else "[]"}
                    assert configurations.implementation.allDependencies.count { it.group == 'io.cratis' && it.name == 'arc' } == ${if (managed && runtimeScope !in listOf("compileOnly", "runtimeOnly")) 1 else 0}
                    assert configurations.ksp.allDependencies.count { it.group == 'io.cratis' && it.name == 'arc-ksp' } == ${if (managed) 1 else 0}
                    println 'DECLARATIONS_VERIFIED $expectedVersion'
                    def artifacts = [configurations.compileClasspath, configurations.runtimeClasspath, configurations.processorResolution].collect { configuration ->
                        configuration.incoming.artifactView { lenient = true }.artifacts
                    }
                    def failures = artifacts.collectMany { it.failures }
                    assert failures.empty : failures.collect { it.message }.join('\n')
                    def coordinates = artifacts.collect { collection ->
                        collection.artifacts.collect { it.id.componentIdentifier }
                            .findAll { it instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier && it.group == 'io.cratis' }
                            .collect { it.group + ':' + it.module + ':' + it.version }.sort()
                    }
                    println 'RESOLVED_ARC_COORDINATES ' + coordinates
                    assert coordinates == [${if (managed && runtimeScope != "runtimeOnly") "['io.cratis:arc:$expectedVersion']" else "[]"},
                        ${if (managed && runtimeScope != "compileOnly") "['io.cratis:arc:$expectedVersion']" else "[]"},
                        ${if (managed) "['io.cratis:arc-ksp:$expectedVersion', 'io.cratis:arc:$expectedVersion']" else "[]"}]
                    println 'RESOLVED_DEPENDENCIES_VERIFIED $expectedVersion'
                }
            }
            tasks.matching { it.name == 'kspKotlin' }.configureEach {
                dependsOn('verifyDependencyResolution')
                doFirst {
                    def expectedProcessor = configurations.processorResolution.resolvedConfiguration.resolvedArtifacts
                        .find { it.moduleVersion.id.group == 'io.cratis' && it.name == 'arc-ksp' }.file
                    println 'ACTUAL_KSP_PROCESSOR_CLASSPATH ' + kspConfig.processorClasspath.files
                    assert kspConfig.processorClasspath.files.contains(expectedProcessor) :
                        'Actual KSP task must use the resolved Arc processor JAR'
                }
            }
            tasks.register('runtimeProbe', JavaExec) {
                dependsOn('classes', 'verifyDependencyResolution')
                classpath = sourceSets.main.runtimeClasspath
                mainClass.set('fixture.RuntimeProbe')
                doFirst {
                    def runtime = configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts
                        .findAll { it.moduleVersion.id.group == 'io.cratis' && it.name == 'arc' }
                    assert runtime.size() == 1
                    args runtime[0].file.absolutePath,
                        '${repository.resolve("io/cratis/arc/$version/arc-$version.jar").invariantSeparatorsPath}',
                        file('src/main/kotlin/fixture/Extra.kt').exists().toString()
                }
            }
        """.trimIndent())
        write(consumer, "src/main/kotlin/fixture/Fixtures.kt", """
            package fixture
            import io.cratis.arc.artifacts.Command
            import io.cratis.arc.artifacts.ReadModel
            @Command
            public data class Input(public val value: String) { public fun handle(): String = value }
            @ReadModel
            public data class View(public val value: String) {
                public companion object { @JvmStatic public fun all(): List<View> = listOf(View("value")) }
            }
        """.trimIndent())
        // An original package-only source is essential: cold KSP rounds must retain its dependency safely.
        write(consumer, "src/main/kotlin/fixture/Marker.kt", "package fixture")
        write(consumer, "src/main/java/fixture/JavaInput.java", """
            package fixture;
            @io.cratis.arc.artifacts.Command
            public record JavaInput(String value) { public String handle() { return value; } }
        """.trimIndent())
        write(consumer, "src/main/java/fixture/RuntimeProbe.java", runtimeProbe())
        return consumer
    }

    private fun runtimeProbe(): String = """
        package fixture;
        import io.cratis.arc.artifacts.ArcArtifactModule;
        import io.cratis.arc.authorization.ArcPrincipal;
        import io.cratis.arc.commands.CommandExecutionOptions;
        import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry;
        import io.cratis.arc.commands.DefaultCommandPipeline;
        import io.cratis.arc.commands.ServiceResolver;
        import io.cratis.arc.java.JavaAsyncScope;
        import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
        import io.cratis.arc.queries.DefaultQueryPipeline;
        import io.cratis.arc.queries.QueryExecutionOptions;
        import io.cratis.arc.queries.QueryRequest;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.util.Collections;
        import java.util.List;
        import java.util.ServiceLoader;
        import java.util.UUID;
        import java.util.concurrent.Executors;
        import java.util.concurrent.TimeUnit;
        public final class RuntimeProbe {
            private RuntimeProbe() { }
            public static void main(String[] args) throws Exception {
                Path origin = Path.of(ArcArtifactModule.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                require(Files.isSameFile(origin, Path.of(args[0])), "Source must come from resolved runtime JAR");
                require(Files.mismatch(origin, Path.of(args[1])) == -1, "Source must equal actual fixture JAR");
                var loader = RuntimeProbe.class.getClassLoader();
                require(Collections.list(loader.getResources("io/cratis/arc/artifacts/ArcArtifactModule.class")).size() == 1,
                    "Exactly one Source class resource");
                for (String forbidden : List.of("org.junit.jupiter.api.Test", "com.tschuchort.compiletesting.KotlinCompilation",
                    "io.cratis.arc.gradle.ArcGradlePlugin", "io.cratis.arc.codegeneration.ksp.ArcSymbolProcessorProvider")) {
                    try { Class.forName(forbidden, false, loader); throw new AssertionError("Classpath leak: " + forbidden); }
                    catch (ClassNotFoundException expected) { /* Not on the consumer runtime classpath. */ }
                }
                var modules = ServiceLoader.load(ArcArtifactModule.class).stream().map(ServiceLoader.Provider::get).toList();
                require(modules.size() == 1, "One generated service module");
                var module = modules.get(0);
                require(module.getClass().getName().equals("io.cratis.arc.generated.ConsumerArcArtifactModule"), "Module name");
                var names = module.getCommandHandlers().stream().map(handler -> handler.getMetadata().getName()).sorted().toList();
                require(names.equals(Boolean.parseBoolean(args[2]) ? List.of("Extra", "Input", "JavaInput") : List.of("Input", "JavaInput")),
                    "Module must track command additions and removals");
                var commands = new ConcurrentCommandHandlerRegistry();
                module.getCommandHandlers().forEach(commands::register);
                var queries = new ConcurrentQueryPerformerRegistry();
                module.getQueryPerformers().forEach(queries::register);
                ServiceResolver services = new ServiceResolver() {
                    @Override public <T> T resolve(Class<T> type) { return null; }
                };
                var executor = Executors.newSingleThreadExecutor();
                try (var scope = JavaAsyncScope.owningExecutorService(executor)) {
                    var options = new CommandExecutionOptions(UUID.randomUUID(), new ArcPrincipal(), services);
                    for (Object command : List.of(new Input("kotlin"), new JavaInput("java"))) {
                        var result = scope.commands(new DefaultCommandPipeline(commands)).execute(command, options)
                            .toCompletableFuture().get(5, TimeUnit.SECONDS);
                        require(result.isSuccess(), "Generated command execution");
                    }
                    require(module.getQueryPerformers().size() == 1, "One generated query");
                    var request = new QueryRequest(module.getQueryPerformers().get(0).getFullyQualifiedName());
                    var result = scope.queries(new DefaultQueryPipeline(queries)).perform(request,
                        new QueryExecutionOptions(UUID.randomUUID(), new ArcPrincipal(), services))
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                    require(result.isSuccess() && List.of(new View("value")).equals(result.getData()), "Generated query execution");
                } finally {
                    executor.shutdownNow();
                    require(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor cleanup");
                }
                System.out.println("RUNTIME_PROBE_VERIFIED " + origin);
            }
            private static void require(boolean condition, String message) {
                if (!condition) throw new AssertionError(message);
            }
        }
    """.trimIndent()

    private fun mapModelProbe(): String = """
        package fixture;
        import fixture.kotlinmaps.HeatMap;
        import fixture.kotlinmaps.SaveHeatMap;
        import fixture.javamaps.RoadMap;
        import fixture.javamaps.SaveRoadMap;
        import io.cratis.arc.artifacts.ArcArtifactModule;
        import io.cratis.arc.authorization.ArcPrincipal;
        import io.cratis.arc.commands.CommandExecutionOptions;
        import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry;
        import io.cratis.arc.commands.DefaultCommandPipeline;
        import io.cratis.arc.commands.ServiceResolver;
        import io.cratis.arc.java.JavaAsyncScope;
        import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
        import io.cratis.arc.queries.DefaultQueryPipeline;
        import io.cratis.arc.queries.QueryExecutionOptions;
        import io.cratis.arc.queries.QueryRequest;
        import java.util.List;
        import java.util.Map;
        import java.util.ServiceLoader;
        import java.util.UUID;
        import java.util.concurrent.Executors;
        import java.util.concurrent.TimeUnit;
        public final class MapModelProbe {
            private MapModelProbe() { }
            public static void main(String[] args) throws Exception {
                var modules = ServiceLoader.load(ArcArtifactModule.class).stream().map(ServiceLoader.Provider::get).toList();
                require(modules.size() == 1, "One generated service module");
                var module = modules.get(0);
                var commands = new ConcurrentCommandHandlerRegistry();
                module.getCommandHandlers().forEach(commands::register);
                var queries = new ConcurrentQueryPerformerRegistry();
                module.getQueryPerformers().forEach(queries::register);
                ServiceResolver services = new ServiceResolver() {
                    @Override public <T> T resolve(Class<T> type) { return null; }
                };
                var executor = Executors.newSingleThreadExecutor();
                try (var scope = JavaAsyncScope.owningExecutorService(executor)) {
                    var options = new CommandExecutionOptions(UUID.randomUUID(), new ArcPrincipal(), services);
                    for (Object command : List.of(new SaveHeatMap(new HeatMap("command-kotlin")),
                        new SaveRoadMap(new RoadMap("command-java")))) {
                        var result = scope.commands(new DefaultCommandPipeline(commands)).execute(command, options)
                            .toCompletableFuture().get(5, TimeUnit.SECONDS);
                        require(result.isSuccess(), "Generated Map-suffix command execution");
                        require((command instanceof SaveHeatMap ? "command-kotlin" : "command-java").equals(result.getResponse()),
                            "Generated command must receive its ordinary model property");
                    }
                    var expected = Map.of("fixture.kotlinmaps.HeatMap.hottest", new HeatMap("query-kotlin"),
                        "fixture.javamaps.RoadMap.shortest", new RoadMap("query-java"));
                    for (var entry : expected.entrySet()) {
                        var result = scope.queries(new DefaultQueryPipeline(queries)).perform(new QueryRequest(new io.cratis.arc.queries.FullyQualifiedQueryName(entry.getKey())),
                            new QueryExecutionOptions(UUID.randomUUID(), new ArcPrincipal(), services))
                            .toCompletableFuture().get(5, TimeUnit.SECONDS);
                        require(result.isSuccess() && entry.getValue().equals(result.getData()), "Generated Map-suffix query execution");
                    }
                } finally {
                    executor.shutdownNow();
                    require(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor cleanup");
                }
                System.out.println("MAP_MODEL_INVOCATIONS_VERIFIED");
            }
            private static void require(boolean condition, String message) {
                if (!condition) throw new AssertionError(message);
            }
        }
    """.trimIndent()

    private fun generatedArtifacts(consumer: File): Map<String, String> =
        listOf("build/generated/ksp/main/kotlin", "build/generated/ksp/main/resources", "build/generated/proxies")
            .flatMap { consumer.resolve(it).walkTopDown().filter(File::isFile).toList() }
            .associate { it.relativeTo(consumer).invariantSeparatorsPath to it.readText() }

    private fun verifyGenerated(consumer: File, result: BuildResult, extra: Boolean, incremental: Boolean = false) {
        for (task in listOf("kspKotlin", "compileKotlin", "compileJava", "processResources", "generateArcProxies", "runtimeProbe")) {
            val outcome = result.task(":$task")?.outcome
            if (incremental && task == "compileJava") {
                // Only Kotlin changed; Gradle can correctly reuse unchanged Java classes.
                assertTrue(outcome in listOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE), result.output)
            } else {
                assertEquals(TaskOutcome.SUCCESS, outcome, result.output)
            }
        }
        assertTrue(result.output.contains("PLUGIN_ORIGIN_JAR "), result.output)
        assertTrue(result.output.contains("RESOLVED_DEPENDENCIES_VERIFIED $version"), result.output)
        assertTrue(result.output.contains("RUNTIME_PROBE_VERIFIED "), result.output)
        val manifest = JsonMapper.builder().build().readTree(consumer.resolve("build/generated/ksp/main/resources/META-INF/cratis/arc/Consumer.json"))
        assertEquals("Consumer", manifest["moduleName"].asString())
        assertEquals(if (extra) listOf("Extra", "Input", "JavaInput") else listOf("Input", "JavaInput"),
            manifest["commands"].values().map { it["name"].asString() })
        assertEquals(listOf("all"), manifest["queries"].values().map { it["name"].asString() })
        assertEquals("io.cratis.arc.generated.ConsumerArcArtifactModule\n", consumer.resolve(
            "build/resources/main/META-INF/services/io.cratis.arc.artifacts.ArcArtifactModule").readText())
        for (proxy in listOf("Input", "JavaInput", "All", "View") + if (extra) listOf("Extra") else emptyList()) {
            assertTrue(consumer.resolve("build/generated/proxies/$proxy.ts").isFile, proxy)
        }
    }

    private fun run(consumer: File): BuildResult = runner(consumer, "generateArcProxies", "runtimeProbe").build()

    private fun runner(consumer: File, vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(consumer)
        .withGradleInstallation(File(property("gradleHome")))
        .withPluginClasspath(pluginClasspath)
        .withTestKitDir(work.resolve("testkit"))
        .withEnvironment(mapOf(
            "JAVA_HOME" to System.getProperty("java.home"),
            "PATH" to "${System.getProperty("java.home")}/bin:/usr/bin:/bin",
            "HOME" to home.absolutePath,
            "GRADLE_USER_HOME" to cache.absolutePath,
            "TMPDIR" to work.resolve("tmp").apply { mkdirs() }.absolutePath,
            "LANG" to "en_US.UTF-8"
        ))
        // Tooling API rejects --no-daemon; private fixture properties configure daemon lifetime instead.
        .forwardOutput()
        .withArguments(*tasks, "--max-workers=2", "--no-configuration-cache", "--console=plain",
            "--stacktrace", "--gradle-user-home", cache.absolutePath, "-Duser.home=${home.absolutePath}")

    private fun write(directory: File, path: String, text: String) {
        directory.resolve(path).apply { parentFile.mkdirs(); writeText(header + text + "\n") }
    }

    private fun property(name: String): String = requireNotNull(System.getProperty("arc.functional.$name"))
}
