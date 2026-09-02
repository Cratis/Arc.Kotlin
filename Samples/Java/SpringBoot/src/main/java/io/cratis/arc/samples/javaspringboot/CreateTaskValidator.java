// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot;

import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.java.BlockingCommandValidator;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.results.ValidationResultSeverity;
import java.util.List;

/** Validates task creation requests through Arc's public validation seam. */
public final class CreateTaskValidator implements BlockingCommandValidator<CreateTask> {
    private static final int MAXIMUM_TITLE_LENGTH = 80;

    @Override
    public Class<CreateTask> getCommandType() {
        return CreateTask.class;
    }

    @Override
    public List<ValidationResult> validate(CreateTask command, CommandContext context) {
        if (command.title() == null || command.title().isBlank()) {
            return List.of(new ValidationResult(
                ValidationResultSeverity.Error,
                "A task title is required.",
                List.of("title")));
        }
        if (command.title().length() > MAXIMUM_TITLE_LENGTH) {
            return List.of(new ValidationResult(
                ValidationResultSeverity.Error,
                "A task title cannot exceed " + MAXIMUM_TITLE_LENGTH + " characters.",
                List.of("title")));
        }
        return List.of();
    }
}
