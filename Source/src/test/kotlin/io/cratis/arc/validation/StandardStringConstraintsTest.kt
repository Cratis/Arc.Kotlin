// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import jakarta.validation.ConstraintValidatorContext
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class StandardStringConstraintsTest {
    @Test
    fun `phone matches Arc TypeScript edge cases`() {
        val validator = PhoneValidator()

        listOf<CharSequence?>(null, "", "+1 (555) 010-0200", "12\u00A034").forEach { value ->
            assertTrue(validator.isValid(value, context), "Expected '$value' to be valid")
        }
        listOf("555.0100", "phone", "١٢٣").forEach { value ->
            assertFalse(validator.isValid(value, context), "Expected '$value' to be invalid")
        }
    }

    @Test
    fun `url matches Arc TypeScript edge cases`() {
        val validator = UrlValidator()

        listOf<CharSequence?>(null, "", "http://x", "HTTPS://example.com", "http://x\nignored").forEach { value ->
            assertTrue(validator.isValid(value, context), "Expected '$value' to be valid")
        }
        listOf("ftp://example.com", "http://", "https://\nexample.com").forEach { value ->
            assertFalse(validator.isValid(value, context), "Expected '$value' to be invalid")
        }
    }

    @Test
    fun `credit card accepts Luhn values and only Arc separators`() {
        val validator = CreditCardValidator()

        listOf<CharSequence?>(null, "", "4111111111111111", "4111-1111 1111-1111").forEach { value ->
            assertTrue(validator.isValid(value, context), "Expected '$value' to be valid")
        }
        listOf("4111111111111112", "4111_1111_1111_1111", "----").forEach { value ->
            assertFalse(validator.isValid(value, context), "Expected '$value' to be invalid")
        }
    }

    private companion object {
        val context: ConstraintValidatorContext = Proxy.newProxyInstance(
            ConstraintValidatorContext::class.java.classLoader,
            arrayOf(ConstraintValidatorContext::class.java)
        ) { _, _, _ -> error("Constraint validators must not consult their context") } as ConstraintValidatorContext
    }
}
