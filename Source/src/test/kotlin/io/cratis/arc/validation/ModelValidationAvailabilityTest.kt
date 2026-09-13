// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ModelValidationAvailabilityTest {
    @Test
    fun `reusable model validation exposes Kotlin and ordinary Java contracts`() {
        listOf(
            "io.cratis.arc.validation.ModelValidator",
            "io.cratis.arc.validation.ModelValidationContext",
            "io.cratis.arc.java.BlockingModelValidator",
            "io.cratis.arc.java.AsyncModelValidator",
            "io.cratis.arc.java.BlockingModelValidatorAdapter",
            "io.cratis.arc.java.AsyncModelValidatorAdapter"
        ).forEach { assertNotNull(Class.forName(it)) }
    }
}
