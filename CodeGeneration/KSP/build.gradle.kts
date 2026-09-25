// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

plugins {
    kotlin("jvm")
    `java-library`
    id("com.vanniktech.maven.publish")
}

val kspVersion = "2.3.12"
val springDataVersion = "4.1.1"

dependencies {
    implementation(project(":Source"))
    implementation("com.google.devtools.ksp:symbol-processing-api:$kspVersion")
    // Whole-body syntax parsing and class-file annotation inspection, exact compiler baseline. Never an application dependency.
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20")
    testImplementation("dev.zacsweers.kctfork:ksp:0.14.0")
    testImplementation("jakarta.validation:jakarta.validation-api:3.1.1")
    testImplementation("org.hibernate.validator:hibernate-validator:9.1.4.Final")
    testImplementation("org.springframework.data:spring-data-commons:$springDataVersion")
    // The generated performer for an RxJava query imports the bridge, so the integration module
    // must be on the test classpath for that generated code to compile.
    testImplementation("io.reactivex.rxjava3:rxjava:3.1.12")
    testImplementation(project(":Integrations:RxJava3"))
}

tasks.test {
    systemProperty("arc.ksp.projectDir", projectDir.absolutePath)
    providers.gradleProperty("arc.prototype.evidence").orNull?.let { systemProperty("arc.prototype.evidence", it) }
    providers.gradleProperty("arc.fluent.evidence").orNull?.let { systemProperty("arc.fluent.evidence", it) }
    systemProperty("arc.contractNegativeFixtures", rootProject.project(":ContractTests").file("src/negativeFixtures").absolutePath)
}

// Deliberately not a publication/variant or runtime dependency: only the native prototype test uses this JAR.
// Reuse the embedded proof's helpers, excluding its JUnit/compile-testing test class and all other tests.
val fluentValidationPrototypeJar by tasks.registering(Jar::class) {
    dependsOn(tasks.named("testClasses"))
    archiveClassifier.set("fluent-validation-prototype-test-only")
    from(sourceSets["test"].output.classesDirs) {
        include("io/cratis/arc/codegeneration/ksp/Prototype*.class")
        include("io/cratis/arc/codegeneration/ksp/ArcFluentValidationNativePrototypeProvider*.class")
        include("io/cratis/arc/codegeneration/ksp/ArcFluentValidationExtractionPrototypeCompilationTestKt.class")
    }
    from("src/test/resources/prototype-native")
}

mavenPublishing {
    publishToMavenCentral()
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
