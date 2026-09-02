// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("plugin.spring") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    id("org.springframework.boot") version "3.5.3" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
    id("com.github.ben-manes.versions") version "0.51.0"
    id("se.patrikerdes.use-latest-versions") version "0.2.18"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
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

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }

        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors.set(true)
                freeCompilerArgs.add("-J-Xmx2g")
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

        dependencies.add("testImplementation", "org.junit.jupiter:junit-jupiter:5.11.4")
        dependencies.add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
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
