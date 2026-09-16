// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.artifacts.ArcArtifactModule;
import io.cratis.arc.identity.AsyncIdentityDetailsProvider;
import io.cratis.arc.identity.IdentityDetails;
import io.cratis.arc.identity.IdentityProviderContext;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = PlatformApplicationChainJavaConformanceTest.Application.class,
    properties = "cratis.arc.platform-identity.enabled=true")
@AutoConfigureMockMvc
final class PlatformApplicationChainJavaConformanceTest {
    @Autowired private MockMvc mvc;
    @Autowired private ApplicationContext context;

    @Test
    void explicitJavaChainParticipatesAndOwnsAnonymousRoutePolicy() throws Exception {
        assertFalse(context.containsBean("arcPlatformSecurityFilterChain"));
        assertEquals(1, context.getBeansOfType(SecurityFilterChain.class).size());
        mvc.perform(get("/.cratis/commands")).andExpect(status().isOk());
        mvc.perform(get("/.cratis/me")).andExpect(status().isUnauthorized());
        var initial = mvc.perform(get("/.cratis/me")
            .with(request -> { request.setRemoteAddr("192.0.2.10"); return request; })
            .header("x-ms-client-principal", Base64.getEncoder().encodeToString(
                "{\"userDetails\":\"Java consumer\",\"userRoles\":[\"admin\"]}".getBytes(StandardCharsets.UTF_8)))
            .header("x-ms-client-principal-id", "java-id")
            .header("x-ms-client-principal-name", "ingress-name")).andReturn();
        mvc.perform(asyncDispatch(initial)).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("java-id"))
            .andExpect(jsonPath("$.name").value("Java consumer"))
            .andExpect(jsonPath("$.roles[0]").value("admin"));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
    static class Application {
        @Bean
        ArcPlatformIdentityTrust platformTrust() {
            return request -> "192.0.2.10".equals(request.getRemoteAddr());
        }

        @Bean
        SecurityFilterChain applicationChain(HttpSecurity http, ArcPlatformAuthenticationFilter filter) throws Exception {
            http.addFilterBefore(filter, AnonymousAuthenticationFilter.class);
            http.authorizeHttpRequests(rules -> rules.requestMatchers("/.cratis/commands").permitAll()
                .anyRequest().authenticated());
            http.exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> response.setStatus(401)));
            return http.build();
        }

        @Bean
        ArcArtifactModule emptyArtifacts() {
            return new ArcArtifactModule(List.of(), List.of()) {};
        }

        @Bean
        AsyncIdentityDetailsProvider<String> identityDetails() {
            return new AsyncIdentityDetailsProvider<>() {
                @Override
                public Class<String> getDetailsType() { return String.class; }

                @Override
                public CompletionStage<IdentityDetails<String>> provide(IdentityProviderContext context) {
                    return CompletableFuture.completedFuture(new IdentityDetails<>(true, context.getName()));
                }
            };
        }
    }
}
