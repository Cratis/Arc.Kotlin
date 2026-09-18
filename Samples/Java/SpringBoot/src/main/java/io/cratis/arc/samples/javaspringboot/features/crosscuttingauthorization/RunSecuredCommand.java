// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.crosscuttingauthorization;

import io.cratis.arc.artifacts.Command;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The command the matching command filter guards.
 *
 * @param message The message to echo back from the command execution.
 */
@Command
public record RunSecuredCommand(String message) {
    /**
     * Handles the command.
     *
     * @return A message naming when the command was authorized and executed.
     */
    public String handle() {
        var stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return "Command authorized and executed at " + stamp + " — " + message;
    }
}
