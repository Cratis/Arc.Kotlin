// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandValidator
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity
import org.springframework.stereotype.Component

/** Validates bounded task-batch creation requests through Arc's public validation seam. */
@Component
public class CreateTaskBatchValidator : CommandValidator<CreateTaskBatch> {
    override val commandType: Class<CreateTaskBatch> = CreateTaskBatch::class.java

    override suspend fun validate(command: CreateTaskBatch, context: CommandContext): List<ValidationResult> = when {
        command.titles.isEmpty() -> listOf(error("A task batch must contain at least one title."))
        command.titles.size > CreateTaskBatch.MAXIMUM_BATCH_SIZE -> listOf(
            error("A task batch cannot contain more than ${CreateTaskBatch.MAXIMUM_BATCH_SIZE} titles.")
        )
        command.titles.any(TaskTitleRule::isBlank) -> listOf(error("Every task in a batch requires a title."))
        command.titles.any(TaskTitleRule::isTooLong) -> listOf(
            error("A task title cannot exceed ${TaskTitleRule.MAXIMUM_LENGTH} characters.")
        )
        else -> emptyList()
    }

    private fun error(message: String): ValidationResult =
        ValidationResult(ValidationResultSeverity.Error, message, listOf("titles"))
}
