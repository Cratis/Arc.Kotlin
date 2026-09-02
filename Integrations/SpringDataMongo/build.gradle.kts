// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `java-library`
    id("com.vanniktech.maven.publish")
}

val springBootVersion = "3.5.3"
val mongoJavaServerVersion = "1.47.0"

dependencies {
    api(project(":Source"))
    api(project(":Integrations:SpringBoot"))
    api("org.springframework.boot:spring-boot-starter-data-mongodb:$springBootVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion")
    testImplementation("de.bwaldvogel:mongo-java-server:$mongoJavaServerVersion")
    testImplementation("de.bwaldvogel:mongo-java-server-memory-backend:$mongoJavaServerVersion")
}

tasks.named("apiCheck") {
    mustRunAfter(tasks.named("apiDump"))
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("io.cratis", "arc-spring-data-mongodb", version.toString())

    pom {
        name.set("Arc Spring Data MongoDB Integration")
        description.set("Spring Data MongoDB query, read-model, and command transaction integration for Arc")
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
