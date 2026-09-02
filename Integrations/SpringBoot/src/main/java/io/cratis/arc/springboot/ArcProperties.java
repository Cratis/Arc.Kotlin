// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.metadata.ApiEndpointOptions;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Configuration for the Arc Spring Boot host. */
@ConfigurationProperties("cratis.arc")
public final class ArcProperties {
    private EndpointProperties endpoints = new EndpointProperties();
    private String correlationHeader = "X-Correlation-ID";
    private String tenantHeader = "x-cratis-tenant-id";
    private ArcTenancyProperties tenancy = new ArcTenancyProperties();
    private Duration requestTimeout = Duration.ofSeconds(30);
    private int coroutineParallelism = 4;
    private int coroutineQueueCapacity = 256;
    private int overloadRetryAfterSeconds = 5;
    private long maximumRequestBodyBytes = 1024 * 1024;
    private Duration commandScopeCompletionTimeout = Duration.ofSeconds(5);
    private IdentityCookieSecurePolicy identityCookieSecurePolicy = IdentityCookieSecurePolicy.AUTO;
    private Boolean exposeExceptionDetails;
    private ObservableQueryProperties observableQueries = new ObservableQueryProperties();

    /** Gets HTTP endpoint route settings. */
    public EndpointProperties getEndpoints() {
        return endpoints;
    }

    /** Sets HTTP endpoint route settings. */
    public void setEndpoints(EndpointProperties value) {
        endpoints = value;
    }

    /** Gets the request and response header carrying Arc correlation identifiers. */
    public String getCorrelationHeader() {
        return correlationHeader;
    }

    /** Sets the request and response header carrying Arc correlation identifiers. */
    public void setCorrelationHeader(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("correlationHeader must not be blank.");
        correlationHeader = value;
    }

    /** Gets the header from which the tenant identifier is read. */
    public String getTenantHeader() {
        return tenantHeader;
    }

    /** Sets the header from which the tenant identifier is read. */
    public void setTenantHeader(String value) {
        tenantHeader = value;
    }

    /** Gets tenant resolution, precedence, and access settings. */
    public ArcTenancyProperties getTenancy() {
        return tenancy;
    }

    /** Sets tenant resolution, precedence, and access settings. */
    public void setTenancy(ArcTenancyProperties value) {
        if (value == null) throw new IllegalArgumentException("tenancy cannot be null.");
        tenancy = value;
    }

    /** Gets the maximum time allowed for an asynchronously dispatched Arc request. */
    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    /** Sets the maximum time allowed for an asynchronously dispatched Arc request. */
    public void setRequestTimeout(Duration value) {
        requestTimeout = value;
    }

    /** Gets the maximum number of Arc request coroutines that may execute concurrently. */
    public int getCoroutineParallelism() {
        return coroutineParallelism;
    }

    /** Sets the maximum number of Arc request coroutines that may execute concurrently. */
    public void setCoroutineParallelism(int value) {
        if (value <= 0) throw new IllegalArgumentException("coroutineParallelism must be greater than zero.");
        coroutineParallelism = value;
    }

    /** Gets the bounded number of Arc request coroutines that may wait for an execution thread. */
    public int getCoroutineQueueCapacity() {
        return coroutineQueueCapacity;
    }

    /** Sets the bounded number of Arc request coroutines that may wait; zero permits no waiting. */
    public void setCoroutineQueueCapacity(int value) {
        if (value < 0) throw new IllegalArgumentException("coroutineQueueCapacity cannot be negative.");
        coroutineQueueCapacity = value;
    }

    /** Gets the Retry-After value returned when Arc request admission is exhausted. */
    public int getOverloadRetryAfterSeconds() {
        return overloadRetryAfterSeconds;
    }

    /** Sets the Retry-After value returned when Arc request admission is exhausted. */
    public void setOverloadRetryAfterSeconds(int value) {
        if (value <= 0) throw new IllegalArgumentException("overloadRetryAfterSeconds must be greater than zero.");
        overloadRetryAfterSeconds = value;
    }

    /** Gets the maximum accepted command or QUERY body size in bytes. */
    public long getMaximumRequestBodyBytes() {
        return maximumRequestBodyBytes;
    }

    /** Sets the maximum accepted command or QUERY body size in bytes. */
    public void setMaximumRequestBodyBytes(long value) {
        if (value <= 0) throw new IllegalArgumentException("maximumRequestBodyBytes must be greater than zero.");
        maximumRequestBodyBytes = value;
    }

