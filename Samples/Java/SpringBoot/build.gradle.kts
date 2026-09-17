// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    kotlin("jvm")
    java
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

val arcModuleName = "JavaSpringBootSample"
val arcManifestDirectory = layout.buildDirectory.dir("generated/ksp/main/resources")
val arcProxyDirectory = layout.buildDirectory.dir("generated/arc-proxies")
val javaForKotlinDirectory = layout.buildDirectory.dir("intermediates/java-for-kotlin")
// Endpoint-options resource produced by the CLI for the Spring Boot startup consistency check.
val arcEndpointOptionsDirectory = layout.buildDirectory.dir("generated/arc-endpoint-options/main")

dependencies {
    implementation(project(":Integrations:SpringBoot"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    ksp(project(":CodeGeneration:KSP"))
    arcProxyGenerator(project(":GradlePlugin"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

ksp {
    arg("arc.moduleName", arcModuleName)
}

// Generated Kotlin handlers reference the Java task-board types, so make those types available before Kotlin compilation.
val compileJavaForKotlin by tasks.registering(JavaCompile::class) {
    source(sourceSets.main.get().java)
    classpath = configurations.named("compileClasspath").get()
    destinationDirectory.set(javaForKotlinDirectory)
    options.release.set(17)
}

val javaForKotlinClasses = files(javaForKotlinDirectory).builtBy(compileJavaForKotlin)
sourceSets.main {
    compileClasspath += javaForKotlinClasses
}
tasks.named("compileKotlin") {
    dependsOn(compileJavaForKotlin)
}

val generateArcProxies by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Generates TypeScript proxies from the Java sample's real KSP manifest and writes the endpoint-options resource"
    dependsOn(tasks.named("kspKotlin"))
    classpath = arcProxyGenerator
    mainClass.set("io.cratis.arc.gradle.GenerateArcProxiesCli")
    args(
        "--manifest-classpath", arcManifestDirectory.get().asFile.absolutePath,
        "--output-directory", arcProxyDirectory.get().asFile.absolutePath,
        "--route-prefix", "api",
        "--route-segments-to-skip", "5",
        "--proxy-segments-to-skip", "5",
        // Write META-INF/arc/endpoint-options.json so the startup consistency check is active.
        "--endpoint-options-output", arcEndpointOptionsDirectory.get().asFile.absolutePath
    )
    inputs.file(arcManifestDirectory.map { it.file("META-INF/cratis/arc/$arcModuleName.json") })
    outputs.dir(arcProxyDirectory)
    outputs.dir(arcEndpointOptionsDirectory)
    doLast {
        listOf("CompleteTask.ts", "CreateTask.ts", "TaskCreated.ts", "TaskView.ts", "ById.ts", "All.ts", "Observe.ts")
            .forEach { name ->
            check(arcProxyDirectory.get().file(name).asFile.isFile) { "Expected generated proxy '$name'." }
        }
    }
}

// Add the endpoint-options output as a resource source dir so processResources copies it into
// build/resources/main and the boot JAR contains META-INF/arc/endpoint-options.json.
// generateArcProxies only depends on kspKotlin (no processResources dependency), so wiring
// processResources to wait for it creates no cycle.
sourceSets.main.get().resources.srcDir(arcEndpointOptionsDirectory)
tasks.named("processResources") { dependsOn(generateArcProxies) }

tasks.named("check") {
    dependsOn(generateArcProxies)
}

tasks.matching { it.name == "apiCheck" || it.name == "apiDump" }.configureEach {
    enabled = false
}
