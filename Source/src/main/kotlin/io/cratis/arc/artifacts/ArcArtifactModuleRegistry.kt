// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.artifacts

import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.polymorphism.DerivedTypeRegistry
import io.cratis.arc.queries.QueryPerformerRegistry

/** Registers every generated artifact exposed by an [ArcArtifactModule]. */
public object ArcArtifactModuleRegistry {
    /** Registers [module] with the supplied runtime registries. */
    @JvmStatic
    public fun register(
        module: ArcArtifactModule,
        commandHandlers: CommandHandlerRegistry,
        queryPerformers: QueryPerformerRegistry
    ) {
        module.commandHandlers.forEach(commandHandlers::register)
        module.queryPerformers.forEach(queryPerformers::register)
    }

    /**
     * Registers the base-to-derivative mappings [module] declares, so `_derivedTypeId` resolves when reading.
     *
     * Registration is idempotent, so registering the same module or an overlapping module twice is safe. Do this
     * before the first polymorphic value is read.
     */
    @JvmStatic
    public fun registerDerivedTypes(module: ArcArtifactModule, derivedTypes: DerivedTypeRegistry) {
        module.derivedTypes.forEach { registration ->
            derivedTypes.register(registration.baseType, registration.derivedType)
        }
    }
}
