// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** Extracts dependency declarations into a canonical format-1 compiler index, including an empty index. */
@CacheableTask
public abstract class ExtractArcResponseHandlerMetadata : DefaultTask() {
    /** Resolved compile dependency artifacts, including resources; do not supply this compilation's own outputs. */
    @get:Classpath
    public abstract val dependencyArtifacts: ConfigurableFileCollection

    /** Deterministic UTF-8 index. Unchanged bytes are not rewritten. */
    @get:OutputFile
    public abstract val outputFile: RegularFileProperty

    /** Validates and extracts only the Arc response-handler declaration namespace. */
    @TaskAction
    public fun extract() {
        val bytes = ArcResponseHandlerMetadataDiscovery.extract(dependencyArtifacts.files)
        val output = outputFile.get().asFile
        if (output.isFile && output.readBytes().contentEquals(bytes)) return
        output.parentFile.mkdirs()
        output.writeBytes(bytes)
    }
}
