// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        if (providers.gradleProperty("useMavenLocal").orNull?.toBoolean() == true) {
            mavenLocal()
        }
        mavenCentral()
    }
}

rootProject.name = "arc-kotlin-workspace"

include("Source")
include("CodeGeneration:KSP")
include("GradlePlugin")
include("Integrations:SpringBoot")
include("Integrations:SpringDataJpa")
include("Integrations:SpringDataMongo")
include("Integrations:OpenApi")
include("Integrations:Observability")
include("Integrations:Chronicle")
include("Testing")
include("ContractTests")
include("Samples:Kotlin:SpringBoot")
include("Samples:Kotlin:ChronicleSpringBoot")
include("Samples:Java:SpringBoot")
include("Samples:Java:ChronicleSpringBoot")
