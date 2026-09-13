// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.commands.DefaultCommandValidationFilter
import io.cratis.arc.queries.DefaultQueryValidationFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConceptExclusionAvailabilityTest {
    @Test
    fun `direct exclusion registration and additive four iterable filters are available`() {
        val type = Class.forName("io.cratis.arc.validation.ConceptValidationExclusion")
        type.getConstructor(Class::class.java, String::class.java)
        assertEquals(Class::class.java, type.getMethod("getOwnerType").returnType)
        assertEquals(String::class.java, type.getMethod("getMember").returnType)
        for (filter in listOf(DefaultCommandValidationFilter::class.java, DefaultQueryValidationFilter::class.java)) {
            filter.getConstructor(Iterable::class.java, Iterable::class.java, Iterable::class.java, Iterable::class.java)
        }
    }
}
