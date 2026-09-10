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
listOf(":Source", ":CodeGeneration:KSP").forEach { modulePath ->
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
    dependsOn(prepareFunctionalRepository, functionalPluginJar)
    inputs.files(functionalPluginClasspath)
    inputs.dir(functionalRepository)
    systemProperty("arc.functional.repository", functionalRepository.get().asFile.absolutePath)
    systemProperty("arc.functional.version", project.version.toString())
    systemProperty("arc.functional.gradleHome", requireNotNull(gradle.gradleHomeDir).absolutePath)
    systemProperty("arc.functional.work", layout.buildDirectory.dir("functional-tests").get().asFile.absolutePath)
    doFirst {
        systemProperty("arc.functional.pluginClasspath", functionalPluginClasspath.asPath)
        systemProperty("arc.functional.pluginJar", functionalPluginJar.get().archiveFile.get().asFile.absolutePath)
    }
}

val contractManifestDirectory = rootProject.layout.projectDirectory.dir(
    "ContractTests/build/generated/ksp/testFixtures/resources"
)
val contractProxyDirectory = rootProject.layout.projectDirectory.dir("ContractTests/TypeScript/generated")
val contractProxySnapshot = layout.buildDirectory.file("contract-tests/type-script-proxy-hashes.txt")

fun proxyArguments(): List<String> = listOf(
    "--manifest-classpath", contractManifestDirectory.asFile.absolutePath,
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
    dependsOn(tasks.named("classes"), ":ContractTests:kspTestFixturesKotlin")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.cratis.arc.gradle.GenerateArcProxiesCli")
    args(proxyArguments())
    outputs.dir(contractProxyDirectory)
    outputs.upToDateWhen { false }
}

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
