// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.spring") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.vanniktech.maven.publish") version "0.33.0" apply false
    id("com.github.ben-manes.versions") version "0.61.0"
    id("se.patrikerdes.use-latest-versions") version "0.2.19"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.2"
}

subprojects {
    apply(plugin = "com.github.ben-manes.versions")
    apply(plugin = "se.patrikerdes.use-latest-versions")
}

fun isNonStable(version: String): Boolean {
    val hasUnstableKeyword = listOf("ALPHA", "BETA", "RC", "M", "PREVIEW", "EAP")
        .any { version.uppercase().contains(it) }
    val stableVersionPattern = "^[0-9,.v-]+(-r)?$".toRegex()
    return hasUnstableKeyword || !stableVersionPattern.matches(version)
}

allprojects {
    tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask>().configureEach {
        rejectVersionIf {
            isNonStable(candidate.version) && !isNonStable(currentVersion)
        }
    }

    group = "io.cratis"
    version = providers.gradleProperty("version").getOrElse("0.0.0-SNAPSHOT")
}

val verifyTestDeclarationChecker = tasks.register<Exec>("verifyTestDeclarationChecker") {
    group = "verification"
    description = "Compile and run the bounded Jupiter declaration checker fixtures."
}
val checkTestDeclarations = tasks.register("checkTestDeclarations") {
    group = "verification"
    description = "Check direct standard Jupiter method declarations in ordinary test outputs."
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }

        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors.set(true)
            }
        }
    }

    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(17))
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
            options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
        }

        dependencies.add("testImplementation", "org.junit.jupiter:junit-jupiter:6.1.3")
        dependencies.add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")

        val compiler = extensions.getByType<JavaToolchainService>().compilerFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
        val testClasses = extensions.getByType<SourceSetContainer>().named("test").map { it.output.classesDirs }
        val declarationCheck = tasks.register<Exec>("checkTestDeclarations") {
            group = "verification"
            description = "Reject invalid direct standard Jupiter test method declarations."
            dependsOn(tasks.named("testClasses"), verifyTestDeclarationChecker)
            inputs.files(testClasses)
            inputs.file(rootProject.file("gradle/verification/check-test-declarations.py"))
            // No outputs: inspect even when the test task itself is up-to-date.
            doFirst {
                commandLine(
                    listOf(
                        "python3", rootProject.file("gradle/verification/check-test-declarations.py").absolutePath,
                        "--javap", compiler.get().metadata.installationPath.file("bin/javap").asFile.absolutePath
                    ) + testClasses.get().files.filter { it.exists() }.sorted().map { it.absolutePath }
                )
            }
        }
        checkTestDeclarations.configure { dependsOn(declarationCheck) }
        tasks.named("check") { dependsOn(declarationCheck) }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // Keep the separate real-kernel source set outside this ordinary-test guard.
            if (name == "test") dependsOn(declarationCheck)
        }
        if (path == ":Source") {
            val fixtureClasspath = configurations.named("testCompileClasspath")
            val fixtureWork = rootProject.providers.gradleProperty("arc.testDeclarationFixtures.work")
                .orElse(rootProject.layout.buildDirectory.dir("test-declaration-fixtures").map { it.asFile.absolutePath })
            verifyTestDeclarationChecker.configure {
                inputs.dir(rootProject.file("gradle/verification"))
                inputs.files(fixtureClasspath)
                doFirst {
                    commandLine(
                        "python3", rootProject.file("gradle/verification/test-check-test-declarations.py").absolutePath,
                        "--javac", compiler.get().executablePath.asFile.absolutePath,
                        "--javap", compiler.get().metadata.installationPath.file("bin/javap").asFile.absolutePath,
                        "--classpath", fixtureClasspath.get().asPath,
                        "--work-dir", fixtureWork.get()
                    )
                }
            }
        }
    }

    tasks.matching { it.name == "apiCheck" }.configureEach {
        mustRunAfter(tasks.matching { it.name == "apiDump" })
    }
}

apiValidation {
    ignoredProjects.addAll(listOf("ContractTests", "Samples", "Kotlin", "Java"))
}

gradle.projectsEvaluated {
    listOf(
        ":Samples:Kotlin:SpringBoot",
        ":Samples:Java:SpringBoot",
        ":Samples:Kotlin:ChronicleSpringBoot",
        ":Samples:Java:ChronicleSpringBoot"
    ).forEach { samplePath ->
        project(samplePath).tasks
            .matching { it.name == "apiCheck" || it.name == "apiDump" }
            .configureEach {
                enabled = false
            }
    }
}
