// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.results.ValidationResultSeverity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CallerValidationSeverityPropertiesJavaConformanceTest {
    @Test
    void defaultsLimitCallersToWarnings() {
        var command = new ArcProperties().getCommand();
        assertEquals(ValidationResultSeverity.Warning, command.getMaximumCallerAllowedSeverity());
        assertFalse(command.isIgnoreCallerAllowedSeverity());
    }

    @Test
    void settersExposeCommandPolicyToJava() {
        var properties = new ArcProperties();
        var command = new ArcProperties.CommandProperties();
        command.setMaximumCallerAllowedSeverity(ValidationResultSeverity.Error);
        command.setIgnoreCallerAllowedSeverity(true);
        properties.setCommand(command);
        assertSame(command, properties.getCommand());
        assertEquals(ValidationResultSeverity.Error, command.getMaximumCallerAllowedSeverity());
        assertTrue(command.isIgnoreCallerAllowedSeverity());
    }

    @Test
    void maximumCannotBeNull() {
        var command = new ArcProperties.CommandProperties();
        var exception = assertThrows(IllegalArgumentException.class, () -> command.setMaximumCallerAllowedSeverity(null));
        assertEquals("maximumCallerAllowedSeverity must not be null.", exception.getMessage());
    }
}
