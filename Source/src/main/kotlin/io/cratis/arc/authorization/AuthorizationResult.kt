// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authorization

/** Immutable result of evaluating authorization. */
public class AuthorizationResult @JvmOverloads constructor(
    /** Whether authorization succeeded. */
    @get:JvmName("isAuthorized")
    public val isAuthorized: Boolean,
    /** Optional reason for a failed authorization decision. */
    public val failureReason: String? = null
) {
    public companion object {
        /** Creates a successful authorization result. */
        @JvmStatic
        public fun success(): AuthorizationResult = AuthorizationResult(true)

        /** Creates a failed authorization result. */
        @JvmStatic
        public fun failure(reason: String): AuthorizationResult = AuthorizationResult(false, reason)
    }
}
