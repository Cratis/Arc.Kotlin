// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.results.ValidationResult

/**
 * Command-only validation feedback supplied by an application exception.
 *
 * Supply a nonempty list of nonnull results. Command exception conversion snapshots the list,
 * preserving its feedback without exposing the exception message or stack trace. All payload fields,
 * including state and reason detail, are client-visible application data and must not contain secrets.
 * Malformed payloads and ordinary exceptions during extraction retain the original exception result.
 * Cancellation and fatal errors propagate. Queries do not use this contract.
 */
public interface ValidationFailure {
    /** Validation feedback to snapshot when converting this exception to a command result. */
    public val validationResults: List<ValidationResult>
}
