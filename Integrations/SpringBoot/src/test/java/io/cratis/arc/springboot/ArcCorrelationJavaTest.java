// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.correlation.CorrelationIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Java-consumer coverage for host-wide correlation on an ordinary Spring MVC route. */
@SpringBootTest(
    classes = ArcCorrelationJavaTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
final class ArcCorrelationJavaTest {
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String JAVA_ROUTE = "/plain/java-correlation";

    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @LocalServerPort
    private int port;

    @Test
    void javaControllerReadsTheCorrelationEstablishedForTheRequest() throws Exception {
        UUID supplied = UUID.randomUUID();

        HttpResponse<String> correlated = send(supplied.toString());
        assertEquals(supplied.toString(), correlated.headers().firstValue(CORRELATION_HEADER).orElseThrow());
        assertEquals(supplied.toString(), correlated.body());

        HttpResponse<String> generated = send(null);
        String echoed = generated.headers().firstValue(CORRELATION_HEADER).orElseThrow();
        assertEquals(UUID.fromString(echoed).toString(), generated.body());
        assertNotEquals(supplied.toString(), generated.body());
    }

    @Test
    void correlationIdResolverIsStraightforwardFromJava() {
        UUID identifier = UUID.randomUUID();

        assertEquals(identifier, CorrelationIdResolver.parse(identifier.toString()));
        assertNull(CorrelationIdResolver.parse("not-a-correlation-id"));
        assertEquals(identifier, CorrelationIdResolver.resolveOrCreate(identifier.toString()));
        assertNotEquals(
            CorrelationIdResolver.resolveOrCreate(null),
            CorrelationIdResolver.resolveOrCreate(null));
    }

    private HttpResponse<String> send(String correlationId) throws Exception {
        HttpRequest.Builder request = HttpRequest
            .newBuilder(URI.create("http://127.0.0.1:" + port + JAVA_ROUTE))
            .timeout(Duration.ofSeconds(5));
        if (correlationId != null) {
            request.header(CORRELATION_HEADER, correlationId);
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Application hosting a Java controller Arc does not know about. */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {ServletWebSecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    public static class Application {
        /** Registers the plain Java controller. */
        @Bean
        public JavaCorrelationController javaCorrelationController() {
            return new JavaCorrelationController();
        }
    }

    /** Ordinary Spring MVC controller written in Java. */
    @RestController
    public static class JavaCorrelationController {
        /** Returns the correlation identifier Arc established for the request. */
        @GetMapping(JAVA_ROUTE)
        public String correlation(HttpServletRequest request) {
            UUID correlationId = ArcCorrelation.of(request);
            return correlationId == null ? "" : correlationId.toString();
        }
    }
}
