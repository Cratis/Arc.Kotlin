// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.BlockingQueryRendererFor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPaging
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryRendererFor
import io.cratis.arc.queries.QueryRendererResult
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryableQueryRenderer
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Symbolic legacy-descriptor linkage against the current runtime, not a recreation of old consumer binaries. */
class JvmDefaultDispatchCompatibilityTest {
    private val lookup = MethodHandles.publicLookup()
    private val booleanGetter = MethodType.methodType(Boolean::class.javaPrimitiveType)
    private val orderType = MethodType.methodType(Int::class.javaPrimitiveType)

    @Test
    fun `command adapter and interface getters resolve and execute metadata defaults`() {
        for (anonymous in listOf(false, true)) {
            val metadata = CommandDescriptor("Command", "Tests.Command",
                authorization = AuthorizationMetadata(allowAnonymous = anonymous))
            val blocking = BlockingCommandHandlerAdapter(object : BlockingCommandHandler {
                override val commandType = String::class.java
                override val metadata = metadata
                override fun invoke(context: CommandContext): Any = context.command
            })
            val async = AsyncCommandHandlerAdapter(object : AsyncCommandHandler {
                override val commandType = String::class.java
                override val metadata = metadata
                override fun invoke(context: CommandContext): CompletionStage<*> =
                    CompletableFuture.completedFuture(context.command)
            })
            for (handler in listOf(blocking, async)) {
                assertSame(metadata, handler.metadata)
                for (owner in listOf(handler.javaClass, CommandHandler::class.java)) {
                    assertEquals(anonymous, lookup.findVirtual(owner, "getAllowsAnonymous", booleanGetter)
                        .invokeWithArguments(handler))
                }
                assertEquals(anonymous, lookup.findStatic(
                    Class.forName("io.cratis.arc.commands.CommandHandler\$DefaultImpls"), "getAllowsAnonymous",
                    MethodType.methodType(Boolean::class.javaPrimitiveType, CommandHandler::class.java)
                ).invokeWithArguments(handler))
            }
        }
    }

    @Test
    fun `both query adapters retain independent anonymity paging and sorting default dispatch`() {
        for (anonymous in listOf(false, true)) for (paging in listOf(false, true)) for (sorting in listOf(false, true)) {
            val descriptor = QueryDescriptor("query", "Tests", "java.lang.String",
                authorization = AuthorizationMetadata(allowAnonymous = anonymous),
                supportsPaging = paging, supportsSorting = sorting)
            val name = FullyQualifiedQueryName(descriptor.fullyQualifiedName)
            val blocking = BlockingQueryPerformerAdapter(object : BlockingQueryPerformer {
                override val descriptor = descriptor
                override val fullyQualifiedName = name
                override fun perform(context: QueryContext): Any = context.queryName.value()
            })
            val async = AsyncQueryPerformerAdapter(object : AsyncQueryPerformer {
                override val descriptor = descriptor
                override val fullyQualifiedName = name
                override fun perform(context: QueryContext): CompletionStage<*> =
                    CompletableFuture.completedFuture(context.queryName.value())
            })
            for (performer in listOf(blocking, async)) {
                assertSame(descriptor, performer.descriptor)
                assertEquals(name, performer.fullyQualifiedName)
                for ((getter, expected) in mapOf(
                    "getAllowsAnonymous" to anonymous, "getSupportsPaging" to paging, "getSupportsSorting" to sorting
                )) {
                    // findVirtual permits inherited resolution; declared forwarding owners are not the contract.
                    for (owner in listOf(performer.javaClass, QueryPerformer::class.java)) {
                        assertEquals(expected, lookup.findVirtual(owner, getter, booleanGetter)
                            .invokeWithArguments(performer))
                    }
                    assertEquals(expected, lookup.findStatic(
                        Class.forName("io.cratis.arc.queries.QueryPerformer\$DefaultImpls"), getter,
                        MethodType.methodType(Boolean::class.javaPrimitiveType, QueryPerformer::class.java)
                    ).invokeWithArguments(performer))
                }
            }
        }
    }

    @Test
    fun `renderer legacy erased render and typed and erased blocking descriptors execute paging`() {
        val renderer = QueryableQueryRenderer()
        val name = FullyQualifiedQueryName("Tests.query")
        val context = QueryContext(UUID.randomUUID(), QueryRequest(name, paging = QueryPaging(1, 2)), name,
            ArcPrincipal(), null, null, object : ServiceResolver {
                override fun <T : Any> resolve(type: Class<T>): T? = null
            }, null, false)
        val values = listOf("zero", "one", "two", "three", "four")
        val initial = QueryRendererResult(values)
        val defaults = Class.forName("io.cratis.arc.queries.BlockingQueryRendererFor\$DefaultImpls")
        for (owner in listOf(QueryableQueryRenderer::class.java, BlockingQueryRendererFor::class.java,
            QueryRendererFor::class.java)) {
            assertEquals(0, lookup.findVirtual(owner, "order", orderType).invokeWithArguments(renderer))
            // Object is the legacy descriptor. The newly emitted render(Iterable, ...) is not a substitute.
            val stage = lookup.findVirtual(owner, "render", MethodType.methodType(CompletionStage::class.java,
                Any::class.java, QueryRendererResult::class.java, QueryContext::class.java))
                .invokeWithArguments(renderer, values, initial, context) as CompletionStage<*>
            assertRendered(stage.toCompletableFuture().get(5, TimeUnit.SECONDS))
        }
        for (argument in listOf(Iterable::class.java, Any::class.java)) {
            assertRendered(lookup.findVirtual(QueryableQueryRenderer::class.java, "renderBlocking",
                MethodType.methodType(QueryRendererResult::class.java, argument, QueryRendererResult::class.java,
                    QueryContext::class.java)).invokeWithArguments(renderer, values, initial, context))
        }
        val stage = lookup.findStatic(defaults, "render", MethodType.methodType(CompletionStage::class.java,
            BlockingQueryRendererFor::class.java, Any::class.java, QueryRendererResult::class.java,
            QueryContext::class.java)).invokeWithArguments(renderer, values, initial, context) as CompletionStage<*>
        assertRendered(stage.toCompletableFuture().get(5, TimeUnit.SECONDS))
        assertEquals(0, lookup.findStatic(defaults, "order",
            MethodType.methodType(Int::class.javaPrimitiveType, BlockingQueryRendererFor::class.java))
            .invokeWithArguments(renderer))
    }

    private fun assertRendered(value: Any?) {
        val rendered = value as QueryRendererResult
        assertEquals(listOf("two", "three"), rendered.data)
        assertEquals(1, rendered.paging.page)
        assertEquals(2, rendered.paging.size)
        assertEquals(5L, rendered.paging.totalItems)
    }
}
