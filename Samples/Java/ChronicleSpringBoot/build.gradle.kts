// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import org.gradle.api.tasks.JavaExec
import org.springframework.boot.gradle.tasks.bundling.BootJar

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

val arcModuleName = "JavaChronicleSpringBootSample"
val arcManifestDirectory = layout.buildDirectory.dir("generated/ksp/main/resources")
val arcProxyDirectory = layout.buildDirectory.dir("generated/arc-proxies")

dependencies {
    implementation(project(":Integrations:Chronicle"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    ksp(project(":CodeGeneration:KSP"))
    arcProxyGenerator(project(":GradlePlugin"))

    testImplementation(project(":Testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

ksp {
    arg("arc.moduleName", arcModuleName)
}

val generateArcProxies by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Generates TypeScript proxies from the Java Chronicle sample's KSP manifest"
    dependsOn(tasks.named("kspKotlin"))
    classpath = arcProxyGenerator
    mainClass.set("io.cratis.arc.gradle.GenerateArcProxiesCli")
    args(
        "--manifest-classpath", arcManifestDirectory.get().asFile.absolutePath,
        "--output-directory", arcProxyDirectory.get().asFile.absolutePath,
        "--route-prefix", "api",
        "--route-segments-to-skip", "5",
        "--proxy-segments-to-skip", "5"
    )
    inputs.file(arcManifestDirectory.map { it.file("META-INF/cratis/arc/$arcModuleName.json") })
    outputs.dir(arcProxyDirectory)
    doLast {
        listOf("CreateTask.ts", "RenameTask.ts", "TaskView.ts", "ById.ts", "All.ts").forEach { name ->
            check(arcProxyDirectory.get().file(name).asFile.isFile) { "Expected generated proxy '$name'." }
        }
        listOf("CreateTask" to "ICreateTask", "RenameTask" to "IRenameTask").forEach { (name, contract) ->
            val source = arcProxyDirectory.get().file("$name.ts").asFile.readText()
            check("extends Command<$contract>" in source) { "$name must expose a response-less command contract." }
            check("extends Command<$contract," !in source) { "$name must not expose its server-handled event." }
        }
    }
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("arc-java-chronicle-sample.jar")
}

tasks.named("check") {
    dependsOn(generateArcProxies)
}

tasks.matching { it.name == "apiCheck" || it.name == "apiDump" }.configureEach {
    enabled = false
}
