// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.results.ValidationResult

/** Typed host-neutral validator for one command type. */
public interface CommandValidator<T : Any> {
    /** Exact command type accepted by this validator. */
    public val commandType: Class<T>

    /** Validates [command] and returns validation feedback in declaration order. */
    public suspend fun validate(command: T, context: CommandContext): List<ValidationResult>
}
