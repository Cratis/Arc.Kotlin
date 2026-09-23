// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import java.security.MessageDigest
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.tasks.Jar

plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish")
}

dependencies {
    implementation(project(":Source"))
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.12")
    testImplementation(gradleTestKit())
}

// Offline capture consistency: no dotnet invocation, package restore, network, or snapshot regeneration.
val captureHarness = layout.projectDirectory.dir("src/test/resources/differential/capture")
val capturedBaseline = layout.projectDirectory.dir("src/test/resources/differential/captured")
val testCapturedProxyBaseline by tasks.registering(Exec::class) {
    group = "verification"
    description = "Tests the offline capture consistency verifier against stale-input and tampering mutations"
    inputs.dir(captureHarness)
    inputs.dir(capturedBaseline)
    val testWork = providers.gradleProperty("arc.captureBaseline.testWork")
        .orElse(layout.buildDirectory.dir("capture-baseline-tests").map { it.asFile.absolutePath })
    commandLine("python3", "-B", "-m", "unittest", "discover", "-s", captureHarness.asFile.absolutePath,
        "-p", "test_baseline.py", "-v")
    doFirst {
        val directory = file(testWork.get())
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create capture verifier test workspace: $directory" }
        environment("AI_WORK_OUTPUT", directory.absolutePath)
    }
}
val verifyCapturedProxyBaseline by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks the seven-file captured baseline against current input hashes and reviewed SDK/runtime/package pins"
    dependsOn(testCapturedProxyBaseline)
    inputs.dir(captureHarness)
    inputs.dir(capturedBaseline)
    commandLine("python3", "-B", captureHarness.file("verify_baseline.py").asFile.absolutePath, "--check")
}
tasks.test { dependsOn(verifyCapturedProxyBaseline) }

// Both internal readers exercise the same module-name cases without a compiler dependency.
tasks.processTestResources {
    from(rootProject.file("CodeGeneration/KSP/src/test/resources/response-handler-module-names.csv"))
}

tasks.withType<Jar>().configureEach {
    manifest.attributes["Implementation-Version"] = project.version.toString()
}

gradlePlugin {
    plugins {
        create("arc") {
            id = "io.cratis.arc"
            implementationClass = "io.cratis.arc.gradle.ArcGradlePlugin"
            displayName = "Cratis Arc"
            description = "Configures Arc Kotlin/Java compilation, KSP manifests, dependencies, and TypeScript proxies"
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.cratis", "arc-gradle-plugin", version.toString())

    pom {
        name.set("Arc Gradle Plugin")
        description.set("Gradle plugin for Arc Kotlin and Java compilation, KSP manifests, dependencies, and TypeScript proxies")
        url.set("https://github.com/Cratis/Arc.Kotlin")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("cratis")
                name.set("Cratis")
                email.set("post@cratis.io")
            }
        }
        scm {
            url.set("https://github.com/Cratis/Arc.Kotlin")
            connection.set("scm:git:git://github.com/Cratis/Arc.Kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/Cratis/Arc.Kotlin.git")
        }
    }
}

