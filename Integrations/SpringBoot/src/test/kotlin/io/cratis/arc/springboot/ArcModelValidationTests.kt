// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandValidator
import io.cratis.arc.commands.DefaultCommandValidationFilter
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.queries.DefaultQueryValidationFilter
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryValidator
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ModelValidationContext
import io.cratis.arc.validation.ModelValidator
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

internal class ArcModelValidationTests {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java))

    @Test
    fun `ordered model beans validate nested command and query inputs through existing factories`() {
        runner.withUserConfiguration(Rules::class.java, Providers::class.java).run { context ->
            val configuration = context.getBean(ArcAutoConfiguration::class.java)
            val providers = context.getBean(Providers::class.java)
            runBlocking {
                val commands = context.getBean(DefaultCommandValidationFilter::class.java)
                val queries = context.getBean(DefaultQueryValidationFilter::class.java)
                assertFeedback(commands.execute(command()).validationResults, "input.name")
                assertFeedback(queries.execute(query()).validationResults, "arg.input.name")
                assertFeedback(configuration.arcCommandValidationFilter(providers.commands).execute(command()).validationResults, "input.name")
                assertFeedback(configuration.arcQueryValidationFilter(providers.queries).execute(query()).validationResults, "arg.input.name")
                assertTrue(ArcAutoConfiguration().arcCommandValidationFilter(providers.commands).execute(command()).isSuccess)
                assertTrue(ArcAutoConfiguration().arcQueryValidationFilter(providers.queries).execute(query()).isSuccess)
                assertTrue(commands.execute(command("valid")).isSuccess)
                assertTrue(queries.execute(query("valid")).isSuccess)
            }
        }
    }

    @Test
    fun `model rules require no servlet security or Jakarta and excluded beans remain lazy`() {
        runner.withClassLoader(FilteredClassLoader("jakarta.servlet", "org.springframework.web", "org.springframework.security", "jakarta.validation"))
            .withUserConfiguration(Rules::class.java, Excluded::class.java).run { context ->
                runBlocking {
                    assertFeedback(context.getBean(DefaultCommandValidationFilter::class.java).execute(command()).validationResults, "input.name")
                    assertFeedback(context.getBean(DefaultQueryValidationFilter::class.java).execute(query()).validationResults, "arg.input.name")
                }
                assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("excluded"))
                assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("nondefault"))
            }
    }

    @Test
    fun `replacement filters do not discover unused lazy model beans`() {
        val commands = DefaultCommandValidationFilter(emptyList())
        val queries = DefaultQueryValidationFilter(emptyList())
        runner.withUserConfiguration(Unused::class.java)
            .withBean(DefaultCommandValidationFilter::class.java, { commands })
            .withBean(DefaultQueryValidationFilter::class.java, { queries }).run { context ->
                assertSame(commands, context.getBean(DefaultCommandValidationFilter::class.java))
                assertSame(queries, context.getBean(DefaultQueryValidationFilter::class.java))
                assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("unused"))
            }
    }

    class Input(val name: String)
    class Owner(val input: Input)
    open class Rule(private val id: String) : ModelValidator<Input> {
        override val modelType = Input::class.java
        override suspend fun validate(model: Input, context: ModelValidationContext): List<ValidationResult> =
            if (model.name.isBlank()) listOf(ValidationResult.error(id, listOf("name"))) else emptyList()
    }
    @Order(-30) class ClassRule : Rule("class")
    class OrderedRule : Rule("ordered"), Ordered { override fun getOrder(): Int = 0 }
    @Configuration(proxyBeanMethods = false)
    class Rules {
        @Bean @Order(30) fun factory(): ModelValidator<Input> = Rule("factory")
        @Bean fun ordered(): ModelValidator<Input> = OrderedRule()
        @Bean fun classRule(): ModelValidator<Input> = ClassRule()
    }
    class Providers {
        @Autowired lateinit var commands: ObjectProvider<CommandValidator<*>>
        @Autowired lateinit var queries: ObjectProvider<QueryValidator>
    }
    @Configuration(proxyBeanMethods = false)
    class Excluded {
        @Bean(autowireCandidate = false) @Lazy fun excluded(): ModelValidator<Input> = error("Excluded rule initialized")
        @Bean(defaultCandidate = false) @Lazy fun nondefault(): ModelValidator<Input> = error("Nondefault rule initialized")
    }
    @Configuration(proxyBeanMethods = false)
    class Unused {
        @Bean @Lazy fun unused(): ModelValidator<Input> = error("Unused rule initialized")
    }
    private fun assertFeedback(results: List<ValidationResult>, member: String) {
        assertEquals(listOf("class", "ordered", "factory"), results.map { it.message })
        assertEquals(List(3) { member }, results.flatMap { it.members })
    }
    private fun command(name: String = "") = CommandContext(UUID.randomUUID(), Owner(Input(name)), Owner::class.java,
        ArcPrincipal.anonymous(), serviceResolver = Services)
    private fun query(name: String = ""): QueryContext {
        val request = QueryRequest(FullyQualifiedQueryName("models.query"), mapOf("arg" to Owner(Input(name))))
        return QueryContext(UUID.randomUUID(), request, request.queryName, ArcPrincipal.anonymous(), null, null, Services, null, false)
    }
    private object Services : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null }
}
