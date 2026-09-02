// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.artifacts.ArcArtifactModuleRegistry
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.queries.QueryPerformerRegistry
import java.util.ServiceLoader
import org.springframework.context.ApplicationContext

/** Deterministic snapshot of generated Arc modules loaded for a Spring application. */
public class ArcArtifactModules internal constructor(
    applicationContext: ApplicationContext,
    commandHandlers: CommandHandlerRegistry,
    queryPerformers: QueryPerformerRegistry
) {
    /** Modules deduplicated by concrete module class and ordered by fully qualified class name. */
    public val modules: List<ArcArtifactModule>

    init {
        val byClass = linkedMapOf<Class<out ArcArtifactModule>, ArcArtifactModule>()
        ServiceLoader.load(ArcArtifactModule::class.java, applicationContext.classLoader)
            .forEach { module -> byClass.putIfAbsent(module.javaClass, module) }
        applicationContext.getBeansOfType(ArcArtifactModule::class.java)
            .toSortedMap()
            .values
            .forEach { module -> byClass[module.javaClass] = module }

        modules = java.util.List.copyOf(byClass.values.sortedBy { it.javaClass.name })
        modules.forEach { module ->
            ArcArtifactModuleRegistry.register(module, commandHandlers, queryPerformers)
        }
    }
}