// Private functional-test repository: real JARs and generated POMs, never publication/signing tasks.
val functionalRepository = layout.buildDirectory.dir("functional-fixtures/repository")
val functionalPluginJar = tasks.named<Jar>("jar")
val functionalPluginClasspath = files(functionalPluginJar.flatMap { it.archiveFile }, configurations.runtimeClasspath)
val prepareFunctionalRepository by tasks.registering(Sync::class) {
    into(functionalRepository)
}
listOf(":Source", ":CodeGeneration:KSP", ":Integrations:SpringBoot", ":Integrations:OpenApi").forEach { modulePath ->
    evaluationDependsOn(modulePath)
    val module = project(modulePath)
    val publication = module.extensions.getByType<org.gradle.api.publish.PublishingExtension>()
        .publications.getByName("maven") as org.gradle.api.publish.maven.MavenPublication
    val jar = module.tasks.named<Jar>("jar")
    val pom = module.tasks.named<org.gradle.api.publish.maven.tasks.GenerateMavenPom>("generatePomFileForMavenPublication")
    prepareFunctionalRepository.configure {
        dependsOn(jar, pom)
        val gavPath = "${publication.groupId.replace('.', '/')}/${publication.artifactId}/${publication.version}"
        from(jar.flatMap { it.archiveFile }) {
            into(gavPath)
            rename { "${publication.artifactId}-${publication.version}.jar" }
        }
        from(pom.map { it.destination }) {
            into(gavPath)
            rename { "${publication.artifactId}-${publication.version}.pom" }
        }
    }
}
tasks.test {
    dependsOn(prepareFunctionalRepository, functionalPluginJar, ":ContractTests:typeScriptInstall")
    val mappingClient = rootProject.layout.projectDirectory.dir("ContractTests/TypeScript")
    inputs.files(mappingClient.file("package.json"), mappingClient.file("package-lock.json"))
    systemProperty("arc.mapping.nodeModules", mappingClient.dir("node_modules").asFile.absolutePath)
    inputs.files(functionalPluginClasspath)
    inputs.dir(functionalRepository)
    systemProperty("arc.functional.repository", functionalRepository.get().asFile.absolutePath)
    systemProperty("arc.functional.version", project.version.toString())
    providers.gradleProperty("arc.fluent.evidence").orNull?.let { systemProperty("arc.fluent.evidence", it) }
    systemProperty("arc.functional.gradleHome", requireNotNull(gradle.gradleHomeDir).absolutePath)
    systemProperty("arc.functional.work", layout.buildDirectory.dir("functional-tests").get().asFile.absolutePath)
    val capturedDifferential = layout.projectDirectory.dir("src/test/resources/differential/captured")
    inputs.dir(capturedDifferential)
    systemProperty("arc.functional.capturedDifferential", capturedDifferential.asFile.absolutePath)
    providers.gradleProperty("arc.handlerIndex.evidence").orNull?.let {
        systemProperty("arc.handlerIndex.evidence", it)
    }
    doFirst {
        systemProperty("arc.functional.pluginClasspath", functionalPluginClasspath.asPath)
        systemProperty("arc.functional.pluginJar", functionalPluginJar.get().archiveFile.get().asFile.absolutePath)
    }
}

// Private service-loaded processor: never added to the plugin or published KSP runtime classpath.
val fluentValidationPrototypeJar = project(":CodeGeneration:KSP").tasks.named<Jar>("fluentValidationPrototypeJar")
tasks.test {
    dependsOn(fluentValidationPrototypeJar)
    inputs.file(fluentValidationPrototypeJar.flatMap { it.archiveFile })
    systemProperty("arc.prototype.processorJar", fluentValidationPrototypeJar.get().archiveFile.get().asFile.absolutePath)
    providers.gradleProperty("arc.prototype.evidence").orNull?.let { systemProperty("arc.prototype.evidence", it) }
}

// Onboarding resolves the published marker, not TestKit's injected plugin classpath.
// Stage publication outputs only: no publish/sign tasks and no starter runtime dependency here.
val onboardingRepository = layout.buildDirectory.dir("onboarding-fixtures/repository")
val prepareOnboardingRepository by tasks.registering(Copy::class) {
    into(onboardingRepository)
}
evaluationDependsOn(":Integrations:SpringBoot")
gradle.projectsEvaluated {
    listOf(project, project(":Source"), project(":CodeGeneration:KSP"), project(":Integrations:SpringBoot"))
        .forEach { module ->
            module.extensions.getByType<org.gradle.api.publish.PublishingExtension>().publications
                .withType<org.gradle.api.publish.maven.MavenPublication>().forEach { publication ->
                    val pomTaskName = "generatePomFileFor${publication.name.replaceFirstChar { it.uppercase() }}Publication"
                    val pom = module.tasks.named<org.gradle.api.publish.maven.tasks.GenerateMavenPom>(pomTaskName)
                    prepareOnboardingRepository.configure {
                        dependsOn(pom)
                        val gavPath = "${publication.groupId.replace('.', '/')}/${publication.artifactId}/${publication.version}"
                        from(pom.map { it.destination }) {
                            into(gavPath)
                            rename { "${publication.artifactId}-${publication.version}.pom" }
                        }
                        if (!publication.name.endsWith("PluginMarkerMaven")) {
                            val jar = module.tasks.named<Jar>("jar")
                            dependsOn(jar)
                            from(jar.flatMap { it.archiveFile }) {
                                into(gavPath)
                                rename { "${publication.artifactId}-${publication.version}.jar" }
                            }
                        }
                    }
                }
        }
}
tasks.test {
    dependsOn(prepareOnboardingRepository)
    inputs.dir(rootProject.layout.projectDirectory.dir("Documentation/get-started"))
    inputs.dir(onboardingRepository)
    systemProperty("arc.onboarding.repository", onboardingRepository.get().asFile.absolutePath)
    systemProperty("arc.onboarding.documentation", rootProject.file("Documentation/get-started").absolutePath)
    systemProperty("arc.onboarding.gradleUserHome", gradle.gradleUserHomeDir.absolutePath)
    systemProperty("arc.onboarding.work", providers.gradleProperty("arc.onboarding.work")
        .getOrElse(layout.buildDirectory.dir("onboarding-tests").get().asFile.absolutePath))
}

