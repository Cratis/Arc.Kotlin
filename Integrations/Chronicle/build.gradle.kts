// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `java-library`
    id("com.vanniktech.maven.publish")
}

val chronicleVersion = providers.gradleProperty("chronicleVersion").getOrElse("4.0.0")
val springBootVersion = "3.5.3"
val mockkVersion = "1.13.14"

dependencies {
    api(project(":Source"))
    api(project(":Integrations:SpringBoot"))
    api("io.cratis:chronicle-spring-boot-starter:$chronicleVersion")
    compileOnlyApi(project(":Testing"))

    testImplementation(project(":Testing"))
    testImplementation(project(":Integrations:SpringDataJpa"))
    testImplementation(project(":Integrations:SpringDataMongo"))
    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")
}

tasks.named("apiCheck") {
    mustRunAfter(tasks.named("apiDump"))
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("io.cratis", "arc-chronicle-spring-boot-starter", version.toString())

    pom {
        name.set("Arc Chronicle Spring Boot Starter")
        description.set("Optional Chronicle integration for Arc Spring Boot applications")
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
