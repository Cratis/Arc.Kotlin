// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.metadata.ApiEndpointOptions
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Discovers Arc manifests and generates TypeScript command, query, model, and enum proxies. */
@CacheableTask
public abstract class GenerateArcProxies : DefaultTask() {
    @get:Input
    public abstract val generationEnabled: Property<Boolean>

    @get:Input
    public abstract val moduleName: Property<String>

    @get:Input
    public abstract val routePrefix: Property<String>

    @get:Input
    public abstract val routeSegmentsToSkip: Property<Int>

    @get:Input
    public abstract val includeCommandNames: Property<Boolean>

    @get:Input
    public abstract val includeQueryNames: Property<Boolean>

    @get:Input
    public abstract val enableQueryHttpMethod: Property<Boolean>

    @get:Input
    public abstract val removeStaleGeneratedFiles: Property<Boolean>

    @get:Input
    public abstract val proxySegmentsToSkip: Property<Int>

    @get:Classpath
    public abstract val manifestClasspath: ConfigurableFileCollection

    @get:Optional
    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    @TaskAction
    public fun generate() {
        if (!generationEnabled.get() || !outputDirectory.isPresent) return
        val manifests = ArcManifestDiscovery.discover(manifestClasspath.files)
        val artifacts = ArcManifestDiscovery.merge(manifests)
        TypeScriptProxyGenerator(
            artifacts,
            ProxyGenerationOptions(
                outputDirectory.get().asFile,
                ApiEndpointOptions(
                    routePrefix.get(),
                    routeSegmentsToSkip.get(),
                    includeCommandNames.get(),
                    includeQueryNames.get(),
                    enableQueryHttpMethod.get()
                ),
                removeStaleGeneratedFiles.get(),
                proxySegmentsToSkip.get()
            )
        ).generate()
    }
}
