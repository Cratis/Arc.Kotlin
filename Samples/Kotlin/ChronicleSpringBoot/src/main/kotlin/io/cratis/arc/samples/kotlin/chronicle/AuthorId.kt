// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.chronicle

import io.cratis.arc.concepts.ConceptAs as ArcConceptAs
import io.cratis.chronicle.concepts.ConceptAs as ChronicleConceptAs

/** A string-backed command key serialized consistently by Arc and Chronicle. */
public data class AuthorId(private val id: String) : ArcConceptAs<String>, ChronicleConceptAs<String> {
    override fun value(): String = id
    override val value: String get() = id
}
