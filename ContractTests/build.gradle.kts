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

val springDataVersion = "4.1.1"
val testcontainersVersion = "2.0.5"
val defaultChronicleKernelImage =
    "cratis/chronicle:19.0.1-development@sha256:c9b77e74d689da7449cd25133ba99f0272716d36ac1eed9ca6cd82571ed960fa"
val defaultMongoReplicaSetImage =
    "mongo:8.2@sha256:e0ce8c35124d4a9f9785532d1f268f39e9728ffa1cb38f46fa482436424c4bd3"

val chronicleRealKernelTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[chronicleRealKernelTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[chronicleRealKernelTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

val mongoReplicaSetTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[mongoReplicaSetTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[mongoReplicaSetTest.runtimeOnlyConfigurationName]
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
    testImplementation(project(":Integrations:OpenApi"))
    testImplementation(project(":Integrations:Chronicle"))
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.springframework.boot:spring-boot-starter-test:4.1.1")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc:4.1.1")
    testImplementation("org.springframework.boot:spring-boot-starter-websocket:4.1.1")

    add(chronicleRealKernelTest.implementationConfigurationName, project(":Integrations:Chronicle"))
    add(chronicleRealKernelTest.implementationConfigurationName, "org.junit.jupiter:junit-jupiter:6.1.3")
    add(chronicleRealKernelTest.implementationConfigurationName, "org.testcontainers:testcontainers:$testcontainersVersion")
    add(chronicleRealKernelTest.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher")

    add(mongoReplicaSetTest.implementationConfigurationName, project(":Integrations:SpringDataMongo"))
    add(mongoReplicaSetTest.implementationConfigurationName, "org.junit.jupiter:junit-jupiter:6.1.3")
    add(mongoReplicaSetTest.implementationConfigurationName, "org.testcontainers:testcontainers:$testcontainersVersion")
    add(mongoReplicaSetTest.implementationConfigurationName, "org.testcontainers:testcontainers-mongodb:$testcontainersVersion")
    add(mongoReplicaSetTest.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher")
}

val fluentCompilerTools by configurations.creating { isCanBeConsumed = false; isCanBeResolved = true }
dependencies { fluentCompilerTools(project(":GradlePlugin")) }
val fluentIndex = layout.buildDirectory.file("arc/fluent-validation/testFixtures.json")
val extractFluentIndex by tasks.registering(JavaExec::class) {
    classpath = fluentCompilerTools
    mainClass.set("io.cratis.arc.gradle.ExtractArcFluentValidationMetadataCli")
    fun jars(configuration: Configuration) = configuration.incoming.artifactView {
        attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
    }.files
    val compile = jars(configurations.getByName("testFixturesCompileClasspath"))
    val runtime = jars(configurations.getByName("testFixturesRuntimeClasspath"))
    inputs.files(compile, runtime)
    outputs.file(fluentIndex)
    doFirst { args(compile.asPath, runtime.asPath, fluentIndex.get().asFile.absolutePath) }
}
ksp {
    arg("arc.moduleName", "ContractTests")
    arg("arc.fluentValidationMetadata", fluentIndex.map { it.asFile.toURI().toASCIIString() })
    arg("arc.fluentValidationRoot", "true")
    arg("arc.validationClasspath", providers.provider {
        configurations.getByName("testFixturesCompileClasspath").files.filter(File::exists)
            .map { it.toURI().toASCIIString() }.sorted().joinToString("|")
    })
}
tasks.matching { it.name == "kspTestFixturesKotlin" }.configureEach {
    dependsOn(extractFluentIndex)
    inputs.file(fluentIndex).withPropertyName("arcFluentValidationMetadata").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(configurations.getByName("testFixturesCompileClasspath")).withPropertyName("arcValidationClasspath")
        .withNormalizer(ClasspathNormalizer::class.java)
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

val mongoReplicaSetTestTask = tasks.register<Test>("mongoReplicaSetTest") {
    group = "verification"
    description = "Proves change-stream readiness, two-tenant isolation, DefaultNamingPolicy routing, and concept-storage BSON fidelity against a real pinned MongoDB replica set"
    testClassesDirs = mongoReplicaSetTest.output.classesDirs
    classpath = mongoReplicaSetTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    systemProperty(
        "arc.mongo.replicaset.image",
        providers.gradleProperty("mongoReplicaSetImage").getOrElse(defaultMongoReplicaSetImage)
    )
}

// Explicit cross-runtime HTTP proof; deliberately not a dependency of check/build or proxy generation.
val javaHttpSample = project(":Samples:Java:SpringBoot")
evaluationDependsOn(":Samples:Java:SpringBoot")
val javaHttpBootJar = javaHttpSample.tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
val httpConformanceHarnessTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks paired HTTP assertions, loopback transport and bounded child lifecycle without starting framework hosts"
    inputs.dir(layout.projectDirectory.dir("HttpConformance"))
    commandLine("python3", "-B", "-m", "unittest", "discover", "-s",
        layout.projectDirectory.dir("HttpConformance").asFile.absolutePath, "-v")
    doFirst {
        val managed = providers.environmentVariable("AI_WORK_OUTPUT").orNull
        check(managed != null) { "AI_WORK_OUTPUT is required for the explicit HTTP harness tests." }
        environment("AI_WORK_OUTPUT", managed)
    }
}
val httpConformanceTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs selected HTTP contracts against pinned Arc .NET and real generated Kotlin/Java samples"
    dependsOn(httpConformanceHarnessTest, kotlinSampleBootJarTask, javaHttpBootJar)
    val evidence = providers.gradleProperty("arc.httpConformance.output")
    val managed = providers.environmentVariable("AI_WORK_OUTPUT")
    doFirst {
        check(evidence.isPresent && managed.isPresent) {
            "Run explicitly with lifecycle AI_WORK_OUTPUT and -Parc.httpConformance.output=<new .ai-work directory>."
        }
        commandLine("python3", "-B", layout.projectDirectory.file("HttpConformance/run.py").asFile.absolutePath,
            "--kotlin-jar", kotlinSampleBootJar.get().asFile.absolutePath,
            "--java-jar", javaHttpBootJar.get().archiveFile.get().asFile.absolutePath,
            "--java", "${System.getProperty("java.home")}/bin/java",
            "--output", evidence.get())
        environment("AI_WORK_OUTPUT", managed.get())
    }
}

tasks.named("check") {
    dependsOn(typeScriptRuntimeTest)
}
