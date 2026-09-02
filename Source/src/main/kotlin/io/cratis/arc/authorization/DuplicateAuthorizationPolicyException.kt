// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authorization

/** Raised when a policy name is registered more than once. */
public class DuplicateAuthorizationPolicyException(public val policyName: String) :
    IllegalArgumentException("An authorization policy named '$policyName' is already registered.")
