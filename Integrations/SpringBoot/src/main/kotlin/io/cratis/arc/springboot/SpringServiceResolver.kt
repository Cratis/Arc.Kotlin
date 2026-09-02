// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.commands.ServiceResolver
import org.springframework.context.ApplicationContext

/** Resolves generated command dependencies from the current Spring application context. */
public class SpringServiceResolver(private val applicationContext: ApplicationContext) : ServiceResolver {
    override fun <T : Any> resolve(type: Class<T>): T? = applicationContext.getBeanProvider(type).ifAvailable
}
