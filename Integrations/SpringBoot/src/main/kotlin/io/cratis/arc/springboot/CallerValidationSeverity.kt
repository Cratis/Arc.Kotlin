// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.AllowCallerValidationSeverity
import io.cratis.arc.results.ValidationResultSeverity

internal object CallerValidationSeverity {
    fun parse(header: String?): ValidationResultSeverity? {
        if (header == null) return null
        val normalized = header.trim()
        normalized.toIntOrNull()?.let { wireValue ->
            return ValidationResultSeverity.entries.firstOrNull { severity -> severity.value() == wireValue }
        }
        return ValidationResultSeverity.entries.firstOrNull { severity ->
            severity.name.equals(normalized, ignoreCase = true)
        }
    }

    fun commandMaximum(commandType: Class<*>): ValidationResultSeverity? {
        var current: Class<*>? = commandType
        while (current != null) {
            current.getDeclaredAnnotation(AllowCallerValidationSeverity::class.java)?.let { return it.maximum }
            current = current.superclass
        }
        return null
    }

    fun cap(
        requested: ValidationResultSeverity?,
        commandMaximum: ValidationResultSeverity?,
        settings: ArcProperties.CommandProperties
    ): ValidationResultSeverity? {
        if (requested == null || settings.isIgnoreCallerAllowedSeverity) return null
        return minOf(requested, commandMaximum ?: settings.maximumCallerAllowedSeverity, compareBy { it.value() })
    }
}
