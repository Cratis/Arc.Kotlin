// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

/** Public configuration for the Arc Gradle plugin. */
public abstract class ArcExtension @Inject constructor(objects: ObjectFactory) {
    /** Stable module name written to the generated Arc artifact manifest. */
    public val moduleName: Property<String> = objects.property(String::class.java)

    /** Version used for the io.cratis:arc and io.cratis:arc-ksp dependencies. */
    public val dependencyVersion: Property<String> = objects.property(String::class.java)

    /** Whether the plugin adds Arc runtime and KSP dependencies automatically. */
    public val manageDependencies: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    /** HTTP endpoint conventions shared with the host. */
    public val endpoints: ArcEndpointOptions = objects.newInstance(ArcEndpointOptions::class.java)

    /** TypeScript proxy generation options. */
    public val proxies: ArcProxyOptions = objects.newInstance(ArcProxyOptions::class.java)

    /** Configures HTTP endpoint conventions from Kotlin or Groovy DSL. */
    public fun endpoints(action: Action<in ArcEndpointOptions>) {
        action.execute(endpoints)
    }

    /** Configures TypeScript proxy generation from Kotlin or Groovy DSL. */
    public fun proxies(action: Action<in ArcProxyOptions>) {
        action.execute(proxies)
    }
}

/** HTTP endpoint options used while calculating generated proxy routes. */
public abstract class ArcEndpointOptions @Inject constructor(objects: ObjectFactory) {
    public val routePrefix: Property<String> = objects.property(String::class.java).convention("api")
    public val segmentsToSkip: Property<Int> = objects.property(Int::class.java).convention(0)
    public val includeCommandNames: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    public val includeQueryNames: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    public val enableQueryHttpMethod: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
}

/** TypeScript proxy output and cleanup options. */
public abstract class ArcProxyOptions @Inject constructor(objects: ObjectFactory) {
    public val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    public val outputDirectory: DirectoryProperty = objects.directoryProperty()
    public val removeStaleGeneratedFiles: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)
    public val segmentsToSkip: Property<Int> = objects.property(Int::class.java).convention(0)
}
