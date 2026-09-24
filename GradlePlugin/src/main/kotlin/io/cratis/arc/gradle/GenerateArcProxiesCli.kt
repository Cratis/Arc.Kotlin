// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.metadata.ApiEndpointOptions
import java.io.File

/** Command-line entry point for generating TypeScript proxies from Arc artifact manifests. */
public object GenerateArcProxiesCli {
    /**
     * Generates proxies using the same discovery and renderer as the Gradle plugin task.
     *
     * When `--endpoint-options-output <dir>` is supplied the CLI also writes
     * `META-INF/arc/endpoint-options.json` to that directory using the same serialisation as
     * [WriteArcEndpointOptionsResource], so projects that invoke the CLI directly (instead of
     * applying the `io.cratis.arc` Gradle plugin) can still participate in the Spring Boot
     * startup consistency check.
     */
    @JvmStatic
    public fun main(arguments: Array<String>) {
        val options = parseArguments(arguments)
        val manifests = ArcManifestDiscovery.discover(options.manifestClasspath, options.moduleName)
        val artifacts = ArcManifestDiscovery.merge(manifests)
        TypeScriptProxyGenerator(
            artifacts,
            ProxyGenerationOptions(
                options.outputDirectory,
                ApiEndpointOptions(
                    options.routePrefix,
                    options.routeSegmentsToSkip,
                    options.includeCommandNames,
                    options.includeQueryNames,
                    options.enableQueryHttpMethod
                ),
                options.removeStaleGeneratedFiles,
                options.proxySegmentsToSkip,
                ProxyTypeMappings.parseTypeMappings(options.typeMappings) { println("warning: $it") },
                ProxyTypeMappings.parsePackageMappings(options.packageMappings) { println("warning: $it") }
            ),
            options.useProxyFileSuffix
        ).generate()
        // Write the endpoint-options resource after proxy generation so callers that do not use
        // the Gradle plugin can still activate the Spring Boot startup consistency check.
        options.endpointOptionsOutputDirectory?.let { outputDir ->
            writeEndpointOptionsResource(
                outputDir,
                options.routePrefix,
                options.routeSegmentsToSkip,
                options.includeCommandNames,
                options.includeQueryNames,
                options.enableQueryHttpMethod
            )
        }
    }

    private fun parseArguments(arguments: Array<String>): CliOptions {
        val values = mutableMapOf<String, MutableList<String>>()
        var index = 0
        while (index < arguments.size) {
            val option = arguments[index]
            require(option.startsWith("--")) { "Expected an option, but received '$option'." }
            require(index + 1 < arguments.size) { "Missing value for '$option'." }
            values.getOrPut(option) { mutableListOf() }.add(arguments[index + 1])
            index += 2
        }

        val classpath = values.remove("--manifest-classpath")
            ?.flatMap { it.split(File.pathSeparatorChar) }
            ?.filter(String::isNotBlank)
            ?.map(::File)
            .orEmpty()
        require(classpath.isNotEmpty()) { "At least one --manifest-classpath directory or jar is required." }
        val output = values.single("--output-directory")?.let(::File)
            ?: error("--output-directory is required.")
        val endpointOptionsOutput = values.single("--endpoint-options-output")?.let(::File)
        val result = CliOptions(
            classpath,
            output,
            values.single("--route-prefix") ?: "api",
            values.integer("--route-segments-to-skip", 0),
            values.boolean("--include-command-names", true),
            values.boolean("--include-query-names", true),
            values.boolean("--enable-query-http-method", true),
            values.boolean("--remove-stale-generated-files", true),
            values.integer("--proxy-segments-to-skip", 0),
            values.boolean("--use-proxy-file-suffix", false),
            values.remove("--type-to-typescript").orEmpty(),
            values.remove("--package-to-npm").orEmpty(),
            values.single("--module-name"),
            endpointOptionsOutput
        )
        require(result.routeSegmentsToSkip >= 0) { "--route-segments-to-skip cannot be negative." }
        require(result.proxySegmentsToSkip >= 0) { "--proxy-segments-to-skip cannot be negative." }
        require(values.isEmpty()) { "Unknown options: ${values.keys.sorted().joinToString()}." }
        return result
    }

    private fun MutableMap<String, MutableList<String>>.single(name: String): String? =
        remove(name)?.also { require(it.size == 1) { "'$name' can only be specified once." } }?.single()

    private fun MutableMap<String, MutableList<String>>.integer(name: String, default: Int): Int =
        single(name)?.toIntOrNull() ?: default

    private fun MutableMap<String, MutableList<String>>.boolean(name: String, default: Boolean): Boolean =
        single(name)?.let {
            require(it == "true" || it == "false") { "'$name' must be true or false." }
            it.toBoolean()
        } ?: default

    private data class CliOptions(
        val manifestClasspath: List<File>,
        val outputDirectory: File,
        val routePrefix: String,
        val routeSegmentsToSkip: Int,
        val includeCommandNames: Boolean,
        val includeQueryNames: Boolean,
        val enableQueryHttpMethod: Boolean,
        val removeStaleGeneratedFiles: Boolean,
        val proxySegmentsToSkip: Int,
        val useProxyFileSuffix: Boolean,
        val typeMappings: List<String> = emptyList(),
        val packageMappings: List<String> = emptyList(),
        val moduleName: String? = null,
        /** When non-null the CLI also writes `META-INF/arc/endpoint-options.json` here. */
        val endpointOptionsOutputDirectory: File? = null
    )
}
