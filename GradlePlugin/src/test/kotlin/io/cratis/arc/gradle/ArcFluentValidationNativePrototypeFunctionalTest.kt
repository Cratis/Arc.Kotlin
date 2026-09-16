// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

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

/** Bounded native packaging/lifecycle gate for the test-only parser, not a delivered validation DSL. */
internal class ArcFluentValidationNativePrototypeFunctionalTest {
    private val version = System.getProperty("arc.functional.version")
    private val repository = File(System.getProperty("arc.functional.repository"))
    private val processor = File(System.getProperty("arc.prototype.processorJar"))
    private val root = Files.createTempDirectory(File(System.getProperty("arc.prototype.evidence")
        ?: System.getProperty("arc.functional.work")).apply { mkdirs() }.toPath(), "native-prototype-").toFile()
    private val resource = "generated/ksp/main/resources/prototype-descriptors.txt"
    private val kotlinPath = "src/main/kotlin/fixture/KotlinRules.kt"
    private val javaPath = "src/main/java/fixture/JavaRules.java"

    @Test
    fun `service loaded Kotlin and Java parsers isolate dependencies and recover native incremental descriptors`() {
        assertPackaging()
        writeFixture()
        val missing = run("missing-parser", fails = true, parser = false)
        assertTrue("[PROTOTYPE-PARSER-MISSING] Explicit native processor compiler-embeddable dependency required." in missing.output, missing.output)
        assertFalse(root.resolve("build/$resource").exists())
        assertFalse("kotlin-compiler-embeddable" in actualClasspath(missing), missing.output)

        val cold = run("cold")
        assertOrigin(cold)
        assertDescriptors("Too long", "Too long")
        assertTrue(root.resolve("build/classes/java/main/fixture/JavaRules.class").isFile, "Ordinary Java must compile")
        assertTrue(root.resolve("build/classes/kotlin/main/fixture/KotlinRules.class").isFile)
        val initial = snapshot("build")
        run("fresh-initial", fresh = true)
        assertEquals(initial, snapshot("fresh-initial"), "Independent cold outputs must be byte-identical")
        assertEquals(TaskOutcome.UP_TO_DATE, run("noop", success = false).task(":kspKotlin")?.outcome)

        write(kotlinPath, kotlinRules("Edited Kotlin"))
        run("kotlin-edited")
        assertDescriptors("Edited Kotlin", "Too long")
        assertFalse(initial == snapshot("build"), "Source edit must move actual descriptor bytes")
        write(kotlinPath, kotlinRules("Edited Kotlin", invalid = true))
        val kotlinFailure = run("kotlin-invalid", fails = true)
        assertDiagnostic(kotlinFailure, "KotlinRules.kt", "KotlinRules", "only direct fluent chains")
        assertFalse(root.resolve("build/$resource").exists(), "Neither partial nor previous current KSP descriptors may survive failure")
        write(kotlinPath, kotlinRules("Edited Kotlin"))
        run("kotlin-corrected")
        assertDescriptors("Edited Kotlin", "Too long")
        val correctedKotlin = snapshot("build")
        run("fresh-kotlin-corrected", fresh = true)
        assertEquals(correctedKotlin, snapshot("fresh-kotlin-corrected"))

        write(javaPath, javaRules("Edited Java"))
        run("java-edited")
        assertDescriptors("Edited Kotlin", "Edited Java")
        write(javaPath, javaRules("Edited Java", invalid = true))
        val javaFailure = run("java-invalid", fails = true)
        assertDiagnostic(javaFailure, "JavaRules.java", "JavaRules", "only expression statements allowed")
        assertFalse(root.resolve("build/$resource").exists(), "A valid sibling must not escape as a partial resource")
        write(javaPath, javaRules("Edited Java"))
        run("java-corrected")
        assertDescriptors("Edited Kotlin", "Edited Java")
        val correctedJava = snapshot("build")
        run("fresh-java-corrected", fresh = true)
        assertEquals(correctedJava, snapshot("fresh-java-corrected"))

        assertTrue(root.resolve(kotlinPath).delete())
        run("kotlin-removed")
        assertDescriptors(null, "Edited Java")
        assertTrue(root.resolve(javaPath).delete())
        run("all-declarations-removed")
        assertFalse(root.resolve("build/$resource").exists(), "Terminal declaration removal must drop stale aggregate resource")
        assertFalse(root.resolve("build/resources/main/prototype-descriptors.txt").exists(), "Packaged resource must also be removed")
        val removed = snapshot("build")
        run("fresh-removed", fresh = true)
        assertEquals(removed, snapshot("fresh-removed"), "True fresh vs incremental terminal removal")
        println("PROTOTYPE native evidence: $root; 15 native builds, 2 source failures, 1 missing-parser control")
    }

