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
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework:spring-websocket")
    implementation(kotlin("reflect"))
    ksp(project(":CodeGeneration:KSP"))
    arcProxyGenerator(project(":GradlePlugin"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

ksp {
    arg("arc.moduleName", arcModuleName)
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("arc-kotlin-runtime-sample.jar")
}

val generateArcProxies by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Generates TypeScript proxies from the Kotlin sample's real KSP manifest"
    dependsOn(tasks.named("kspKotlin"))
    classpath = arcProxyGenerator
    mainClass.set("io.cratis.arc.gradle.GenerateArcProxiesCli")
    args(
        "--manifest-classpath", arcManifestDirectory.get().asFile.absolutePath,
        "--output-directory", arcProxyDirectory.get().asFile.absolutePath,
        "--route-prefix", "api",
        "--route-segments-to-skip", "6",
        "--proxy-segments-to-skip", "6"
    )
    inputs.file(arcManifestDirectory.map { it.file("META-INF/cratis/arc/$arcModuleName.json") })
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
