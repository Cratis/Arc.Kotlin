// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.web.socket.server.support.OriginHandshakeInterceptor
import org.springframework.web.socket.server.support.WebSocketHandlerMapping

/**
 * A cross-origin WebSocket handshake is Spring's to refuse, and it refuses by default.
 *
 * That default is right for a deployment and wrong for development, where the page comes from a dev
 * server on another port. The symptom is unhelpful — a browser reports a `403` on an upgrade as a
 * socket that never opens, with no status anywhere the page can see — so the registration has to
 * declare its origins rather than leave the reader guessing.
 */
internal class ArcObservableQueryWebSocketOriginTests {
    private val runner = WebApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(
            JacksonAutoConfiguration::class.java,
            ArcAutoConfiguration::class.java,
            ArcWebAutoConfiguration::class.java,
            ArcObservableQueryWebSocketConfiguration::class.java
        )
    )

    @Test
    fun `no configured origins leaves Spring's same-origin default in place`() {
        val registered = origins()

        assertTrue(registered.isNotEmpty(), "Expected at least the multiplexed hub route to be registered.")
        registered.forEach { (route, allowed) ->
            assertTrue(allowed.isEmpty(), "Route $route declared origins without being asked: $allowed")
        }
    }

    @Test
    fun `a configured origin reaches every registered handler`() {
        val registered = origins("http://localhost:5173")

        assertTrue(registered.isNotEmpty(), "Expected at least the multiplexed hub route to be registered.")
        registered.forEach { (route, allowed) ->
            assertEquals(listOf("http://localhost:5173"), allowed, "Route $route did not receive the origin.")
        }
    }

    @Test
    fun `several origins are all carried through`() {
        origins("http://localhost:5173", "https://app.example.com").forEach { (route, allowed) ->
            assertEquals(
                listOf("http://localhost:5173", "https://app.example.com"),
                allowed,
                "Route $route did not receive both origins."
            )
        }
    }

    private fun origins(vararg allowedOrigins: String): Map<String, List<String>> {
        var result = emptyMap<String, List<String>>()
        runner
            .withPropertyValues(
                *allowedOrigins.mapIndexed { index, origin ->
                    "cratis.arc.observable-queries.allowed-origins[$index]=$origin"
                }.toTypedArray()
            )
            .run { context ->
                // Reading the handler mapping Spring built is what proves the origins reached the
                // registration, rather than only reaching the properties object.
                val mapping = context.getBean(WebSocketHandlerMapping::class.java)
                result = mapping.urlMap.keys.associate { route ->
                    route to allowedOriginsFor(mapping, route)
                }
            }
        return result
    }

    // Reads what Spring actually installed. It throws rather than returning an empty list when it
    // cannot find the interceptors, so a reflection path that stops matching fails the tests instead
    // of quietly making all three of them pass against nothing.
    private fun allowedOriginsFor(mapping: WebSocketHandlerMapping, route: Any): List<String> {
        val handler = requireNotNull(mapping.urlMap[route]) { "No handler registered for $route." }
        val accessor = requireNotNull(
            handler.javaClass.methods.firstOrNull { it.name == "getHandshakeInterceptors" && it.parameterCount == 0 }
        ) { "${handler.javaClass.name} exposes no handshake interceptors to inspect." }
        val interceptors = when (val value = accessor.apply { isAccessible = true }.invoke(handler)) {
            is Collection<*> -> value.toList()
            is Array<*> -> value.toList()
            else -> error("Unexpected handshake interceptor container: ${value?.javaClass?.name}")
        }
        val origin = interceptors.filterIsInstance<OriginHandshakeInterceptor>().firstOrNull() ?: return emptyList()
        return origin.allowedOrigins.toList()
    }
}
