// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `java-library`
    id("com.vanniktech.maven.publish")
}

val springBootVersion = "3.5.3"
val micrometerVersion = "1.17.1"
val openTelemetryVersion = "1.65.0"
val slf4jVersion = "2.0.18"

dependencies {
    api(project(":Source"))
    api(project(":Integrations:SpringBoot"))
    api("io.micrometer:micrometer-observation:$micrometerVersion")

    compileOnly("io.opentelemetry:opentelemetry-api:$openTelemetryVersion")
    compileOnly("org.slf4j:slf4j-api:$slf4jVersion")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")

    testImplementation("io.micrometer:micrometer-observation-test:$micrometerVersion")
    testImplementation("io.opentelemetry:opentelemetry-api:$openTelemetryVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion")
}

tasks.named("apiCheck") {
    mustRunAfter(tasks.named("apiDump"))
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("io.cratis", "arc-observability-spring-boot-starter", version.toString())

    pom {
        name.set("Arc Observability Spring Boot Starter")
        description.set("Micrometer observations and optional OpenTelemetry correlation for Arc pipelines")
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
