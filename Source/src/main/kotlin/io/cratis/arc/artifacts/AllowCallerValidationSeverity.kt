// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.artifacts

import io.cratis.arc.results.ValidationResultSeverity

/**
 * Lets callers make validation results up to [maximum] nonblocking through the HTTP X-Allowed-Severity header only.
 *
 * Replaces the host maximum, allowing either a higher or lower cap. Superclasses are consulted, but interfaces are not.
 * The host's ignore-caller-allowed-severity setting overrides this annotation. Only relax Error-level rules when they
 * are advisory; never use caller-controlled severity for authorization or invariants.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
public annotation class AllowCallerValidationSeverity(val maximum: ValidationResultSeverity)
