// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.chronicle

import io.cratis.arc.concepts.ConceptAs as ArcConceptAs
import io.cratis.chronicle.concepts.ConceptAs as ChronicleConceptAs
import java.util.UUID

/** A UUID-backed command key: a `Guid` in the generated proxy, a string event source id in Chronicle. */
public data class AuthorId(private val id: UUID) : ArcConceptAs<UUID>, ChronicleConceptAs<UUID> {
    override fun value(): UUID = id
    override val value: UUID get() = id
}
