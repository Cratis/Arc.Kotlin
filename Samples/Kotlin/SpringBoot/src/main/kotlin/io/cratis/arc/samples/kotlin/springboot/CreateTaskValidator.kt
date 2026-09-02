// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandValidator
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity
import org.springframework.stereotype.Component

/** Validates task creation requests through Arc's public validation seam. */
@Component
public class CreateTaskValidator : CommandValidator<CreateTask> {
    override val commandType: Class<CreateTask> = CreateTask::class.java

    override suspend fun validate(command: CreateTask, context: CommandContext): List<ValidationResult> = when {
        TaskTitleRule.isBlank(command.title) -> listOf(
            ValidationResult(ValidationResultSeverity.Error, "A task title is required.", listOf("title"))
        )
        TaskTitleRule.isTooLong(command.title) -> listOf(
            ValidationResult(
                ValidationResultSeverity.Error,
                "A task title cannot exceed ${TaskTitleRule.MAXIMUM_LENGTH} characters.",
                listOf("title")
            )
        )
        else -> emptyList()
    }
}
