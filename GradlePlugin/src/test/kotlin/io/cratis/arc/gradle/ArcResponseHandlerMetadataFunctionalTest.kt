// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ArcResponseHandlerMetadataFunctionalTest {
    private val version = System.getProperty("arc.functional.version")
    private val repository = File(System.getProperty("arc.functional.repository"))
    private val pluginClasspath = System.getProperty("arc.functional.pluginClasspath").split(File.pathSeparator).map(::File)
    private val root = Files.createTempDirectory(File(System.getProperty("arc.functional.work"))
        .apply { mkdirs() }.toPath(), "handler index ").toFile()

    @Test
    fun `native resource only removal reclassifies every unchanged consumer source`() {
        write("settings.gradle", "rootProject.name='HandlerIndex'; include('producer', 'consumer')")
        write("build.gradle", """
            allprojects {
                repositories { maven { url = uri('${repository.toURI()}') }; mavenCentral() }
                if (providers.gradleProperty('freshOutput').orNull == 'true') layout.buildDirectory.set(layout.projectDirectory.dir('fresh-build'))
            }
        """.trimIndent())
        write("gradle.properties", "org.gradle.jvmargs=-Xmx768m\norg.gradle.workers.max=2\nkotlin.compiler.execution.strategy=in-process\nksp.incremental=true\nksp.incremental.log=true\n")
        write("producer/build.gradle", """
            plugins { id 'io.cratis.arc' }
            cratisArc { moduleName.set('Producer'); dependencyVersion.set('$version') }
            tasks.register('resourceJar', Jar) {
                dependsOn tasks.named('jar')
                archiveFileName.set('resource-producer.jar')
                destinationDirectory.set(layout.buildDirectory.dir('resource-artifact'))
                from({ zipTree(tasks.named('jar').get().archiveFile.get().asFile) }) {
                    exclude 'META-INF/cratis/arc-response-handlers/**'
                }
                from('declarations')
            }
            tasks.register('classOnlyJar', Jar) {
                dependsOn tasks.named('jar')
                archiveFileName.set('classes-only.jar')
                destinationDirectory.set(layout.buildDirectory.dir('class-artifact'))
                from({ zipTree(tasks.named('jar').get().archiveFile.get().asFile) }) { exclude 'META-INF/cratis/arc-response-handlers/**' }
            }
            configurations {
                handlerArtifact { canBeConsumed = true; canBeResolved = false }
                classArtifact { canBeConsumed = true; canBeResolved = false }
            }
            artifacts { handlerArtifact tasks.named('resourceJar'); classArtifact tasks.named('classOnlyJar') }
        """.trimIndent())
        write("producer/src/main/kotlin/producer/Handler.kt", """
            package producer
            public class Payload(public val value: String)
            @io.cratis.arc.commands.HandlesCommandResponseValues(Payload::class)
            public class Handler : io.cratis.arc.java.BlockingCommandResponseValueHandler {
                override fun canHandle(context: io.cratis.arc.commands.CommandContext, value: Any): Boolean = value is Payload
                override fun handle(context: io.cratis.arc.commands.CommandContext, value: Any): io.cratis.arc.results.CommandResult<*> =
                    io.cratis.arc.results.CommandResult.success(context.correlationId)
            }
        """.trimIndent())
        val declarationPath = "producer/declarations/META-INF/cratis/arc-response-handlers/Producer.json"
        write(declarationPath, document)
        write("consumer/build.gradle", """
            plugins { id 'io.cratis.arc' }
            cratisArc { moduleName.set('Consumer'); dependencyVersion.set('$version') }
            dependencies { implementation project(path: ':producer', configuration:
                providers.gradleProperty('withoutHandlerMetadata').orNull == 'true' ? 'classArtifact' : 'handlerArtifact') }
        """.trimIndent())
        for (name in listOf("First", "Second")) write("consumer/src/main/kotlin/consumer/$name.kt", """
            package consumer
            @io.cratis.arc.artifacts.Command
            public class $name { public fun handle(): producer.Payload = producer.Payload("server") }
        """.trimIndent())
        val first = run("cold")
        assertTrue(first.task(":consumer:kspKotlin")?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE), first.output)
        val producerResource = root.resolve("producer/build/generated/ksp/main/resources/META-INF/cratis/arc-response-handlers/Producer.json")
        assertEquals(document + "\n", producerResource.readText())
        assertFalse(root.resolve("producer/build/generated/ksp/main/resources/META-INF/cratis/arc/Producer.json").exists())
        assertDispositions("HANDLED")
        val classes = classBytes()
        val unchanged = run("noop")
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":consumer:kspKotlin")?.outcome, unchanged.output)
        assertTrue(root.resolve(declarationPath).delete())
        val removed = run("resource-removal")
        assertEquals(classes, classBytes(), "Resource-only mutation must leave all producer class bytes identical")
        assertEquals(TaskOutcome.SUCCESS, removed.task(":consumer:kspKotlin")?.outcome, removed.output)
        assertDispositions("CLIENT")
        write(declarationPath, document)
        val restored = run("resource-recovery")
        assertEquals(TaskOutcome.FROM_CACHE, restored.task(":consumer:kspKotlin")?.outcome, restored.output)
        assertEquals(classes, classBytes())
        assertDispositions("HANDLED")
        write(declarationPath, document.replace("producer.Payload", "kotlin.String"))
        val mismatch = run("annotation-mismatch", fails = true)
        assertTrue("[ARCKSP0102]" in mismatch.output, mismatch.output)
        assertTrue("META-INF/cratis/arc-response-handlers/Producer.json" in mismatch.output, mismatch.output)
        assertEquals(classes, classBytes())
        write(declarationPath, document)
        run("mismatch-recovery")
        assertDispositions("HANDLED")
        write(declarationPath, "{invalid")
        val corrupt = run("corrupt-resource", fails = true)
        assertTrue("Invalid Arc response handler metadata" in corrupt.output, corrupt.output)
        assertEquals(classes, classBytes())
        write(declarationPath, document)
        run("corruption-recovery")
        assertDispositions("HANDLED")
        assertTrue(root.resolve(declarationPath).delete())
        run("second-removal")
        assertDispositions("CLIENT")
        write(declarationPath, document)
        run("uncached-addition")
        assertDispositions("HANDLED")
        assertEquals(classes, classBytes())
        val incremental = generatedSnapshot("build")
        val source = root.resolve("consumer/src/main/kotlin/consumer/First.kt")
        source.appendText("\n// ordinary source edit\n")
        val edited = run("ordinary-source-edit")
        assertEquals(TaskOutcome.UP_TO_DATE, edited.task(":consumer:extractMainArcResponseHandlerMetadata")?.outcome)
        assertDispositions("HANDLED")
        assertEquals(incremental, generatedSnapshot("build"))
        run("fresh-output", fresh = true)
        assertEquals(incremental, generatedSnapshot("fresh-build"), "Fresh native generation must equal incrementally reclassified output")
        run("final-resource-dependency-removal", withoutMetadata = true)
        assertDispositions("CLIENT")
        assertEquals("{\"formatVersion\":1,\"modules\":[]}\n", root.resolve("consumer/build/arc/response-handlers/main.json").readText())
        run("dependency-recovery")
        assertDispositions("HANDLED")
        val stored = run("configuration-store", configurationCache = true)
        assertTrue("Configuration cache entry stored" in stored.output, stored.output)
        val reused = run("configuration-reuse", configurationCache = true)
        assertTrue("Reusing configuration cache" in reused.output, reused.output)
    }

    @Test
    fun `Kotlin and Java handler jars cross an api bridge and manual KSP wiring executes async aggregates`() {
        write("settings.gradle", "rootProject.name='BinaryHandlers'; include('kotlinProducer', 'javaProducer', 'bridge', 'consumer')")
        write("build.gradle", """
            buildscript {
                repositories { maven { url = uri('${File(System.getProperty("arc.onboarding.repository")).toURI()}') }; mavenCentral(); gradlePluginPortal() }
                dependencies { classpath 'io.cratis:arc-gradle-plugin:$version' }
            }
            allprojects { repositories { maven { url = uri('${repository.toURI()}') }; mavenCentral() } }
        """.trimIndent())
        write("gradle.properties", "org.gradle.jvmargs=-Xmx768m\norg.gradle.workers.max=2\nkotlin.compiler.execution.strategy=in-process\nksp.incremental=true\n")
        for (language in listOf("kotlin", "java")) write("${language}Producer/build.gradle", """
            plugins { id 'io.cratis.arc'; id 'java-library' }
            cratisArc { moduleName.set('${language}Producer'); dependencyVersion.set('$version') }
        """.trimIndent())
        write("kotlinProducer/src/main/kotlin/kotlinproducer/Handler.kt", """
            package kotlinproducer
            public class Payload(public val secret: String)
            @io.cratis.arc.commands.HandlesCommandResponseValues(Payload::class)
            public class Handler : io.cratis.arc.java.BlockingCommandResponseValueHandler {
                private var calls: Int = 0
                public fun invocationCount(): Int = calls
                override fun canHandle(context: io.cratis.arc.commands.CommandContext, value: Any): Boolean = value is Payload
                override fun handle(context: io.cratis.arc.commands.CommandContext, value: Any): io.cratis.arc.results.CommandResult<*> {
                    calls++
                    return io.cratis.arc.results.CommandResult.success(context.correlationId)
                }
            }
        """.trimIndent())
        write("javaProducer/src/main/java/javaproducer/Payload.java", "package javaproducer; public record Payload(String secret) {}")
        write("javaProducer/src/main/java/javaproducer/Handler.java", """
            package javaproducer;
            import io.cratis.arc.commands.CommandContext;
            import io.cratis.arc.results.CommandResult;
            @io.cratis.arc.commands.HandlesCommandResponseValues(Payload.class)
            public final class Handler implements io.cratis.arc.java.AsyncCommandResponseValueHandler {
                private int calls;
                public int invocationCount() { return calls; }
                public boolean canHandle(CommandContext context, Object value) { return value instanceof Payload; }
                public java.util.concurrent.CompletionStage<CommandResult<?>> handle(CommandContext context, Object value) {
                    calls++;
                    return java.util.concurrent.CompletableFuture.completedFuture(CommandResult.success(context.getCorrelationId()));
                }
            }
        """.trimIndent())
        write("bridge/build.gradle", """
            plugins { id 'java-library' }
            dependencies { api project(':kotlinProducer'); api project(':javaProducer') }
        """.trimIndent())
        // Applying the Kotlin/KSP plugins directly is deliberate: the public Arc task/provider recipe owns the wiring.
        write("consumer/build.gradle", """
            plugins { id 'org.jetbrains.kotlin.jvm'; id 'com.google.devtools.ksp' }
            dependencies {
                implementation project(':bridge'); implementation 'io.cratis:arc:$version'; ksp 'io.cratis:arc-ksp:$version'
                implementation 'io.cratis:arc-openapi-spring-boot-starter:$version'
            }
            kotlin { jvmToolchain(17) }
            ksp { arg('arc.moduleName', 'Consumer') }
            def extract = tasks.register('extractHandlers', io.cratis.arc.gradle.ExtractArcResponseHandlerMetadata) {
                dependencyArtifacts.from(configurations.compileClasspath.incoming.artifactView {
                    attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.JAR))
                }.files)
                outputFile.set(layout.buildDirectory.file('handler index/main.json'))
            }
            tasks.withType(com.google.devtools.ksp.gradle.KspAATask).configureEach {
                if (name == 'kspKotlin') {
                    def provider = objects.newInstance(io.cratis.arc.gradle.ArcResponseHandlerMetadataArgumentProvider)
                    provider.metadataFile.set(extract.flatMap { it.outputFile })
                    commandLineArgumentProviders.add(provider)
                    inputs.file(provider.metadataFile).withPropertyName('arcResponseHandlerMetadata').withPathSensitivity(PathSensitivity.NONE)
                }
            }
            tasks.withType(JavaCompile).configureEach { options.compilerArgs.addAll(['-Xlint:all', '-Werror']) }
            tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile).configureEach { compilerOptions.allWarningsAsErrors.set(true) }
            tasks.register('probe', JavaExec) { dependsOn('classes'); classpath = sourceSets.main.runtimeClasspath; mainClass.set('consumer.ProbeKt') }
        """.trimIndent())
        write("consumer/src/main/kotlin/consumer/Save.kt", """
            package consumer
            @io.cratis.arc.artifacts.Command
            public class Save { public fun handle(): kotlinproducer.Payload = kotlinproducer.Payload("server-kotlin") }
        """.trimIndent())
        write("consumer/src/main/java/consumer/Aggregate.java", """
            package consumer;
            @io.cratis.arc.artifacts.Command
            public final class Aggregate {
                public java.util.concurrent.CompletionStage<kotlin.Pair<String, javaproducer.Payload>> handle() {
                    return java.util.concurrent.CompletableFuture.completedFuture(new kotlin.Pair<>("client", new javaproducer.Payload("server-java")));
                }
            }
        """.trimIndent())
        write("consumer/src/main/kotlin/consumer/Probe.kt", """
            package consumer
            import io.cratis.arc.commands.*
            import io.cratis.arc.java.AsyncCommandResponseValueHandlerAdapter
            import io.cratis.arc.java.BlockingCommandResponseValueHandlerAdapter
            import kotlinx.coroutines.runBlocking
            public fun main(): Unit = runBlocking {
                val module = io.cratis.arc.generated.ConsumerArcArtifactModule()
                val registry = ConcurrentCommandHandlerRegistry()
                module.commandHandlers.forEach(registry::register)
                val kotlinHandler = kotlinproducer.Handler()
                val javaHandler = javaproducer.Handler()
                val options = CommandExecutionOptions(java.util.UUID.randomUUID(), io.cratis.arc.authorization.ArcPrincipal.anonymous(), object : ServiceResolver {
                    override fun <T : Any> resolve(type: Class<T>): T? = null
                })
                val registered = DefaultCommandPipeline(registry, responseValueHandlers = listOf(
                    BlockingCommandResponseValueHandlerAdapter(kotlinHandler), AsyncCommandResponseValueHandlerAdapter(javaHandler)))
                val scalar = registered.execute(Save(), options)
                check(scalar.isSuccess && scalar.response == null)
                val aggregate = registered.execute(Aggregate(), options)
                check(aggregate.isSuccess && aggregate.response == "client")
                check(kotlinHandler.invocationCount() == 1 && javaHandler.invocationCount() == 1)
                for (handlers in listOf(emptyList(), listOf(BlockingCommandResponseValueHandlerAdapter(kotlinHandler)))) {
                    val missing = DefaultCommandPipeline(registry, responseValueHandlers = handlers).execute(Aggregate(), options)
                    check(!missing.isSuccess && missing.response == null)
                }
                check(module.types.none { it.fullyQualifiedName.endsWith(".Payload") })
                val openApi = io.cratis.arc.openapi.springboot.ArcOpenApiGenerator().generate(listOf(module))
                val json = String(openApi.json(), Charsets.UTF_8)
                check("Payload" !in json && "server-java" !in json && "server-kotlin" !in json)
                val response = openApi.openApi.paths["/api/consumer/aggregate"]!!.post.responses["200"]!!.content["application/json"]!!.schema
                check(response.properties["response"]!!.anyOf.map { it.type } == listOf("string", "null"))
                println("BINARY_HANDLER_INVOCATIONS_VERIFIED")
            }
        """.trimIndent())
        val result = run("manual-api-bridge", ":consumer:probe", injectPlugins = false)
        assertTrue("BINARY_HANDLER_INVOCATIONS_VERIFIED" in result.output, result.output)
        val manifests = ArcManifestDiscovery.discover(listOf(root.resolve("consumer/build/resources/main")))
        val manifest = manifests.single().manifest
        assertEquals(listOf("CLIENT", "HANDLED"), manifest.commands.single { it.name == "Aggregate" }.responseValues.map { it.disposition.name })
        assertEquals(listOf("HANDLED"), manifest.commands.single { it.name == "Save" }.responseValues.map { it.disposition.name })
        assertFalse(manifest.types.any { it.name == "Payload" })
        val proxies = root.resolve("proxies")
        val options = ProxyGenerationOptions(proxies, io.cratis.arc.metadata.ApiEndpointOptions(), true, 1)
        val files = TypeScriptProxyGenerator(ArcManifestDiscovery.merge(manifests), options).generate()
        assertTrue(files.single { it.name == "Aggregate.ts" }.readText().contains("extends Command<IAggregate, string>"))
        assertTrue(files.single { it.name == "Save.ts" }.readText().contains("extends Command<ISave>"))
        assertFalse(files.any { "Payload" in it.readText() })
        assertFalse(root.resolve("consumer/build/generated/ksp/main/resources/META-INF/cratis/arc-response-handlers/Consumer.json").exists())
        for (language in listOf("kotlin", "java")) {
            assertTrue(root.resolve("${language}Producer/build/generated/ksp/main/resources/META-INF/cratis/arc-response-handlers/${language}Producer.json").isFile)
            assertFalse(root.resolve("${language}Producer/build/generated/ksp/main/resources/META-INF/cratis/arc/${language}Producer.json").exists())
        }
    }

    @Test
    fun `test dependency declarations do not affect main compilation classification`() {
        write("settings.gradle", "rootProject.name='ScopedHandlers'; include('types', 'handlers', 'consumer')")
        write("build.gradle", "allprojects { repositories { maven { url = uri('${repository.toURI()}') }; mavenCentral() } }")
        write("gradle.properties", "org.gradle.jvmargs=-Xmx768m\norg.gradle.workers.max=2\nkotlin.compiler.execution.strategy=in-process\nksp.incremental=true\n")
        write("types/build.gradle", "plugins { id 'java-library' }; java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }")
        write("types/src/main/java/types/Payload.java", "package types; public record Payload(String value) {}")
        write("handlers/build.gradle", """
            plugins { id 'io.cratis.arc' }
            cratisArc { moduleName.set('Handlers'); dependencyVersion.set('$version') }
            dependencies { implementation project(':types') }
        """.trimIndent())
        write("handlers/src/main/kotlin/handlers/Handler.kt", """
            package handlers
            @io.cratis.arc.commands.HandlesCommandResponseValues(types.Payload::class)
            public class Handler : io.cratis.arc.java.BlockingCommandResponseValueHandler {
                override fun canHandle(context: io.cratis.arc.commands.CommandContext, value: Any): Boolean = value is types.Payload
                override fun handle(context: io.cratis.arc.commands.CommandContext, value: Any): io.cratis.arc.results.CommandResult<*> =
                    io.cratis.arc.results.CommandResult.success(context.correlationId)
            }
        """.trimIndent())
        write("consumer/build.gradle", """
            plugins { id 'io.cratis.arc' }
            cratisArc { moduleName.set('Consumer'); dependencyVersion.set('$version') }
            dependencies {
                implementation project(':types'); testImplementation project(':handlers')
                kspTest 'io.cratis:arc-ksp:$version'
            }
        """.trimIndent())
        for ((scope, name) in listOf("main" to "MainCommand", "test" to "TestCommand")) {
            write("consumer/src/$scope/kotlin/consumer/$name.kt", """
                package consumer
                @io.cratis.arc.artifacts.Command
                public class $name { public fun handle(): types.Payload = types.Payload("value") }
            """.trimIndent())
        }
        val result = run("independent-source-sets", ":consumer:testClasses")
        assertEquals(TaskOutcome.SUCCESS, result.task(":consumer:kspKotlin")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":consumer:kspTestKotlin")?.outcome, result.output)
        for ((scope, disposition) in listOf("main" to "CLIENT", "test" to "HANDLED")) {
            val manifest = ArcManifestDiscovery.discover(listOf(root.resolve("consumer/build/generated/ksp/$scope/resources"))).single().manifest
            assertEquals(listOf(disposition), manifest.commands.single().responseValues.map { it.disposition.name })
        }
        assertEquals("{\"formatVersion\":1,\"modules\":[]}\n", root.resolve("consumer/build/arc/response-handlers/main.json").readText())
        assertTrue("handlers.Handler" in root.resolve("consumer/build/arc/response-handlers/test.json").readText())
    }

    private fun generatedSnapshot(build: String): Map<String, String> {
        val generated = root.resolve("consumer/$build/generated/ksp/main")
        return generated.walkTopDown().filter { it.isFile }.associate { it.relativeTo(generated).invariantSeparatorsPath to it.readText() }
    }

    private fun assertDispositions(expected: String) {
        val manifest = ArcManifestDiscovery.discover(listOf(root.resolve("consumer/build/resources/main"))).single().manifest
        assertEquals(listOf("First", "Second"), manifest.commands.map { it.name })
        manifest.commands.forEach { assertEquals(listOf(expected), it.responseValues.map { value -> value.disposition.name }, it.name) }
        assertEquals(expected == "CLIENT", manifest.types.any { it.fullyQualifiedName == "producer.Payload" })
    }

    private fun classBytes(): Map<String, List<Byte>> = JarFile(root.resolve("producer/build/resource-artifact/resource-producer.jar")).use { jar ->
        jar.entries().asSequence().filter { it.name.endsWith(".class") }.associate { it.name to jar.getInputStream(it).use { stream -> stream.readBytes().toList() } }
    }

    private fun run(label: String, task: String = ":consumer:classes", fails: Boolean = false,
        fresh: Boolean = false, withoutMetadata: Boolean = false, configurationCache: Boolean = false,
        injectPlugins: Boolean = true): org.gradle.testkit.runner.BuildResult {
        val runner = GradleRunner.create()
        .withProjectDir(root)
        .withGradleInstallation(File(System.getProperty("arc.functional.gradleHome")))
        .apply { if (injectPlugins) withPluginClasspath(this@ArcResponseHandlerMetadataFunctionalTest.pluginClasspath) }
        .withArguments(task, "--stacktrace", "--console=plain",
            if (configurationCache) "--configuration-cache" else "--no-configuration-cache",
            "-PfreshOutput=$fresh", "-PwithoutHandlerMetadata=$withoutMetadata", if (label in setOf("cold", "noop", "resource-recovery")) "--build-cache" else "--no-build-cache",
            "--gradle-user-home", System.getProperty("arc.onboarding.gradleUserHome"))
        return (if (fails) runner.buildAndFail() else runner.build()).also { result ->
            System.getProperty("arc.handlerIndex.evidence")?.let { directory ->
                File(directory).resolve("${root.name}-$label.log").apply {
                    check(!exists()) { "Refusing to replace native evidence $this" }
                    writeText("Fixture: $root\n" + result.output)
                }
            }
        }
    }

    private fun write(path: String, text: String) {
        root.resolve(path).apply { parentFile.mkdirs(); writeText(text + "\n") }
    }

    private val document = "{\"formatVersion\":1,\"moduleName\":\"Producer\",\"handlers\":[{\"handlerTypeName\":\"producer.Handler\",\"handledTypeNames\":[\"producer.Payload\"]}]}"
}
