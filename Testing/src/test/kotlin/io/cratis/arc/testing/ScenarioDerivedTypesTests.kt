// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import com.fasterxml.jackson.annotation.JsonProperty
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.polymorphism.DerivedType
import io.cratis.arc.polymorphism.DerivedTypeRegistration
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.DatabindException

internal class ScenarioDerivedTypesTests {
    private val original = ScenarioDerivative("original")
    private val interfaceShape = TypeShapeDescriptor.value(ScenarioContract::class.java.name)
    private val baseShape = TypeShapeDescriptor.value(ScenarioBase::class.java.name)

    @Test
    fun `module registrations round trip nested data`() = runBlocking {
        val performer = performer(TypeShapeDescriptor.value(ScenarioHolder::class.java.name)) { ScenarioHolder(original) }
        val result = scenario(performer).perform().shouldSucceed().result.data as ScenarioHolder
        assertEquals(original, result.value)
        assertNotSame(original, result.value)
    }

    @Test
    fun `direct interface and abstract base results read through declared registered bases`() = runBlocking {
        for (shape in listOf(interfaceShape, baseShape)) {
            val result = scenario(performer(shape) { original }).perform().shouldSucceed().result.data
            assertEquals(original, result)
            assertNotSame(original, result)
        }
    }

    @Test
    fun `list entries read through their declared registered base`() = runBlocking {
        val shape = TypeShapeDescriptor.sequence(SequenceKind.LIST, interfaceShape)
        val result = scenario(performer(shape) { listOf(original) }).perform().shouldSucceed().result.data as List<*>
        assertEquals(listOf(original), result)
        assertNotSame(original, result.single())
    }

    @Test
    fun `typed polymorphic arguments are copied without losing their declared base`() = runBlocking {
        val performer = performer(interfaceShape, listOf(ParameterDescriptor("value", interfaceShape))) { context ->
            val argument = context.request.arguments.getValue("value")
            assertEquals(original, argument)
            assertNotSame(original, argument)
            argument
        }
        val result = scenario(performer).perform(mapOf("value" to original)).shouldSucceed().result.data
        assertEquals(original, result)
        assertNotSame(original, result)
    }

    @Test
    fun `module registrations are isolated from other modules and manual artifacts`() = runBlocking {
        val performer = performer(TypeShapeDescriptor.value(ScenarioHolder::class.java.name)) { ScenarioHolder(original) }
        scenario(performer).perform().shouldSucceed()
        val unregistered = object : ArcArtifactModule(emptyList(), listOf(performer)) {}
        assertThrows(DatabindException::class.java) {
            runBlocking { QueryScenario<Any>(unregistered, performer.fullyQualifiedName).perform() }
        }
        assertThrows(DatabindException::class.java) {
            runBlocking { QueryScenario<Any>(performer).perform() }
        }
        scenario(performer).perform().shouldSucceed()
        Unit
    }

    @Test
    fun `unknown discriminator fails rather than falling back to runtime class`() {
        val failure = assertThrows(DatabindException::class.java) {
            runBlocking { scenario(performer(interfaceShape) { UnknownScenarioContract("unknown") }).perform() }
        }
        assertTrue(failure.message!!.contains("Unknown derived type identifier 'unknown'"), failure.message)
    }

    @Test
    fun `missing discriminator fails rather than falling back to runtime class`() {
        val failure = assertThrows(DatabindException::class.java) {
            runBlocking { scenario(performer(interfaceShape) { PlainScenarioContract("plain") }).perform() }
        }
        assertTrue(failure.message!!.contains("Missing textual _derivedTypeId"), failure.message)
    }

    @Test
    fun `unregistered annotated derivative is rejected rather than self registered`() {
        val failure = assertThrows(DatabindException::class.java) {
            runBlocking { scenario(performer(interfaceShape) { UnregisteredScenarioContract("other") }).perform() }
        }
        assertTrue(failure.message!!.contains("is not registered for"), failure.message)
    }

    private fun scenario(performer: QueryPerformer): QueryScenario<Any> {
        val module = object : ArcArtifactModule(
            emptyList(), listOf(performer), derivedTypes = listOf(
                DerivedTypeRegistration(ScenarioContract::class.java, ScenarioDerivative::class.java),
                DerivedTypeRegistration(ScenarioBase::class.java, ScenarioDerivative::class.java)
            )
        ) {}
        return QueryScenario(module, performer.fullyQualifiedName)
    }

    private fun performer(
        shape: TypeShapeDescriptor,
        parameters: List<ParameterDescriptor> = emptyList(),
        operation: (QueryContext) -> Any?
    ): QueryPerformer = object : QueryPerformer {
        override val fullyQualifiedName = FullyQualifiedQueryName("scenario.derived")
        override val descriptor = QueryDescriptor(
            "derived", "scenario", shape, parameters,
            authorization = AuthorizationMetadata(allowAnonymous = true)
        )
        override suspend fun perform(context: QueryContext): Any? = operation(context)
    }
}

internal interface ScenarioContract {
    val name: String
}
internal abstract class ScenarioBase
@DerivedType("scenario")
internal data class ScenarioDerivative(override val name: String) : ScenarioBase(), ScenarioContract
internal data class ScenarioHolder(val value: ScenarioContract)
internal data class PlainScenarioContract(override val name: String) : ScenarioContract
internal data class UnknownScenarioContract(override val name: String) : ScenarioContract {
    @get:JsonProperty("_derivedTypeId")
    val discriminator: String = "unknown"
}
@DerivedType("unregistered")
internal data class UnregisteredScenarioContract(override val name: String) : ScenarioContract