    /** Gets the cooperative timeout applied independently to each command execution-scope completion. */
    public Duration getCommandScopeCompletionTimeout() {
        return commandScopeCompletionTimeout;
    }

    /** Sets the cooperative timeout applied independently to each command execution-scope completion. */
    public void setCommandScopeCompletionTimeout(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("commandScopeCompletionTimeout must be positive.");
        }
        commandScopeCompletionTimeout = value;
    }

    /** Gets the Secure-attribute policy for Arc's client-readable identity cookie. */
    public IdentityCookieSecurePolicy getIdentityCookieSecurePolicy() {
        return identityCookieSecurePolicy;
    }

    /** Sets the Secure-attribute policy for Arc's client-readable identity cookie. */
    public void setIdentityCookieSecurePolicy(IdentityCookieSecurePolicy value) {
        if (value == null) throw new IllegalArgumentException("identityCookieSecurePolicy cannot be null.");
        identityCookieSecurePolicy = value;
    }

    /** Gets whether full exception details may be returned, or null to derive the safe profile default. */
    public Boolean getExposeExceptionDetails() {
        return exposeExceptionDetails;
    }

    /** Sets whether full exception details may be returned. */
    public void setExposeExceptionDetails(Boolean value) {
        exposeExceptionDetails = value;
    }

    boolean shouldExposeExceptionDetails(Environment environment) {
        return exposeExceptionDetails != null
            ? exposeExceptionDetails
            : isDevelopment(environment);
    }

    boolean shouldSecureIdentityCookie(Environment environment, boolean requestIsSecure) {
        return switch (identityCookieSecurePolicy) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> requestIsSecure || !isDevelopment(environment);
        };
    }

    private static boolean isDevelopment(Environment environment) {
        return environment.acceptsProfiles(Profiles.of("dev", "development", "local"));
    }

    /** Gets observable-query transport settings. */
    public ObservableQueryProperties getObservableQueries() {
        return observableQueries;
    }

    /** Sets observable-query transport settings. */
    public void setObservableQueries(ObservableQueryProperties value) {
        observableQueries = value;
    }

    /** Observable-query snapshot, streaming, and hub limits. */
    public static final class ObservableQueryProperties {
        private Duration waitForFirstResultTimeout = Duration.ofSeconds(30);
        private Duration keepAliveInterval = Duration.ofSeconds(30);
        private Duration connectionTimeout = Duration.ofMinutes(30);
        private int maximumConnections = 1000;
        private int maximumSubscriptionsPerConnection = 100;
        private int outboundBufferCapacity = 64;
        private int maximumInboundMessageSize = 65536;
        private int overloadRetryAfterSeconds = 5;
        private boolean webSocketEnabled = true;

        /** Gets the default maximum wait for an observable HTTP snapshot. */
        public Duration getWaitForFirstResultTimeout() {
            return waitForFirstResultTimeout;
        }

        /** Sets the default maximum wait for an observable HTTP snapshot. */
        public void setWaitForFirstResultTimeout(Duration value) {
            if (value.isNegative() || value.isZero()) throw new IllegalArgumentException("waitForFirstResultTimeout must be positive.");
            waitForFirstResultTimeout = value;
        }

        /** Gets the interval between idle hub heartbeat messages; zero disables heartbeats. */
        public Duration getKeepAliveInterval() {
            return keepAliveInterval;
        }

        /** Sets the interval between idle hub heartbeat messages; zero disables heartbeats. */
        public void setKeepAliveInterval(Duration value) {
            if (value.isNegative()) throw new IllegalArgumentException("keepAliveInterval cannot be negative.");
            keepAliveInterval = value;
        }

        /** Gets the maximum lifetime of an SSE connection; zero disables the transport timeout. */
        public Duration getConnectionTimeout() {
            return connectionTimeout;
        }

        /** Sets the maximum lifetime of an SSE connection; zero disables the transport timeout. */
        public void setConnectionTimeout(Duration value) {
            if (value.isNegative()) throw new IllegalArgumentException("connectionTimeout cannot be negative.");
            connectionTimeout = value;
        }

        /** Gets the maximum number of concurrent observable transport connections. */
        public int getMaximumConnections() {
            return maximumConnections;
        }

        /** Sets the maximum number of concurrent observable transport connections. */
        public void setMaximumConnections(int value) {
            if (value <= 0) throw new IllegalArgumentException("maximumConnections must be greater than zero.");
            maximumConnections = value;
        }

        /** Gets the maximum subscriptions retained by one multiplexed connection. */
        public int getMaximumSubscriptionsPerConnection() {
            return maximumSubscriptionsPerConnection;
        }

        /** Sets the maximum subscriptions retained by one multiplexed connection. */
        public void setMaximumSubscriptionsPerConnection(int value) {
            if (value <= 0) throw new IllegalArgumentException("maximumSubscriptionsPerConnection must be greater than zero.");
            maximumSubscriptionsPerConnection = value;
        }

        /** Gets the bounded number of pending outbound frames per streaming connection. */
        public int getOutboundBufferCapacity() {
            return outboundBufferCapacity;
        }

        /** Sets the bounded number of pending outbound frames per streaming connection. */
        public void setOutboundBufferCapacity(int value) {
            if (value <= 0) throw new IllegalArgumentException("outboundBufferCapacity must be greater than zero.");
            outboundBufferCapacity = value;
        }

        /** Gets the maximum accepted WebSocket text message size in bytes. */
        public int getMaximumInboundMessageSize() {
            return maximumInboundMessageSize;
        }

        /** Sets the maximum accepted WebSocket text message size in bytes. */
        public void setMaximumInboundMessageSize(int value) {
            if (value <= 0) throw new IllegalArgumentException("maximumInboundMessageSize must be greater than zero.");
            maximumInboundMessageSize = value;
        }

        /** Gets the Retry-After value returned when observable transport capacity is exhausted. */
        public int getOverloadRetryAfterSeconds() {
            return overloadRetryAfterSeconds;
        }

        /** Sets the Retry-After value returned when observable transport capacity is exhausted. */
        public void setOverloadRetryAfterSeconds(int value) {
            if (value <= 0) throw new IllegalArgumentException("overloadRetryAfterSeconds must be greater than zero.");
            overloadRetryAfterSeconds = value;
        }

        /** Gets whether Spring WebSocket routes are registered when Spring WebSocket is available. */
        public boolean isWebSocketEnabled() {
            return webSocketEnabled;
        }

        /** Sets whether Spring WebSocket routes are registered when Spring WebSocket is available. */
        public void setWebSocketEnabled(boolean value) {
            webSocketEnabled = value;
        }
    }

    /** HTTP endpoint route settings corresponding to Arc's host-neutral endpoint options. */
    public static final class EndpointProperties {
        private String routePrefix = "api";
        private int segmentsToSkipForRoute;
        private boolean includeCommandNameInRoute = true;
        private boolean includeQueryNameInRoute = true;
        private boolean enableQueryHttpMethod = true;

        /** Gets the prefix applied to conventional routes. */
        public String getRoutePrefix() {
            return routePrefix;
        }

        /** Sets the prefix applied to conventional routes. */
        public void setRoutePrefix(String value) {
            routePrefix = value;
        }

        /** Gets the number of leading package segments omitted from conventional routes. */
        public int getSegmentsToSkipForRoute() {
            return segmentsToSkipForRoute;
        }

        /** Sets the number of leading package segments omitted from conventional routes. */
        public void setSegmentsToSkipForRoute(int value) {
            if (value < 0) throw new IllegalArgumentException("segmentsToSkipForRoute cannot be negative.");
            segmentsToSkipForRoute = value;
        }

        /** Gets whether conventional command routes include the command name. */
        public boolean isIncludeCommandNameInRoute() {
            return includeCommandNameInRoute;
        }

        /** Sets whether conventional command routes include the command name. */
        public void setIncludeCommandNameInRoute(boolean value) {
            includeCommandNameInRoute = value;
        }

        /** Gets whether conventional query routes include the query name. */
        public boolean isIncludeQueryNameInRoute() {
            return includeQueryNameInRoute;
        }

        /** Sets whether conventional query routes include the query name. */
        public void setIncludeQueryNameInRoute(boolean value) {
            includeQueryNameInRoute = value;
        }

        /** Gets whether query endpoints also accept the RFC QUERY HTTP method. */
        public boolean isEnableQueryHttpMethod() {
            return enableQueryHttpMethod;
        }

        /** Sets whether query endpoints also accept the RFC QUERY HTTP method. */
        public void setEnableQueryHttpMethod(boolean value) {
            enableQueryHttpMethod = value;
        }

        ApiEndpointOptions toOptions() {
            return new ApiEndpointOptions(
                routePrefix,
                segmentsToSkipForRoute,
                includeCommandNameInRoute,
                includeQueryNameInRoute,
                enableQueryHttpMethod);
        }
    }
}
