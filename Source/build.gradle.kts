// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    kotlin("jvm")
    `java-library`
    id("com.vanniktech.maven.publish")
}

// Compile against the versions Spring Boot 4.1.1 supplies to consuming applications. Keeping Arc
// aligned prevents the compile/runtime binary skew that issue #135 exposed under the previous host.
val jacksonVersion = "3.1.5"
val coroutinesVersion = "1.10.2"
val jakartaValidationVersion = "3.1.1"
val slf4jVersion = "2.0.19"

kotlin {
    compilerOptions {
        jvmDefault.set(JvmDefaultMode.ENABLE)
    }
}

dependencies {
    api("org.jetbrains.kotlin:kotlin-reflect:2.4.10")
    api("jakarta.validation:jakarta.validation-api:$jakartaValidationVersion")
    api("tools.jackson.core:jackson-databind:$jacksonVersion")
    api("tools.jackson.module:jackson-module-kotlin:$jacksonVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    api("org.slf4j:slf4j-api:$slf4jVersion")
}

tasks.named("apiCheck") {
    mustRunAfter(tasks.named("apiDump"))
}

mavenPublishing {
    publishToMavenCentral()
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
