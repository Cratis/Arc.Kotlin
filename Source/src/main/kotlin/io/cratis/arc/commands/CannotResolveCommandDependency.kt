// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

/** Deterministic failure raised when a generated command method dependency cannot be resolved. */
public class CannotResolveCommandDependency(
    public val commandType: Class<*>,
    public val methodName: String,
    public val parameterName: String,
    public val dependencyType: Class<*>
) : IllegalStateException(
    "Cannot invoke '${commandType.name}.$methodName' because its required dependency '${dependencyType.name}' " +
        "for parameter '$parameterName' could not be resolved or resolved to null."
)

/** Internal control failure for an absent registry-owned command-side read model. */
internal class CommandDependencyUnavailable(
    val commandType: Class<*>,
    val methodName: String,
    val parameterName: String,
    val dependencyType: Class<*>
) : IllegalStateException(
    "Cannot invoke '${commandType.name}.$methodName' because command-side read model '${dependencyType.name}' " +
        "for parameter '$parameterName' is unavailable."
)
