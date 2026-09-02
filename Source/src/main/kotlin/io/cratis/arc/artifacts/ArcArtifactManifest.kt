// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.artifacts

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ConceptDescriptor
import io.cratis.arc.metadata.EnumDescriptor
import io.cratis.arc.metadata.InterfaceDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.TypeDescriptor

/** Immutable, language-neutral manifest for the Arc artifacts contributed by one compilation module. */
@JsonPropertyOrder("formatVersion", "moduleName", "commands", "queries", "types", "interfaces", "enums", "concepts")
public class ArcArtifactManifest @JvmOverloads constructor(
    /** Stable module name supplied to code generation. */
    public val moduleName: String,
    commands: List<CommandDescriptor> = emptyList(),
    queries: List<QueryDescriptor> = emptyList(),
    types: List<TypeDescriptor> = emptyList(),
    enums: List<EnumDescriptor> = emptyList(),
    /** Manifest contract version. */
    public val formatVersion: Int = CURRENT_FORMAT_VERSION,
    interfaces: List<InterfaceDescriptor> = emptyList(),
    concepts: List<ConceptDescriptor> = emptyList()
) {
    public val commands: List<CommandDescriptor> = java.util.List.copyOf(commands)
    public val queries: List<QueryDescriptor> = java.util.List.copyOf(queries)
    public val types: List<TypeDescriptor> = java.util.List.copyOf(types)
    /** Serializable interface contracts in deterministic fully-qualified-name order. */
    public val interfaces: List<InterfaceDescriptor> = java.util.List.copyOf(interfaces)
    public val enums: List<EnumDescriptor> = java.util.List.copyOf(enums)
    /** Strongly typed scalar concepts in deterministic fully-qualified-name order. */
    public val concepts: List<ConceptDescriptor> = java.util.List.copyOf(concepts)

    public companion object {
        /** Current language-neutral manifest contract version. */
        public const val CURRENT_FORMAT_VERSION: Int = 5
    }
}
