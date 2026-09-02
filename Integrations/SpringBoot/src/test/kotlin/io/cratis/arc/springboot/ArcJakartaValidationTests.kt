// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.DefaultObservableQueryPipeline
import io.cratis.arc.queries.DefaultQueryPipeline
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.ObservableQueryOpenResult
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.validation.CreditCard
import io.cratis.arc.validation.Phone
import io.cratis.arc.validation.Url
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Valid
import jakarta.validation.Validation
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ArcJakartaValidationTests {
    @Test
    fun `command validation uses the same nested graph semantics`() = runBlocking {
        val root = InvalidQueryModel("", "Accepted")
        root.children = listOf(InvalidQueryModel("", "Accepted"))
        root.next = root
        val context = CommandContext(
            UUID.randomUUID(),
            root,
            InvalidQueryModel::class.java,
            ArcPrincipal.anonymous(),
            serviceResolver = EmptyServices
        )

        val result = ArcValidationAutoConfiguration()
            .arcJakartaBeanValidationCommandFilter(jakartaValidator())
            .execute(context)

        assertEquals(setOf("name", "children[0].name"), result.validationResults.flatMap { it.members }.toSet())
    }

    @Test
    fun `command graph automatically enforces Arc string constraints`() = runBlocking {
        val filter = ArcValidationAutoConfiguration()
            .arcJakartaBeanValidationCommandFilter(jakartaValidator())
        val invalid = filter.execute(
            CommandContext(
                UUID.randomUUID(),
                ArcStringConstraintsModel("555.0100", "ftp://example.com", "4111111111111112"),
                ArcStringConstraintsModel::class.java,
                ArcPrincipal.anonymous(),
                serviceResolver = EmptyServices
            )
        )
        val valid = filter.execute(
            CommandContext(
                UUID.randomUUID(),
                ArcStringConstraintsModel("+1 (555) 010-0200", "HTTPS://example.com", "4111-1111 1111-1111"),
                ArcStringConstraintsModel::class.java,
                ArcPrincipal.anonymous(),
                serviceResolver = EmptyServices
            )
        )
        val empty = filter.execute(
            CommandContext(
                UUID.randomUUID(),
                ArcStringConstraintsModel("", "", ""),
                ArcStringConstraintsModel::class.java,
                ArcPrincipal.anonymous(),
                serviceResolver = EmptyServices
            )
        )

        assertEquals(setOf("phone", "url", "creditCard"), invalid.validationResults.flatMap { it.members }.toSet())
        assertEquals(
            setOf("must be a valid phone number", "must be a valid URL", "must be a valid credit card number"),
            invalid.validationResults.map { it.message }.toSet()
        )
        assertTrue(valid.isSuccess)
        assertTrue(empty.isSuccess)
    }

    @Test
    fun `one-shot validates nested list map cycle null and custom constraints before performer`() = runBlocking {
        val performer = ValidationPerformer(QueryTransportType.REQUEST_RESPONSE)
        val pipeline = pipeline(performer)
        val root = InvalidQueryModel("", "invalid")
        root.children = listOf(InvalidQueryModel("", "Accepted"))
        root.byName = mapOf("first" to InvalidQueryModel("", "Accepted"))
        root.next = root

        val result = pipeline.perform(QueryRequest(performer.fullyQualifiedName, mapOf("request" to root)), options())

        assertEquals(0, performer.invocations.get())
        assertEquals(ValidationResultReasons.RULE, result.validationResults.first().reason)
        assertEquals(
            setOf("request.name", "request.code", "request.children[0].name", "request.byName[first].name"),
            result.validationResults.flatMap { it.members }.toSet()
        )

        val nullResult = pipeline.perform(
            QueryRequest(performer.fullyQualifiedName, mapOf("request" to null)),
            options()
        )
        assertEquals(listOf("request"), nullResult.validationResults.single().members)
        assertEquals(0, performer.invocations.get())
    }

    @Test
    fun `observable validates once and rejects before opening performer`() = runBlocking {
        val performer = ValidationPerformer(QueryTransportType.OBSERVABLE)
        val registry = registry(performer)
        val filter = ArcValidationAutoConfiguration().arcJakartaBeanValidationQueryFilter(jakartaValidator(), registry)
        val pipeline = DefaultObservableQueryPipeline(registry, listOf(filter))

        val opened = pipeline.open(
            QueryRequest(performer.fullyQualifiedName, mapOf("request" to InvalidQueryModel("", "Accepted"))),
            options()
        )

        val failure = assertInstanceOf(ObservableQueryOpenResult.Failure::class.java, opened)
        assertEquals(listOf("request.name"), failure.result.validationResults.single().members)
        assertEquals(0, performer.invocations.get())
    }

    @Test
    fun `query executable validation receives infrastructure values in declaration order`() = runBlocking {
        val performer = InfrastructureValidationPerformer()
        val result = pipeline(performer).perform(
            QueryRequest(performer.fullyQualifiedName, mapOf("marker" to "present")),
            options()
        )

        assertTrue(result.isSuccess)
        assertEquals(1, performer.invocations.get())
    }

    @Test
    fun `omitted Kotlin defaults are not validated as fabricated null arguments`() = runBlocking {
        val performer = DefaultedValidationPerformer()
        val absent = pipeline(performer).perform(
            QueryRequest(performer.fullyQualifiedName, mapOf("marker" to "present")),
            options()
        )
        val invalidSupplied = pipeline(performer).perform(
            QueryRequest(performer.fullyQualifiedName, mapOf("label" to "provided", "marker" to "")),
            options()
        )
        val explicitNull = pipeline(performer).perform(
            QueryRequest(performer.fullyQualifiedName, mapOf("label" to null, "marker" to "present")),
            options()
        )

        assertTrue(absent.isSuccess)
        assertEquals(listOf("marker"), invalidSupplied.validationResults.single().members)
        assertEquals(listOf("label"), explicitNull.validationResults.single().members)
        assertEquals(1, performer.invocations.get())
    }

    @Test
    fun `allowed error severity permits a Jakarta-invalid query`() = runBlocking {
        val performer = ValidationPerformer(QueryTransportType.REQUEST_RESPONSE)
        val result = pipeline(performer).perform(
            QueryRequest(performer.fullyQualifiedName, mapOf("request" to InvalidQueryModel("", "Accepted"))),
            options(ValidationResultSeverity.Error)
        )

        assertTrue(result.isSuccess)
        assertEquals(1, performer.invocations.get())
    }

    private fun pipeline(performer: QueryPerformer): DefaultQueryPipeline {
        val registry = registry(performer)
        val filter = ArcValidationAutoConfiguration().arcJakartaBeanValidationQueryFilter(jakartaValidator(), registry)
        return DefaultQueryPipeline(registry, listOf(filter))
    }

    private fun registry(performer: QueryPerformer): ConcurrentQueryPerformerRegistry =
        ConcurrentQueryPerformerRegistry().also { it.register(performer) }

    private fun jakartaValidator() = Validation.buildDefaultValidatorFactory().validator

    private fun options(allowedSeverity: ValidationResultSeverity? = null) = QueryExecutionOptions(
        UUID.randomUUID(),
        ArcPrincipal.anonymous(),
        EmptyServices,
        allowedValidationSeverity = allowedSeverity
    )

    private class ValidationPerformer(transport: QueryTransportType) : QueryPerformer {
        override val fullyQualifiedName = FullyQualifiedQueryName("${ValidationQueries::class.java.name}.validate")
        override val descriptor = QueryDescriptor(
            name = "validate",
            declaringTypeName = ValidationQueries::class.java.name,
            returnTypeName = "kotlin.String",
            parameters = listOf(
                ParameterDescriptor(
                    name = "request",
                    typeName = InvalidQueryModel::class.java.name,
                    validateRecursively = true
                )
            ),
            fullyQualifiedName = fullyQualifiedName.value,
            authorization = AuthorizationMetadata(allowAnonymous = true),
            transport = transport
        )
        val invocations = AtomicInteger()

        override suspend fun perform(context: QueryContext): Any {
            invocations.incrementAndGet()
            return if (descriptor.transport == QueryTransportType.OBSERVABLE) flowOf("one", "two") else "performed"
        }
    }

    private class DefaultedValidationPerformer : QueryPerformer {
        override val fullyQualifiedName = FullyQualifiedQueryName(
            "${DefaultedValidationQueries::class.java.name}.validate"
        )
        override val descriptor = QueryDescriptor(
            name = "validate",
            declaringTypeName = DefaultedValidationQueries::class.java.name,
            returnTypeName = "kotlin.String",
            parameters = listOf(
                ParameterDescriptor(
                    "context",
                    TypeShapeDescriptor.value(QueryContext::class.java.name),
                    QueryParameterSource.QUERY_CONTEXT
                ),
                ParameterDescriptor(
                    "label",
                    TypeShapeDescriptor.value("kotlin.String"),
                    QueryParameterSource.CLIENT,
                    hasDefault = true
                ),
                ParameterDescriptor("marker", TypeShapeDescriptor.value("kotlin.String"), QueryParameterSource.CLIENT),
                ParameterDescriptor(
                    "request",
                    TypeShapeDescriptor.value(QueryRequest::class.java.name),
                    QueryParameterSource.QUERY_REQUEST
                )
            ),
            fullyQualifiedName = fullyQualifiedName.value,
            authorization = AuthorizationMetadata(allowAnonymous = true)
        )
        val invocations = AtomicInteger()

        override suspend fun perform(context: QueryContext): Any {
            invocations.incrementAndGet()
            return "performed"
        }
    }

    private class InfrastructureValidationPerformer : QueryPerformer {
        override val fullyQualifiedName = FullyQualifiedQueryName(
            "${InfrastructureValidationQueries::class.java.name}.validate"
        )
        override val descriptor = QueryDescriptor(
            name = "validate",
            declaringTypeName = InfrastructureValidationQueries::class.java.name,
            returnTypeName = "kotlin.String",
            parameters = listOf(
                ParameterDescriptor(
                    "context",
                    TypeShapeDescriptor.value(QueryContext::class.java.name),
                    QueryParameterSource.QUERY_CONTEXT
                ),
                ParameterDescriptor("marker", TypeShapeDescriptor.value("kotlin.String"), QueryParameterSource.CLIENT),
                ParameterDescriptor(
                    "request",
                    TypeShapeDescriptor.value(QueryRequest::class.java.name),
                    QueryParameterSource.QUERY_REQUEST
                )
            ),
            fullyQualifiedName = fullyQualifiedName.value,
            authorization = AuthorizationMetadata(allowAnonymous = true)
        )
        val invocations = AtomicInteger()

        override suspend fun perform(context: QueryContext): Any {
            invocations.incrementAndGet()
            return "performed"
        }
    }

    internal data class ArcStringConstraintsModel(
        @field:Phone val phone: String?,
        @field:Url val url: String?,
        @field:CreditCard val creditCard: String?
    )

    internal class ValidationQueries {
        companion object {
            @Suppress("UNUSED_PARAMETER")
            fun validate(@NotNull @Valid request: InvalidQueryModel): Flow<String> = flowOf("unused")
        }
    }

    internal class DefaultedValidationQueries {
        public companion object {
            @Suppress("UNUSED_PARAMETER")
            public fun validate(
                @NotNull context: QueryContext,
                @NotNull label: String = "server-default",
                @NotBlank marker: String,
                @NotNull request: QueryRequest
            ): String = "unused"
        }
    }

    internal class InfrastructureValidationQueries {
        public companion object {
            @Suppress("UNUSED_PARAMETER")
            public fun validate(
                @NotNull context: QueryContext,
                @NotNull marker: String,
                @NotNull request: QueryRequest
            ): String = "unused"
        }
    }

    internal class InvalidQueryModel(
        @field:NotBlank(message = "Name is required.") val name: String?,
        @field:StartsWithA(message = "Code must start with A.") val code: String?
    ) {
        @field:Valid
        var children: List<InvalidQueryModel> = emptyList()

        @field:Valid
        var byName: Map<String, InvalidQueryModel> = emptyMap()

        @field:Valid
        var next: InvalidQueryModel? = null
    }

    @Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.RUNTIME)
    @Constraint(validatedBy = [StartsWithAValidator::class])
    internal annotation class StartsWithA(
        val message: String = "Must start with A.",
        val groups: Array<KClass<*>> = [],
        val payload: Array<KClass<out jakarta.validation.Payload>> = []
    )

    internal class StartsWithAValidator : ConstraintValidator<StartsWithA, String?> {
        override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean =
            value == null || value.startsWith('A')
    }

    private data object EmptyServices : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
}
