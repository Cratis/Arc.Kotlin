// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.Command
import io.cratis.arc.authorization.AllowAnonymous

/** First value supplied by the provide phase. */
public data class FirstProvidedValue(public val value: String)

/** Second value supplied by the provide phase. */
public data class SecondProvidedValue(public val value: String)

/** Service resolved only when no provided value matches a handler argument. */
public data class ProvideFallback(public val suffix: String)

/** Scoped fixture service used by the generated provide call. */
public fun interface ProvideFixtureService {
    public fun provide(value: String): Any?
}

/** Exercises model-bound provide and handler argument resolution. */
@Command
@AllowAnonymous
public data class ProvideCommand(public val value: String) {
    public fun provide(service: ProvideFixtureService): Any? = service.provide(value)

    public fun handle(
        first: FirstProvidedValue,
        second: SecondProvidedValue,
        fallback: ProvideFallback
    ): String = "${first.value}:${second.value}:${fallback.suffix}"
}
