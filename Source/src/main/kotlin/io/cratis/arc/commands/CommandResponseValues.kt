// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

/** Explicit ordered aggregate of values returned by a command handler. */
public class CommandResponseValues(values: Collection<Any>) {
    /** Values in handler-declared order. */
    public val values: List<Any> = java.util.List.copyOf(values)

    /** Java-friendly mutable builder whose result is immutable. */
    public class Builder {
        private val values = mutableListOf<Any>()

        /** Appends [value]. */
        public fun add(value: Any): Builder = apply { values.add(value) }

        /** Builds an immutable aggregate. */
        public fun build(): CommandResponseValues = CommandResponseValues(values)
    }

    public companion object {
        /** Creates an aggregate from ordered [values]. */
        @JvmStatic
        public fun of(vararg values: Any): CommandResponseValues = CommandResponseValues(values.asList())

        /** Creates a Java-friendly builder. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
