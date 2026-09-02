// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.polymorphism

import java.util.concurrent.ConcurrentHashMap

/** Thread-safe, explicitly populated [DerivedTypeRegistry]. */
public class ConcurrentDerivedTypeRegistry : DerivedTypeRegistry {
    private data class Registrations(
        val byId: Map<String, Class<*>> = emptyMap(),
        val byType: Map<Class<*>, String> = emptyMap()
    )

    private val registrations = ConcurrentHashMap<Class<*>, Registrations>()

    @Synchronized
    override fun register(baseType: Class<*>, derivedType: Class<*>) {
        require(baseType.isAssignableFrom(derivedType)) {
            "${derivedType.name} is not assignable to ${baseType.name}"
        }

        val annotation = derivedType.getAnnotation(DerivedType::class.java)
            ?: throw IllegalArgumentException("${derivedType.name} must be annotated with @DerivedType")
        require(annotation.id.isNotBlank()) { "Derived type identifiers cannot be blank" }

        val current = registrations[baseType] ?: Registrations()
        val typeForId = current.byId[annotation.id]
        require(typeForId == null || typeForId == derivedType) {
            "Derived type identifier '${annotation.id}' is already registered for ${typeForId?.name}"
        }
        val idForType = current.byType[derivedType]
        require(idForType == null || idForType == annotation.id) {
            "${derivedType.name} is already registered with derived type identifier '$idForType'"
        }

        registrations[baseType] = Registrations(
            current.byId + (annotation.id to derivedType),
            current.byType + (derivedType to annotation.id)
        )
    }

    override fun resolve(baseType: Class<*>, id: String): Class<*>? = registrations[baseType]?.byId?.get(id)

    override fun idFor(baseType: Class<*>, derivedType: Class<*>): String? =
        registrations[baseType]?.byType?.get(derivedType)

    override fun registeredBaseTypes(): Set<Class<*>> = java.util.Set.copyOf(registrations.keys)
}
