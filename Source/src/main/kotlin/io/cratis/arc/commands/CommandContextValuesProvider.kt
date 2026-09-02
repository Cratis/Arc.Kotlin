// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

/** Contributes named immutable values to each command context before filters and scopes run. */
public fun interface CommandContextValuesProvider {
    /** Provides values for [command]. Later providers replace an earlier value with the same name. */
    public fun provide(command: Any): Map<String, Any>
}
