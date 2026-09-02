// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Runs the Kotlin Spring Boot sample host. */
@SpringBootApplication
class KotlinSampleApplication

fun main(args: Array<String>) {
    runApplication<KotlinSampleApplication>(*args)
}
