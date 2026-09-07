// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspProcessorOptions
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.authorization.AuthorizationEvaluator
import io.cratis.arc.authorization.AuthorizationResult
import io.cratis.arc.authorization.ConcurrentAuthorizationPolicyRegistry
import io.cratis.arc.metadata.AuthorizationMetadata
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Operation-level authorization must replace class-level authorization, never widen it.
 *
 * Arc .NET resolves a method by returning `IsAuthorizedWithRoles` from the method's own `[Authorize]` when it has one
 * and only falling back to `IsAuthorized(declaringType)` when it has none. Merging the two sets would let a class role
 * satisfy an operation that deliberately narrowed access.
 */
@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorAuthorizationPrecedenceCompilationTest {
    @Test
    fun `operation authorization replaces class authorization instead of merging with it`() {
        val result = compile(precedenceSources())

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val module = module(result)
        val commands = module.commandHandlers.associate { handler ->
            handler.metadata.name to handler.metadata.authorization
        }
        val queries = module.queryPerformers.associate { performer ->
            performer.descriptor.name to performer.descriptor.authorization
        }

        val classOnly = requireNotNull(commands["ClassOnlyCommand"])
        assertEquals("class-policy", classOnly.policy)
        assertEquals(listOf("class-role"), classOnly.roles)
        assertEquals(listOf("class-scheme"), classOnly.schemes)
        assertFalse(classOnly.allowAnonymous)

        val methodOnly = requireNotNull(commands["MethodOnlyCommand"])
        assertEquals("method-policy", methodOnly.policy)
        assertEquals(listOf("method-role"), methodOnly.roles)
        assertEquals(listOf("method-scheme"), methodOnly.schemes)

        val narrowed = requireNotNull(commands["NarrowedCommand"])
        assertEquals(listOf("method-role"), narrowed.roles)
        assertNull(narrowed.policy)
        assertEquals(emptyList<String>(), narrowed.schemes)

        assertTrue(requireNotNull(commands["AnonymousClassCommand"]).allowAnonymous)
        assertTrue(requireNotNull(commands["AnonymousMethodCommand"]).allowAnonymous)

        val inherited = requireNotNull(queries["inherited"])
        assertEquals("class-policy", inherited.policy)
        assertEquals(listOf("class-role"), inherited.roles)

        val overridden = requireNotNull(queries["overridden"])
        assertEquals("method-policy", overridden.policy)
        assertEquals(listOf("method-role"), overridden.roles)
        assertEquals(listOf("method-scheme"), overridden.schemes)
    }

    @Test
    fun `a class role never satisfies an operation that narrowed the required roles`() {
        val result = compile(precedenceSources())

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val module = module(result)
        val commands = module.commandHandlers.associate { handler ->
            handler.metadata.name to handler.metadata.authorization
        }
        val queries = module.queryPerformers.associate { performer ->
            performer.descriptor.name to performer.descriptor.authorization
        }
        val policies = ConcurrentAuthorizationPolicyRegistry().apply {
            register("class-policy") { AuthorizationResult.success() }
            register("method-policy") { AuthorizationResult.success() }
        }
        val evaluator = AuthorizationEvaluator(policies)

        runBlocking {
            // The class declares class-role and class-extra-role; the operation narrows to method-role alone.
            val narrowed = requireNotNull(commands["NarrowedCommand"])
            assertFalse(evaluator.isAuthorized(narrowed, principal("class-role", "class-extra-role")))
            assertTrue(evaluator.isAuthorized(narrowed, principal("method-role")))

            val classOnly = requireNotNull(commands["ClassOnlyCommand"])
            assertTrue(evaluator.isAuthorized(classOnly, principal("class-role", scheme = "class-scheme")))
            assertFalse(evaluator.isAuthorized(classOnly, principal("method-role", scheme = "class-scheme")))

            val methodOnly = requireNotNull(commands["MethodOnlyCommand"])
            assertFalse(evaluator.isAuthorized(methodOnly, principal("class-role", scheme = "method-scheme")))
            assertTrue(evaluator.isAuthorized(methodOnly, principal("method-role", scheme = "method-scheme")))

            val inherited = requireNotNull(queries["inherited"])
            assertTrue(evaluator.isAuthorized(inherited, principal("class-role")))
            assertFalse(evaluator.isAuthorized(inherited, principal("method-role")))

            val overridden = requireNotNull(queries["overridden"])
            assertFalse(evaluator.isAuthorized(overridden, principal("class-role", scheme = "method-scheme")))
            assertTrue(evaluator.isAuthorized(overridden, principal("method-role", scheme = "method-scheme")))

            assertTrue(evaluator.isAuthorized(requireNotNull(commands["AnonymousClassCommand"]), ArcPrincipal.anonymous()))
            assertTrue(evaluator.isAuthorized(requireNotNull(commands["AnonymousMethodCommand"]), ArcPrincipal.anonymous()))
        }
    }

    private fun principal(vararg roles: String, scheme: String? = null): ArcPrincipal =
        ArcPrincipal("caller", true, roles.toSet(), "caller", emptyList(), scheme)

    @Test
    fun `combining AllowAnonymous with authorization metadata remains a compile-time error`() {
        listOf(
            "@AllowAnonymous on the class and the operation" to """
                @Command
                @AllowAnonymous
                public data class DoubleAnonymousCommand(public val value: String) {
                    @AllowAnonymous
                    public fun handle(): String = value
                }
            """,
            "@AllowAnonymous on the class and @Roles on the operation" to """
                @Command
                @AllowAnonymous
                public data class AnonymousClassAuthorizedMethodCommand(public val value: String) {
                    @Roles("method-role")
                    public fun handle(): String = value
                }
            """,
            "@Authorize on the class and @AllowAnonymous on the operation" to """
                @Command
                @Authorize(roles = ["class-role"])
                public data class AuthorizedClassAnonymousMethodCommand(public val value: String) {
                    @AllowAnonymous
                    public fun handle(): String = value
                }
            """
        ).forEach { (description, declaration) ->
            val result = compile(listOf(SourceFile.kotlin("InvalidAuthorization.kt", fixtureFile(declaration))))

            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, "$description: ${result.messages}")
            assertTrue("[ARCKSP0108]" in result.messages, "$description: ${result.messages}")
        }
    }

    private suspend fun AuthorizationEvaluator.isAuthorized(
        metadata: AuthorizationMetadata,
        principal: ArcPrincipal
    ): Boolean = evaluate(metadata, principal).isAuthorized

    private fun fixtureFile(declaration: String): String = """
        package authorization.fixtures

        import io.cratis.arc.artifacts.Command
        import io.cratis.arc.authorization.AllowAnonymous
        import io.cratis.arc.authorization.Authorize
        import io.cratis.arc.authorization.Roles

        ${declaration.trimIndent()}
    """.trimIndent()

    private fun precedenceSources(): List<SourceFile> = listOf(
        SourceFile.kotlin(
            "AuthorizationPrecedence.kt",
            """
            package authorization.fixtures

            import io.cratis.arc.artifacts.Command
            import io.cratis.arc.artifacts.ReadModel
            import io.cratis.arc.authorization.AllowAnonymous
            import io.cratis.arc.authorization.Authorize
            import io.cratis.arc.authorization.Roles

            @Command
            @Authorize(policy = "class-policy", roles = ["class-role"], schemes = ["class-scheme"])
            public data class ClassOnlyCommand(public val value: String) {
                public fun handle(): String = value
            }

            @Command
            public data class MethodOnlyCommand(public val value: String) {
                @Authorize(policy = "method-policy", roles = ["method-role"], schemes = ["method-scheme"])
                public fun handle(): String = value
            }

            @Command
            @Authorize(policy = "class-policy", roles = ["class-role"], schemes = ["class-scheme"])
            @Roles("class-extra-role")
            public data class NarrowedCommand(public val value: String) {
                @Roles("method-role")
                public fun handle(): String = value
            }

            @Command
            @AllowAnonymous
            public data class AnonymousClassCommand(public val value: String) {
                public fun handle(): String = value
            }

            @Command
            public data class AnonymousMethodCommand(public val value: String) {
                @AllowAnonymous
                public fun handle(): String = value
            }

            @ReadModel
            @Authorize(policy = "class-policy", roles = ["class-role"])
            public data class PrecedenceReadModel(public val value: String) {
                public companion object {
                    public fun inherited(): PrecedenceReadModel = PrecedenceReadModel("inherited")

                    @Authorize(policy = "method-policy", roles = ["method-role"], schemes = ["method-scheme"])
                    public fun overridden(): PrecedenceReadModel = PrecedenceReadModel("overridden")
                }
            }
            """.trimIndent()
        )
    )

    private fun module(result: JvmCompilationResult): ArcArtifactModule = result.classLoader
        .loadClass("io.cratis.arc.generated.AuthorizationPrecedenceArcArtifactModule")
        .getDeclaredConstructor()
        .newInstance() as ArcArtifactModule

    private fun compile(sources: List<SourceFile>): JvmCompilationResult =
        KotlinCompilation().apply {
            useKsp2()
            this.sources = sources
            inheritClassPath = true
            symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "AuthorizationPrecedence")
            kspWithCompilation = true
            messageOutputStream = System.out
        }.compile()
}
