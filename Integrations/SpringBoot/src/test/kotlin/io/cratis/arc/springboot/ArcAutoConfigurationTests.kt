// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authentication.Authentication
import io.cratis.arc.authentication.AuthenticationRequestContext
import io.cratis.arc.authentication.AuthenticationResult
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.identity.IdentityDetails
import io.cratis.arc.identity.IdentityDetailsProvider
import io.cratis.arc.identity.IdentityProviderContext
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.commands.CommandPipeline
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.RouteOptions
import io.cratis.arc.queries.AsyncQueryPipeline
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.DefaultQueryPipeline
import io.cratis.arc.queries.DefaultQueryValidationFilter
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryAuthorizationFilter
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryFilter
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryPerformerRegistry
import io.cratis.arc.queries.QueryPipeline
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.QueryResult
import io.cratis.arc.identity.IdentityClaim
import io.cratis.arc.tenancy.TenantId
import io.cratis.arc.tenancy.TenantIdResolver
import io.cratis.arc.tenancy.TenantResolutionContext
import jakarta.validation.Validation
import jakarta.validation.Validator
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping

internal class ArcAutoConfigurationTests {
    @Test
    fun `Spring Jackson mapper uses the Arc duration wire format`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(JacksonAutoConfiguration::class.java, ArcAutoConfiguration::class.java)
            )
            .run { context ->
                val mapper = context.getBean(ObjectMapper::class.java)
                assertThat(mapper.serializationConfig.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS))
                    .isFalse()
                assertThat(mapper.serializationConfig.isEnabled(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS))
                    .isFalse()
                assertEquals("\"PT1.5S\"", mapper.writeValueAsString(Duration.ofMillis(1_500)))
                assertEquals(
                    Duration.ofMillis(-1_500),
                    mapper.readValue("\"PT-1.5S\"", Duration::class.java)
                )
            }
    }

    @Test
    fun `authentication service backs off for an application supplied implementation`() {
        val authentication = object : Authentication {
            override val hasHandlers = true
            override suspend fun handleAuthentication(context: AuthenticationRequestContext): AuthenticationResult =
                AuthenticationResult.ANONYMOUS
        }
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java))
            .withBean(Authentication::class.java, { authentication })
            .run { context -> assertSame(authentication, context.getBean(Authentication::class.java)) }
    }

    @Test
    fun `runtime beans back off for application supplied contracts`() {
        val registry = ConcurrentCommandHandlerRegistry()
        val resolver = object : ServiceResolver {
            override fun <T : Any> resolve(type: Class<T>): T? = null
        }
        val pipeline = object : CommandPipeline {
            override suspend fun execute(command: Any, options: CommandExecutionOptions): CommandResult<*> =
                CommandResult.success(options.correlationId)

            override suspend fun validate(command: Any, options: CommandExecutionOptions): CommandResult<*> =
                CommandResult.success(options.correlationId)
        }

        val queryRegistry = ConcurrentQueryPerformerRegistry()
        val queryPipeline = object : QueryPipeline {
            override suspend fun perform(request: QueryRequest, options: QueryExecutionOptions): QueryResult<*> =
                QueryResult.success<Any?>(options.correlationId)
        }

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java))
            .withBean(CommandHandlerRegistry::class.java, { registry })
            .withBean(ServiceResolver::class.java, { resolver })
            .withBean(CommandPipeline::class.java, { pipeline })
            .withBean(QueryPerformerRegistry::class.java, { queryRegistry })
            .withBean(QueryPipeline::class.java, { queryPipeline })
            .run { context ->
                assertSame(registry, context.getBean(CommandHandlerRegistry::class.java))
                assertSame(resolver, context.getBean(ServiceResolver::class.java))
                assertSame(pipeline, context.getBean(CommandPipeline::class.java))
                assertSame(queryRegistry, context.getBean(QueryPerformerRegistry::class.java))
                assertSame(queryPipeline, context.getBean(QueryPipeline::class.java))
            }
    }

    @Test
    fun `default query runtime includes authorization validation real pipeline and async facade`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasSingleBean(QueryAuthorizationFilter::class.java)
                assertThat(context).hasSingleBean(DefaultQueryValidationFilter::class.java)
                assertThat(context).hasSingleBean(QueryPipeline::class.java)
                assertThat(context.getBean(QueryPipeline::class.java)).isInstanceOf(DefaultQueryPipeline::class.java)
                assertThat(context).hasSingleBean(AsyncQueryPipeline::class.java)
            }
    }

    @Test
    fun `Jakarta query validation filter is automatic and backs off by bean name`() {
        val validator = Validation.buildDefaultValidatorFactory().validator
        val supplied = QueryFilter { context -> QueryResult.success<Any?>(context.correlationId) }
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(ArcAutoConfiguration::class.java, ArcValidationAutoConfiguration::class.java)
            )
            .withBean(Validator::class.java, { validator })
            .withBean("arcJakartaBeanValidationQueryFilter", QueryFilter::class.java, { supplied })
            .run { context ->
                assertSame(supplied, context.getBean("arcJakartaBeanValidationQueryFilter"))
                assertThat(context).hasBean("arcJakartaBeanValidationCommandFilter")
            }
    }

    @Test
    fun `configured tenant resolvers preserve declared strategy behavior`() {
        val cases = listOf(
            ResolverCase("fixed", listOf("cratis.arc.tenancy.fixed-tenant-id=fixed-one"), TenantResolutionContext(), "fixed-one"),
            ResolverCase("header", emptyList(), TenantResolutionContext(headers = mapOf("X-Cratis-Tenant-Id" to "header-one")), "header-one"),
            ResolverCase("query", emptyList(), TenantResolutionContext(query = mapOf("tenantId" to "query-one")), "query-one"),
            ResolverCase("claim", emptyList(), TenantResolutionContext(claims = listOf(IdentityClaim("tenant_id", "claim-one"))), "claim-one"),
            ResolverCase("subdomain", listOf("cratis.arc.tenancy.base-domain=myapp.com"), TenantResolutionContext(host = "subdomain-one.myapp.com"), "subdomain-one"),
            ResolverCase("development", listOf("cratis.arc.tenancy.fixed-tenant-id=dev-one"), TenantResolutionContext(), "dev-one")
        )
        cases.forEach { case ->
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java))
                .withPropertyValues("cratis.arc.tenancy.resolvers=${case.strategy}", *case.properties.toTypedArray())
                .run { context ->
                    assertEquals(case.expected, context.getBean(TenantIdResolver::class.java).resolve(case.context)?.value())
                }
        }
    }

    @Test
    fun `application tenant resolver and access evaluator override defaults`() {
        val resolver = TenantIdResolver { TenantId.of("application") }
        val evaluator = TenantAccessEvaluator { _, _ -> false }
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java))
            .withBean(TenantIdResolver::class.java, { resolver })
            .withBean(TenantAccessEvaluator::class.java, { evaluator })
            .run { context ->
                assertSame(resolver, context.getBean(TenantIdResolver::class.java))
                assertSame(evaluator, context.getBean(TenantAccessEvaluator::class.java))
            }
    }

    @Test
    fun `invalid tenant configuration fails startup`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java))
            .withPropertyValues("cratis.arc.tenancy.resolvers=subdomain", "cratis.arc.tenancy.base-domain=localhost")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("cannot be used as the base domain")
            }
    }

    @Test
    fun `multiple identity details providers fail startup clearly`() {
        WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    JacksonAutoConfiguration::class.java,
                    ArcAutoConfiguration::class.java,
                    ArcValidationAutoConfiguration::class.java,
                    ArcWebAutoConfiguration::class.java
                )
            )
            .withUserConfiguration(MultipleIdentityProviders::class.java)
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasStackTraceContaining("Exactly one Arc identity details provider may be registered; found 2")
                    .hasStackTraceContaining(FirstIdentityDetailsProvider::class.java.name)
                    .hasStackTraceContaining(SecondIdentityDetailsProvider::class.java.name)
            }
    }

    @Test
    fun `web auto configuration beans back off for application supplied beans`() {
        val mapping = SimpleUrlHandlerMapping()
        val principalFactory = ArcPrincipalFactory { _, _ -> io.cratis.arc.authorization.ArcPrincipal.anonymous() }
        WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    JacksonAutoConfiguration::class.java,
                    ArcAutoConfiguration::class.java,
                    ArcValidationAutoConfiguration::class.java,
                    ArcWebAutoConfiguration::class.java
                )
            )
            .withBean("arcCommandHandlerMapping", SimpleUrlHandlerMapping::class.java, { mapping })
            .withBean(ArcPrincipalFactory::class.java, { principalFactory })
            .run { context ->
                assertSame(mapping, context.getBean("arcCommandHandlerMapping"))
                assertSame(principalFactory, context.getBean(ArcPrincipalFactory::class.java))
            }
    }

    @Test
    fun `duplicate method and route fails startup naming both artifacts`() {
        WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    JacksonAutoConfiguration::class.java,
                    ArcAutoConfiguration::class.java,
                    ArcValidationAutoConfiguration::class.java,
                    ArcWebAutoConfiguration::class.java
                )
            )
            .withUserConfiguration(DuplicateModules::class.java)
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasStackTraceContaining("Duplicate Arc POST route '/api/duplicates/same-command'")
                    .hasStackTraceContaining(FirstCommand::class.java.name)
                    .hasStackTraceContaining(SecondCommand::class.java.name)
            }
    }

    @Test
    fun `duplicate query method and route fails startup naming both artifacts`() {
        WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    JacksonAutoConfiguration::class.java,
                    ArcAutoConfiguration::class.java,
                    ArcValidationAutoConfiguration::class.java,
                    ArcWebAutoConfiguration::class.java
                )
            )
            .withUserConfiguration(DuplicateQueryModules::class.java)
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasStackTraceContaining("Duplicate Arc GET route '/same-query'")
                    .hasStackTraceContaining("io.cratis.First.same")
                    .hasStackTraceContaining("io.cratis.Second.same")
            }
    }

    @Configuration(proxyBeanMethods = false)
    class MultipleIdentityProviders {
        @Bean
        fun firstIdentityDetailsProvider(): IdentityDetailsProvider<IdentityFixtureDetails> =
            FirstIdentityDetailsProvider()

        @Bean
        fun secondIdentityDetailsProvider(): IdentityDetailsProvider<IdentityFixtureDetails> =
            SecondIdentityDetailsProvider()
    }

    @Configuration(proxyBeanMethods = false)
    class DuplicateModules {
        @Bean
        fun firstModule(): ArcArtifactModule = FixtureModule(handler(FirstCommand::class.java))

        @Bean
        fun secondModule(): ArcArtifactModule = OtherFixtureModule(handler(SecondCommand::class.java))
    }

    @Configuration(proxyBeanMethods = false)
    class DuplicateQueryModules {
        @Bean
        fun firstQueryModule(): ArcArtifactModule = QueryFixtureModule(query("io.cratis.First.same"))

        @Bean
        fun secondQueryModule(): ArcArtifactModule = OtherQueryFixtureModule(query("io.cratis.Second.same"))
    }

    class FirstCommand
    class SecondCommand
    data class IdentityFixtureDetails(val name: String)

    class FirstIdentityDetailsProvider : IdentityDetailsProvider<IdentityFixtureDetails> {
        override val detailsType = IdentityFixtureDetails::class.java
        override suspend fun provide(context: IdentityProviderContext): IdentityDetails<IdentityFixtureDetails> =
            IdentityDetails(true, IdentityFixtureDetails(context.name))
    }

    class SecondIdentityDetailsProvider : IdentityDetailsProvider<IdentityFixtureDetails> {
        override val detailsType = IdentityFixtureDetails::class.java
        override suspend fun provide(context: IdentityProviderContext): IdentityDetails<IdentityFixtureDetails> =
            IdentityDetails(true, IdentityFixtureDetails(context.name))
    }

    private class FixtureModule(handler: CommandHandler) : ArcArtifactModule(listOf(handler), emptyList())
    private class OtherFixtureModule(handler: CommandHandler) : ArcArtifactModule(listOf(handler), emptyList())
    private class QueryFixtureModule(performer: QueryPerformer) : ArcArtifactModule(emptyList(), listOf(performer))
    private class OtherQueryFixtureModule(performer: QueryPerformer) : ArcArtifactModule(emptyList(), listOf(performer))

    private class FixtureHandler(override val commandType: Class<*>) : CommandHandler {
        override val metadata: CommandDescriptor = CommandDescriptor(
            "SameCommand",
            commandType.name,
            location = listOf("duplicates"),
            authorization = AuthorizationMetadata(allowAnonymous = true)
        )

        override suspend fun invoke(context: CommandContext): Any? = null
    }

    private class FixtureQueryPerformer(name: String) : QueryPerformer {
        override val fullyQualifiedName = FullyQualifiedQueryName(name)
        override val descriptor = QueryDescriptor(
            "same",
            name.substringBeforeLast('.'),
            "java.lang.Object",
            routeOptions = RouteOptions("/same-query"),
            fullyQualifiedName = name,
            explicitPath = "/same-query",
            authorization = AuthorizationMetadata(allowAnonymous = true)
        )

        override suspend fun perform(context: QueryContext): Any? = null
    }

    private data class ResolverCase(
        val strategy: String,
        val properties: List<String>,
        val context: TenantResolutionContext,
        val expected: String
    )

    private companion object {
        fun handler(type: Class<*>): CommandHandler = FixtureHandler(type)
        fun query(name: String): QueryPerformer = FixtureQueryPerformer(name)
    }
}
