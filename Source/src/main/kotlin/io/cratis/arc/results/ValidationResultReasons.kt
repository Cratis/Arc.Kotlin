// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.results

/** Open string reason values emitted by Arc validation. Consumers must tolerate values added in the future. */
public object ValidationResultReasons {
    public const val RULE: String = "rule"
    public const val CONCURRENCY_VIOLATION: String = "concurrencyViolation"
    public const val CONSTRAINT_VIOLATION: String = "constraintViolation"
    public const val VALIDATOR_FAILED: String = "validatorFailed"
    public const val DEPENDENCY_UNAVAILABLE: String = "dependencyUnavailable"
    public const val MALFORMED_REQUEST: String = "malformedRequest"
}
