// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authorization

import io.cratis.arc.commands.CommandAuthorizationFilter
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryAuthorizationFilter
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryRequest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthorizationTest {
    private val policies = ConcurrentAuthorizationPolicyRegistry()
    private val evaluator = AuthorizationEvaluator(policies)
    private val authenticated = ArcPrincipal("Ada", true, setOf("operator"))

    @Test
    fun `registry rejects duplicates and named policy decisions are preserved`() = runBlocking {
        policies.register("allowed", AuthorizationPolicy { AuthorizationResult.success() })
        val duplicate = assertThrows(DuplicateAuthorizationPolicyException::class.java) {
            policies.register("allowed", AuthorizationPolicy { AuthorizationResult.failure("no") })
        }
        assertEquals("allowed", duplicate.policyName)
        assertTrue(evaluator.evaluate(AuthorizationMetadata(policy = "allowed"), authenticated).isAuthorized)
    }

    @Test
    fun `anonymous is handled first and authentication is otherwise required`() = runBlocking {
        policies.register("deny", AuthorizationPolicy { AuthorizationResult.failure("denied") })
        assertTrue(
            evaluator.evaluate(
                AuthorizationMetadata(allowAnonymous = true, policy = "deny", roles = listOf("admin")),
                ArcPrincipal.anonymous()
            ).isAuthorized
        )
        val result = evaluator.evaluate(AuthorizationMetadata(), ArcPrincipal.anonymous())
        assertFalse(result.isAuthorized)
        assertEquals("An authenticated caller is required.", result.failureReason)
    }

    @Test
    fun `roles use comma and list declarations with any role semantics`() = runBlocking {
        assertTrue(
            evaluator.evaluate(
                AuthorizationMetadata(roles = listOf("admin, operator", "auditor")),
                authenticated
            ).isAuthorized
        )
        val rejected = evaluator.evaluate(AuthorizationMetadata(roles = listOf("admin", "auditor")), authenticated)
        assertFalse(rejected.isAuthorized)
        assertEquals("The caller must belong to at least one required role.", rejected.failureReason)
    }

    @Test
    fun `missing policy has deterministic failure`() = runBlocking {
        val result = evaluator.evaluate(AuthorizationMetadata(policy = "missing"), authenticated)
        assertEquals("Authorization policy 'missing' was not found.", result.failureReason)
    }

    @Test
    fun `command and query authorization filters use generated metadata`() = runBlocking {
        val commandRegistry = ConcurrentCommandHandlerRegistry()
        commandRegistry.register(TestHandler(AuthorizationMetadata(roles = listOf("admin"))))
        val commandResult = CommandAuthorizationFilter(commandRegistry, evaluator).execute(commandContext(authenticated))
        assertFalse(commandResult.isAuthorized)
        assertEquals("The caller must belong to at least one required role.", commandResult.authorizationFailureReason)

        val queryRegistry = ConcurrentQueryPerformerRegistry()
        queryRegistry.register(TestPerformer(AuthorizationMetadata(allowAnonymous = true)))
        val queryResult = QueryAuthorizationFilter(queryRegistry, evaluator).execute(queryContext(ArcPrincipal.anonymous()))
        assertTrue(queryResult.isAuthorized)
    }

    private fun commandContext(principal: ArcPrincipal): CommandContext = CommandContext(
        UUID.randomUUID(),
        TestCommand,
        TestCommand::class.java,
        principal,
        serviceResolver = EmptyServices()
    )

    private fun queryContext(principal: ArcPrincipal): QueryContext {
        val name = FullyQualifiedQueryName("io.cratis.Tests.all")
        return QueryContext(
            UUID.randomUUID(),
            QueryRequest(name),
            name,
            principal,
            null,
            null,
            EmptyServices(),
            null,
            false
        )
    }

    private data object TestCommand

    private class TestHandler(authorization: AuthorizationMetadata) : CommandHandler {
        override val commandType: Class<*> = TestCommand::class.java
        override val metadata = CommandDescriptor("TestCommand", commandType.name, authorization = authorization)
        override val allowsAnonymous: Boolean = false
        override suspend fun invoke(context: CommandContext): Any? = null
    }

    private class TestPerformer(authorization: AuthorizationMetadata) : QueryPerformer {
        override val descriptor = QueryDescriptor(
            "all",
            "io.cratis.Tests",
            "kotlin.String",
            authorization = authorization
        )
        override val fullyQualifiedName = FullyQualifiedQueryName("io.cratis.Tests.all")
        override val allowsAnonymous: Boolean = false
        override val supportsPaging: Boolean = false
        override suspend fun perform(context: QueryContext): Any? = null
    }

    private class EmptyServices : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
}