    private fun assertPackaging() {
        JarFile(processor).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            val classes = names.filter { it.endsWith(".class") }
            assertTrue(classes.any { it.endsWith("PrototypeKotlinParser.class") })
            assertTrue(classes.any { it.endsWith("PrototypeJavaParser.class") })
            assertFalse(classes.any { "CompilationTest.class" in it || "org/jetbrains" in it || "junit" in it })
            assertEquals("io.cratis.arc.codegeneration.ksp.ArcFluentValidationNativePrototypeProvider\n",
                jar.getInputStream(jar.getJarEntry("META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider")).reader().readText())
            write("processor-jar-inventory.txt", names.sorted().joinToString("\n"))
        }
        val published = repository.resolve("io/cratis/arc-ksp/$version/arc-ksp-$version.jar")
        JarFile(published).use { jar ->
            assertFalse(jar.entries().asSequence().any { "Prototype" in it.name || "org/jetbrains/kotlin/cli" in it.name })
        }
        val pom = published.resolveSibling("arc-ksp-$version.pom").readText()
        // Production now owns the exact parser dependency. The prototype still verifies isolation
        // and its missing-dependency control explicitly excludes that new transitive edge below.
        assertTrue("kotlin-compiler-embeddable" in pom && "2.4.20" in pom)
        assertFalse("kctfork" in pom)
        write("published-ksp.pom", pom)
    }

    private fun writeFixture() {
        write("settings.gradle", "rootProject.name='FluentNativePrototype'")
        write("gradle.properties", "org.gradle.jvmargs=-Xmx768m\norg.gradle.workers.max=1\nkotlin.compiler.execution.strategy=in-process\nksp.incremental=true\nksp.incremental.log=true")
        write("build.gradle", """
            plugins { id 'io.cratis.arc' }
            repositories { maven { url = uri('${repository.toURI()}') }; mavenCentral() }
            cratisArc { moduleName.set('FluentNativePrototype'); dependencyVersion.set('$version') }
            def freshOutput = providers.gradleProperty('freshOutput').orNull
            if (freshOutput != null) layout.buildDirectory.set(layout.projectDirectory.dir(freshOutput))
            dependencies {
                implementation 'jakarta.validation:jakarta.validation-api:3.1.1'
                ksp files('${processor.invariantSeparatorsPath}')
                if (providers.gradleProperty('withParser').get() == 'true') {
                    ksp 'org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20'
                }
            }
            if (providers.gradleProperty('withParser').get() != 'true') {
                configurations.ksp.exclude group:'org.jetbrains.kotlin', module:'kotlin-compiler-embeddable'
            }
            configurations { prototypeResolution { canBeConsumed = false; canBeResolved = true; extendsFrom ksp } }
            tasks.matching { it.name == 'kspKotlin' }.configureEach {
                doFirst {
                    def actual = kspConfig.processorClasspath.files
                    assert actual == configurations.prototypeResolution.files
                    println 'ACTUAL_KSP_PROCESSOR_CLASSPATH ' + actual.sort()
                    println 'RESOLVED_NATIVE_PROCESSOR ' + configurations.prototypeResolution.resolvedConfiguration.resolvedArtifacts
                        .collect { it.moduleVersion.id.toString() + ' -> ' + it.file }.sort()
                    assert actual.contains(file('${processor.invariantSeparatorsPath}'))
                    assert !configurations.runtimeClasspath.files.any { it.name.contains('compiler-embeddable') || it == file('${processor.invariantSeparatorsPath}') }
                    println 'APPLICATION_PARSER_DEPENDENCIES_ABSENT'
                }
            }
        """.trimIndent())
        // Real Arc invoker generation forces additional KSP rounds alongside the prototype processor.
        write("src/main/kotlin/fixture/Anchor.kt", """
            package fixture
            @io.cratis.arc.artifacts.Command
            class Anchor(val value: String) { fun handle(): String = value }
        """.trimIndent())
        write("src/main/java/prototype/Extract.java", "package prototype; public @interface Extract {}")
        write("src/main/java/prototype/ValidationDeclaration.java", """
            package prototype;
            public abstract class ValidationDeclaration<T> {
                protected ValidationDeclaration() { throw new AssertionError("APPLICATION CONSTRUCTOR EXECUTED"); }
                protected final Chain ruleFor(String property) { throw new AssertionError("APPLICATION DSL EXECUTED"); }
                public static final class Chain {
                    public Chain notNull() { throw new AssertionError(); }
                    public Chain maxLength(int length) { throw new AssertionError(); }
                    public Chain withMessage(String message) { throw new AssertionError(); }
                }
            }
        """.trimIndent())
        write("src/main/java/fixture/Person.java", """
            package fixture;
            public final class Person {
                @jakarta.validation.constraints.Size(max=40) public String name;
            }
        """.trimIndent())
        write(kotlinPath, kotlinRules("Too long"))
        write(javaPath, javaRules("Too long"))
    }

    private fun kotlinRules(message: String, invalid: Boolean = false): String = """
        package fixture
        import prototype.ValidationDeclaration
        @prototype.Extract
        class KotlinRules : ValidationDeclaration<Person>() {
            init {
                ruleFor("name").maxLength(40).withMessage("$message").notNull()
                ruleFor("name").maxLength(40)
                ${if (invalid) "if (true) ruleFor(\"name\").notNull()" else "// ruleFor(\"decoy\").creditCard()"}
            }
        }
    """.trimIndent()

    private fun javaRules(message: String, invalid: Boolean = false): String = """
        package fixture;
        import prototype.ValidationDeclaration;
        @prototype.Extract
        public final class JavaRules extends ValidationDeclaration<Person> {
            public JavaRules() {
                ruleFor("name").maxLength(40).withMessage("$message").notNull();
                ruleFor("name").maxLength(40);
                ${if (invalid) "if (true) ruleFor(\"name\").notNull();" else "// ruleFor(\"decoy\").creditCard();"}
            }
        }
    """.trimIndent()

    private fun assertDescriptors(kotlinMessage: String?, javaMessage: String) {
        fun line(name: String, message: String): String = "fixture.$name->fixture.Person={name=[" +
            "ValidationRuleDescriptor(ruleName=notNull, arguments=[], message=null), " +
            "ValidationRuleDescriptor(ruleName=maxLength, arguments=[40], message=null), " +
            "ValidationRuleDescriptor(ruleName=maxLength, arguments=[40], message=$message)]}"
        val expected = listOfNotNull(line("JavaRules", javaMessage), kotlinMessage?.let { line("KotlinRules", it) }).joinToString("\n")
        assertEquals(expected, root.resolve("build/$resource").readText())
        assertEquals(expected, root.resolve("build/resources/main/prototype-descriptors.txt").readText())
    }

    private fun assertDiagnostic(result: BuildResult, file: String, name: String, reason: String) {
        val diagnostic = "$file:4: [PROTOTYPE] 'fixture.$name': $reason; use only the restricted literal declaration grammar."
        assertTrue(diagnostic in result.output, result.output)
        assertEquals(1, result.output.lineSequence().count { diagnostic in it }, "Exactly one source-local test diagnostic")
    }

    private fun actualClasspath(result: BuildResult): String = result.output.lineSequence()
        .single { it.startsWith("ACTUAL_KSP_PROCESSOR_CLASSPATH ") }

    private fun assertOrigin(result: BuildResult) {
        val origin = result.output.lineSequence().single { "[PROTOTYPE-ORIGIN]" in it }
        assertTrue("provider=${processor.toURI().toURL()}" in origin, origin)
        assertTrue("kotlin-compiler-embeddable-2.4.20.jar" in origin, origin)
        assertTrue("version=2.4.20; sameLoader=true; parentParser=false;" in origin, origin)
        assertTrue("java=17." in origin && "javac=jdk.compiler" in origin, origin)
        assertTrue("kotlin-compiler-embeddable-2.4.20.jar" in actualClasspath(result))
        assertTrue("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20 ->" in result.output)
        assertTrue("[PROTOTYPE-ROUND] 2" in result.output, "Exercise native multi-round aggregation: ${result.output}")
        assertTrue("APPLICATION_PARSER_DEPENDENCIES_ABSENT" in result.output)
    }

    /** Hex encodes untouched bytes; comparison never normalizes content or paths embedded in artifacts. */
    private fun snapshot(build: String): Map<String, String> {
        val generated = root.resolve("$build/generated/ksp/main")
        return generated.walkTopDown().filter { it.isFile }.associate { file ->
            file.relativeTo(generated).invariantSeparatorsPath to file.readBytes().joinToString("") { "%02x".format(it) }
        }
    }

    private fun run(label: String, fails: Boolean = false, fresh: Boolean = false, parser: Boolean = true, success: Boolean = true): BuildResult {
        val arguments = mutableListOf("classes", "--stacktrace", "--console=plain", "--no-configuration-cache", "--no-build-cache",
            "--max-workers=1", "--gradle-user-home", System.getProperty("arc.onboarding.gradleUserHome"), "-PwithParser=$parser")
        if (fresh) {
            val cache = root.resolve(".gradle-$label")
            assertFalse(root.resolve(label).exists(), "Fresh output must be unique and empty")
            assertFalse(cache.exists(), "Fresh project cache must be unique and empty")
            arguments += listOf("-PfreshOutput=$label", "--project-cache-dir", cache.absolutePath)
        }
        write("$label-command.txt", arguments.joinToString("\n"))
        // Preserve deleted/invalid inputs as evidence too, not just the terminal corrected fixture.
        root.resolve("src").walkTopDown().filter { it.isFile }.forEach { file ->
            val target = root.resolve("inputs/$label/${file.relativeTo(root).invariantSeparatorsPath}")
            target.parentFile.mkdirs()
            file.copyTo(target)
        }
        val runner = GradleRunner.create().withProjectDir(root)
            .withGradleInstallation(File(System.getProperty("arc.functional.gradleHome")))
            .withPluginClasspath(System.getProperty("arc.functional.pluginClasspath").split(File.pathSeparator).map(::File))
            .withArguments(arguments)
        return (if (fails) runner.buildAndFail() else runner.build()).also { result ->
            write("$label.log", result.output)
            write("$label-tasks.txt", result.tasks.joinToString("\n") { "${it.path} ${it.outcome}" })
            if (fails) assertEquals(TaskOutcome.FAILED, result.task(":kspKotlin")?.outcome, result.output)
            else if (success) assertEquals(TaskOutcome.SUCCESS, result.task(":kspKotlin")?.outcome, result.output)
            // Retain each stage's exact generated bytes, including absence after failure/removal.
            val generated = root.resolve("${if (fresh) label else "build"}/generated/ksp/main")
            write("$label-artifacts.txt", snapshot(if (fresh) label else "build").keys.sorted().joinToString("\n"))
            // A failed KSP task never runs ProcessResources: record any LAST-SUCCESSFUL copied
            // resource separately; it is not current generated output or a successful build.
            val processed = root.resolve("${if (fresh) label else "build"}/resources/main/prototype-descriptors.txt")
            write("$label-processed-resource.txt", processed.takeIf { it.isFile }?.readText() ?: "ABSENT")
            generated.walkTopDown().filter { it.isFile }.forEach { file ->
                val target = root.resolve("evidence/$label/${file.relativeTo(generated).invariantSeparatorsPath}")
                target.parentFile.mkdirs()
                file.copyTo(target)
            }
        }
    }

    private fun write(path: String, text: String) {
        root.resolve(path).apply { parentFile.mkdirs(); writeText(text + "\n") }
    }
}
