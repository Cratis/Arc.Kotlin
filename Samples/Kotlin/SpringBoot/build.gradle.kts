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
// Endpoint-options resource produced by the CLI for the Spring Boot startup consistency check.
// Written to build/resources/main so the boot JAR and bootRun both find it on the classpath.
val arcEndpointOptionsOutputDir = layout.buildDirectory.dir("resources/main")

dependencies {
    implementation(project(":Integrations:SpringBoot"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework:spring-websocket")
    implementation(kotlin("reflect"))
    // ./run.sh --database mongodb|postgres activates one of these through a Spring profile. Neither
    // auto-configuration runs in the default profile, which is what keeps the memory path free of a
    // connection string - see src/main/resources/application.properties.
    implementation(project(":Integrations:SpringDataMongo"))
    implementation(project(":Integrations:SpringDataJpa"))
    runtimeOnly("org.postgresql:postgresql:42.7.13")
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
    arg("arc.validationClasspath", providers.provider {
        configurations.compileClasspath.get().files.filter(File::exists).map { it.toURI().toASCIIString() }.sorted().joinToString("|")
    })
}
tasks.matching { it.name == "kspKotlin" }.configureEach {
    dependsOn(extractFluentIndex)
    inputs.file(fluentIndex).withPropertyName("arcFluentValidationMetadata").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(configurations.compileClasspath).withPropertyName("arcValidationClasspath").withNormalizer(ClasspathNormalizer::class.java)
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("arc-kotlin-runtime-sample.jar")
    // Ensure the endpoint-options resource is written before the JAR is assembled.
    dependsOn("generateArcProxies")
}

val generateArcProxies by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Generates TypeScript proxies from the Kotlin sample's real KSP manifest and writes the endpoint-options resource"
    dependsOn(tasks.named("classes"))
    classpath = arcProxyGenerator
    mainClass.set("io.cratis.arc.gradle.GenerateArcProxiesCli")
    args(
        "--module-name", arcModuleName,
        "--output-directory", arcProxyDirectory.get().asFile.absolutePath,
        "--route-prefix", "api",
        "--route-segments-to-skip", "6",
        "--proxy-segments-to-skip", "6",
        // Write META-INF/arc/endpoint-options.json so the startup consistency check is active.
        // Targets build/resources/main directly (processResources already ran at this point).
        "--endpoint-options-output", arcEndpointOptionsOutputDir.get().asFile.absolutePath
    )
    val proxyInputs = files(sourceSets.main.get().output, configurations.runtimeClasspath)
    inputs.files(proxyInputs)
    doFirst { args("--manifest-classpath", proxyInputs.asPath) }
    outputs.dir(arcProxyDirectory)
    doLast {
        listOf(
            // Task board.
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
            // Reached only through the declared identity details provider: no command or query references it.
            "SampleIdentityDetails.ts",
            "TaskCreated.ts",
            "TaskView.ts",
            // Showcase features. One entry per generated shape the frontend consumes, so a feature that
            // stops generating fails this sample's check instead of failing silently in the browser.
            "features/authenticationqueries/Anonymous.ts",
            "features/authenticationqueries/Authenticated.ts",
            "features/authenticationqueries/AuthenticationQueryItem.ts",
            "features/changestream/AddChangeStreamItem.ts",
            "features/changestream/All.ts",
            "features/changestream/ChangeStreamItem.ts",
            "features/changestream/RemoveChangeStreamItem.ts",
            "features/changestream/UpdateChangeStreamItem.ts",
            "features/crosscuttingauthorization/CrossCuttingAuthorizationStatus.ts",
            "features/crosscuttingauthorization/RunSecuredCommand.ts",
            "features/crosscuttingauthorization/Secured.ts",
            "features/livefeed/All.ts",
            "features/livefeed/ByAuthor.ts",
            "features/livefeed/LiveFeed.ts",
            "features/livefeed/LiveFeedMessage.ts",
            "features/livefeed/PostToFeed.ts",
            "features/modelbound/GetAll.ts",
            "features/modelbound/GetById.ts",
            "features/modelbound/ModelBoundCommand.ts",
            "features/modelbound/ModelBoundReadModel.ts",
            "features/observablecollection/AddObservableCollectionItem.ts",
            "features/observablecollection/All.ts",
            "features/observablecollection/ObservableCollectionItem.ts",
            "features/observablecollection/RemoveObservableCollectionItem.ts",
            "features/observablecollectionwithguid/AddObservableCollectionWithGuidItem.ts",
            "features/observablecollectionwithguid/All.ts",
            "features/observablecollectionwithguid/ObservableCollectionWithGuidItem.ts",
            "features/observablecollectionwithguid/RemoveObservableCollectionWithGuidItem.ts",
            "features/queryshowcase/All.ts",
            "features/queryshowcase/ById.ts",
            "features/queryshowcase/GetAll.ts",
            "features/queryshowcase/Latest.ts",
            "features/queryshowcase/ShowcaseItem.ts",
            "features/ticker/Observe.ts",
            "features/ticker/Ticker.ts"
        ).forEach { name ->
            check(arcProxyDirectory.get().file(name).asFile.isFile) { "Expected generated proxy '$name'." }
        }
    }
}

tasks.named("check") {
    dependsOn(generateArcProxies)
}