val contractManifestDirectory = rootProject.layout.projectDirectory.dir(
    "ContractTests/build/generated/ksp/testFixtures/resources"
)
val contractProxyDirectory = rootProject.layout.projectDirectory.dir("ContractTests/TypeScript/generated")
val contractProxySnapshot = layout.buildDirectory.file("contract-tests/type-script-proxy-hashes.txt")

fun proxyArguments(): List<String> = listOf(
    "--module-name", "ContractTests",
    "--manifest-classpath", listOf("ContractTests/build/classes/kotlin/testFixtures", "ContractTests/build/classes/java/testFixtures",
        "ContractTests/build/resources/testFixtures").joinToString(File.pathSeparator) { rootProject.file(it).absolutePath },
    "--output-directory", contractProxyDirectory.asFile.absolutePath,
    "--route-prefix", "api",
    "--route-segments-to-skip", "5",
    "--include-command-names", "true",
    "--include-query-names", "true",
    "--enable-query-http-method", "true",
    "--remove-stale-generated-files", "true",
    "--proxy-segments-to-skip", "5"
)

fun proxyHashes(): String = contractProxyDirectory.asFile.walkTopDown()
    .filter { it.isFile && it.extension == "ts" }
    .sortedBy { it.relativeTo(contractProxyDirectory.asFile).invariantSeparatorsPath }
    .joinToString(separator = "\n", postfix = "\n") { file ->
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        "${file.relativeTo(contractProxyDirectory.asFile).invariantSeparatorsPath} $digest"
    }

val generateContractTestProxies by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Generates TypeScript proxies from the real ContractTests KSP manifest"
    dependsOn(tasks.named("classes"), ":ContractTests:testFixturesClasses")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.cratis.arc.gradle.GenerateArcProxiesCli")
    args(proxyArguments())
    outputs.dir(contractProxyDirectory)
    outputs.upToDateWhen { false }
}

// Compile suffixed proxies from the real Kotlin and Java contract fixtures against the pinned npm packages.
// The default generation/determinism/strict build runs first: default generation cleans stale files
// in its output tree, which contains the suffixed subtree. The suffix pass must therefore run last.
val generateSuffixedContractTestProxies by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Generates suffixed proxies from the Kotlin and Java contract fixtures"
    dependsOn(":ContractTests:typeScriptBuild")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.cratis.arc.gradle.GenerateArcProxiesCli")
    val arguments = proxyArguments().toMutableList()
    arguments[arguments.indexOf("--output-directory") + 1] = contractProxyDirectory.dir("suffix").asFile.absolutePath
    args(arguments + listOf("--use-proxy-file-suffix", "true"))
    outputs.dir(contractProxyDirectory.dir("suffix"))
    outputs.upToDateWhen { false }
}
val verifySuffixedContractTestProxies by tasks.registering(Exec::class) {
    group = "verification"
    description = "Strictly type-checks suffixed Kotlin and Java proxies with the pinned TypeScript client runtime"
    dependsOn(generateSuffixedContractTestProxies)
    workingDir(rootProject.file("ContractTests/TypeScript"))
    commandLine("npm", "run", "build")
}
tasks.named("check") { dependsOn(verifySuffixedContractTestProxies) }

val captureContractTestProxyHashes by tasks.registering {
    dependsOn(generateContractTestProxies)
    outputs.file(contractProxySnapshot)
    outputs.upToDateWhen { false }
    doLast {
        val snapshot = contractProxySnapshot.get().asFile
        snapshot.parentFile.mkdirs()
        snapshot.writeText(proxyHashes())
    }
}

val generateContractTestProxiesSecondPass by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Regenerates ContractTests TypeScript proxies to verify byte stability"
    dependsOn(captureContractTestProxyHashes)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.cratis.arc.gradle.GenerateArcProxiesCli")
    args(proxyArguments())
    outputs.upToDateWhen { false }
}

tasks.register("verifyContractTestProxyDeterminism") {
    group = "verification"
    description = "Verifies a second proxy generation changes no bytes"
    dependsOn(generateContractTestProxiesSecondPass)
    doLast {
        val firstPass = contractProxySnapshot.get().asFile.readText()
        val secondPass = proxyHashes()
        check(firstPass == secondPass) {
            "Contract test TypeScript proxies changed between consecutive generations."
        }
    }
}
