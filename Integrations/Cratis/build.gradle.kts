// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

plugins {
    kotlin("jvm")
    `java-library`
    id("com.vanniktech.maven.publish")
}

// A pure aggregator - no code of its own. An event-sourced Cratis application depends on this one
// artifact instead of assembling Arc, its Spring Boot wiring, and the Chronicle integration by hand;
// see Integrations/Chronicle for what that pulls in beneath it.
dependencies {
    api(project(":Integrations:Chronicle"))
}

tasks.named("apiCheck") {
    mustRunAfter(tasks.named("apiDump"))
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.cratis", "cratis", version.toString())

    pom {
        name.set("Cratis")
        description.set("The one dependency for a Cratis application: Arc, its Spring Boot wiring, and the Chronicle event-sourcing integration")
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
