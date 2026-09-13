// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/** Executes preferred tutorial fences against staged publications, not an injected plugin classpath. */
class ArcOnboardingFunctionalTest {
    private val version = requireNotNull(System.getProperty("arc.functional.version"))
    private val repository = File(property("repository"))
    private val documentation = File(property("documentation"))

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `preferred Kotlin tutorial resolves packaged marker and serves documented requests`() = verifyTutorial("index.md", "kotlin")

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `preferred Java tutorial resolves packaged marker and serves documented requests`() = verifyTutorial("java.md", "java")

    private fun verifyTutorial(page: String, language: String) {
        val markdown = documentation.resolve(page).readText()
        val fences = Regex("```(\\w+)\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL).findAll(markdown)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        val consumer = Files.createTempDirectory(File(property("work")).apply { mkdirs() }.toPath(), "$language-").toFile()
        println("TUTORIAL_CONSUMER ${consumer.absolutePath}")
        val settings = fences.single { it.second.startsWith("pluginManagement {") }.second
        // Only Arc is supplied locally. In particular, NEVER add Maven Central for application dependencies here.
        val localRepository = """
            exclusiveContent {
                forRepository {
                    maven {
                        url = uri("${repository.toURI()}")
                        metadataSources { mavenPom(); artifact() }
                    }
                }
                filter { includeGroup("io.cratis"); includeGroup("io.cratis.arc") }
            }
        """.trimIndent()
        write(consumer, "settings.gradle.kts", settings.replaceFirst("repositories {", "repositories {\n$localRepository"))
        val build = fences.first { it.second.startsWith("plugins {") }.second.replace("<version>", version)
        write(consumer, "build.gradle.kts", "import java.time.Duration\n\n" + build + "\nrepositories {\n$localRepository\n}\n" + verificationTasks())
        write(consumer, "gradle.properties", "org.gradle.jvmargs=-Xmx512m\norg.gradle.daemon=false\n" +
            "org.gradle.daemon.idletimeout=1000\nkotlin.compiler.execution.strategy=in-process\n")
        write(consumer, "src/main/resources/application.properties", fences.single { it.first == "properties" }.second)
        for ((_, source) in fences.filter { it.first == language && it.second.startsWith("package example") }) {
            val packageName = Regex("package ([\\w.]+)").find(source)!!.groupValues[1]
            val name = if (language == "java") {
                Regex("public (?:final )?(?:class|record) (\\w+)").find(source)!!.groupValues[1] + ".java"
            } else if (packageName == "example") "TaskApplication.kt" else "Tasks.kt"
            write(consumer, "src/main/$language/${packageName.replace('.', '/')}/$name", source)
        }
        val requests = Regex("curl -sS -X (POST|QUERY) (http://localhost:8080/\\S+)\\s*\\\\\\s*" +
            "-H '([^']+)'\\s*\\\\\\s*-d '([^']+)'", RegexOption.DOT_MATCHES_ALL).findAll(markdown).toList()
        assertEquals(listOf("POST", "QUERY"), requests.map { it.groupValues[1] })
        requests.forEachIndexed { index, request ->
            val (method, url, header, body) = request.destructured
            write(consumer, "src/main/resources/request-$index.txt", "$method\n${url.removePrefix("http://localhost:8080")}\n$header\n$body")
        }
        // Compare the complete documented envelopes, including the List/array paging-total distinction.
        val envelopes = Regex("```json\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
            .findAll(markdown).map { it.groupValues[1] }.toList()
        assertEquals(2, envelopes.size)
        envelopes.forEachIndexed { index, json -> write(consumer, "src/main/resources/expected-$index.json", json) }
        val probe = requireNotNull(javaClass.getResource("/onboarding/TutorialProbe.java")).readText()
        write(consumer, "src/main/java/probe/TutorialProbe.java", probe)
        assertFalse(consumer.resolve("build").exists())
        verifyMarker()
        val result = GradleRunner.create()
            .withProjectDir(consumer)
            .withGradleInstallation(File(requireNotNull(System.getProperty("arc.functional.gradleHome"))))
            .withTestKitDir(File(property("gradleUserHome")))
            .withArguments("verifyTutorialResolution", "tutorialProbe", "--max-workers=2", "--no-configuration-cache", "--console=plain",
                "--stacktrace", "--gradle-user-home", property("gradleUserHome"))
            .forwardOutput()
            .build()
        for (task in listOf("verifyTutorialResolution", "kspKotlin", "compileKotlin", "compileJava", "processResources", "tutorialProbe")) {
            assertEquals(TaskOutcome.SUCCESS, result.task(":$task")?.outcome, result.output)
        }
        assertTrue(result.output.contains("TUTORIAL_HTTP_VERIFIED POST /api/create-task QUERY /api/tasks"), result.output)
        assertTrue(result.output.contains("TUTORIAL_PLUGIN_ORIGIN_JAR"), result.output)
        val resources = consumer.resolve("build/resources/main")
        assertEquals("io.cratis.arc.generated.TaskApplicationArcArtifactModule\n", resources.resolve(
            "META-INF/services/io.cratis.arc.artifacts.ArcArtifactModule").readText())
        val artifacts = ArcManifestDiscovery.merge(ArcManifestDiscovery.discover(listOf(resources)))
        assertEquals(listOf("example.tasks.CreateTask"), artifacts.commands.map { it.typeName })
        assertEquals(listOf("all"), artifacts.queries.map { it.name })
        assertEquals("/api/tasks", artifacts.queries.single().explicitPath)
        val classes = listOf("build/classes/kotlin/main/io/cratis/arc/generated/TaskApplicationArcArtifactModule.class",
            "build/classes/java/main/probe/TutorialProbe.class") + if (language == "java") listOf(
            "build/classes/java/main/example/TaskApplication.class",
            "build/classes/java/main/example/tasks/CreateTask.class", "build/classes/java/main/example/tasks/TaskView.class",
            "build/classes/java/main/example/tasks/TaskRepository.class", "build/classes/java/main/example/tasks/TaskCreated.class"
        ) else listOf("build/classes/kotlin/main/example/TaskApplication.class", "build/classes/kotlin/main/example/tasks/CreateTask.class")
        classes.forEach { path ->
            consumer.resolve(path).inputStream().use { stream ->
                val bytes = stream.readNBytes(8)
                assertEquals(8, bytes.size, path)
                assertEquals(61, ((bytes[6].toInt() and 255) shl 8) or (bytes[7].toInt() and 255), path)
            }
        }
    }

    private fun verifyMarker() {
        val marker = repository.resolve("io/cratis/arc/io.cratis.arc.gradle.plugin/$version/io.cratis.arc.gradle.plugin-$version.pom")
        val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(marker)
        val dependency = xml.getElementsByTagName("dependency").item(0) as org.w3c.dom.Element
        assertEquals("io.cratis", dependency.getElementsByTagName("groupId").item(0).textContent)
        assertEquals("arc-gradle-plugin", dependency.getElementsByTagName("artifactId").item(0).textContent)
        assertEquals(version, dependency.getElementsByTagName("version").item(0).textContent)
        JarFile(repository.resolve("io/cratis/arc-gradle-plugin/$version/arc-gradle-plugin-$version.jar")).use { jar ->
            assertEquals(version, jar.manifest.mainAttributes.getValue("Implementation-Version"))
            assertTrue(jar.getJarEntry("META-INF/gradle-plugins/io.cratis.arc.properties") != null)
        }
    }

    private fun verificationTasks(): String = """
        val processorResolution by configurations.creating {
            isCanBeConsumed = false
            extendsFrom(configurations.getByName("ksp"))
        }
        tasks.register("verifyTutorialResolution") {
            doLast {
                val plugin = plugins.getPlugin("io.cratis.arc")
                val origin = File(plugin.javaClass.protectionDomain.codeSource.location.toURI())
                check(origin.isFile && origin.name.contains("arc-gradle-plugin-$version") && origin.extension == "jar")
                check(plugin.javaClass.`package`.implementationVersion == "$version")
                println("TUTORIAL_PLUGIN_ORIGIN_JAR " + origin)
                val expected = listOf(setOf("arc", "arc-spring-boot-starter"), setOf("arc", "arc-ksp"))
                listOf(configurations.runtimeClasspath.get(), processorResolution).forEachIndexed { index, configuration ->
                    val artifacts = configuration.resolvedConfiguration.resolvedArtifacts.filter { it.moduleVersion.id.group == "io.cratis" }
                    check(artifacts.map { it.name }.toSet() == expected[index])
                    artifacts.forEach {
                        check(it.moduleVersion.id.version == "$version")
                        val staged = file("${repository.toURI()}").resolve("io/cratis/" + it.name + "/$version/" + it.name + "-$version.jar")
                        check(it.file.readBytes().contentEquals(staged.readBytes()))
                    }
                    println("TUTORIAL_RESOLVED_GAVS " + artifacts.map { it.moduleVersion.id })
                }
                tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().forEach {
                    check(it.compilerOptions.allWarningsAsErrors.get())
                }
                tasks.withType<JavaCompile>().forEach {
                    check(it.options.compilerArgs.containsAll(listOf("-Xlint:all", "-Werror")))
                    check(it.options.release.get() == 17)
                }
            }
        }
        tasks.register<JavaExec>("tutorialProbe") {
            dependsOn("classes", "verifyTutorialResolution")
            classpath = sourceSets.main.get().runtimeClasspath
            mainClass.set("probe.TutorialProbe")
            timeout.set(Duration.ofMinutes(2))
        }
    """.trimIndent()

    private fun write(consumer: File, path: String, text: String) {
        consumer.resolve(path).apply { parentFile.mkdirs(); writeText(text + "\n") }
    }

    private fun property(name: String): String = requireNotNull(System.getProperty("arc.onboarding.$name"))
}
