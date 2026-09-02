// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class GeneratedTypeScriptProxiesTest {
    private val generatedDirectory: Path = Path.of(
        requireNotNull(System.getProperty("arc.contractTests.generatedProxies")) {
            "The generated proxy directory must be supplied by the ContractTests test task."
        }
    )

    @Test
    fun `direct Kotlin and Java temporal commands use value imports descriptors and typed response constructors`() {
        mapOf(
            "KotlinTemporalCommand" to "KotlinTemporalResult",
            "JavaTemporalCommand" to "JavaTemporalResult"
        ).forEach { (commandName, resultName) ->
            val generated = generated(commandName)

            assertContains(generated, "import { DateOnly, Guid, TimeOnly } from '@cratis/fundamentals';")
            assertContains(generated, "extends Command<I$commandName, $resultName>")
            assertContains(generated, "new PropertyDescriptor('identifier', Guid, false)")
            assertContains(generated, "new PropertyDescriptor('date', DateOnly, false)")
            assertContains(generated, "new PropertyDescriptor('time', TimeOnly, false)")
            assertContains(generated, "super($resultName, false);")
            assertFalse(generated.contains("new PropertyDescriptor('identifier', String"))
            assertFalse(generated.contains("new PropertyDescriptor('date', Date, false)"))
            assertFalse(generated.contains("new PropertyDescriptor('time', String"))
        }
    }

    @Test
    fun `direct Kotlin and Java temporal models and results use value decorators`() {
        listOf(
            "KotlinTemporalResult",
            "JavaTemporalResult",
            "KotlinTemporalReadModel",
            "JavaTemporalReadModel"
        ).forEach { typeName ->
            val generated = generated(typeName)

            assertContains(generated, "import { field, DateOnly, Guid, TimeOnly } from '@cratis/fundamentals';")
            assertContains(generated, "@field(Guid)\n    identifier!: Guid;")
            assertContains(generated, "@field(DateOnly)\n    date!: DateOnly;")
            assertContains(generated, "@field(TimeOnly)\n    time!: TimeOnly;")
            assertFalse(generated.contains("identifier!: string;"))
            assertFalse(generated.contains("date!: Date;"))
            assertFalse(generated.contains("time!: string;"))
        }
    }

    @Test
    fun `direct Kotlin and Java temporal queries use value parameter descriptors and model constructors`() {
        mapOf(
            "FindKotlinTemporal" to "KotlinTemporalReadModel",
            "FindJavaTemporal" to "JavaTemporalReadModel"
        ).forEach { (queryName, modelName) ->
            val generated = generated(queryName)

            assertContains(generated, "import { DateOnly, Guid, TimeOnly } from '@cratis/fundamentals';")
            assertContains(generated, "new ParameterDescriptor('identifier', Guid, false)")
            assertContains(generated, "new ParameterDescriptor('date', DateOnly, false)")
            assertContains(generated, "new ParameterDescriptor('time', TimeOnly, false)")
            assertContains(generated, "super($modelName, false);")
            assertFalse(generated.contains("identifier: string;"))
            assertFalse(generated.contains("date: Date;"))
            assertFalse(generated.contains("time: string;"))
        }
    }

    @Test
    fun `concept backed temporal surfaces use their scalar value mappings without wrapper imports`() {
        val command = generated("MetadataCommand")
        val model = generated("ConceptTemporalReadModel")
        val query = generated("FindConceptTemporal")

        listOf(command, model, query).forEach { generated ->
            assertTrue(generated.contains("Guid"))
            assertTrue(generated.contains("DateOnly"))
            assertTrue(generated.contains("TimeOnly"))
            listOf(
                "OrderId",
                "DeliveryDate",
                "DeliveryTime",
                "JavaOrderId",
                "JavaDeliveryDate",
                "JavaDeliveryTime"
            ).forEach { conceptName ->
                assertFalse(generated.contains("import { $conceptName }"))
            }
        }

        assertContains(command, "new PropertyDescriptor('orderId', Guid, false)")
        assertContains(command, "new PropertyDescriptor('deliveryDate', DateOnly, false)")
        assertContains(command, "new PropertyDescriptor('deliveryTime', TimeOnly, false)")
        assertContains(command, "new PropertyDescriptor('javaOrderId', Guid, false)")
        assertContains(command, "new PropertyDescriptor('javaDeliveryDate', DateOnly, false)")
        assertContains(command, "new PropertyDescriptor('javaDeliveryTime', TimeOnly, false)")

        assertContains(model, "identifier!: Guid;")
        assertContains(model, "date!: DateOnly;")
        assertContains(model, "time!: TimeOnly;")
        assertContains(model, "javaIdentifier!: Guid;")
        assertContains(model, "javaDate!: DateOnly;")
        assertContains(model, "javaTime!: TimeOnly;")

        assertContains(query, "new ParameterDescriptor('identifier', Guid, false)")
        assertContains(query, "new ParameterDescriptor('date', DateOnly, false)")
        assertContains(query, "new ParameterDescriptor('time', TimeOnly, false)")
        assertContains(query, "new ParameterDescriptor('javaIdentifier', Guid, false)")
        assertContains(query, "new ParameterDescriptor('javaDate', DateOnly, false)")
        assertContains(query, "new ParameterDescriptor('javaTime', TimeOnly, false)")
    }

    @Test
    fun `Kotlin and Java map commands and read models use recursive records and Object runtime metadata`() {
        listOf("KotlinMapMetadataCommand", "JavaMapMetadataCommand").forEach { commandName ->
            val generated = generated(commandName)
            assertContains(generated, "strings?: Record<string, string>;")
            assertContains(generated, "numbers?: Record<string, number[]>;")
            assertContains(generated, "nested?: Record<string, Record<string, boolean>>;")
            assertContains(generated, "optional?: Record<string, string>;")
            assertContains(generated, "new PropertyDescriptor('strings', Object, false)")
            assertContains(generated, "new PropertyDescriptor('optional', Object, true)")
            assertFalse(generated.contains("ValueMap"))
            assertFalse(generated.contains("_entries"))
        }
        listOf("KotlinMapReadModel", "JavaMapReadModel").forEach { modelName ->
            val generated = generated(modelName)
            assertContains(generated, "@field(Object)\n    strings!: Record<string, string>;")
            assertContains(generated, "@field(Object)\n    numbers!: Record<string, number[]>;")
            assertContains(generated, "@field(Object)\n    nested!: Record<string, Record<string, boolean>>;")
            assertContains(generated, "@field(Object)\n    optional?: Record<string, string>;")
            assertFalse(generated.contains("ValueMap"))
        }
    }

    @Test
    fun `one shot and observable proxies expose only client query parameters`() {
        mapOf(
            "All" to listOf("prefix"),
            "ContextualKotlin" to listOf("label"),
            "ById" to listOf("identifier"),
            "ObserveAll" to listOf("label"),
            "ObserveJava" to listOf("label"),
            "SpringDataAsync" to listOf("label"),
            "SpringDataDirect" to listOf("label"),
            "SpringDataJavaDirect" to listOf("label"),
            "SpringDataSuspend" to listOf("label")
        ).forEach { (queryName, clientParameters) ->
            val generated = generated(queryName)

            clientParameters.forEach { parameter ->
                assertContains(generated, "new ParameterDescriptor('$parameter',")
            }
            listOf("dependency", "request", "context", "pageable", "sort").forEach { infrastructureParameter ->
                listOf(
                    "new ParameterDescriptor('$infrastructureParameter',",
                    "\n    $infrastructureParameter:",
                    "\n    $infrastructureParameter!:"
                ).forEach { leakedFragment ->
                    assertFalse(
                        generated.contains(leakedFragment),
                        "$queryName leaked infrastructure parameter '$infrastructureParameter'."
                    )
                }
            }
            listOf(
                "KotlinQueryDependency",
                "JavaQueryDependency",
                "QueryRequest",
                "QueryContext",
                "Pageable"
            ).forEach { typeName ->
                assertFalse(generated.contains(typeName), "$queryName leaked infrastructure import '$typeName'.")
            }
        }
    }

    @Test
    fun `defaulted Kotlin query parameters are optional without copied default expressions`() {
        val oneShot = generated("Defaulted")
        listOf("count?: number;", "prefix?: string;", "suffix?: string;").forEach { fragment ->
            assertContains(oneShot, fragment)
        }
        assertContains(oneShot, "required: string;")
        assertContains(oneShot, "required!: string;")
        assertContains(oneShot, "return [\n            'required',\n        ];")
        assertFalse(oneShot.contains("default-suffix"))
        assertFalse(oneShot.contains("= 2"))

        val observable = generated("ObserveDefaulted")
        assertContains(observable, "label?: string;")
        assertContains(observable, "return [\n        ];")
        assertFalse(observable.contains("flow-default"))
    }

    @Test
    fun `existing direct identifiers use Guid while instant fields continue to use Date`() {
        val response = generated("FixtureResponse")
        val filter = generated("FixtureFilter")
        val shapeBase = generated("FixtureShapeBase")
        val circle = generated("FixtureCircle")

        assertContains(response, "@field(Guid)\n    identifier!: Guid;")
        assertContains(filter, "@field(Guid, true)\n    ids!: Guid[];")
        assertContains(shapeBase, "@field(Date)\n    createdAt!: Date;")
        assertContains(circle, "@field(Date)\n    created!: Date;")
        assertFalse(response.contains("identifier!: string;"))
        assertFalse(filter.contains("ids!: string[];"))
    }

    private fun generated(typeName: String): String {
        val file = generatedDirectory.resolve("$typeName.ts")
        assertTrue(Files.isRegularFile(file), "Expected generated proxy at $file")
        return Files.readString(file)
    }

    private fun assertContains(actual: String, expected: String) {
        assertTrue(actual.contains(expected), "Missing generated fragment '$expected'")
    }
}
