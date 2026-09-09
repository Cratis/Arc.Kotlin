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
