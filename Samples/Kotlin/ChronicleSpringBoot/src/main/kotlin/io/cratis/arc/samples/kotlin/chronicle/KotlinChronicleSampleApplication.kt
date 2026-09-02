// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.chronicle

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Runs the Kotlin Arc and Chronicle Spring Boot sample. */
@SpringBootApplication
public class KotlinChronicleSampleApplication

public fun main(args: Array<String>) {
    runApplication<KotlinChronicleSampleApplication>(*args)
}
