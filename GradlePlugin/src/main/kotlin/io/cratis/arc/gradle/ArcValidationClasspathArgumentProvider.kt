// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.process.CommandLineArgumentProvider

/** Compiler input locations, not a new metadata resource or a runtime/application class loader. */
internal abstract class ArcValidationClasspathArgumentProvider : CommandLineArgumentProvider {
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> = listOf("arc.validationClasspath=" + classpath.files
        .filter { it.exists() }.map { it.toURI().toASCIIString() }.sorted().joinToString("|"))
}
