// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

/** One configured mapping of a JVM type to the TypeScript type it crosses the wire as. */
internal data class ProxyTypeMapping(
    val typeName: String,
    val typeScriptType: String,
    val npmPackage: String?
)

/**
 * Parses the repeatable type-mapping and package-mapping entries shared by the Gradle task and the CLI.
 *
 * Mirrors the Arc .NET proxy generator's `--type-to-ts` and `--assembly-to-package` contract, including its
 * bounded three-part split: anything after the second separator stays part of the npm package rather than
 * being dropped silently. The TypeScript type itself therefore cannot contain an `=`.
 */
internal object ProxyTypeMappings {
    private const val PARTS = 3

    /**
     * Parses `<FullyQualifiedTypeName>=<TypeScriptType>[=<NpmPackage>]` entries, keyed by JVM type name.
     *
     * An unusable entry is reported through [warn] and skipped rather than dropped silently: a mapping that never
     * took effect otherwise produces the generator's built-in type, which looks plausible in output nobody reads
     * until it is wrong. A later entry for the same type name replaces an earlier one.
     */
    fun parseTypeMappings(entries: List<String>, warn: (String) -> Unit): Map<String, ProxyTypeMapping> {
        val result = linkedMapOf<String, ProxyTypeMapping>()
        entries.forEach { entry ->
            val parts = entry.split('=', limit = PARTS)
            val typeName = parts.getOrNull(0)?.trim().orEmpty()
            val typeScriptType = parts.getOrNull(1)?.trim().orEmpty()
            if (parts.size < 2 || typeName.isEmpty() || typeScriptType.isEmpty()) {
                warn(
                    "Ignoring unusable Arc proxy type mapping '$entry'. Expected " +
                        "<FullyQualifiedTypeName>=<TypeScriptType>[=<NpmPackage>] with both a type name and a " +
                        "TypeScript type."
                )
                return@forEach
            }
            val npmPackage = parts.getOrNull(2)?.trim()?.takeUnless(String::isEmpty)
            result[typeName] = ProxyTypeMapping(typeName, typeScriptType, npmPackage)
        }
        return result
    }

    /** Parses `<JvmPackage>=<NpmPackage>` entries, reporting and skipping unusable ones. */
    fun parsePackageMappings(entries: List<String>, warn: (String) -> Unit): Map<String, String> {
        val result = linkedMapOf<String, String>()
        entries.forEach { entry ->
            val separator = entry.indexOf('=')
            val javaPackage = entry.take(if (separator < 0) 0 else separator).trim()
            val npmPackage = if (separator < 0) "" else entry.substring(separator + 1).trim()
            if (javaPackage.isEmpty() || npmPackage.isEmpty()) {
                warn(
                    "Ignoring unusable Arc proxy package mapping '$entry'. Expected <JvmPackage>=<NpmPackage> " +
                        "with both a JVM package and an npm package."
                )
                return@forEach
            }
            result[javaPackage] = npmPackage
        }
        return result
    }

    /**
     * Returns the npm package a JVM type belongs to, matching the longest configured package on a package boundary.
     *
     * The longest match wins so a nested package can override a broader one.
     */
    fun npmPackageFor(typeName: String, packageMappings: Map<String, String>): String? = packageMappings
        .filterKeys { javaPackage -> typeName.startsWith("$javaPackage.") }
        .maxByOrNull { entry -> entry.key.length }
        ?.value
}
