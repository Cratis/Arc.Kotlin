// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/** Requires a non-empty value to use the phone-number characters accepted by the Arc TypeScript validator. */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [PhoneValidator::class])
public annotation class Phone(
    val message: String = "{io.cratis.arc.validation.Phone.message}",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

/** Requires a non-empty value to start with an HTTP or HTTPS scheme, matching the Arc TypeScript validator. */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [UrlValidator::class])
public annotation class Url(
    val message: String = "{io.cratis.arc.validation.Url.message}",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

/** Requires a non-empty value to be a Luhn-valid credit-card number containing optional spaces or hyphens. */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [CreditCardValidator::class])
public annotation class CreditCard(
    val message: String = "{io.cratis.arc.validation.CreditCard.message}",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

/** Jakarta validator for [Phone]. Null and empty values are left to presence constraints. */
public class PhoneValidator : ConstraintValidator<Phone, CharSequence?> {
    override fun isValid(value: CharSequence?, context: ConstraintValidatorContext): Boolean =
        value == null || value.isEmpty() || value.all(::isArcPhoneCharacter)

    private fun isArcPhoneCharacter(character: Char): Boolean =
        character in '0'..'9' || character == '(' || character == ')' || character == '+' || character == '-' ||
            character.isJavaScriptWhitespace()

    private fun Char.isJavaScriptWhitespace(): Boolean =
        this in '\u0009'..'\u000D' || this == '\u0020' || this == '\u00A0' || this == '\u1680' ||
            this in '\u2000'..'\u200A' || this == '\u2028' || this == '\u2029' || this == '\u202F' ||
            this == '\u205F' || this == '\u3000' || this == '\uFEFF'
}

/** Jakarta validator for [Url]. Null and empty values are left to presence constraints. */
public class UrlValidator : ConstraintValidator<Url, CharSequence?> {
    override fun isValid(value: CharSequence?, context: ConstraintValidatorContext): Boolean {
        if (value == null || value.isEmpty()) return true
        val text = value.toString()
        val schemeLength = when {
            text.startsWith("http://", ignoreCase = true) -> 7
            text.startsWith("https://", ignoreCase = true) -> 8
            else -> return false
        }
        return text.length > schemeLength && text[schemeLength] !in JAVASCRIPT_LINE_TERMINATORS
    }

    private companion object {
        val JAVASCRIPT_LINE_TERMINATORS: Set<Char> = setOf('\n', '\r', '\u2028', '\u2029')
    }
}

/** Jakarta validator for [CreditCard]. Null and empty values are left to presence constraints. */
public class CreditCardValidator : ConstraintValidator<CreditCard, CharSequence?> {
    override fun isValid(value: CharSequence?, context: ConstraintValidatorContext): Boolean {
        if (value == null || value.isEmpty()) return true
        var checksum = 0
        var doubleDigit = false
        var digitCount = 0
        for (index in value.indices.reversed()) {
            val character = value[index]
            if (character == ' ' || character == '-') continue
            if (character !in '0'..'9') return false
            var digit = character - '0'
            if (doubleDigit) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            checksum += digit
            digitCount++
            doubleDigit = !doubleDigit
        }
        return digitCount > 0 && checksum % 10 == 0
    }
}
