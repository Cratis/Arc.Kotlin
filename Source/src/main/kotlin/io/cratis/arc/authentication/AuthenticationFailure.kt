// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authentication

/** One or more reasons explaining why configured authentication handlers rejected a request. */
public class AuthenticationFailure(reasons: List<AuthenticationFailureReason>) {
    init {
        require(reasons.isNotEmpty()) { "Authentication failure must contain at least one reason." }
    }

    /** Failure reasons in authentication-handler order. */
    public val reasons: List<AuthenticationFailureReason> = java.util.List.copyOf(reasons)

    /** Compatibility view of the first failure reason. */
    public val reason: AuthenticationFailureReason
        get() = reasons.first()

    /** Creates a failure containing one [reason]. */
    public constructor(reason: AuthenticationFailureReason) : this(listOf(reason))
}
