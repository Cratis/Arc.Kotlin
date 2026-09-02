// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

/** Raised when more than one generated handler is registered for a command type. */
public class DuplicateCommandHandlerException(
    /** Command type that already had a handler. */
    public val commandType: Class<*>
) : IllegalStateException("A command handler is already registered for '${commandType.name}'.")
