// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandHandlerArgumentResolver
import io.cratis.arc.commands.CommandKeyProvider
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer

public data class TestCommand(public val value: String)
public data class TestResponse(public val value: String)
public data class TestModel(public val value: String)
public class TestDependency(public val prefix: String)

public class ManualCommandHandler @JvmOverloads constructor(
    authorization: AuthorizationMetadata = AuthorizationMetadata(allowAnonymous = true),
    private val operation: suspend (CommandContext) -> Any? = { TestResponse((it.command as TestCommand).value) }
) : CommandHandler {
    override val commandType: Class<*> = TestCommand::class.java
    override val metadata: CommandDescriptor = CommandDescriptor(
        "TestCommand",
        commandType.name,
        authorization = authorization
    )
    public var invocationCount: Int = 0
        private set
    public var lastContext: CommandContext? = null
        private set

    override suspend fun invoke(context: CommandContext): Any? {
        invocationCount++
        lastContext = context
        return operation(context)
    }
}

public class ManualQueryPerformer @JvmOverloads constructor(
    override val fullyQualifiedName: FullyQualifiedQueryName = QUERY_NAME,
    authorization: AuthorizationMetadata = AuthorizationMetadata(allowAnonymous = true),
    private val operation: suspend (QueryContext) -> Any? = { TestModel("default") }
) : QueryPerformer {
    override val descriptor: QueryDescriptor = QueryDescriptor(
        "all",
        "io.cratis.arc.testing.TestModels",
        TestModel::class.java.name,
        fullyQualifiedName = fullyQualifiedName.value,
        authorization = authorization
    )
    public var invocationCount: Int = 0
        private set
    public var lastContext: QueryContext? = null
        private set

    override suspend fun perform(context: QueryContext): Any? {
        invocationCount++
        lastContext = context
        return operation(context)
    }

    public companion object {
        @JvmField
        public val QUERY_NAME: FullyQualifiedQueryName = FullyQualifiedQueryName("io.cratis.arc.testing.TestModels.all")
    }
}

public class ManualArtifactModule @JvmOverloads constructor(
    handler: CommandHandler = ManualCommandHandler(),
    performer: QueryPerformer = ManualQueryPerformer()
) : ArcArtifactModule(listOf(handler), listOf(performer))

/** Concept-typed command key proving pins written with a scalar match a wrapped key. */
public data class TestModelId(private val rawValue: String) : ConceptAs<String> {
    override fun value(): String = rawValue
}

/** Command carrying an explicit key so the pipeline can resolve a command-side read model. */
public data class KeyedTestCommand(public val id: TestModelId, public val value: String) : CommandKeyProvider {
    override fun commandKey(): Any = id
}

/** How the handler below asks for its command-side read model. */
public enum class TestReadModelArgument {
    /** A required Kotlin parameter. */
    REQUIRED,

    /** A nullable Kotlin parameter. */
    NULLABLE,

    /** A Java `Optional` parameter. */
    OPTIONAL
}

/** Manual handler that resolves a [TestModel] the way a generated handler does. */
public class ReadModelCommandHandler @JvmOverloads constructor(
    private val argument: TestReadModelArgument = TestReadModelArgument.REQUIRED
) : CommandHandler {
    override val commandType: Class<*> = KeyedTestCommand::class.java
    override val metadata: CommandDescriptor = CommandDescriptor(
        "KeyedTestCommand",
        commandType.name,
        authorization = AuthorizationMetadata(allowAnonymous = true)
    )

    override suspend fun invoke(context: CommandContext): Any? {
        val resolver = CommandHandlerArgumentResolver(context)
        val model = when (argument) {
            TestReadModelArgument.REQUIRED -> resolver.resolve(TestModel::class.java, "handle", "model")
            TestReadModelArgument.NULLABLE -> resolver.resolveNullable(TestModel::class.java, "handle", "model")
            TestReadModelArgument.OPTIONAL ->
                resolver.resolveOptional(TestModel::class.java, "handle", "model").orElse(null)
        }
        return TestResponse(model?.value ?: "absent")
    }
}
