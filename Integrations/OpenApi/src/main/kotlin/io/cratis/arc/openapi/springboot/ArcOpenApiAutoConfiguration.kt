// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.openapi.springboot

import com.fasterxml.jackson.databind.ObjectMapper
import io.cratis.arc.identity.AsyncIdentityDetailsProvider
import io.cratis.arc.identity.IdentityDetailsProvider
import io.cratis.arc.metadata.ApiEndpointOptions
import io.cratis.arc.springboot.ArcArtifactModules
import io.cratis.arc.springboot.ArcProperties
import io.cratis.arc.springboot.ArcWebAutoConfiguration
import io.swagger.v3.oas.models.OpenAPI
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.MediaType
import org.springframework.web.HttpRequestHandler
import org.springframework.web.servlet.DispatcherServlet
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping

/** Auto-configures a cached OpenAPI document for servlet applications hosting generated Arc modules. */
@AutoConfiguration(after = [ArcWebAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet::class, OpenAPI::class)
@ConditionalOnBean(ArcArtifactModules::class)
public class ArcOpenApiAutoConfiguration {
    /** Generates the document only when the application has not supplied an OpenAPI model or Arc document. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(value = [OpenAPI::class, ArcOpenApiDocument::class])
    public class GeneratedDocument {
        /** Generates and caches the model and JSON bytes during application startup. */
        @Bean
        public fun arcOpenApiDocument(
            modules: ArcArtifactModules,
            properties: ArcProperties,
            objectMapper: ObjectMapper,
            identityProviders: ObjectProvider<IdentityDetailsProvider<*>>,
            asyncIdentityProviders: ObjectProvider<AsyncIdentityDetailsProvider<*>>
        ): ArcOpenApiDocument {
            val endpointProperties = properties.endpoints
            val options = ApiEndpointOptions(
                endpointProperties.routePrefix,
                endpointProperties.segmentsToSkipForRoute,
                endpointProperties.isIncludeCommandNameInRoute,
                endpointProperties.isIncludeQueryNameInRoute,
                endpointProperties.isEnableQueryHttpMethod
            )
            val providers = identityProviders.orderedStream().map { provider -> provider as Any }.toList() +
                asyncIdentityProviders.orderedStream().map { provider -> provider as Any }.toList()
            val identityProviderPresent = providers.distinctBy(System::identityHashCode).isNotEmpty()
            return ArcOpenApiGenerator(objectMapper).generate(modules.modules, options, identityProviderPresent)
        }

        /** Exposes the generated swagger-models document for application integrations. */
        @Bean
        public fun arcOpenApi(document: ArcOpenApiDocument): OpenAPI = document.openApi
    }

    /** Publishes the cached bytes on both conventional Arc OpenAPI routes. */
    @Bean("arcOpenApiHandlerMapping")
    @ConditionalOnBean(ArcOpenApiDocument::class)
    @ConditionalOnMissingBean(name = ["arcOpenApiHandlerMapping"])
    public fun arcOpenApiHandlerMapping(document: ArcOpenApiDocument): SimpleUrlHandlerMapping {
        val handler = CachedOpenApiHttpRequestHandler(document.json())
        return SimpleUrlHandlerMapping(
            linkedMapOf(
                "/v3/api-docs" to handler,
                "/.cratis/openapi.json" to handler
            ),
            Ordered.LOWEST_PRECEDENCE - 10
        )
    }
}

private class CachedOpenApiHttpRequestHandler(bytes: ByteArray) : HttpRequestHandler {
    private val document = bytes.copyOf()

    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_OK
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.outputStream.write(document)
    }
}
