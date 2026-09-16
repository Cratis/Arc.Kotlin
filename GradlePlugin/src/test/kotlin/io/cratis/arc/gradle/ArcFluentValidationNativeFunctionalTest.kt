// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.JarEntry
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real published-shaped plugin + production Arc processor, including native dependency invalidation. */
internal class ArcFluentValidationNativeFunctionalTest {
    private val version = System.getProperty("arc.functional.version")
    private val repository = File(System.getProperty("arc.onboarding.repository"))
    private val root = Files.createTempDirectory(File(System.getProperty("arc.fluent.evidence")
        ?: System.getProperty("arc.functional.work")).apply { mkdirs() }.toPath(), "fluent-native-").toFile()
    private val kotlinPath = "producer/src/main/kotlin/library/Rules.kt"
    private val javaPath = "producer/src/main/java/library/JavaRules.java"
    private val declarationResource = "META-INF/cratis/arc-fluent-validation/Producer.json"

    @Test
    fun `published production parser handles two languages dependencies edits recovery removal and true fresh outputs`() {
        fixture()
        val pom = repository.resolve("io/cratis/arc-ksp/$version/arc-ksp-$version.pom").readText()
        assertTrue("kotlin-compiler-embeddable" in pom && "2.4.20" in pom && "kctfork" !in pom)
        write("evidence/ksp.pom", pom)
        val missing = run("missing-parser", fails = true, extra = listOf("-PwithoutParser=true"))
        assertTrue("[ARCKSP0310]" in missing.output && "parser" in missing.output, missing.output)
        assertFalse(resource("producer", declarationResource).exists())
        val cold = run("cold")
        assertTrue("[ARC-FLUENT-PARSER]" in cold.output && "version=2.4.20; sameLoader=true; parentParser=false; javac=jdk.compiler" in cold.output, cold.output)
        assertTrue("[ARC-FLUENT-ROUND] 2" in cold.output, cold.output)
        assertTrue("APPLICATION_AND_PLUGIN_PARSER_ABSENT" in cold.output)
        assertTrue(root.resolve("producer/build/classes/java/main/library/JavaRules.class").isFile)
        assertTrue(root.resolve("producer/build/classes/kotlin/main/library/KotlinRules.class").isFile)
        assertRules(5, true)
        verifyClient("cold", 5)
        val aggregation = root.resolve("aggregation/build/generated/ksp/main/kotlin/io/cratis/arc/generated/AggregationArcArtifactModule.kt").readText()
        assertTrue("library.KotlinRules()" in aggregation && "library.JavaRules()" in aggregation)
        assertFalse(root.resolve("aggregation/build/generated/ksp/main/resources/META-INF/cratis/arc-fluent-validation/Aggregation.json").exists())
        val associatedIndex = root.resolve("consumer/build/arc/fluent-validation/test.json").readText()
        assertTrue("consumer.CreateRules" in associatedIndex && "consumer.JavaTagRules" in associatedIndex && "library.JavaRules" in associatedIndex, associatedIndex)
        val initial = snapshot("build")
        run("fresh-initial", fresh = true)
        assertEquals(initial, snapshot("fresh-initial"))
        assertEquals(TaskOutcome.UP_TO_DATE, run("noop", executed = false).task(":consumer:kspKotlin")?.outcome)

        write(kotlinPath, kotlinRules(7))
        val changed = run("dependency-edit")
        assertEquals(TaskOutcome.SUCCESS, changed.task(":consumer:kspKotlin")?.outcome, "Unchanged consumer source must rebuild from dependency index")
        assertRules(7, true)
        verifyClient("dependency-edit", 7)
        assertFalse(initial == snapshot("build"))
        write(kotlinPath, kotlinRules(7, invalid = true))
        val badKotlin = run("kotlin-invalid", fails = true)
        assertTrue("Rules.kt:3: [ARCKSP0308] Fluent validator 'library.KotlinRules'" in badKotlin.output, badKotlin.output)
        assertFalse(resource("producer", declarationResource).exists())
        write(kotlinPath, kotlinRules(7))
        assertEquals(TaskOutcome.SUCCESS, run("kotlin-recovered", executed = false).task(":producer:kspKotlin")?.outcome)
        val recoveredKotlin = snapshot("build")
        run("fresh-kotlin", fresh = true)
        assertEquals(recoveredKotlin, snapshot("fresh-kotlin"))

        write(javaPath, javaRules(12))
        run("java-edit")
        assertTrue("\"arguments\":[12]" in resource("consumer", "META-INF/cratis/arc/Consumer.json").readText())
        write(javaPath, javaRules(12, invalid = true))
        val badJava = run("java-invalid", fails = true)
        assertTrue("JavaRules.java:3: [ARCKSP0308] Fluent validator 'library.JavaRules'" in badJava.output, badJava.output)
        assertFalse(resource("producer", declarationResource).exists())
        write(javaPath, javaRules(12))
        assertEquals(TaskOutcome.SUCCESS, run("java-recovered", executed = false).task(":producer:kspKotlin")?.outcome)
        val recoveredJava = snapshot("build")
        run("fresh-java", fresh = true)
        assertEquals(recoveredJava, snapshot("fresh-java"))

        val personPath = "producer/src/main/kotlin/library/Person.kt"
        val originalPerson = root.resolve(personPath).readText()
        write(personPath, "package library\nclass Person(input: String) { val name = input; get() = field.trim() }")
        val computed = run("computed-accessor-invalid", fails = true)
        assertTrue("member 'name' is computed" in computed.output, computed.output)
        assertFalse(resource("producer", declarationResource).exists())
        write(personPath, originalPerson)
        run("computed-accessor-recovered", executed = false)
        val shadowPath = "producer/src/main/kotlin/library/Mapping.kt"
        write(shadowPath, """
            package library
            import kotlin.reflect.KClass
            val KClass<Person>.java: Class<Person>
                get() { check(System.getProperty("arc.test.runtime") == "true") { "BUILD_TIME_MAPPING_EXECUTED" }; return javaObjectType }
        """.trimIndent())
        val shadowed = run("class-mapping-invalid", fails = true)
        assertTrue("[ARCKSP0308]" in shadowed.output && "class mapping may be shadowed" in shadowed.output, shadowed.output)
        assertFalse(resource("producer", declarationResource).exists())
        assertTrue(root.resolve(shadowPath).delete())
        run("class-mapping-recovered", executed = false)

        // Runtime-only declarations must fail even when this consumer does not reference their model.
        write("consumer/src/main/kotlin/consumer/Create.kt", command(false))
        val runtimeOnly = run("runtime-only", fails = true, extra = listOf("-PruntimeOnlyProducer=true"))
        assertTrue("runtime-only=[library.JavaRules, library.KotlinRules]" in runtimeOnly.output, runtimeOnly.output)
        write("consumer/src/main/kotlin/consumer/Create.kt", command(true))
        val producerJar = root.resolve("producer/build/libs/producer.jar")
        val unindexed = root.resolve("unindexed.jar")
        JarFile(producerJar).use { jar -> JarOutputStream(unindexed.outputStream()).use { output ->
            jar.entries().asSequence().filter { !it.isDirectory && it.name != declarationResource }.forEach { entry ->
                output.putNextEntry(JarEntry(entry.name)); jar.getInputStream(entry).use { it.copyTo(output) }; output.closeEntry()
            }
        } }
        val missingMetadata = run("missing-index-resource", fails = true, extra = listOf("-PunindexedProducer=true"))
        assertTrue("unindexed=[library.JavaRules, library.KotlinRules]" in missingMetadata.output, missingMetadata.output)
        run("dependency-recovered", executed = false)

        assertTrue(root.resolve(javaPath).delete())
        run("java-removed")
        assertRules(7, false)
        assertTrue(root.resolve(kotlinPath).delete())
        run("all-library-rules-removed")
        assertFalse(resource("producer", declarationResource).exists())
        assertFalse(root.resolve("producer/build/resources/main/$declarationResource").exists())
        assertFalse(resource("consumer", "META-INF/cratis/arc/Consumer.json").readText().contains("Library name"))
        val removed = snapshot("build")
        run("fresh-removed", fresh = true)
        assertEquals(removed, snapshot("fresh-removed"))
        println("FLUENT_NATIVE_EVIDENCE $root")
    }

