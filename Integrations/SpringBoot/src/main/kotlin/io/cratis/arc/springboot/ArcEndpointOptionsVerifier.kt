// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.ApiEndpointOptions
import tools.jackson.databind.node.ObjectNode

/**
 * Reads the build-time endpoint-options resource written by the Arc Gradle plugin and compares it
 * against the resolved runtime [ApiEndpointOptions].
 *
 * The resource lives at `META-INF/arc/endpoint-options.json` on the application classpath.
 * It is present only when the `io.cratis.arc` Gradle plugin was applied and generated proxies; an
 * absent resource is silently ignored — an application that does not generate proxies is legitimate.
 *
 * When the resource is present and ANY setting disagrees, startup fails with a message that names
 * the setting, the build-time value, and the runtime value, so the operator can correct the
 * discrepancy before a 404 in the browser surfaces it.
 *
 * This is an internal contract between the plugin and the starter; consumers must not depend on
 * the resource format or this class directly.
 */
internal object ArcEndpointOptionsVerifier {
    internal const val RESOURCE_PATH = "META-INF/arc/endpoint-options.json"
    private const val SUPPORTED_VERSION = 1

    /**
     * Reads `META-INF/arc/endpoint-options.json` from [classLoader] and compares it to [runtime].
     * Does nothing when the resource is absent.
     *
     * @throws IllegalStateException when any setting disagrees, naming the setting and both values.
     */
    fun verify(runtime: ApiEndpointOptions, classLoader: ClassLoader) {
        val stream = classLoader.getResourceAsStream(RESOURCE_PATH) ?: return
        val json = stream.use { it.readBytes().decodeToString() }
        verify(runtime, json)
    }

    /**
     * Compares [runtime] against the endpoint options parsed from [resourceJson].
     * Does nothing when [resourceJson] is null (resource absent).
     *
     * @throws IllegalStateException when any setting disagrees.
     */
    fun verify(runtime: ApiEndpointOptions, resourceJson: String?) {
        if (resourceJson == null) return
        val buildTime = parse(resourceJson)
        val mismatches = mutableListOf<String>()

        if (runtime.routePrefix != buildTime.routePrefix) {
            mismatches += "routePrefix: build-time='${buildTime.routePrefix}', runtime='${runtime.routePrefix}'"
        }
        if (runtime.segmentsToSkipForRoute != buildTime.segmentsToSkipForRoute) {
            mismatches += "segmentsToSkipForRoute: build-time=${buildTime.segmentsToSkipForRoute}, runtime=${runtime.segmentsToSkipForRoute}"
        }
        if (runtime.includeCommandNameInRoute != buildTime.includeCommandNameInRoute) {
            mismatches += "includeCommandNameInRoute: build-time=${buildTime.includeCommandNameInRoute}, runtime=${runtime.includeCommandNameInRoute}"
        }
        if (runtime.includeQueryNameInRoute != buildTime.includeQueryNameInRoute) {
            mismatches += "includeQueryNameInRoute: build-time=${buildTime.includeQueryNameInRoute}, runtime=${runtime.includeQueryNameInRoute}"
        }
        if (runtime.enableQueryHttpMethod != buildTime.enableQueryHttpMethod) {
            mismatches += "enableQueryHttpMethod: build-time=${buildTime.enableQueryHttpMethod}, runtime=${runtime.enableQueryHttpMethod}"
        }

        if (mismatches.isNotEmpty()) {
            throw IllegalStateException(
                buildString {
                    appendLine(
                        "Arc endpoint-options mismatch: the Gradle plugin generated proxies with different route settings than the runtime configuration."
                    )
                    appendLine(
                        "Generated clients will call URLs the server does not serve. Align 'cratisArc.endpoints.*' (build) with 'cratis.arc.endpoints.*' (runtime)."
                    )
                    appendLine("Mismatched settings:")
                    mismatches.forEach { mismatch -> appendLine("  - $mismatch") }
                    append(
                        "Fix: update either the Gradle 'cratisArc.endpoints' block or the 'cratis.arc.endpoints' properties so both sides agree."
                    )
                }
            )
        }
    }

    private fun parse(json: String): ApiEndpointOptions {
        val mapper = ArcObjectMapper.create()
        val node = mapper.readTree(json) as? ObjectNode
            ?: error("$RESOURCE_PATH is not a JSON object.")
        val version = node.path("version").asInt(-1)
        require(version == SUPPORTED_VERSION) {
            "$RESOURCE_PATH has unsupported format version $version; expected $SUPPORTED_VERSION. Regenerate proxies with the current plugin."
        }
        // Every setting must be present. Defaulting a missing field would silently compare the
        // runtime against a value the build never recorded, which is the exact class of mismatch
        // this check exists to catch.
        return ApiEndpointOptions(
            routePrefix = node.required("routePrefix") { it.isString }.stringValue(),
            segmentsToSkipForRoute = node.required("segmentsToSkipForRoute") { it.isInt }.asInt(),
            includeCommandNameInRoute = node.required("includeCommandNameInRoute") { it.isBoolean }.asBoolean(),
            includeQueryNameInRoute = node.required("includeQueryNameInRoute") { it.isBoolean }.asBoolean(),
            enableQueryHttpMethod = node.required("enableQueryHttpMethod") { it.isBoolean }.asBoolean()
        )
    }

    private fun ObjectNode.required(
        field: String,
        isExpectedType: (tools.jackson.databind.JsonNode) -> Boolean
    ): tools.jackson.databind.JsonNode {
        val value = path(field)
        check(!value.isMissingNode && !value.isNull && isExpectedType(value)) {
            "$RESOURCE_PATH is missing a usable '$field' value; regenerate proxies with the current plugin."
        }
        return value
    }
}
