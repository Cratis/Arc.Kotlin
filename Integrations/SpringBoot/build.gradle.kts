// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `java-library`
    id("com.vanniktech.maven.publish")
}

val springBootVersion = "3.5.3"

dependencies {
    api(project(":Source"))
    api("org.springframework.boot:spring-boot:$springBootVersion")
    api("org.springframework.boot:spring-boot-autoconfigure:$springBootVersion")
    api("com.fasterxml.jackson.core:jackson-databind")

    compileOnly("org.springframework.boot:spring-boot-starter-web:$springBootVersion")
    compileOnly("org.springframework.boot:spring-boot-starter-websocket:$springBootVersion")
    compileOnly("org.springframework.boot:spring-boot-starter-security:$springBootVersion")
    compileOnly("jakarta.validation:jakarta.validation-api:3.1.1")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-web:$springBootVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-websocket:$springBootVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-security:$springBootVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-validation:$springBootVersion")
}

tasks.named("apiCheck") {
    mustRunAfter(tasks.named("apiDump"))
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("io.cratis", "arc-spring-boot-starter", version.toString())

    pom {
        name.set("Arc Spring Boot Starter")
        description.set("Spring Boot auto-configuration for Arc on Kotlin and Java")
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
