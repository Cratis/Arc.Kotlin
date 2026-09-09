// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.artifacts

import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.metadata.ConceptDescriptor
import io.cratis.arc.metadata.EnumDescriptor
import io.cratis.arc.metadata.InterfaceDescriptor
import io.cratis.arc.metadata.TypeDescriptor
import io.cratis.arc.polymorphism.DerivedTypeRegistration
import io.cratis.arc.queries.QueryPerformer

/** Immutable build-time generated collection of Arc artifacts contributed by one compilation module. */
public abstract class ArcArtifactModule @JvmOverloads protected constructor(
    commandHandlers: List<CommandHandler>,
    queryPerformers: List<QueryPerformer>,
    types: List<TypeDescriptor> = emptyList(),
    enums: List<EnumDescriptor> = emptyList(),
    interfaces: List<InterfaceDescriptor> = emptyList(),
    concepts: List<ConceptDescriptor> = emptyList(),
    derivedTypes: List<DerivedTypeRegistration> = emptyList()
) {
    /** Generated command handlers in deterministic command-name order. */
    public val commandHandlers: List<CommandHandler> = java.util.List.copyOf(commandHandlers)

    /** Generated query performers in deterministic query-name order. */
    public val queryPerformers: List<QueryPerformer> = java.util.List.copyOf(queryPerformers)

    /** Serializable model types in deterministic fully-qualified-name order. */
    public val types: List<TypeDescriptor> = java.util.List.copyOf(types)

    /** Serializable enums in deterministic fully-qualified-name order. */
    public val enums: List<EnumDescriptor> = java.util.List.copyOf(enums)

    /** Serializable interface contracts in deterministic fully-qualified-name order. */
    public val interfaces: List<InterfaceDescriptor> = java.util.List.copyOf(interfaces)

    /** Strongly typed scalar concepts in deterministic fully-qualified-name order. */
    public val concepts: List<ConceptDescriptor> = java.util.List.copyOf(concepts)

    /**
     * Base-to-derivative mappings in deterministic fully-qualified-name order.
     *
     * A host registers these with a [io.cratis.arc.polymorphism.DerivedTypeRegistry] before the first polymorphic
     * value is read; see [ArcArtifactModuleRegistry.registerDerivedTypes].
     */
    public val derivedTypes: List<DerivedTypeRegistration> = java.util.List.copyOf(derivedTypes)
}