    @Test
    fun `ignore edge add remove recovery and fresh native outputs preserve declared rules`() {
        fixture()
        run("ignore-baseline")
        val baseline = snapshot("build")
        val declared = resource("producer", declarationResource).readBytes().toList()
        val commandPath = "consumer/src/main/kotlin/consumer/Create.kt"
        write(commandPath, command(true).replace("val person:", "@io.cratis.arc.validation.IgnoreValidation val person:"))
        run("ignore-added")
        assertEquals(declared, resource("producer", declarationResource).readBytes().toList())
        val manifest = resource("consumer", "META-INF/cratis/arc/Consumer.json").readText()
        assertTrue("\"ignoreValidation\":true" in manifest, manifest)
        val proxy = root.resolve("consumer/build/proxies/consumer/Create.ts").readText()
        assertTrue("person" in proxy)
        assertFalse("value.person" in proxy, proxy)
        val ignored = snapshot("build")
        run("ignore-fresh", fresh = true)
        assertEquals(ignored, snapshot("ignore-fresh"))
        write("consumer/src/main/java/consumer/InvalidIgnore.java", """
            package consumer;
            public final class InvalidIgnore {
                @io.cratis.arc.validation.IgnoreValidation public void setName(String value) {}
            }
        """.trimIndent())
        val invalid = run("ignore-invalid", fails = true)
        assertTrue("InvalidIgnore.java:3: [ARCKSP0311]" in invalid.output, invalid.output)
        assertTrue(root.resolve("consumer/src/main/java/consumer/InvalidIgnore.java").delete())
        run("ignore-recovered", executed = false)
        assertEquals(ignored, snapshot("build"))
        write(commandPath, command(true))
        run("ignore-removed")
        assertEquals(baseline, snapshot("build"))
        run("ignore-removed-fresh", fresh = true)
        assertEquals(baseline, snapshot("ignore-removed-fresh"))

        val personPath = "producer/src/main/kotlin/library/Person.kt"
        val originalPerson = root.resolve(personPath).readText()
        write(personPath, originalPerson.replace("val name:", "@io.cratis.arc.validation.IgnoreValidation val name:"))
        run("dependency-ignore-added", extra = listOf("-PignoreExpected=true"))
        assertEquals(declared, resource("producer", declarationResource).readBytes().toList(), "Authored dependency declarations are unchanged")
        val imported = io.cratis.arc.json.ArcObjectMapper.create().readValue(resource("consumer", "META-INF/cratis/arc/Consumer.json"), io.cratis.arc.artifacts.ArcArtifactManifest::class.java)
        val member = imported.types.single { it.fullyQualifiedName == "library.Person" }.properties.single { it.name == "name" }
        assertTrue(member.ignoreValidation)
        assertTrue(member.validationRules.isEmpty())
        assertFalse("ruleFor(c => c.name)" in root.resolve("consumer/build/proxies/library/Person.ts").readText())
        val dependencyIgnored = snapshot("build")
        run("dependency-ignore-fresh", fresh = true, extra = listOf("-PignoreExpected=true"))
        assertEquals(dependencyIgnored, snapshot("dependency-ignore-fresh"))
        write(personPath, originalPerson)
        run("dependency-ignore-removed")
        assertEquals(baseline, snapshot("build"))
        verifyClient("dependency-ignore-restored", 5)

        // An explicit accessor leaves a header annotation only on the PRIVATE record field.
        // Its edit must invalidate the unchanged consumer even when public ABI/declaration indexes do not change.
        val recordPath = "producer/src/main/java/library/BinaryData.java"
        val recordSource = "package library; public record BinaryData(String value) { public String value() { return value; } }"
        write(recordPath, recordSource)
        write(commandPath, command(true).replace("val tag:", "val binary: library.BinaryData, val tag:"))
        run("record-field-baseline")
        fun importedRecord() = io.cratis.arc.json.ArcObjectMapper.create().readValue(resource("consumer", "META-INF/cratis/arc/Consumer.json"), io.cratis.arc.artifacts.ArcArtifactManifest::class.java)
            .types.single { it.fullyQualifiedName == "library.BinaryData" }.properties.single()
        assertFalse(importedRecord().ignoreValidation)
        val recordBaseline = snapshot("build")
        write(recordPath, recordSource.replace("String value)", "@io.cratis.arc.validation.IgnoreValidation String value)"))
        run("record-field-ignore-added")
        assertTrue(importedRecord().ignoreValidation)
        assertEquals(declared, resource("producer", declarationResource).readBytes().toList())
        val recordIgnored = snapshot("build")
        run("record-field-ignore-fresh", fresh = true)
        assertEquals(recordIgnored, snapshot("record-field-ignore-fresh"))
        write(recordPath, recordSource)
        run("record-field-ignore-removed")
        assertFalse(importedRecord().ignoreValidation)
        assertEquals(recordBaseline, snapshot("build"))
        println("IGNORE_NATIVE_EVIDENCE $root")
    }

