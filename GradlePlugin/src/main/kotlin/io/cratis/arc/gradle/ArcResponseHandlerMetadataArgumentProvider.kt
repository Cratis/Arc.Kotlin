// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/** Supplies a task-local KSP option without resolving or loading application classes. */
public abstract class ArcResponseHandlerMetadataArgumentProvider : CommandLineArgumentProvider {
    /** The format-1 dependency index; also register it as a nonincremental KSP task input. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val metadataFile: RegularFileProperty

    override fun asArguments(): Iterable<String> =
        listOf("arc.responseHandlerMetadata=${metadataFile.get().asFile.toURI().toASCIIString()}")
}
