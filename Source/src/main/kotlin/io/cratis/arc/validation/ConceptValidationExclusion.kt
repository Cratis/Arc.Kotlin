// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.concepts.ConceptAs
import java.lang.reflect.Modifier
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter

/**
 * Suppresses concept rules only on one direct member of an exact runtime owner type.
 * The declared member type must implement [ConceptAs]. Inherited public readable members are
 * supported, but registration for a base owner does not match derived runtime owners.
 * Metadata is checked immediately, without constructing an owner or invoking its getters.
 * Model rules, Jakarta constraints and other pipeline filters are never excluded.
 */
public class ConceptValidationExclusion(public val ownerType: Class<*>, public val member: String) {
    init {
        require(member.isNotBlank()) { "Concept exclusion member must not be blank." }
        require(member.none { it in ".[]*?" }) {
            "Concept exclusion member '$member' must be one direct member name, not a path, index or wildcard."
        }
        val declaredType = if (ownerType.isRecord) {
            ownerType.recordComponents.firstOrNull { it.name == member && Modifier.isPublic(it.accessor.modifiers) }?.type
        } else {
            val property = ownerType.kotlin.memberProperties.firstOrNull { it.name == member && it.visibility == KVisibility.PUBLIC }
            property?.javaGetter?.returnType ?: property?.javaField?.type
                ?: ownerType.fields.firstOrNull { it.name == member && !Modifier.isStatic(it.modifiers) }?.type
        }
        require(declaredType != null) { "Concept exclusion '${ownerType.name}.$member' must name a public readable member." }
        require(ConceptAs::class.java.isAssignableFrom(declaredType)) {
            "Concept exclusion '${ownerType.name}.$member' must have a declared type implementing ConceptAs."
        }
    }
}