    private fun fixture() {
        write("settings.gradle", """
            pluginManagement { repositories { maven { url = uri('${repository.toURI()}') }; gradlePluginPortal(); mavenCentral() } }
            rootProject.name='FluentProduction'
            include 'producer', 'consumer', 'aggregation'
        """.trimIndent())
        write("gradle.properties", "org.gradle.jvmargs=-Xmx768m\norg.gradle.workers.max=1\nkotlin.compiler.execution.strategy=in-process\nksp.incremental=true\nksp.incremental.log=true\n")
        write("build.gradle", """
            plugins { id 'io.cratis.arc' version '$version' apply false }
            subprojects {
                apply plugin: 'io.cratis.arc'
                repositories { maven { url = uri('${repository.toURI()}') }; mavenCentral() }
                cratisArc { moduleName.set(project.name.capitalize()); dependencyVersion.set('$version'); proxies.outputDirectory.set(layout.buildDirectory.dir('proxies')) }
                ksp { arg('arc.fluentValidationTrace', 'true') }
                def fresh = providers.gradleProperty('freshOutput').orNull
                if (fresh != null) layout.buildDirectory.set(layout.projectDirectory.dir(fresh))
                if (providers.gradleProperty('withoutParser').isPresent()) configurations.ksp.exclude group:'org.jetbrains.kotlin', module:'kotlin-compiler-embeddable'
                tasks.matching { it.name == 'kspKotlin' }.configureEach {
                    doFirst {
                        println 'ACTUAL_PROCESSOR ' + kspConfig.processorClasspath.files.sort()
                        assert !configurations.runtimeClasspath.files.any { it.name.contains('compiler-embeddable') || it.name.startsWith('arc-ksp-') }
                        assert !rootProject.buildscript.configurations.classpath.files.any { it.name.contains('compiler-embeddable') }
                        println 'APPLICATION_AND_PLUGIN_PARSER_ABSENT'
                    }
                }
            }
            project(':aggregation') { dependencies { implementation project(':producer') } }
            project(':consumer') {
                tasks.register('verifyRuntime', JavaExec) {
                    dependsOn tasks.named('classes')
                    classpath = sourceSets.main.runtimeClasspath
                    mainClass.set('consumer.Verify')
                    systemProperty 'arc.test.runtime', 'true'
                    systemProperty 'arc.test.ignoreExpected', providers.gradleProperty('ignoreExpected').getOrElse('false')
                }
                dependencies {
                    if (providers.gradleProperty('unindexedProducer').isPresent()) implementation files(rootProject.file('unindexed.jar'))
                    else if (providers.gradleProperty('runtimeOnlyProducer').isPresent()) runtimeOnly project(':producer')
                    else implementation project(':producer')
                }
            }
        """.trimIndent())
        write("producer/src/main/kotlin/library/Person.kt", """
            package library
            class Person(val name: String) {
                init { check(System.getProperty("arc.test.runtime") == "true") { "BUILD_TIME_APPLICATION_CONSTRUCTOR_EXECUTED" } }
            }
        """.trimIndent())
        write("aggregation/src/main/kotlin/aggregate/Marker.kt", "package aggregate\nclass Marker")
        write(kotlinPath, kotlinRules(5))
        write(javaPath, javaRules())
        write("consumer/src/main/kotlin/consumer/Create.kt", command(true))
        write("consumer/src/main/java/consumer/Verify.java", """
            package consumer;
            import io.cratis.arc.artifacts.*;
            import io.cratis.arc.validation.*;
            import io.cratis.arc.results.ValidationResult;
            import java.util.*;
            public final class Verify {
                public static void main(String[] args) {
                    var modules = ServiceLoader.load(ArcArtifactModule.class).stream().map(ServiceLoader.Provider::get).toList();
                    var discovered = ArcArtifactModuleRegistry.modelValidators(modules);
                    var direct = ArcArtifactModuleRegistry.modelValidators(List.of(new io.cratis.arc.generated.ConsumerArcArtifactModule()));
                    if (direct.size() != discovered.size()) throw new AssertionError("In-process module lost dependency rules");
                    var person = new library.Person("a very long value that fails every declared length bound");
                    int checked = 0;
                    for (var validator : discovered) {
                        if (validator instanceof FluentModelValidator<?> fluent && fluent.getModelType().equals(library.Person.class)) {
                            var results = evaluate(fluent, person);
                            boolean ignored = Boolean.getBoolean("arc.test.ignoreExpected");
                            if (ignored ? !results.isEmpty() : results.size() != 1 || !results.get(0).getMembers().equals(List.of("name"))) throw new AssertionError(results);
                            checked++;
                        }
                    }
                    System.out.println("FLUENT_RUNTIME_VERIFIED declarations=" + discovered.size() + " checked=" + checked);
                }
                private static <T> List<ValidationResult> evaluate(FluentModelValidator<T> validator, Object value) {
                    return validator.validate(validator.getModelType().cast(value));
                }
            }
        """.trimIndent())
        write("consumer/src/main/java/consumer/JavaTagRules.java", """
            package consumer;
            import io.cratis.arc.validation.FluentModelValidator;
            public final class JavaTagRules extends FluentModelValidator<Create> {
                public JavaTagRules() { super(Create.class); ruleFor("tag").maxLength(100); }
            }
        """.trimIndent())
        write("consumer/src/test/kotlin/consumer/MainRulesUsage.kt", "package consumer\nclass MainRulesUsage { val type: Class<*> = CreateRules::class.java }")
        write("consumer/src/main/kotlin/consumer/Rules.kt", """
            package consumer
            import io.cratis.arc.validation.FluentModelValidator
            class CreateRules : FluentModelValidator<Create>(Create::class.java) {
                init { ruleFor("tag").notEmpty() }
            }
        """.trimIndent())
    }

