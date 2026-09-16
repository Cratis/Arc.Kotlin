// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import org.gradle.api.tasks.JavaExec
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("com.google.devtools.ksp")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "io.cratis.samples"
version = "1.0.0"

val arcProxyGenerator by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val arcModuleName = "KotlinSpringBootSample"
val arcManifestDirectory = layout.buildDirectory.dir("generated/ksp/main/resources")
val arcProxyDirectory = layout.buildDirectory.dir("generated/arc-proxies")

dependencies {
    implementation(project(":Integrations:SpringBoot"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework:spring-websocket")
    implementation(kotlin("reflect"))
    ksp(project(":CodeGeneration:KSP"))
    arcProxyGenerator(project(":GradlePlugin"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

// The Arc integration libraries are built against the coroutines version pinned in :Source. The Spring Boot
// dependency management pins an older kotlinx-coroutines, and 1.11 changed the binary shape of the $default
// synthetic methods the integration calls - a mixed runtime fails with NoSuchMethodError. Keep the sample's
// runtime on the same coroutines version the libraries were compiled with.
dependencyManagement {
    dependencies {
        dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
    }
}

val fluentIndex = layout.buildDirectory.file("arc/fluent-validation/main.json")
val extractFluentIndex by tasks.registering(JavaExec::class) {
    classpath = arcProxyGenerator
    mainClass.set("io.cratis.arc.gradle.ExtractArcFluentValidationMetadataCli")
    fun jars(configuration: Configuration) = configuration.incoming.artifactView {
        attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
    }.files
    val compile = jars(configurations.compileClasspath.get())
    val runtime = jars(configurations.runtimeClasspath.get())
    inputs.files(compile, runtime)
    outputs.file(fluentIndex)
    doFirst { args(compile.asPath, runtime.asPath, fluentIndex.get().asFile.absolutePath) }
}
ksp {
    arg("arc.moduleName", arcModuleName)
    arg("arc.fluentValidationMetadata", fluentIndex.map { it.asFile.toURI().toASCIIString() })
    arg("arc.fluentValidationRoot", "true")
}
tasks.matching { it.name == "kspKotlin" }.configureEach {
    dependsOn(extractFluentIndex)
    inputs.file(fluentIndex).withPropertyName("arcFluentValidationMetadata").withPathSensitivity(PathSensitivity.NONE)
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("arc-kotlin-runtime-sample.jar")
}

val generateArcProxies by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Generates TypeScript proxies from the Kotlin sample's real KSP manifest"
    dependsOn(tasks.named("classes"))
    classpath = arcProxyGenerator
    mainClass.set("io.cratis.arc.gradle.GenerateArcProxiesCli")
    args(
        "--module-name", arcModuleName,
        "--output-directory", arcProxyDirectory.get().asFile.absolutePath,
        "--route-prefix", "api",
        "--route-segments-to-skip", "6",
        "--proxy-segments-to-skip", "6"
    )
    val proxyInputs = files(sourceSets.main.get().output, configurations.runtimeClasspath)
    inputs.files(proxyInputs)
    doFirst { args("--manifest-classpath", proxyInputs.asPath) }
    outputs.dir(arcProxyDirectory)
    doLast {
        listOf(
            "All.ts",
            "ById.ts",
            "CalendarEcho.ts",
            "CompleteTask.ts",
            "CreateTask.ts",
            "CreateTaskBatch.ts",
            "EchoCalendar.ts",
            "FindCalendarDefaultGet.ts",
            "FindCalendarDefaultQuery.ts",
            "FindCalendarEcho.ts",
            "FindCalendarPrecision.ts",
            "Observe.ts",
            // Reached only through @ExportedType: no command or query references the identity details type.
            "SampleIdentityDetails.ts",
            "TaskCreated.ts",
            "TaskView.ts"
        ).forEach { name ->
            check(arcProxyDirectory.get().file(name).asFile.isFile) { "Expected generated proxy '$name'." }
        }
    }
}

tasks.named("check") {
    dependsOn(generateArcProxies)
}
