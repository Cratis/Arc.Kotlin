// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `java-library`
    id("com.vanniktech.maven.publish")
}

val springBootVersion = "3.5.3"
val swaggerModelsVersion = "2.2.34"

dependencies {
    api(project(":Integrations:SpringBoot"))
    api("io.swagger.core.v3:swagger-models:$swaggerModelsVersion")

    compileOnly("org.springframework.boot:spring-boot-starter-web:$springBootVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-web:$springBootVersion")
}

tasks.named("apiCheck") {
    mustRunAfter(tasks.named("apiDump"))
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("io.cratis", "arc-openapi-spring-boot-starter", version.toString())

    pom {
        name.set("Arc OpenAPI Spring Boot Starter")
        description.set("OpenAPI 3.1 document generation for Arc Spring Boot applications")
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
