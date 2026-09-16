// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.DefaultCommandValidationFilter
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.queries.DefaultQueryValidationFilter
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ConceptValidationExclusion
import io.cratis.arc.validation.ConceptValidator
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy

internal class ArcConceptExclusionTests {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java))

    @Test
    fun `exclusions install through original factories without optional hosting dependencies`() {
        runner.withClassLoader(FilteredClassLoader("jakarta.servlet", "org.springframework.web", "org.springframework.security", "jakarta.validation"))
            .withUserConfiguration(Rules::class.java, Exclusions::class.java, Ineligible::class.java).run { context ->
                val shared = Code("")
                val input = Input(shared, shared)
                runBlocking {
                    val command = CommandContext(UUID.randomUUID(), input, Input::class.java, ArcPrincipal.anonymous(), serviceResolver = Services)
                    assertEquals(listOf("required"), context.getBean(DefaultCommandValidationFilter::class.java).execute(command)
                        .validationResults.flatMap { it.members })
                    val request = QueryRequest(FullyQualifiedQueryName("exclusions.query"), mapOf("input" to input))
                    val query = QueryContext(UUID.randomUUID(), request, request.queryName, ArcPrincipal.anonymous(), null, null, Services, null, false)
                    assertEquals(listOf("input.required"), context.getBean(DefaultQueryValidationFilter::class.java).execute(query)
                        .validationResults.flatMap { it.members })
                }
                assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("excluded"))
                assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("nondefault"))
            }
    }

    @Test
    fun `invalid lazy eligible registration fails at installation rather than the first request`() {
        runner.withUserConfiguration(Invalid::class.java).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("must name a public readable member")
        }
    }

    @Test
    fun `application filters back off without resolving unused lazy exclusions`() {
        val command = DefaultCommandValidationFilter(emptyList())
        val query = DefaultQueryValidationFilter(emptyList())
        runner.withUserConfiguration(Unused::class.java).withBean(DefaultCommandValidationFilter::class.java, { command })
            .withBean(DefaultQueryValidationFilter::class.java, { query }).run { context ->
                assertSame(command, context.getBean(DefaultCommandValidationFilter::class.java))
                assertSame(query, context.getBean(DefaultQueryValidationFilter::class.java))
                assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("unused"))
            }
    }

    data class Code(val raw: String) : ConceptAs<String> { override fun value(): String = raw }
    data class Input(val ignored: Code, val required: Code)
    @Configuration(proxyBeanMethods = false)
    class Rules {
        @Bean fun concept(): ConceptValidator<Code> = object : ConceptValidator<Code> {
            override val conceptType = Code::class.java
            override fun validate(concept: Code) = listOf(ValidationResult.error("concept"))
        }
    }
    @Configuration(proxyBeanMethods = false)
    class Exclusions { @Bean fun ignored(): ConceptValidationExclusion = ConceptValidationExclusion(Input::class.java, "ignored") }
    @Configuration(proxyBeanMethods = false)
    class Invalid { @Bean @Lazy fun invalid(): ConceptValidationExclusion = ConceptValidationExclusion(Input::class.java, "unknown") }
    @Configuration(proxyBeanMethods = false)
    class Ineligible {
        @Bean(autowireCandidate = false) @Lazy fun excluded(): ConceptValidationExclusion = error("Ineligible exclusion initialized")
        @Bean(defaultCandidate = false) @Lazy fun nondefault(): ConceptValidationExclusion = error("Nondefault exclusion initialized")
    }
    @Configuration(proxyBeanMethods = false)
    class Unused { @Bean @Lazy fun unused(): ConceptValidationExclusion = error("Unused exclusion initialized") }
    private object Services : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null }
}
