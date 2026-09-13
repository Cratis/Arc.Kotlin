// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import com.fasterxml.jackson.annotation.JsonProperty
import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey

@Command
public class ComputedBodyInput(public val first: String) {
    public val calculated: String get() = first
    public fun handle() { }
}

public class ComputedBodyChild(public val first: String) {
    public val calculated: String get() = first
}

@Command
public class NestedComputedBodyInput(public val child: ComputedBodyChild) {
    public fun handle() { }
}

public class JavaComputedBodyChild(public val first: String) {
    public val javaCalculated: String get() = first
}

@Command
public class DuplicateBodyKeys(@CommandKey public val first: String) {
    @CommandKey public var second: String = "second"
    public fun handle() { }
}

@Command
public class RenamedBodyInput(public val first: String) {
    @get:JsonProperty("wire_value") public var value: String = "value"
    public fun handle() { }
}
