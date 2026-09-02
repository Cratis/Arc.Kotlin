// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

/**
 * Provides the key that identifies a command when no generated [io.cratis.arc.artifacts.CommandKey] property exists.
 *
 * The method form is intentionally straightforward for Java records and classes to implement.
 */
public fun interface CommandKeyProvider {
    /** Returns the command key, or `null` when this command has no key. */
    public fun commandKey(): Any?
}

/** Kotlin property view of the command key, or `null` when this command has no key. */
@get:JvmSynthetic
public val CommandKeyProvider.key: Any?
    get() = commandKey()
