// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.results

import io.cratis.arc.concepts.ArcEnum

/** Severity values used by Arc validation results. */
public enum class ValidationResultSeverity(private val wireValue: Int) : ArcEnum {
    /** No severity was supplied. */
    Unknown(0),

    /** Informational feedback. */
    Information(1),

    /** A warning that does not necessarily reject an operation. */
    Warning(2),

    /** An error that rejects an operation. */
    Error(3);

    override fun value(): Int = wireValue
}
