// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import java.io.File
import java.nio.file.Files
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Native KSP lifecycle coverage, deliberately independent of the broad plugin functional suite. */
internal class ArcIdentityDiscoveryFunctionalTest {
    private val version = System.getProperty("arc.functional.version")
    private val repository = File(System.getProperty("arc.functional.repository"))
    private val root = Files.createTempDirectory(File(System.getProperty("arc.functional.work"))
        .apply { mkdirs() }.toPath(), "identity ").toFile()

    @Test
    fun `Kotlin identity roots correct add change and remove incrementally`() = exercise(java = false)

    @Test
    fun `ordinary Java identity roots correct add change and remove incrementally`() = exercise(java = true)

    private fun exercise(java: Boolean) {
        write("settings.gradle", "rootProject.name='IdentityNative'")
        write("build.gradle", """
            plugins { id 'io.cratis.arc' }
            repositories { maven { url = uri('${repository.toURI()}') }; mavenCentral() }
            cratisArc {
                moduleName.set('IdentityNative'); dependencyVersion.set('$version')
                proxies { outputDirectory.set(layout.buildDirectory.dir('proxies')); segmentsToSkip.set(1) }
            }
            def freshOutput = providers.gradleProperty('freshOutput').orNull
            if (freshOutput != null) layout.buildDirectory.set(layout.projectDirectory.dir(freshOutput))
            tasks.withType(JavaCompile).configureEach { options.compilerArgs.addAll(['-Xlint:all', '-Werror']) }
            tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile).configureEach { compilerOptions.allWarningsAsErrors.set(true) }
        """.trimIndent())
        write("gradle.properties", "org.gradle.jvmargs=-Xmx768m\norg.gradle.workers.max=2\nkotlin.compiler.execution.strategy=in-process\nksp.incremental=true\n")
        // An independent explicit root ensures the terminal removal still produces a comparable aggregate.
        write("src/main/kotlin/identity/Anchor.kt", "package identity\n@io.cratis.arc.artifacts.ExportedType public data class Anchor(val value: String)")
        val extension = if (java) "java" else "kt"
        val language = if (java) "java" else "kotlin"
        for (name in listOf("First", "Second")) {
            write("src/main/$language/identity/$name.$extension", if (java)
                "package identity; public record $name(String value) {}" else
                "package identity; public data class $name(val value: String)")
        }
        val factory = "src/main/$language/identity/Factory.$extension"
        fun source(details: String) = if (java) """
            package identity;
            import io.cratis.arc.identity.*;
            public final class Factory {
                public static AsyncIdentityDetailsProvider<$details> details() { throw new IllegalStateException("declaration only"); }
            }
        """.trimIndent() else """
            package identity
            public fun details(): io.cratis.arc.identity.IdentityDetailsProvider<$details> = error("declaration only")
        """.trimIndent()
        write(factory, source("First"))
        run("cold")
        assertTypes("Anchor", "First")
        assertTrue(root.resolve("build/proxies/First.ts").isFile)
        assertEquals(TaskOutcome.UP_TO_DATE, run("noop").task(":kspKotlin")?.outcome)
        write(factory, source(if (java) "?" else "*"))
        val invalid = run("invalid", fails = true)
        assertTrue("[ARCKSP0307]" in invalid.output, invalid.output)
        assertTrue("Factory.$extension:" in invalid.output, invalid.output)
        write(factory, source("Second"))
        run("corrected")
        assertTypes("Anchor", "Second")
        assertFalse(root.resolve("build/proxies/First.ts").exists())
        assertTrue(root.resolve("build/proxies/Second.ts").isFile)
        val corrected = snapshot("build")
        run("fresh-corrected", fresh = true)
        assertEquals(corrected, snapshot("fresh-corrected"), "Incremental identity correction must equal fresh KSP generation")
        val additional = "src/main/$language/identity/Additional.$extension"
        write(additional, source("First").replace("class Factory", "class Additional").replace("fun details", "fun additional"))
        run("added")
        assertTypes("Anchor", "First", "Second")
        assertTrue(root.resolve(additional).delete())
        run("removed-addition")
        assertTypes("Anchor", "Second")
        assertTrue(root.resolve(factory).delete())
        run("removed-factory")
        assertTypes("Anchor")
        assertFalse(root.resolve("build/proxies/Second.ts").exists())
        // Changing only an inherited binding must replace the root even though Provider is untouched.
        val base = "src/main/$language/identity/Base.$extension"
        val middle = "src/main/$language/identity/Middle.$extension"
        val provider = "src/main/$language/identity/Provider.$extension"
        write(base, if (java) """
            package identity;
            import io.cratis.arc.identity.*;
            import java.util.concurrent.*;
            public abstract class Base<T> implements AsyncIdentityDetailsProvider<T> {
                public Class<T> getDetailsType() { throw new IllegalStateException("declaration only"); }
                public CompletionStage<IdentityDetails<T>> provide(IdentityProviderContext context) {
                    throw new IllegalStateException("declaration only");
                }
            }
        """.trimIndent() else """
            package identity
            import io.cratis.arc.identity.*
            public sealed class Base<T : Any> : IdentityDetailsProvider<T> {
                override val detailsType: Class<T> get() = error("declaration only")
                override suspend fun provide(context: IdentityProviderContext): IdentityDetails<T> = error("declaration only")
            }
        """.trimIndent())
        fun binding(details: String) = if (java)
            "package identity; public abstract class Middle extends Base<$details> {}" else
            "package identity; public abstract class Middle : Base<$details>()"
        write(middle, binding("First"))
        write(provider, if (java) "package identity; public final class Provider extends Middle {}" else
            "package identity; public class Provider : Middle()")
        run("inherited-added")
        assertTypes("Anchor", "First")
        write(middle, binding("Second"))
        run("inherited-binding-changed")
        assertTypes("Anchor", "Second")
        assertFalse(root.resolve("build/proxies/First.ts").exists())
        val rebound = snapshot("build")
        run("fresh-inherited-binding", fresh = true)
        assertEquals(rebound, snapshot("fresh-inherited-binding"), "Inherited binding edit must equal fresh KSP generation")

        val nested = "src/main/$language/identity/Nested.$extension"
        write(nested, if (java) "package identity; public record Nested(String before) {}" else
            "package identity; public data class Nested(val before: String)")
        write("src/main/$language/identity/Second.$extension", if (java)
            "package identity; public record Second(Nested nested) {}" else
            "package identity; public data class Second(val nested: Nested)")
        run("reachable-property-added")
        assertTypes("Anchor", "Nested", "Second")
        assertProperties("Nested", "before")
        assertProperties("Second", "nested")
        assertTrue("before!: string;" in root.resolve("build/proxies/Nested.ts").readText())
        // Edit only the transitive DTO; neither provider nor its details root changes.
        write(nested, if (java) "package identity; public record Nested(String after) {}" else
            "package identity; public data class Nested(val after: String)")
        run("reachable-property-edited")
        assertTypes("Anchor", "Nested", "Second")
        assertProperties("Nested", "after")
        val nestedProxy = root.resolve("build/proxies/Nested.ts").readText()
        assertTrue("after!: string;" in nestedProxy, nestedProxy)
        assertFalse("before!: string;" in nestedProxy, nestedProxy)
        val edited = snapshot("build")
        run("fresh-reachable-property", fresh = true)
        assertEquals(edited, snapshot("fresh-reachable-property"), "Reachable DTO edit must equal fresh KSP generation")
        assertTrue(root.resolve(provider).delete())
        run("inherited-removed")
        assertTypes("Anchor")
        assertFalse(root.resolve("build/proxies/Second.ts").exists())
        assertFalse(root.resolve("build/proxies/Nested.ts").exists())
        // Fresh comparisons use independent project caches so they cannot replace the main
        // ProcessResources task's previous-output history before terminal removal.
        assertTrue(root.resolve("src/main/kotlin/identity/Anchor.kt").delete())
        run("no-roots")
        assertFalse(root.resolve("build/generated/ksp/main/resources/META-INF/cratis/arc/IdentityNative.json").exists())
        assertFalse(root.resolve("build/generated/ksp/main/resources/META-INF/services/io.cratis.arc.artifacts.ArcArtifactModule").exists())
        assertFalse(root.resolve("build/proxies/Anchor.ts").exists())
        val incremental = snapshot("build")
        run("fresh", fresh = true)
        assertEquals(incremental, snapshot("fresh"), "Incremental terminal removal must equal fresh KSP generation")
    }

