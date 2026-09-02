// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

/** Pure route convention shared by generated code and hosting integrations. */
public object EndpointRouteHelper {
    /** Builds a conventional route from explicit endpoint location inputs. */
    @JvmStatic
    public fun buildRouteUrl(
        options: ApiEndpointOptions,
        location: Iterable<String>,
        segmentsToSkip: Int,
        endpointName: String,
        includeNameInRoute: Boolean
    ): String = conventionalRoute(
        options.copy(segmentsToSkipForRoute = segmentsToSkip),
        location.toList(),
        endpointName,
        includeNameInRoute
    )

    /** Returns whether configuration or a duplicate namespace requires the endpoint name. */
    @JvmStatic
    public fun shouldIncludeNameInRoute(
        includeNameOption: Boolean,
        location: Iterable<String>,
        endpointsByNamespace: Map<String, List<*>>
    ): Boolean {
        val namespaceKey = location.joinToString(".")
        return includeNameOption || (endpointsByNamespace[namespaceKey]?.size ?: 0) > 1
    }

    /** Groups endpoints by package location after skipping leading segments. */
    @JvmStatic
    public fun <T> groupByNamespace(
        endpoints: Iterable<T>,
        locationSelector: (T) -> Iterable<String>,
        segmentsToSkip: Int
    ): Map<String, List<T>> {
        require(segmentsToSkip >= 0) { "segmentsToSkip cannot be negative." }
        val grouped = linkedMapOf<String, MutableList<T>>()
        endpoints.forEach { endpoint ->
            val key = locationSelector(endpoint).drop(segmentsToSkip).joinToString(".")
            grouped.getOrPut(key, ::mutableListOf).add(endpoint)
        }
        return java.util.Collections.unmodifiableMap(
            grouped.mapValues { (_, values) -> java.util.List.copyOf(values) }
        )
    }

    /** Calculates a command route from stable descriptor metadata. */
    @JvmStatic
    @JvmOverloads
    public fun commandRoute(
        descriptor: CommandDescriptor,
        options: ApiEndpointOptions = ApiEndpointOptions(),
        namespaceConflict: Boolean = false
    ): String = conventionalRoute(
        options,
        descriptor.location,
        descriptor.name,
        options.includeCommandNameInRoute,
        namespaceConflict,
        descriptor.explicitPath,
        false
    )

    /** Calculates a query route. Explicit query paths are returned verbatim. */
    @JvmStatic
    @JvmOverloads
    public fun queryRoute(
        descriptor: QueryDescriptor,
        options: ApiEndpointOptions = ApiEndpointOptions(),
        namespaceConflict: Boolean = false
    ): String = conventionalRoute(
        options,
        descriptor.location,
        descriptor.name,
        options.includeQueryNameInRoute,
        namespaceConflict,
        descriptor.explicitPath,
        true
    )

    /** Calculates a conventional route without requiring a descriptor. */
    @JvmStatic
    @JvmOverloads
    public fun conventionalRoute(
        options: ApiEndpointOptions,
        packageSegments: List<String>,
        endpointName: String,
        includeEndpointName: Boolean,
        namespaceConflict: Boolean = false,
        explicitPath: String? = null,
        preserveExplicitPath: Boolean = false
    ): String {
        if (explicitPath != null && preserveExplicitPath) return explicitPath

        val prefix = options.routePrefix.trim('/')
        val location = packageSegments
            .drop(options.segmentsToSkipForRoute)
            .joinToString("/", transform = ::toKebabCase)
        val baseRoute = "/$prefix/$location"
        val route = if (includeEndpointName || namespaceConflict) {
            "$baseRoute/${toKebabCase(endpointName)}"
        } else {
            baseRoute
        }
        return sanitizeUrl(route.lowercase())
    }

    /** Converts an identifier with the exact Arc .NET kebab-case algorithm. */
    @JvmStatic
    public fun toKebabCase(value: String): String = buildString(value.length * 2) {
        value.forEachIndexed { index, character ->
            if (character == '_') {
                append('-')
            } else {
                if (isUppercaseLetter(character) && index > 0 && value[index - 1] != '_') {
                    append('-')
                }
                append(lowercaseInvariant(character))
            }
        }
    }

    private fun sanitizeUrl(value: String): String {
        var sanitized = value.replace(Regex("(?<!:)/{2,}"), "/").trimEnd('/')
        if (!sanitized.startsWith('/') && !sanitized.contains("://")) {
            sanitized = "/$sanitized"
        }
        return sanitized.ifEmpty { "/" }
    }

    private fun isUppercaseLetter(character: Char): Boolean =
        Character.getType(character) == Character.UPPERCASE_LETTER.toInt()

    private fun lowercaseInvariant(character: Char): Char =
        if (character == '\u0130') character else Character.toLowerCase(character)
}
