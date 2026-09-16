// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.provider.Property
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/** Task-local verified fluent dependency index; contains declarations, never loaded application classes. */
public abstract class ArcFluentValidationMetadataArgumentProvider : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val metadataFile: RegularFileProperty

    /** Main application compilations emit a runtime contribution module even for imported-only rules. */
    @get:Input
    @get:Optional
    public abstract val rootCompilation: Property<Boolean>

    override fun asArguments(): Iterable<String> =
        listOf("arc.fluentValidationMetadata=${metadataFile.get().asFile.toURI().toASCIIString()}") +
            if (rootCompilation.orNull == true) listOf("arc.fluentValidationRoot=true") else emptyList()
}
