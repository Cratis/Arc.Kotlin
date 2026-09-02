// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm")
    `java-library`
    `java-test-fixtures`
    id("com.google.devtools.ksp")
}

val springDataVersion = "3.5.1"
val testcontainersVersion = "1.21.4"
val defaultChronicleKernelImage =
    "cratis/chronicle:16.44.1-development@sha256:3e0216892632f87e5386649cf8c1a189573cf82999abf14b7f6031863a6e545f"

val chronicleRealKernelTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[chronicleRealKernelTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[chronicleRealKernelTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    testFixturesApi(project(":Source"))
    testFixturesImplementation(project(":Testing"))
    testFixturesImplementation(project(":Integrations:Chronicle"))
    testFixturesApi("jakarta.validation:jakarta.validation-api:3.1.1")
    testFixturesApi("org.springframework.data:spring-data-commons:$springDataVersion")
    add("kspTestFixtures", project(":CodeGeneration:KSP"))

    testImplementation(testFixtures(project))
    testImplementation(project(":CodeGeneration:KSP"))
    testImplementation(project(":Integrations:SpringBoot"))
    testImplementation(project(":Integrations:Chronicle"))
    testImplementation("io.mockk:mockk:1.13.14")

    add(chronicleRealKernelTest.implementationConfigurationName, project(":Integrations:Chronicle"))
    add(chronicleRealKernelTest.implementationConfigurationName, "org.junit.jupiter:junit-jupiter:5.11.4")
    add(chronicleRealKernelTest.implementationConfigurationName, "org.testcontainers:testcontainers:$testcontainersVersion")
    add(chronicleRealKernelTest.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher")
}

ksp {
    arg("arc.moduleName", "ContractTests")
}

val typeScriptDirectory = layout.projectDirectory.dir("TypeScript")
val kotlinSampleProject = project(":Samples:Kotlin:SpringBoot")
val kotlinSampleBootJar = kotlinSampleProject.layout.buildDirectory.file("libs/arc-kotlin-runtime-sample.jar")
val kotlinSampleBootJarTask = ":Samples:Kotlin:SpringBoot:bootJar"
val kotlinSampleProxyDirectory = kotlinSampleProject.layout.buildDirectory.dir("generated/arc-proxies")
val kotlinChronicleSampleProject = project(":Samples:Kotlin:ChronicleSpringBoot")
val javaChronicleSampleProject = project(":Samples:Java:ChronicleSpringBoot")
val kotlinChronicleSampleBootJar = kotlinChronicleSampleProject.layout.buildDirectory.file("libs/arc-kotlin-chronicle-sample.jar")
val javaChronicleSampleBootJar = javaChronicleSampleProject.layout.buildDirectory.file("libs/arc-java-chronicle-sample.jar")
val kotlinChronicleSampleProxyDirectory = kotlinChronicleSampleProject.layout.buildDirectory.dir("generated/arc-proxies")
val javaChronicleSampleProxyDirectory = javaChronicleSampleProject.layout.buildDirectory.dir("generated/arc-proxies")

tasks.named<Test>("test") {
    dependsOn(":GradlePlugin:generateContractTestProxies")
    systemProperty(
        "arc.contractTests.generatedProxies",
        typeScriptDirectory.dir("generated").asFile.absolutePath
    )
}

val typeScriptInstall by tasks.registering(Exec::class) {
    group = "verification"
    description = "Installs the TypeScript contract dependencies exactly from package-lock.json"
    workingDir(typeScriptDirectory)
    commandLine("npm", "ci", "--ignore-scripts")
    inputs.files(
        typeScriptDirectory.file("package.json"),
        typeScriptDirectory.file("package-lock.json")
    )
    outputs.dir(typeScriptDirectory.dir("node_modules"))
}

val prepareRuntimeProxies by tasks.registering(Sync::class) {
    group = "verification"
    description = "Copies real Kotlin sample proxies into the TypeScript runtime contract workspace"
    dependsOn(
        ":GradlePlugin:verifyContractTestProxyDeterminism",
        ":Samples:Kotlin:SpringBoot:generateArcProxies"
    )
    from(kotlinSampleProxyDirectory)
    into(typeScriptDirectory.dir("generated/runtime"))
}

val prepareChronicleSampleProxies by tasks.registering(Sync::class) {
    group = "verification"
    description = "Copies Kotlin and Java Chronicle sample proxies into the TypeScript contract workspace"
    dependsOn(
        ":GradlePlugin:verifyContractTestProxyDeterminism",
        ":Samples:Kotlin:ChronicleSpringBoot:generateArcProxies",
        ":Samples:Java:ChronicleSpringBoot:generateArcProxies"
    )
    mustRunAfter(prepareRuntimeProxies)
    from(kotlinChronicleSampleProxyDirectory) { into("kotlin") }
    from(javaChronicleSampleProxyDirectory) { into("java") }
    into(typeScriptDirectory.dir("generated/chronicle"))
}

val typeScriptBuild by tasks.registering(Exec::class) {
    group = "verification"
    description = "Strictly type-checks the generated proxies against the published Arc packages"
    dependsOn(typeScriptInstall, prepareRuntimeProxies, prepareChronicleSampleProxies)
    workingDir(typeScriptDirectory)
    commandLine("npm", "run", "build")
}

val typeScriptRuntimeHarnessTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the TypeScript runtime gate harness unit tests"
    dependsOn(typeScriptInstall)
    workingDir(typeScriptDirectory)
    commandLine("npm", "run", "test:runtime-harness")
}

val typeScriptRuntimeTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the published TypeScript proxies against a real Kotlin Spring Boot sample"
    dependsOn(typeScriptBuild, typeScriptRuntimeHarnessTest, kotlinSampleBootJarTask)
    workingDir(typeScriptDirectory)
    commandLine("npm", "run", "test:runtime")
    doFirst {
        val bootJar = kotlinSampleBootJar.get().asFile
        check(bootJar.isFile) { "Expected executable Kotlin Spring Boot sample jar at ${bootJar.absolutePath}." }
        environment("ARC_KOTLIN_SAMPLE_JAR", bootJar.absolutePath)
        environment("ARC_KOTLIN_JAVA", "${System.getProperty("java.home")}/bin/java")
    }
}

val chronicleRealKernelTestTask = tasks.register<Test>("chronicleRealKernelTest") {
    group = "verification"
    description = "Runs generated Kotlin and Java Arc samples against a real pinned Chronicle kernel"
    dependsOn(
        ":Samples:Kotlin:ChronicleSpringBoot:bootJar",
        ":Samples:Java:ChronicleSpringBoot:bootJar"
    )
    testClassesDirs = chronicleRealKernelTest.output.classesDirs
    classpath = chronicleRealKernelTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    systemProperty(
        "arc.chronicle.kernel.image",
        providers.gradleProperty("chronicleKernelImage").getOrElse(defaultChronicleKernelImage)
    )
    systemProperty("arc.chronicle.kotlinSample.jar", kotlinChronicleSampleBootJar.get().asFile.absolutePath)
    systemProperty("arc.chronicle.javaSample.jar", javaChronicleSampleBootJar.get().asFile.absolutePath)
}

tasks.named("check") {
    dependsOn(typeScriptRuntimeTest)
}
