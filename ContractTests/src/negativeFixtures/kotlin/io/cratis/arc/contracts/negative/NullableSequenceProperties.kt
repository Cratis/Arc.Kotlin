// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.ReadModel

public sealed class NullableSequenceBase

@Command
public data class NullableListProperty(public val values: List<String?>?) { public fun handle() { } }

@Command
public data class NullableCollectionProperty(public val values: Collection<NullableSequenceBase?>?) { public fun handle() { } }

@Command
public data class NullableArrayProperty(public val values: Array<NullableSequenceBase?>?) { public fun handle() { } }

public data class NullableSequenceModel(public val values: List<String?>)

@Command
public data class NullableModelProperty(public val model: NullableSequenceModel) { public fun handle() { } }

public interface NullableSequenceView { public val values: Collection<String?>? }

@Command
public data class NullableInterfaceProperty(public val view: NullableSequenceView) { public fun handle() { } }

@ReadModel
public data class NullableSequenceReadModel(public val values: Array<String?>?) {
    public companion object {
        @JvmStatic public fun all(): NullableSequenceReadModel = NullableSequenceReadModel(null)
    }
}
