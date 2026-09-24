// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
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
    /** Name generated type files `Name.proxy.ts` instead of `Name.ts`. */
    public val useProxyFileSuffix: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
    public val segmentsToSkip: Property<Int> = objects.property(Int::class.java).convention(0)

    /**
     * Repeatable `<FullyQualifiedTypeName>=<TypeScriptType>[=<NpmPackage>]` entries.
     *
     * Consulted ahead of the generator's built-in type map, so an entry can correct an existing mapping as well as
     * declare one the generator has never seen. Prefer [mapType] over adding raw strings.
     */
    public val typeMappings: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * JVM package to npm package mappings.
     *
     * Every model type under a mapped JVM package is imported from the npm package by its simple name instead of
     * being generated. The longest matching package wins, so a nested package can override a broader one.
     */
    public val packageMappings: MapProperty<String, String> =
        objects.mapProperty(String::class.java, String::class.java)

    /** Maps a JVM type to a TypeScript type that needs no import, such as `string` or `number`. */
    public fun mapType(typeName: String, typeScriptType: String) {
        typeMappings.add("$typeName=$typeScriptType")
    }

    /** Maps a JVM type to a TypeScript type imported from an npm package. */
    public fun mapType(typeName: String, typeScriptType: String, npmPackage: String) {
        typeMappings.add("$typeName=$typeScriptType=$npmPackage")
    }

    /** Maps every model type under a JVM package to an npm package it is imported from instead of generated. */
    public fun mapPackage(javaPackage: String, npmPackage: String) {
        packageMappings.put(javaPackage, npmPackage)
    }
}
