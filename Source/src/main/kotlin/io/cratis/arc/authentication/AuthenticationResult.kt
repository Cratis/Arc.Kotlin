// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authentication

import io.cratis.arc.authorization.ArcPrincipal

/** Result of a host-neutral authentication attempt. */
public class AuthenticationResult @JvmOverloads constructor(
    /** Authenticated principal, when authentication succeeded. */
    public val principal: ArcPrincipal? = null,
    /** Failure details, when credentials were rejected. */
    public val failure: AuthenticationFailure? = null
) {
    init {
        require(principal == null || failure == null) { "Authentication cannot both succeed and fail." }
    }

    /** Whether an authentication handler supplied a principal. */
    @get:JvmName("isAuthenticated")
    public val isAuthenticated: Boolean
        get() = principal != null

    public companion object {
        /** Result used when no authentication handler recognized the request. */
        @JvmField
        public val ANONYMOUS: AuthenticationResult = AuthenticationResult()

        /** Creates a failed result for one [reason]. */
        @JvmStatic
        public fun failed(reason: AuthenticationFailureReason): AuthenticationResult =
            AuthenticationResult(failure = AuthenticationFailure(reason))

        /** Creates a failed result retaining handler-order [reasons]. */
        @JvmStatic
        public fun failed(reasons: List<AuthenticationFailureReason>): AuthenticationResult =
            AuthenticationResult(failure = AuthenticationFailure(reasons))

        /** Creates a successful result. */
        @JvmStatic
        public fun succeeded(principal: ArcPrincipal): AuthenticationResult = AuthenticationResult(principal = principal)
    }
}
