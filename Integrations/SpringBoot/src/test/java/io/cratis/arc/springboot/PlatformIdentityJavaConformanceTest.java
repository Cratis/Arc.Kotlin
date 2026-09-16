// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationConverter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlatformIdentityJavaConformanceTest {
    @Test
    void beanPropertiesAndConverterAreOrdinaryJavaContracts() {
        var properties = new ArcPlatformIdentityProperties();
        assertFalse(properties.isEnabled());
        properties.setEnabled(true);
        assertTrue(properties.isEnabled());
        ArcPlatformIdentityTrust trust = request -> "192.0.2.10".equals(request.getRemoteAddr());
        AuthenticationConverter converter = new ArcPlatformAuthenticationConverter(trust);
        assertNull(converter.convert(new MockHttpServletRequest()));
        Authentication authentication = converter.convert(request());
        assertEquals("Ada", authentication.getName());
        assertTrue(authentication.isAuthenticated());
        assertEquals(List.of("ROLE_admin"), authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList());
        assertTrue(authentication.getPrincipal() instanceof Map<?, ?>);
        Map<?, ?> claims = (Map<?, ?>) authentication.getPrincipal();
        assertEquals(List.of("canonical-id"), claims.get("sub"));
        assertThrows(UnsupportedOperationException.class, claims::clear);
        assertThrows(UnsupportedOperationException.class, ((List<?>) claims.get("sub"))::clear);
        assertThrows(UnsupportedOperationException.class, authentication.getAuthorities()::clear);
    }

    @Test
    void filterInstallsAndRestoresContextWithoutSavingSession() throws Exception {
        var converter = new ArcPlatformAuthenticationConverter(request -> true);
        var filter = new ArcPlatformAuthenticationFilter(converter);
        var request = request();
        try {
            filter.doFilter(request, new MockHttpServletResponse(), (incoming, response) -> {
                assertEquals("Ada", SecurityContextHolder.getContext().getAuthentication().getName());
            });
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            assertNull(request.getSession(false));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("x-ms-client-principal", Base64.getEncoder().encodeToString(
            "{\"userDetails\":\"Ada\",\"userRoles\":[\"admin\"]}".getBytes(StandardCharsets.UTF_8)));
        request.addHeader("x-ms-client-principal-id", "canonical-id");
        request.addHeader("x-ms-client-principal-name", "header-name");
        return request;
    }
}
