// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import java.io.File
import java.nio.file.Files
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Published processor, actual runtime trace, and relative-path byte parity; never provisions JDKs. */
internal class ArcFluentValidationRuntimeCompatibilityFunctionalTest {
    private val version = System.getProperty("arc.functional.version")
    private val repository = File(System.getProperty("arc.onboarding.repository"))
    private val work = File(System.getProperty("arc.fluent.evidence") ?: System.getProperty("arc.functional.work"))
        .apply { mkdirs() }
    private val homes = System.getProperty("arc.fluentRuntimeJdks")?.split(File.pathSeparatorChar)?.map(::File)
        ?: listOf(File(System.getProperty("java.home")))

    @Test
    fun `supported processor runtimes preserve literal metadata and proxy bytes`() {
        val pom = repository.resolve("io/cratis/arc-ksp/$version/arc-ksp-$version.pom").readText()
        assertTrue("kotlin-compiler-embeddable" in pom && "2.4.20" in pom && "kctfork" !in pom, pom)
        val runs = homes.map { home ->
            require(home.resolve("bin/java").isFile && home.resolve("bin/javac").isFile) { "Use installed full JDK homes" }
            val feature = home.resolve("release").readLines().single { it.startsWith("JAVA_VERSION=") }
                .substringAfter('"').substringBefore('"').substringBefore('.').substringBefore('-').toInt()
            require(feature in setOf(17, 21)) { "Unsupported fixture runtime: $feature" }
            val root = Files.createTempDirectory(work.toPath(), "fluent-runtime-$feature-").toFile()
            fixture(root)
            val output = run(root, home)
            root.resolve("processor.log").writeText(output)
            assertTrue("[ARC-FLUENT-PARSER] parser=" in output && "; version=2.4.20;" in output, output)
            assertTrue("sameLoader=true; parentParser=false; javac=jdk.compiler; runtime=$feature" in output, output)
            assertTrue("APPLICATION_AND_PLUGIN_PARSER_ABSENT" in output, output)
            verifyOutputs(root)
            snapshot(root)
        }
        assertTrue(runs.isNotEmpty())
        runs.drop(1).forEach { assertEquals(runs.first(), it, "JDK17/JDK21 metadata or proxy bytes differ") }
    }

    private fun run(root: File, home: File): String = GradleRunner.create().withProjectDir(root)
        .withGradleInstallation(File(System.getProperty("arc.functional.gradleHome")))
        .withTestKitDir(File(System.getProperty("arc.onboarding.gradleUserHome")))
        .withEnvironment(System.getenv() + ("JAVA_HOME" to home.absolutePath))
        .withArguments("classes", "generateArcProxies", "--info", "--stacktrace", "--no-configuration-cache",
            "--no-build-cache", "--max-workers=1", "--console=plain",
            "--gradle-user-home", System.getProperty("arc.onboarding.gradleUserHome"),
            "-Dorg.gradle.java.home=${home.absolutePath}",
            "-Dorg.gradle.java.installations.paths=${homes.joinToString(",") { it.absolutePath }}",
            "-Dorg.gradle.java.installations.auto-download=false").build().output

    private fun fixture(root: File) {
        write(root, "settings.gradle", """
            pluginManagement { repositories { maven { url = uri('${repository.toURI()}') }; gradlePluginPortal(); mavenCentral() } }
            rootProject.name='RuntimeCompatibility'
        """.trimIndent())
        write(root, "gradle.properties", "org.gradle.jvmargs=-Xmx768m\norg.gradle.workers.max=1\nkotlin.compiler.execution.strategy=in-process\n")
        write(root, "build.gradle", """
            plugins { id 'io.cratis.arc' version '$version' }
            repositories { maven { url = uri('${repository.toURI()}') }; mavenCentral() }
            cratisArc { moduleName.set('Runtime'); dependencyVersion.set('$version'); proxies.outputDirectory.set(layout.buildDirectory.dir('proxies')) }
            ksp { arg('arc.fluentValidationTrace', 'true') }
            tasks.matching { it.name == 'kspKotlin' }.configureEach {
                doFirst {
                    assert !configurations.runtimeClasspath.files.any { it.name.contains('compiler-embeddable') || it.name.startsWith('arc-ksp-') }
                    assert !buildscript.configurations.classpath.files.any { it.name.contains('compiler-embeddable') }
                    println 'APPLICATION_AND_PLUGIN_PARSER_ABSENT'
                }
            }
        """.trimIndent())
        write(root, "src/main/kotlin/fixture/Models.kt", """
            package fixture
            import io.cratis.arc.validation.FluentModelValidator
            class Person(val name: String)
            class PersonRules : FluentModelValidator<Person>(Person::class.java) {
                init { ruleFor("name").notNull().maxLength(10).withMessage("Name bound") }
            }
            @io.cratis.arc.artifacts.Command
            class Create(val person: Person, val record: Values) { fun handle(): String = person.name }
        """.trimIndent())
        write(root, "src/main/java/fixture/Values.java", "package fixture; public record Values(String name, double score) {}")
        write(root, "src/main/java/fixture/ValuesRules.java", """
            package fixture;
            import io.cratis.arc.validation.FluentModelValidator;
            public final class ValuesRules extends FluentModelValidator<Values> {
                public ValuesRules() {
                    super(Values.class);
                    ruleFor("name").notNull().maxLength(10).withMessage("Name bound");
                    ruleFor("score").greaterThan(-1.25).lessThanOrEqual(10).withMessage("Score bound");
                }
            }
        """.trimIndent())
    }

    private fun verifyOutputs(root: File) {
        val generated = root.resolve("build/generated/ksp/main")
        val declaration = generated.resolve("resources/META-INF/cratis/arc-fluent-validation/Runtime.json").readText()
        listOf("fixture.PersonRules", "fixture.ValuesRules", "java.lang.String", "double", "maxLength", "greaterThan",
            "lessThanOrEqual", "-1.25", "Name bound", "Score bound").forEach { assertTrue(it in declaration, declaration) }
        val manifest = generated.resolve("resources/META-INF/cratis/arc/Runtime.json").readText()
        listOf("name", "score", "maxLength", "greaterThan", "Score bound").forEach { assertTrue(it in manifest, manifest) }
        assertEquals("io.cratis.arc.generated.RuntimeArcArtifactModule",
            generated.resolve("resources/META-INF/services/io.cratis.arc.artifacts.ArcArtifactModule").readText().trim())
        val module = generated.resolve("kotlin/io/cratis/arc/generated/RuntimeArcArtifactModule.kt").readText()
        assertTrue("PersonRules" in module && "ValuesRules" in module, module)
        val proxies = root.resolve("build/proxies").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        listOf("maxLength", "greaterThan", "lessThanOrEqual", "Score bound").forEach { assertTrue(it in proxies, proxies) }
    }

    private fun snapshot(root: File): Map<String, List<Byte>> = listOf("generated/ksp/main", "proxies").flatMap { path ->
        val directory = root.resolve("build/$path")
        directory.walkTopDown().filter { it.isFile }.map {
            "$path/${it.relativeTo(directory).invariantSeparatorsPath}" to it.readBytes().toList()
        }.toList()
    }.toMap()

    private fun write(root: File, path: String, content: String) {
        root.resolve(path).apply { parentFile.mkdirs(); writeText(content) }
    }
}
