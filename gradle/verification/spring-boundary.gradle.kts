// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import java.nio.file.Files
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

// Inspect production outputs only. Do not pull in KSP compile fixtures, plugin functional tests,
// samples, or the Spring starter. No new module, published artifact, or tool dependency is needed.
gradle.projectsEvaluated {
    val source = project(":Source")
    val consumers = listOf(project(":CodeGeneration:KSP"), project(":GradlePlugin"))
    val modules = listOf(source) + consumers
    val compiler = source.extensions.getByType<JavaToolchainService>().compilerFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    val checker = rootProject.file("gradle/verification/check-spring-boundary.py")
    val fixtures = rootProject.file("gradle/verification/test-check-spring-boundary.py")
    val fixtureWork = providers.gradleProperty("arc.springBoundary.work")
        .orElse(layout.buildDirectory.dir("spring-boundary-fixtures").map { it.asFile.absolutePath })
    val regression = tasks.register<Exec>("testSpringBoundaryChecker") {
        group = "verification"
        description = "Compile mutations proving the Spring-free linkage checker rejects regressions."
        inputs.files(checker, fixtures, rootProject.file("gradle/verification/spring-boundary.gradle.kts"))
        doFirst {
            val parent = file(fixtureWork.get()).toPath()
            Files.createDirectories(parent)
            val work = Files.createTempDirectory(parent, "run-").resolve("fixtures")
            commandLine("python3", "-B", fixtures.absolutePath,
                "--javac", compiler.get().executablePath.asFile.absolutePath,
                "--jdeps", compiler.get().metadata.installationPath.file("bin/jdeps").asFile.absolutePath,
                "--work-dir", work.toString(),
                "--gradle", rootProject.file("gradlew").absolutePath)
        }
    }
    val boundary = tasks.register<Exec>("checkSpringBoundary") {
        group = "verification"
        description = "Enforce Spring-free compiled metadata and transitive KSP/Gradle-consumed types."
        dependsOn(regression)
        modules.forEach { module ->
            dependsOn(module.tasks.named("classes"))
            inputs.files(module.extensions.getByType<SourceSetContainer>().named("main").map { it.output.classesDirs })
        }
        modules.forEach { module ->
            listOf("compileClasspath", "runtimeClasspath").forEach {
                inputs.files(module.configurations.named(it))
            }
        }
        inputs.file(checker)
        // No outputs: always check, even when production compilation is up-to-date.
        doFirst {
            consumers.forEach { consumer ->
                listOf("compileClasspath", "runtimeClasspath").forEach { configuration ->
                    val spring = consumer.configurations.getByName(configuration)
                        .resolvedConfiguration.resolvedArtifacts.filter {
                            it.moduleVersion.id.group == "org.springframework" ||
                                it.moduleVersion.id.group.startsWith("org.springframework.")
                        }
                    check(spring.isEmpty()) { "Spring on ${consumer.path} $configuration: $spring" }
                }
            }
            val arguments = mutableListOf("python3", "-B", checker.absolutePath,
                "--jdeps", compiler.get().metadata.installationPath.file("bin/jdeps").asFile.absolutePath)
            modules.forEach { module ->
                val directories = module.extensions.getByType<SourceSetContainer>()
                    .getByName("main").output.classesDirs.files.filter { it.isDirectory }.sorted()
                check(directories.any { dir -> dir.walkTopDown().any { it.isFile && it.extension == "class" } }) {
                    "No compiled production classes in ${module.path}"
                }
                directories.forEach {
                    arguments.add(if (module == source) "--source" else "--consumer")
                    arguments.add(it.absolutePath)
                }
            }
            // Gradle includes Java output directories even for Kotlin-only NO-SOURCE sets.
            // Only omit absent outputs belonging to these already-validated local modules;
            // missing dependency files still fail closed in the checker.
            val absentOutputs = modules.flatMap { module ->
                module.extensions.getByType<SourceSetContainer>().getByName("main")
                    .output.classesDirs.files.filter { !it.exists() }
            }.toSet()
            modules.forEach { module ->
                listOf("compileClasspath", "runtimeClasspath").forEach { configuration ->
                    // Configuration.files includes local file dependencies, unlike Maven artifacts.
                    val entries = module.configurations.getByName(configuration).files.filterNot { it in absentOutputs }
                    logger.lifecycle("Spring boundary: ${module.path} $configuration — inspecting ${entries.size} entries")
                    entries.forEach {
                        arguments.add(if (module == source) "--classpath" else "--tool-classpath")
                        arguments.add(it.absolutePath)
                    }
                }
            }
            commandLine(arguments)
        }
    }
    modules.forEach { it.tasks.named("check") { dependsOn(boundary) } }
    tasks.matching { it.name == "check" }.configureEach { dependsOn(boundary) }
}
