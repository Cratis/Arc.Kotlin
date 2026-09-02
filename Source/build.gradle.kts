// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

plugins {
    kotlin("jvm")
    `java-library`
    id("com.vanniktech.maven.publish")
}

val jacksonVersion = "2.18.2"
val coroutinesVersion = "1.9.0"
val jakartaValidationVersion = "3.1.1"
val slf4jVersion = "2.0.16"

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all-compatibility")
    }
}

dependencies {
    api("org.jetbrains.kotlin:kotlin-reflect:2.1.0")
    api("jakarta.validation:jakarta.validation-api:$jakartaValidationVersion")
    api("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    api("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    api("org.slf4j:slf4j-api:$slf4jVersion")
}

tasks.named("apiCheck") {
    mustRunAfter(tasks.named("apiDump"))
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("io.cratis", "arc", version.toString())

    pom {
        name.set("Arc for Kotlin and Java")
        description.set("Host-agnostic Arc runtime for Kotlin and Java")
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
