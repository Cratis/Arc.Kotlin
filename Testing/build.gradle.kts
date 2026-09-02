// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

plugins {
    kotlin("jvm")
    `java-library`
    id("com.vanniktech.maven.publish")
}

dependencies {
    api(project(":Source"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
}

tasks.named("apiCheck") {
    mustRunAfter(tasks.named("apiDump"))
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("io.cratis", "arc-testing", version.toString())

    pom {
        name.set("Arc Testing")
        description.set("Testing support for Arc applications and extensions")
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
