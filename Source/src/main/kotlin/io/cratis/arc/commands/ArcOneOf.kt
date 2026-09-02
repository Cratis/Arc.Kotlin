// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

/** A JVM-friendly discriminated alternative whose selected [value] is processed by Arc. */
public class ArcOneOf<out T : Any> private constructor(public val value: T) {
    public companion object {
        /** Selects [value] as the active alternative. */
        @JvmStatic
        public fun <T : Any> of(value: T): ArcOneOf<T> = ArcOneOf(value)

        /** Java-friendly alias for selecting an alternative. */
        @JvmStatic
        public fun <T : Any> alternative(value: T): ArcOneOf<T> = ArcOneOf(value)
    }
}
