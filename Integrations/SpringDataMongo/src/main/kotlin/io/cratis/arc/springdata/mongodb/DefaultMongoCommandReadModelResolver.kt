// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.commands.CommandHandlerRegistry
import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.mapping.MongoMappingContext

/** Spring Data MongoDB implementation of command-side read-model lookup. */
public class DefaultMongoCommandReadModelResolver(
    private val mongoOperations: MongoOperations,
    private val mappingContext: MongoMappingContext,
    private val handlers: CommandHandlerRegistry
) : MongoCommandReadModelResolver {
    override fun <T : Any> resolve(readModelType: Class<T>, command: Any): T? {
        require(readModelType.isAnnotationPresent(ReadModel::class.java)) {
            "${readModelType.name} is not annotated with @ReadModel."
        }
        val handler = checkNotNull(handlers.find(command.javaClass)) {
            "No Arc command handler is registered for ${command.javaClass.name}."
        }
        val key = handler.resolveCommandKey(command)
        require(key != null && (key !is String || key.isNotEmpty())) {
            "Command ${command.javaClass.name} does not provide a usable command key for ${readModelType.name}."
        }

        val persistentEntity = checkNotNull(mappingContext.getPersistentEntity(readModelType)) {
            "Read model ${readModelType.name} is not mapped by Spring Data MongoDB."
        }
        check(persistentEntity.idProperty != null) {
            "Read model ${readModelType.name} must have a Spring Data MongoDB identifier."
        }
        return mongoOperations.findById(key, readModelType)
    }
}
