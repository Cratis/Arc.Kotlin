// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** A mixed constructor and body wire contract. */
@Command
public class KotlinBodyCommand(public val zulu: String, public val alpha: String) {
    @get:Size(min = 2, max = 30)
    public var title: String = "title"
        private set

    /** Stable body identifier. */
    @CommandKey
    @field:NotBlank
    @get:JsonProperty(access = JsonProperty.Access.READ_WRITE)
    public var id: String = "default-key"

    @field:Valid
    public var child: BodyChild = BodyChild("child")

    public val backed: String = "backed"
    public var URLValue: String = "url"

    @field:JsonIgnore
    public var secret: String = "secret"

    @get:JsonIgnore
    public val computed: String get() = zulu + alpha

    private val hidden: String = "hidden"

    public fun handle(): BodyOutput = BodyOutput(id).also { it.detail = "$title:$backed:${child.last}:$URLValue" }
}

public class BodyChild(public val first: String) {
    @field:NotBlank
    public var last: String = "last"
}

public open class BodyBase(public val base: String) {
    public var baseBody: String = "base-body"
    public open val label: String get() = base
}

public class BodyOutput(public val id: String) : BodyBase(id) {
    @field:JsonProperty(access = JsonProperty.Access.READ_WRITE)
    public var detail: String = "detail"
    public override val label: String get() = "output-$id"
}
