// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.concepts.ConceptAs as ArcConceptAs
import io.cratis.chronicle.concepts.ConceptAs as ChronicleConceptAs
import java.util.UUID

internal fun Any?.toChronicleKey(): String? = when (this) {
    null -> null
    is String -> takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
    is UUID -> toString()
    is Number -> toString().takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
    is ArcConceptAs<*> -> value().toChronicleKey()
    is ChronicleConceptAs<*> -> value.toChronicleKey()
    else -> null
}
