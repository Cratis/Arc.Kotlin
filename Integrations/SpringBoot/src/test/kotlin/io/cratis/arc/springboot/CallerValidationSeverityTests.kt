// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.AllowCallerValidationSeverity
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.results.ValidationResultSeverity.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class CallerValidationSeverityTests {
    @Test
    fun `parse accepts wire numbers and case insensitive names`() {
        mapOf("3" to Error, "Error" to Error, " error " to Error, "wArNiNg" to Warning, "0" to Unknown, "Unknown" to Unknown)
            .forEach { (header, severity) -> assertEquals(severity, CallerValidationSeverity.parse(header), header) }
    }

    @Test
    fun `parse ignores absent blank unrecognised and out of range headers`() {
        listOf(null, "", "   ", "not-a-severity", "4", "999", "-1", "3.0")
            .forEach { header -> assertNull(CallerValidationSeverity.parse(header), header) }
    }

    @Test
    fun `default cap permits at most warnings`() {
        val settings = ArcProperties.CommandProperties()
        mapOf(Error to Warning, Warning to Warning, Information to Information, Unknown to Unknown, null to null)
            .forEach { (requested, expected) ->
                assertEquals(expected, CallerValidationSeverity.cap(requested, null, settings))
            }
    }

    @Test
    fun `command maximum replaces host maximum in either direction`() {
        val settings = ArcProperties.CommandProperties()
        assertEquals(Error, CallerValidationSeverity.cap(Error, Error, settings))
        settings.maximumCallerAllowedSeverity = Error
        assertEquals(Information, CallerValidationSeverity.cap(Warning, Information, settings))
    }

    @Test
    fun `host maximum applies without a command annotation`() {
        val settings = ArcProperties.CommandProperties()
        settings.maximumCallerAllowedSeverity = Error
        assertEquals(Error, CallerValidationSeverity.cap(Error, null, settings))
        settings.maximumCallerAllowedSeverity = Information
        assertEquals(Information, CallerValidationSeverity.cap(Warning, null, settings))
    }

    @Test
    fun `ignore overrides requested severity and command opt in`() {
        val settings = ArcProperties.CommandProperties()
        settings.isIgnoreCallerAllowedSeverity = true
        assertNull(CallerValidationSeverity.cap(Error, null, settings))
        assertNull(CallerValidationSeverity.cap(Error, Error, settings))
    }

    @Test
    fun `warnings as errors floor permits at most information and preserves stricter requests`() {
        val settings = ArcProperties.CommandProperties()
        mapOf(Error to Information, Warning to Information, Information to Information, Unknown to Unknown, null to Information)
            .forEach { (requested, expected) ->
                assertEquals(expected, CallerValidationSeverity.cap(requested, null, settings, true))
            }
    }

    @Test
    fun `command and host opt ins cannot lift the warnings as errors floor`() {
        val settings = ArcProperties.CommandProperties()
        settings.maximumCallerAllowedSeverity = Error
        assertEquals(Information, CallerValidationSeverity.cap(Error, null, settings, true))
        assertEquals(Information, CallerValidationSeverity.cap(Error, Error, settings, true))
        settings.isIgnoreCallerAllowedSeverity = true
        assertEquals(Information, CallerValidationSeverity.cap(Error, Error, settings, true))
        assertEquals(Information, CallerValidationSeverity.cap(Unknown, Error, settings, true))
    }

    @Test
    fun `warnings as errors floor preserves stricter host and command maxima`() {
        val settings = ArcProperties.CommandProperties()
        settings.maximumCallerAllowedSeverity = Unknown
        assertEquals(Unknown, CallerValidationSeverity.cap(Error, null, settings, true))
        settings.maximumCallerAllowedSeverity = Error
        assertEquals(Unknown, CallerValidationSeverity.cap(Error, Unknown, settings, true))
    }

    @Test
    fun `command maximum consults superclasses but prefers the nearest annotation`() {
        assertEquals(Error, CallerValidationSeverity.commandMaximum(InheritedCommand::class.java))
        assertEquals(Information, CallerValidationSeverity.commandMaximum(StricterCommand::class.java))
        assertNull(CallerValidationSeverity.commandMaximum(PlainCommand::class.java))
        assertNull(CallerValidationSeverity.commandMaximum(InterfaceCommand::class.java))
    }

    @AllowCallerValidationSeverity(maximum = Error)
    private open class BaseCommand
    private class InheritedCommand : BaseCommand()
    @AllowCallerValidationSeverity(maximum = Information)
    private class StricterCommand : BaseCommand()
    private class PlainCommand
    @AllowCallerValidationSeverity(maximum = Warning)
    private interface AnnotatedInterface
    private class InterfaceCommand : AnnotatedInterface
}
