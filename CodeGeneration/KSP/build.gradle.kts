// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

plugins {
    kotlin("jvm")
    `java-library`
    id("com.vanniktech.maven.publish")
}

val kspVersion = "2.1.0-1.0.29"
val springDataVersion = "3.5.1"

dependencies {
    implementation(project(":Source"))
    implementation("com.google.devtools.ksp:symbol-processing-api:$kspVersion")
    testImplementation("dev.zacsweers.kctfork:ksp:0.7.0")
    testImplementation("jakarta.validation:jakarta.validation-api:3.1.1")
    testImplementation("org.hibernate.validator:hibernate-validator:9.1.3.Final")
    testImplementation("org.springframework.data:spring-data-commons:$springDataVersion")
}

tasks.test {
    systemProperty("arc.ksp.projectDir", projectDir.absolutePath)
    systemProperty("arc.contractNegativeFixtures", rootProject.project(":ContractTests").file("src/negativeFixtures").absolutePath)
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("io.cratis", "arc-ksp", version.toString())

    pom {
        name.set("Arc KSP Code Generation")
        description.set("Kotlin Symbol Processing code generation for Arc")
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
