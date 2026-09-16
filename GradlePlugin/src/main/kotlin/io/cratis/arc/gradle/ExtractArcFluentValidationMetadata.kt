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

/** Verifies bytecode/declaration completeness and writes a deterministic format-1 fluent compiler index. */
@CacheableTask
public abstract class ExtractArcFluentValidationMetadata : DefaultTask() {
    @get:Classpath
    public abstract val compileArtifacts: ConfigurableFileCollection

    @get:Classpath
    public abstract val runtimeArtifacts: ConfigurableFileCollection

    @get:OutputFile
    public abstract val outputFile: RegularFileProperty

    @TaskAction
    public fun extract() {
        val bytes = ArcFluentValidationMetadataDiscovery.extract(compileArtifacts.files, runtimeArtifacts.files)
        val file = outputFile.get().asFile
        if (file.isFile && file.readBytes().contentEquals(bytes)) return
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
    }
}
