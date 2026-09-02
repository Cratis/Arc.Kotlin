// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authorization

import io.cratis.arc.metadata.AuthorizationMetadata

/** Evaluates Arc authorization metadata without depending on a host security framework. */
public class AuthorizationEvaluator(public val policies: AuthorizationPolicyRegistry) {
    /** Evaluates [metadata] for [principal] in Arc's deterministic order. */
    public suspend fun evaluate(metadata: AuthorizationMetadata, principal: ArcPrincipal): AuthorizationResult {
        if (metadata.allowAnonymous) return AuthorizationResult.success()
        if (!principal.isAuthenticated) return AuthorizationResult.failure("An authenticated caller is required.")

        metadata.policy?.takeIf { it.isNotBlank() }?.let { policyName ->
            val policy = policies.find(policyName)
                ?: return AuthorizationResult.failure("Authorization policy '$policyName' was not found.")
            val result = policy.evaluate(principal)
            if (!result.isAuthorized) {
                return if (result.failureReason.isNullOrBlank()) {
                    AuthorizationResult.failure("Authorization policy '$policyName' was not satisfied.")
                } else {
                    result
                }
            }
        }

        val roles = metadata.roles.flatMap { declaration -> declaration.split(',') }
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (roles.isNotEmpty() && roles.none(principal::isInRole)) {
            return AuthorizationResult.failure("The caller must belong to at least one required role.")
        }

        val schemes = metadata.schemes.flatMap { declaration -> declaration.split(',') }
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (schemes.isNotEmpty() && schemes.none { scheme ->
                scheme.equals(principal.authenticationScheme, ignoreCase = true)
            }) {
            return AuthorizationResult.failure("The caller must use one of the required authentication schemes.")
        }
        return AuthorizationResult.success()
    }
}
