// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    kotlin("jvm")
    `java-library`
    id("com.vanniktech.maven.publish")
}

val jacksonVersion = "2.22.2"

// Do not raise this past 1.9.x while Spring Boot 3.5 is the supported host.
//
// Spring Boot's dependency management pins kotlinx-coroutines to the version in its BOM — 1.8.1 for
// Spring Boot 3.5.3 — and that pin wins in any consuming application, downgrading whatever Arc
// declares here. Arc's call sites still compile against the version below, so the two must stay
// binary compatible.
//
// They stop being compatible at 1.10. Coroutines moved its default-argument bridges onto the
// interfaces themselves, so `job.cancel()` compiled against 1.11.0 emits
// `invokestatic kotlinx/coroutines/Job.cancel$default`, while 1.8.1 only has it on
// `Job$DefaultImpls`. The result is NoSuchMethodError at runtime in a real Spring Boot application —
// the multiplexed observable WebSocket hub dies in HubSocketConnection.close and the subscription
// never delivers. No JVM test sees it; only :ContractTests:typeScriptRuntimeTest, which boots the
// Kotlin sample as a real consumer, catches it. See issue #135.
val coroutinesVersion = "1.9.0"
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
