// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.identity.IdentityClaim
import io.cratis.arc.springboot.ArcPrincipalFactory
import jakarta.servlet.http.HttpServletRequest
import java.util.Base64
import tools.jackson.databind.ObjectMapper

/**
 * Turns the frontend's sign-in toggle into the principal Arc authorizes against.
 *
 * `ArcPrincipalFactory` is the seam where a request's identity is captured, once, before any Arc
 * work leaves the entry thread. That single point is why the same code covers an ordinary `POST`, a
 * Server-Sent Events stream and a WebSocket handshake — each of them enters here.
 *
 * It reads the cookie as well as the header, and the cookie is the half that matters: `EventSource`
 * and the WebSocket handshake cannot send a custom header, so a sample that only read the header
 * would authorize one-shot queries and silently drop to anonymous the moment a subscription opened.
 *
 * A deployment would not trust an unsigned header from the network. Arc ships
 * `ArcPlatformIdentityAutoConfiguration` for the real Microsoft Identity Platform contract, with a
 * deny-by-default ingress trust policy; this sample runs on loopback and trades that for being
 * runnable with no identity provider at all.
 *
 * @property objectMapper Reads the decoded client principal.
 * @property defaultIdentity Whether a request without a client principal is treated as the built-in
 * sample user. `./run.sh` turns this off so the signed-out path is reachable.
 */
public class SampleArcPrincipalFactory(
    private val objectMapper: ObjectMapper,
    private val defaultIdentity: Boolean
) : ArcPrincipalFactory {
    override fun create(request: HttpServletRequest, requiredRoles: Collection<String>): ArcPrincipal {
        val encoded = request.getHeader(PRINCIPAL_HEADER)?.takeIf(String::isNotBlank)
            ?: request.cookies.orEmpty().firstOrNull { it.name == PRINCIPAL_HEADER }?.value?.takeIf(String::isNotBlank)

        if (encoded == null) {
            return if (defaultIdentity) defaultPrincipal() else ArcPrincipal.anonymous()
        }

        val principal = try {
            objectMapper.readTree(Base64.getDecoder().decode(encoded))
        } catch (_: Exception) {
            // A malformed principal is not a reason to fall back to a privileged default.
            return ArcPrincipal.anonymous()
        }

        val id = request.getHeader(IDENTITY_ID_HEADER)?.takeIf(String::isNotBlank)
            ?: principal.path("userId").asString().takeIf(String::isNotBlank)
            ?: return ArcPrincipal.anonymous()
        val name = request.getHeader(IDENTITY_NAME_HEADER)?.takeIf(String::isNotBlank)
            ?: principal.path("userDetails").asString().takeIf(String::isNotBlank)
            ?: id
        val roles = principal.path("userRoles")
            .mapNotNull { role -> role.asString().trim().takeIf(String::isNotEmpty) }
            .toCollection(linkedSetOf())
        val claims = principal.path("claims").mapNotNull { claim ->
            val type = claim.path("typ").asString().takeIf(String::isNotBlank) ?: return@mapNotNull null
            IdentityClaim(type, claim.path("val").asString())
        } + IdentityClaim(SUBJECT_CLAIM, id)

        return ArcPrincipal(name, true, roles, id, claims, SCHEME)
    }

    private fun defaultPrincipal(): ArcPrincipal = ArcPrincipal(
        SAMPLE_USER_NAME,
        true,
        setOf(SAMPLE_ROLE, CROSS_CUTTING_ROLE),
        SAMPLE_USER_ID,
        listOf(IdentityClaim(SUBJECT_CLAIM, SAMPLE_USER_ID)),
        "Sample"
    )

    private companion object {
        const val PRINCIPAL_HEADER = "x-ms-client-principal"
        const val IDENTITY_ID_HEADER = "x-ms-client-principal-id"
        const val IDENTITY_NAME_HEADER = "x-ms-client-principal-name"
        const val SUBJECT_CLAIM = "sub"
        const val SCHEME = "MicrosoftIdentityPlatform"
        const val SAMPLE_USER_ID = "arc-kotlin-runtime-gate"
        const val SAMPLE_USER_NAME = "Arc Kotlin Runtime Gate"
        const val SAMPLE_ROLE = "sample"
        const val CROSS_CUTTING_ROLE = "CrossCuttingAuthorization"
    }
}
