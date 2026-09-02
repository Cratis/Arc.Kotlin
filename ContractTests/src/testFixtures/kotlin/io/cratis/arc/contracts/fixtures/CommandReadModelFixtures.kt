// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.arc.artifacts.ReadModel

/** Shared command-side read model used to verify generated contextual argument resolution. */
@ReadModel
public data class CommandReadModel(public val id: String, public val value: String)

/** Kotlin command whose generated handler resolves its current read model by command key. */
@Command
public data class KotlinReadModelCommand(@CommandKey public val id: String) {
    public fun handle(current: CommandReadModel): String = "kotlin:${current.value}"
}

/** Kotlin command whose nullable owned read model receives null when no keyed row exists. */
@Command
public data class KotlinNullableReadModelCommand(@CommandKey public val id: String) {
    public fun handle(current: CommandReadModel?): String = "kotlin:${current?.value ?: "none"}"
}

/** Kotlin command used to prove an owned required read model needs a generated command key. */
@Command
public data class KotlinReadModelCommandWithoutKey(public val id: String) {
    public fun handle(current: CommandReadModel): String = "unexpected:${current.value}"
}
