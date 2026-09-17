// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Writes a small versioned JSON resource that records the [ApiEndpointOptions][io.cratis.arc.metadata.ApiEndpointOptions]
 * used during proxy generation, so the Spring Boot starter can compare them against the runtime
 * configuration at startup and fail fast on any disagreement.
 *
 * The resource is written to `META-INF/arc/endpoint-options.json` inside [outputDirectory] and
 * lands on the application's runtime classpath via the `main` source set. The format is an
 * internal contract between this plugin and the starter; the `version` field guards readers against
 * stale or incompatible files.
 *
 * **Format version 1** carries exactly the five endpoint settings:
 * - `routePrefix`
 * - `segmentsToSkipForRoute`
 * - `includeCommandNameInRoute`
 * - `includeQueryNameInRoute`
 * - `enableQueryHttpMethod`
 */
@CacheableTask
public abstract class WriteArcEndpointOptionsResource : DefaultTask() {
    /** Route prefix applied to conventional routes; default `"api"`. */
    @get:Input
    public abstract val routePrefix: Property<String>

    /** Number of leading package segments omitted from conventional routes; default `0`. */
    @get:Input
    public abstract val segmentsToSkipForRoute: Property<Int>

    /** Whether conventional command routes include the command name; default `true`. */
    @get:Input
    public abstract val includeCommandNameInRoute: Property<Boolean>

    /** Whether conventional query routes include the query name; default `true`. */
    @get:Input
    public abstract val includeQueryNameInRoute: Property<Boolean>

    /** Whether query endpoints also accept the RFC QUERY HTTP method; default `true`. */
    @get:Input
    public abstract val enableQueryHttpMethod: Property<Boolean>

    /** Directory that receives `META-INF/arc/endpoint-options.json`. */
    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    @TaskAction
    public fun write() {
        // Delegate to the shared writer so that the Gradle task and the CLI produce
        // byte-identical output for the same options — the exact failure this check prevents.
        writeEndpointOptionsResource(
            outputDirectory.get().asFile,
            routePrefix.get(),
            segmentsToSkipForRoute.get(),
            includeCommandNameInRoute.get(),
            includeQueryNameInRoute.get(),
            enableQueryHttpMethod.get()
        )
    }
}
