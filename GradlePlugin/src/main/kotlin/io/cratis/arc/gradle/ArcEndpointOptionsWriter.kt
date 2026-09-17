// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import java.io.File

/**
 * Writes `META-INF/arc/endpoint-options.json` to [outputDirectory].
 *
 * This is the single point of truth for the resource format. Both the Gradle task
 * ([WriteArcEndpointOptionsResource]) and the CLI ([GenerateArcProxiesCli]) delegate here so
 * that the JSON shape cannot drift between the two build-time paths.
 */
internal fun writeEndpointOptionsResource(
    outputDirectory: File,
    routePrefix: String,
    segmentsToSkipForRoute: Int,
    includeCommandNameInRoute: Boolean,
    includeQueryNameInRoute: Boolean,
    enableQueryHttpMethod: Boolean
) {
    val resourceFile = File(outputDirectory, "META-INF/arc/endpoint-options.json")
    resourceFile.parentFile.mkdirs()
    resourceFile.writeText(
        buildString {
            appendLine("{")
            appendLine("  \"version\": 1,")
            appendLine("  \"routePrefix\": ${endpointOptionsJsonString(routePrefix)},")
            appendLine("  \"segmentsToSkipForRoute\": $segmentsToSkipForRoute,")
            appendLine("  \"includeCommandNameInRoute\": $includeCommandNameInRoute,")
            appendLine("  \"includeQueryNameInRoute\": $includeQueryNameInRoute,")
            append("  \"enableQueryHttpMethod\": $enableQueryHttpMethod")
            appendLine()
            append("}")
        }
    )
}

private fun endpointOptionsJsonString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