    private fun kotlinRules(max: Int, invalid: Boolean = false) = """
        package library
        import io.cratis.arc.validation.FluentModelValidator
        class KotlinRules : FluentModelValidator<Person>(Person::class.java) {
            init { ruleFor("name").notNull().maxLength($max).withMessage("Library name"); ${if (invalid) "if (true) println(\"forbidden\")" else ""} }
        }
    """.trimIndent()
    private fun javaRules(max: Int = 10, invalid: Boolean = false) = """
        package library;
        import io.cratis.arc.validation.FluentModelValidator;
        public final class JavaRules extends FluentModelValidator<Person> {
            public JavaRules() { super(Person.class); ruleFor("name").maxLength($max); ${if (invalid) "if (true) System.out.println(\"forbidden\");" else ""} }
        }
    """.trimIndent()
    private fun command(person: Boolean) = """
        package consumer
        @io.cratis.arc.artifacts.Command
        class Create(${if (person) "val person: library.Person, " else ""}val tag: String) { fun handle(): String = tag }
    """.trimIndent()

    private fun assertRules(max: Int, java: Boolean) {
        val producer = resource("producer", declarationResource).readText()
        val consumer = resource("consumer", "META-INF/cratis/arc/Consumer.json").readText()
        assertTrue("Library name" in producer && "\"arguments\":[$max]" in producer, producer)
        assertEquals(java, "library.JavaRules" in producer)
        assertTrue("Library name" in consumer && "\"arguments\":[$max]" in consumer, consumer)
        val exported = resource("consumer", "META-INF/cratis/arc-fluent-validation/Consumer.json").readText()
        assertTrue("consumer.CreateRules" in exported)
        assertFalse("library.KotlinRules" in exported || "library.JavaRules" in exported, "Imported declarations must not be re-exported")
        val module = root.resolve("consumer/build/generated/ksp/main/kotlin/io/cratis/arc/generated/ConsumerArcArtifactModule.kt").readText()
        assertTrue("library.KotlinRules()" in module, "In-process consumer modules must retain imported runtime rules")
        val classpath = listOf("consumer/build/classes/kotlin/main", "consumer/build/classes/java/main", "consumer/build/resources/main", "producer/build/libs/producer.jar").map(root::resolve)
        assertTrue(ArcManifestDiscovery.discover(classpath, "Consumer").isNotEmpty())
    }
    private fun verifyClient(label: String, max: Int) {
        val script = root.resolve("verify-client-$label.ts")
        script.writeText("""
            import assert from 'node:assert/strict';
            import { CreateValidator } from './consumer/build/proxies/consumer/Create';
            import { PersonValidator } from './consumer/build/proxies/library/Person';
            const good = {name: 'ok'};
            assert.equal(new PersonValidator().validate(good).length, 0);
            const bad = {name: 'x'.repeat(${max + 1})};
            assert.deepEqual(new PersonValidator().validate(bad).map(r => r.message), ['Library name']);
            const feedback = new CreateValidator().validate({person: bad, tag: 'ok'});
            assert.deepEqual(feedback.map(r => r.members), [['person.name']]);
            assert.equal(new CreateValidator().validate({person: good, tag: ''}).length, 1);
            console.log('IMPORTED_SHARED_CLIENT_VERIFIED max=$max');
        """.trimIndent())
        val nodeModules = File(System.getProperty("arc.mapping.nodeModules"))
        val log = root.resolve("evidence/$label-client.log")
        val process = ProcessBuilder("node", "--require", nodeModules.resolve("tsx/dist/cjs/index.cjs").absolutePath, script.absolutePath)
            .directory(root).redirectErrorStream(true).redirectOutput(log).apply { environment()["NODE_PATH"] = nodeModules.absolutePath }.start()
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "Client probe timed out")
        assertEquals(0, process.exitValue(), log.readText())
        assertTrue("IMPORTED_SHARED_CLIENT_VERIFIED max=$max" in log.readText())
    }

    private fun resource(module: String, path: String): File = root.resolve("$module/build/generated/ksp/main/resources/$path")
    private fun snapshot(build: String): Map<String, List<Byte>> = listOf("producer", "consumer", "aggregation").flatMap { module ->
        val directory = root.resolve("$module/$build/generated/ksp/main")
        directory.walkTopDown().filter { it.isFile }.map { "$module/${it.relativeTo(directory).invariantSeparatorsPath}" to it.readBytes().toList() }.toList()
    }.toMap()
    private fun run(label: String, fails: Boolean = false, fresh: Boolean = false, executed: Boolean = true, extra: List<String> = emptyList()): BuildResult {
        val arguments = mutableListOf(":producer:jar", ":consumer:classes", "--info", "--stacktrace", "--no-configuration-cache", "--no-build-cache", "--max-workers=1", "--console=plain",
            "--gradle-user-home", System.getProperty("arc.onboarding.gradleUserHome"))
        if (fresh) {
            assertFalse(root.resolve("producer/$label").exists()); assertFalse(root.resolve("consumer/$label").exists()); assertFalse(root.resolve(".gradle-$label").exists())
            arguments += listOf("-PfreshOutput=$label", "--project-cache-dir", root.resolve(".gradle-$label").absolutePath)
        }
        arguments += extra
        if (!fails) arguments += listOf(":consumer:verifyRuntime", ":consumer:testClasses", ":aggregation:classes", ":consumer:generateArcProxies")
        write("evidence/$label-command.txt", arguments.joinToString("\n"))
        listOf("producer", "consumer", "aggregation").forEach { module -> root.resolve("$module/src").copyRecursively(root.resolve("evidence/$label-input/$module/src")) }
        val runner = GradleRunner.create().withProjectDir(root).withGradleInstallation(File(System.getProperty("arc.functional.gradleHome")))
            .withTestKitDir(File(System.getProperty("arc.onboarding.gradleUserHome"))).withArguments(arguments)
        val result = try { if (fails) runner.buildAndFail() else runner.build() } catch (exception: org.gradle.testkit.runner.UnexpectedBuildFailure) {
            write("evidence/$label.log", exception.buildResult.output)
            throw exception
        } catch (exception: org.gradle.testkit.runner.UnexpectedBuildSuccess) {
            write("evidence/$label.log", exception.buildResult.output)
            throw exception
        }
        write("evidence/$label.log", result.output)
        write("evidence/$label-tasks.txt", result.tasks.joinToString("\n") { "${it.path} ${it.outcome}" })
        val build = if (fresh) label else "build"
        listOf("producer", "consumer", "aggregation").forEach { module ->
            val generated = root.resolve("$module/$build/generated/ksp/main")
            if (generated.isDirectory) generated.copyRecursively(root.resolve("evidence/$label-output/$module"))
        }
        if (!fails && executed) assertEquals(TaskOutcome.SUCCESS, result.task(":consumer:kspKotlin")?.outcome, result.output)
        if (!fails) assertTrue("FLUENT_RUNTIME_VERIFIED" in result.output, result.output)
        return result
    }
    private fun write(path: String, content: String) { root.resolve(path).apply { parentFile.mkdirs(); writeText(content) } }
}
