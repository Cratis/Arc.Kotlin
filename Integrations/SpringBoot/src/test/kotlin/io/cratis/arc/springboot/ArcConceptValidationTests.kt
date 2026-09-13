// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.commands.CommandPipeline
import io.cratis.arc.commands.CommandValidator
import io.cratis.arc.commands.DefaultCommandValidationFilter
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.DefaultQueryValidationFilter
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.ObservableQueryOpenResult
import io.cratis.arc.queries.ObservableQueryPipeline
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryPerformerRegistry
import io.cratis.arc.queries.QueryPipeline
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.queries.QueryValidator
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ConceptValidator
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
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

internal class ArcConceptValidationTests {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java))

    @Test
    fun `registered Kotlin rules reject command execution before handler`() {
        runner.withUserConfiguration(Rules::class.java).run { context ->
            val handler = Handler(Graph::class.java)
            context.getBean(CommandHandlerRegistry::class.java).register(handler)
            val result = runBlocking { context.getBean(CommandPipeline::class.java).execute(graph(), commandOptions()) }
            assertFalse(result.isSuccess)
            assertEquals(expectedMembers(), result.validationResults.flatMap { it.members })
            assertEquals(expectedMessages(), result.validationResults.map { it.message })
            assertEquals(0, handler.invocations.get())
        }
    }

    @Test
    fun `registered Kotlin rules reject validation without handling and allow valid execution`() {
        runner.withUserConfiguration(Rules::class.java).run { context ->
            val handler = Handler(Graph::class.java)
            context.getBean(CommandHandlerRegistry::class.java).register(handler)
            val pipeline = context.getBean(CommandPipeline::class.java)
            runBlocking {
                val invalid = pipeline.validate(graph(), commandOptions())
                assertFalse(invalid.isSuccess)
                assertEquals(expectedMembers(), invalid.validationResults.flatMap { it.members })
                assertTrue(pipeline.validate(Graph(Code("valid")), commandOptions()).isSuccess)
                assertEquals(0, handler.invocations.get())
                assertTrue(pipeline.execute(Graph(Code("valid")), commandOptions()).isSuccess)
                assertEquals(1, handler.invocations.get())
            }
        }
    }

    @Test
    fun `registered rules reject one-shot argument graph before performer`() {
        runner.withUserConfiguration(Rules::class.java).run { context ->
            val performer = Performer(QueryTransportType.REQUEST_RESPONSE)
            context.getBean(QueryPerformerRegistry::class.java).register(performer)
            runBlocking {
                val pipeline = context.getBean(QueryPipeline::class.java)
                val result = pipeline.perform(request(performer, graph()), queryOptions())
                assertFalse(result.isSuccess)
                assertEquals(expectedMembers().map { "input.$it" }, result.validationResults.flatMap { it.members })
                assertEquals(expectedMessages(), result.validationResults.map { it.message })
                assertEquals(0, performer.invocations.get())
                assertTrue(pipeline.perform(request(performer, Graph(Code("valid"))), queryOptions()).isSuccess)
                assertEquals(1, performer.invocations.get())
            }
        }
    }

    @Test
    fun `observable rejects argument rules before opening and validates valid input only once`() {
        runner.withUserConfiguration(Rules::class.java).run { context ->
            val performer = Performer(QueryTransportType.OBSERVABLE)
            context.getBean(QueryPerformerRegistry::class.java).register(performer)
            runBlocking {
                val pipeline = context.getBean(ObservableQueryPipeline::class.java)
                val failure = assertInstanceOf(ObservableQueryOpenResult.Failure::class.java,
                    pipeline.open(request(performer, graph()), queryOptions()))
                assertEquals(expectedMembers().map { "input.$it" }, failure.result.validationResults.flatMap { it.members })
                assertEquals(0, performer.invocations.get())
                val calls = context.getBean(Calls::class.java)
                calls.values.clear()
                val success = assertInstanceOf(ObservableQueryOpenResult.Stream::class.java,
                    pipeline.open(request(performer, Graph(Code("valid"))), queryOptions()))
                assertEquals(listOf("class", "ordered", "factory"), calls.values)
                val emissions = success.results.toList()
                assertEquals(listOf("first", "second"), emissions.map { it.data })
                assertTrue(emissions.all { it.isSuccess })
                assertEquals(listOf("class", "ordered", "factory"), calls.values)
                assertEquals(1, performer.invocations.get())
            }
        }
    }

    @Test
    fun `typed command and query validators remain combined with concept rules`() {
        runner.withUserConfiguration(Rules::class.java, TypedRules::class.java).run { context ->
            val handler = Handler(Graph::class.java)
            val performer = Performer(QueryTransportType.REQUEST_RESPONSE)
            context.getBean(CommandHandlerRegistry::class.java).register(handler)
            context.getBean(QueryPerformerRegistry::class.java).register(performer)
            runBlocking {
                val command = context.getBean(CommandPipeline::class.java).execute(Graph(Code("")), commandOptions())
                val query = context.getBean(QueryPipeline::class.java).perform(request(performer, Graph(Code(""))), queryOptions())
                assertEquals(listOf("command", "class", "ordered", "factory", "second rule"), command.validationResults.map { it.message })
                assertEquals(listOf("query", "class", "ordered", "factory", "second rule"), query.validationResults.map { it.message })
                assertEquals(0, handler.invocations.get())
                assertEquals(0, performer.invocations.get())
            }
        }
    }

    @Test
    fun `managed original one-provider factories retain injected concept contributions`() {
        runner.withUserConfiguration(Rules::class.java, TypedProviders::class.java).run { context ->
            val configuration = context.getBean(ArcAutoConfiguration::class.java)
            val providers = context.getBean(TypedProviders::class.java)
            runBlocking {
                val command = configuration.arcCommandValidationFilter(providers.commands).execute(commandContext(Graph(Code(""))))
                val query = configuration.arcQueryValidationFilter(providers.queries).execute(queryContext(Graph(Code(""))))
                assertEquals(listOf("class", "ordered", "factory", "second rule"), command.validationResults.map { it.message })
                assertEquals(listOf("class", "ordered", "factory", "second rule"), query.validationResults.map { it.message })
            }
        }
    }

    @Test
    fun `standalone no-arg configuration retains supplied typed validators without implicit Spring discovery`() {
        runner.withUserConfiguration(Rules::class.java, TypedRules::class.java, TypedProviders::class.java).run { context ->
            val configuration = ArcAutoConfiguration()
            val providers = context.getBean(TypedProviders::class.java)
            runBlocking {
                val command = configuration.arcCommandValidationFilter(providers.commands).execute(commandContext(Graph(Code(""))))
                val query = configuration.arcQueryValidationFilter(providers.queries).execute(queryContext(Graph(Code(""))))
                assertEquals(listOf("command"), command.validationResults.map { it.message })
                assertEquals(listOf("query"), query.validationResults.map { it.message })
            }
        }
    }

    @Test
    fun `empty contribution beans keep both defaults usable`() {
        runner.run { context ->
            runBlocking {
                assertTrue(context.getBean(DefaultCommandValidationFilter::class.java).execute(commandContext(graph())).isSuccess)
                assertTrue(context.getBean(DefaultQueryValidationFilter::class.java).execute(queryContext(graph())).isSuccess)
            }
        }
    }

    @Test
    fun `concept wiring requires no optional servlet security or Jakarta classes`() {
        runner.withClassLoader(FilteredClassLoader("jakarta.servlet", "org.springframework.web", "org.springframework.security", "jakarta.validation"))
            .withUserConfiguration(Rules::class.java).run { context ->
                runBlocking {
                    val command = context.getBean(DefaultCommandValidationFilter::class.java).execute(commandContext(Graph(Code(""))))
                    val query = context.getBean(DefaultQueryValidationFilter::class.java).execute(queryContext(Graph(Code(""))))
                    assertEquals(4, command.validationResults.size)
                    assertEquals(4, query.validationResults.size)
                }
            }
    }

    @Test
    fun `excluded lazy and nondefault concept beans are never instantiated`() {
        runner.withUserConfiguration(ExcludedRules::class.java).run { context ->
            runBlocking {
                assertTrue(context.getBean(DefaultCommandValidationFilter::class.java).execute(commandContext(graph())).isSuccess)
                assertTrue(context.getBean(DefaultQueryValidationFilter::class.java).execute(queryContext(graph())).isSuccess)
            }
            assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("excluded"))
            assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("nondefault"))
        }
    }

    @Test
    fun `excluded lazy beans stay excluded alongside eligible rules`() {
        runner.withUserConfiguration(Rules::class.java, ExcludedRules::class.java).run { context ->
            runBlocking {
                assertEquals(4, context.getBean(DefaultCommandValidationFilter::class.java).execute(commandContext(Graph(Code("")))).validationResults.size)
                assertEquals(4, context.getBean(DefaultQueryValidationFilter::class.java).execute(queryContext(Graph(Code("")))).validationResults.size)
            }
            assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("excluded"))
            assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("nondefault"))
        }
    }

    @Test
    fun `replacement default filters remain authoritative and do not initialize unused lazy rules`() {
        val commands = DefaultCommandValidationFilter(emptyList())
        val queries = DefaultQueryValidationFilter(emptyList())
        runner.withUserConfiguration(UnusedRules::class.java)
            .withBean(DefaultCommandValidationFilter::class.java, { commands })
            .withBean(DefaultQueryValidationFilter::class.java, { queries }).run { context ->
                assertSame(commands, context.getBean(DefaultCommandValidationFilter::class.java))
                assertSame(queries, context.getBean(DefaultQueryValidationFilter::class.java))
                val handler = Handler(Graph::class.java)
                val performer = Performer(QueryTransportType.REQUEST_RESPONSE)
                context.getBean(CommandHandlerRegistry::class.java).register(handler)
                context.getBean(QueryPerformerRegistry::class.java).register(performer)
                runBlocking {
                    assertTrue(context.getBean(CommandPipeline::class.java).execute(graph(), commandOptions()).isSuccess)
                    assertTrue(context.getBean(QueryPipeline::class.java).perform(request(performer, graph()), queryOptions()).isSuccess)
                }
                assertEquals(1, handler.invocations.get())
                assertEquals(1, performer.invocations.get())
                assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("unused"))
            }
    }

    class Code(private val raw: String) : ConceptAs<String> { override fun value(): String = raw }
    class Graph(val code: Code) {
        var array: Array<Code> = emptyArray()
        var children: List<Graph> = emptyList()
        var named: Map<String, Graph> = emptyMap()
        var next: Graph? = null
    }
    class Calls { val values = mutableListOf<String>() }

    open class Rule(private val calls: Calls, private val id: String) : ConceptValidator<Code> {
        override val conceptType = Code::class.java
        override fun validate(concept: Code): List<ValidationResult> {
            calls.values.add(id)
            return if (concept.value().isEmpty()) listOf(ValidationResult.error(id, listOf("value"))) else emptyList()
        }
    }
    @Order(-30)
    class ClassRule(calls: Calls) : Rule(calls, "class")
    class OrderedRule(calls: Calls) : Rule(calls, "ordered"), Ordered { override fun getOrder(): Int = 0 }

    @Configuration(proxyBeanMethods = false)
    class Rules {
        @Bean fun calls(): Calls = Calls()
        // Deliberately register in reverse precedence; factory and runtime class metadata both matter.
        @Bean @Order(30) fun factory(calls: Calls): ConceptValidator<Code> = object : Rule(calls, "factory") {
            override fun validate(concept: Code): List<ValidationResult> = super.validate(concept) +
                if (concept.value().isEmpty()) listOf(ValidationResult.error("second rule", listOf("rawValue"))) else emptyList()
        }
        @Bean fun ordered(calls: Calls): ConceptValidator<Code> = OrderedRule(calls)
        @Bean fun classRule(calls: Calls): ConceptValidator<Code> = ClassRule(calls)
    }
    @Configuration(proxyBeanMethods = false)
    class TypedRules {
        @Bean fun command(): CommandValidator<Graph> = object : CommandValidator<Graph> {
            override val commandType = Graph::class.java
            override suspend fun validate(command: Graph, context: CommandContext): List<ValidationResult> = listOf(ValidationResult.error("command"))
        }
        @Bean fun query(): QueryValidator = object : QueryValidator {
            override val queryName: FullyQualifiedQueryName? = null
            override suspend fun validate(request: QueryRequest, context: QueryContext): List<ValidationResult> = listOf(ValidationResult.error("query"))
        }
    }
    class TypedProviders {
        @Autowired lateinit var commands: ObjectProvider<CommandValidator<*>>
        @Autowired lateinit var queries: ObjectProvider<QueryValidator>
    }
    @Configuration(proxyBeanMethods = false)
    class ExcludedRules {
        @Bean(autowireCandidate = false) @Lazy fun excluded(): ConceptValidator<Code> = error("Excluded bean instantiated")
        @Bean(defaultCandidate = false) @Lazy fun nondefault(): ConceptValidator<Code> = error("Nondefault bean instantiated")
    }
    @Configuration(proxyBeanMethods = false)
    class UnusedRules {
        @Bean @Lazy fun unused(): ConceptValidator<Code> = error("Replacement filters must not discover unused rules")
    }

    class Handler(override val commandType: Class<*>) : CommandHandler {
        val invocations = AtomicInteger()
        override val metadata = CommandDescriptor("ConceptCommand", commandType.name, authorization = AuthorizationMetadata(allowAnonymous = true))
        override suspend fun invoke(context: CommandContext): Any {
            invocations.incrementAndGet()
            return "handled"
        }
    }
    class Performer(transport: QueryTransportType) : QueryPerformer {
        val invocations = AtomicInteger()
        override val fullyQualifiedName = FullyQualifiedQueryName("concepts.${transport.name}")
        override val descriptor = QueryDescriptor("concepts", "ConceptQueries", "kotlin.String",
            fullyQualifiedName = fullyQualifiedName.value, authorization = AuthorizationMetadata(allowAnonymous = true), transport = transport)
        override suspend fun perform(context: QueryContext): Any {
            invocations.incrementAndGet()
            return if (descriptor.transport == QueryTransportType.OBSERVABLE) flowOf("first", "second") else "performed"
        }
    }

    private fun graph(): Graph = Graph(Code("")).apply {
        array = arrayOf(Code(""))
        children = listOf(Graph(Code("")))
        named = mapOf("first" to Graph(Code("")))
        next = this
    }
    private fun expectedMembers(): List<String> = listOf("array[0]", "children[0].code", "code", "named.first.code").flatMap { path -> List(4) { path } }
    private fun expectedMessages(): List<String> = List(4) { listOf("class", "ordered", "factory", "second rule") }.flatten()
    private fun commandOptions() = CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), EmptyServices)
    private fun queryOptions() = QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), EmptyServices)
    private fun request(performer: Performer, graph: Graph) = QueryRequest(performer.fullyQualifiedName, mapOf("input" to graph))
    private fun commandContext(graph: Graph) = CommandContext(UUID.randomUUID(), graph, Graph::class.java, ArcPrincipal.anonymous(), serviceResolver = EmptyServices)
    private fun queryContext(graph: Graph): QueryContext {
        val request = QueryRequest(FullyQualifiedQueryName("concepts.query"), mapOf("input" to graph))
        return QueryContext(UUID.randomUUID(), request, request.queryName, ArcPrincipal.anonymous(), null, null, EmptyServices, null, false)
    }
    private object EmptyServices : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null }
}