    private fun assertTypes(vararg names: String) {
        val manifest = ArcManifestDiscovery.discover(listOf(root.resolve("build/generated/ksp/main/resources"))).single().manifest
        assertEquals(names.map { "identity.$it" }.sorted(), manifest.types.map { it.fullyQualifiedName })
        assertEquals(8, manifest.formatVersion)
        assertTrue(manifest.commands.isEmpty())
        assertTrue(manifest.queries.isEmpty())
    }

    private fun assertProperties(type: String, vararg names: String) {
        val manifest = ArcManifestDiscovery.discover(listOf(root.resolve("build/generated/ksp/main/resources"))).single().manifest
        assertEquals(names.toList(), manifest.types.single { it.fullyQualifiedName == "identity.$type" }.properties.map { it.name })
    }

    private fun snapshot(build: String): Map<String, String> {
        val generated = root.resolve("$build/generated/ksp/main")
        return generated.walkTopDown().filter { it.isFile }.associate { it.relativeTo(generated).invariantSeparatorsPath to it.readText() }
    }

    private fun run(label: String, fails: Boolean = false, fresh: Boolean = false): org.gradle.testkit.runner.BuildResult {
        val arguments = mutableListOf("generateArcProxies", "--stacktrace", "--console=plain", "--no-configuration-cache",
            "--no-build-cache", "--gradle-user-home", System.getProperty("arc.onboarding.gradleUserHome"))
        if (fresh) {
            val cache = root.resolve(".gradle-$label")
            assertFalse(root.resolve(label).exists(), "Fresh output must never reuse an earlier comparison")
            assertFalse(cache.exists(), "Fresh project cache must never reuse task history")
            arguments += listOf("-PfreshOutput=$label", "--project-cache-dir", cache.absolutePath)
        }
        val runner = GradleRunner.create().withProjectDir(root)
            .withGradleInstallation(File(System.getProperty("arc.functional.gradleHome")))
            .withPluginClasspath(System.getProperty("arc.functional.pluginClasspath").split(File.pathSeparator).map(::File))
            .withArguments(arguments)
        return (if (fails) runner.buildAndFail() else runner.build()).also { result ->
            root.resolve("$label.log").writeText(result.output)
            System.getProperty("arc.handlerIndex.evidence")?.let { directory ->
                File(directory).resolve("${root.name}-$label.log").writeText("Fixture: $root\n" + result.output)
            }
            if (fresh) assertEquals(TaskOutcome.SUCCESS, result.task(":kspKotlin")?.outcome,
                "Fresh comparison must execute KSP, not reuse cached or up-to-date output: ${result.output}")
        }
    }

    private fun write(path: String, text: String) {
        root.resolve(path).apply { parentFile.mkdirs(); writeText(text + "\n") }
    }
}
