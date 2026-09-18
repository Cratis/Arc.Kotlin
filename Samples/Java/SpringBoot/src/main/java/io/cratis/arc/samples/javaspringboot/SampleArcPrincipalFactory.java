// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.identity.IdentityClaim;
import io.cratis.arc.springboot.ArcPrincipalFactory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns the frontend's sign-in toggle into the principal Arc authorizes against.
 *
 * {@link ArcPrincipalFactory} is the seam where a request's identity is captured, once, before any
 * Arc work leaves the entry thread. That single point is why the same code covers an ordinary
 * {@code POST}, a Server-Sent Events stream and a WebSocket handshake — each of them enters here.
 *
 * It reads the cookie as well as the header, and the cookie is the half that matters:
 * {@code EventSource} and the WebSocket handshake cannot send a custom header, so a sample that only
 * read the header would authorize one-shot queries and silently drop to anonymous the moment a
 * subscription opened.
 *
 * A deployment would not trust an unsigned header from the network. Arc ships
 * {@code ArcPlatformIdentityAutoConfiguration} for the real Microsoft Identity Platform contract,
 * with a deny-by-default ingress trust policy; this sample runs on loopback and trades that for
 * being runnable with no identity provider at all.
 */
public final class SampleArcPrincipalFactory implements ArcPrincipalFactory {
    private static final String PRINCIPAL_HEADER = "x-ms-client-principal";
    private static final String IDENTITY_ID_HEADER = "x-ms-client-principal-id";
    private static final String IDENTITY_NAME_HEADER = "x-ms-client-principal-name";
    private static final String SUBJECT_CLAIM = "sub";
    private static final String SCHEME = "MicrosoftIdentityPlatform";
    private static final String SAMPLE_USER_ID = "arc-java-sample";
    private static final String SAMPLE_USER_NAME = "Arc Java Sample";
    private static final String SAMPLE_ROLE = "sample";
    private static final String CROSS_CUTTING_ROLE = "CrossCuttingAuthorization";

    private final ObjectMapper objectMapper;
    private final boolean defaultIdentity;

    /**
     * Initializes a new instance of the {@link SampleArcPrincipalFactory} class.
     *
     * @param objectMapper Reads the decoded client principal.
     * @param defaultIdentity Whether a request without a client principal is treated as the built-in
     *     sample user. {@code ./run.sh} turns this off so the signed-out path is reachable.
     */
    public SampleArcPrincipalFactory(ObjectMapper objectMapper, boolean defaultIdentity) {
        this.objectMapper = objectMapper;
        this.defaultIdentity = defaultIdentity;
    }

    @Override
    public ArcPrincipal create(HttpServletRequest request, Collection<String> requiredRoles) {
        var encoded = value(request.getHeader(PRINCIPAL_HEADER));
        if (encoded == null) {
            encoded = cookie(request);
        }
        if (encoded == null) {
            return defaultIdentity ? defaultPrincipal() : ArcPrincipal.anonymous();
        }

        JsonNode principal;
        try {
            principal = objectMapper.readTree(Base64.getDecoder().decode(encoded));
        } catch (RuntimeException ignored) {
            // A malformed principal is not a reason to fall back to a privileged default.
            return ArcPrincipal.anonymous();
        }

        var id = value(request.getHeader(IDENTITY_ID_HEADER));
        if (id == null) {
            id = value(principal.path("userId").asString());
        }
        if (id == null) {
            return ArcPrincipal.anonymous();
        }

        var name = value(request.getHeader(IDENTITY_NAME_HEADER));
        if (name == null) {
            name = value(principal.path("userDetails").asString());
        }

        var roles = new LinkedHashSet<String>();
        principal.path("userRoles").forEach(role -> {
            var resolved = value(role.asString());
            if (resolved != null) {
                roles.add(resolved);
            }
        });

        var claims = new ArrayList<IdentityClaim>();
        principal.path("claims").forEach(claim -> {
            var type = value(claim.path("typ").asString());
            if (type != null) {
                claims.add(new IdentityClaim(type, claim.path("val").asString()));
            }
        });
        claims.add(new IdentityClaim(SUBJECT_CLAIM, id));

        return new ArcPrincipal(name == null ? id : name, true, roles, id, claims, SCHEME);
    }

    private static ArcPrincipal defaultPrincipal() {
        return new ArcPrincipal(
            SAMPLE_USER_NAME,
            true,
            new LinkedHashSet<>(List.of(SAMPLE_ROLE, CROSS_CUTTING_ROLE)),
            SAMPLE_USER_ID,
            List.of(new IdentityClaim(SUBJECT_CLAIM, SAMPLE_USER_ID)),
            "Sample");
    }

    private static String cookie(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
            .filter(cookie -> PRINCIPAL_HEADER.equals(cookie.getName()))
            .map(cookie -> value(cookie.getValue()))
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    private static String value(String candidate) {
        return candidate == null || candidate.isBlank() ? null : candidate;
    }
}
