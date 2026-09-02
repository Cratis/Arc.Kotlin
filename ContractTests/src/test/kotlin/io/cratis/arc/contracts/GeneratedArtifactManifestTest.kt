// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.generated.ContractTestsArcArtifactModule
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.CommandResponseValueDisposition
import io.cratis.arc.metadata.MapKeyCodec
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeShapeKind
import io.cratis.arc.metadata.ValidationRuleDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class GeneratedArtifactManifestTest {
    @Test
    fun `generated manifest is deterministic complete and timestamp free`() {
        val resourceName = "META-INF/cratis/arc/ContractTests.json"
        val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream(resourceName)).use { it.readAllBytes() }
        val json = bytes.toString(Charsets.UTF_8)
        val manifest = ArcObjectMapper.create().readValue(bytes, ArcArtifactManifest::class.java)

        assertEquals(ArcArtifactManifest.CURRENT_FORMAT_VERSION, manifest.formatVersion)
        assertEquals("ContractTests", manifest.moduleName)
        assertEquals(
            listOf(
                "ChronicleScopedResponseCommand",
                "EventCommand",
                "JavaAsyncCommand",
                "JavaMapMetadataCommand",
                "JavaOptionalReadModelCommand",
                "JavaOptionalServiceCommand",
                "JavaPairResponseCommand",
                "JavaReadModelCommand",
                "JavaRoutedEventArrayResponseCommand",
                "JavaTemporalCommand",
                "KotlinCommandResultResponseCommand",
                "KotlinHandledOnlyResponseCommand",
                "KotlinMapMetadataCommand",
                "KotlinNestedResponseCommand",
                "KotlinNullableReadModelCommand",
                "KotlinPairResponseCommand",
                "KotlinReadModelCommand",
                "KotlinReadModelCommandWithoutKey",
                "KotlinRegularCommand",
                "KotlinRoutedEventListResponseCommand",
                "KotlinSuspendCommand",
                "KotlinTemporalCommand",
                "MetadataCommand",
                "ProvideCommand",
                "RoutedEventResponseCommand"
            ),
            manifest.commands.map { it.name }
        )
        assertEquals(manifest.types.map { it.fullyQualifiedName }.sorted(), manifest.types.map { it.fullyQualifiedName })
        assertEquals(manifest.interfaces.map { it.fullyQualifiedName }.sorted(), manifest.interfaces.map { it.fullyQualifiedName })
        assertEquals(manifest.enums.map { it.fullyQualifiedName }.sorted(), manifest.enums.map { it.fullyQualifiedName })
        assertEquals(manifest.concepts.map { it.fullyQualifiedName }.sorted(), manifest.concepts.map { it.fullyQualifiedName })
        assertFalse(json.contains("timestamp", ignoreCase = true))
        assertFalse(json.contains("generatedAt", ignoreCase = true))
    }

    @Test
    fun `generated manifest preserves keys response shapes reachable models and enums`() {
        val manifest = loadManifest()
        val metadataCommand = manifest.commands.single { it.name == "MetadataCommand" }
        val eventCommand = manifest.commands.single { it.name == "EventCommand" }
        val metadataType = manifest.types.single { it.name == "MetadataCommand" }
        val responseType = manifest.types.single { it.name == "FixtureResponse" }
        val cyclicType = manifest.types.single { it.name == "CyclicFixture" }
        val fixtureEnum = manifest.enums.single { it.name == "FixtureState" }

        assertTrue(metadataCommand.properties.single { it.name == "commandId" }.isCommandKey)
        assertTrue(metadataType.properties.single { it.name == "commandId" }.isCommandKey)
        assertTrue(metadataCommand.properties.single { it.name == "states" }.isEnumerable)
        assertEquals("io.cratis.arc.contracts.fixtures.FixtureState", metadataCommand.properties.single { it.name == "states" }.elementTypeName)
        assertEquals("io.cratis.arc.contracts.fixtures.FixtureResponse", metadataCommand.responseTypeName)
        assertTrue(metadataCommand.responseIsEnumerable)
        val optionalConcept = metadataCommand.properties.single { it.name == "optionalCustomerName" }
        assertTrue(optionalConcept.isNullable)
        assertFalse(optionalConcept.isEnumerable)
        val conceptList = metadataCommand.properties.single { it.name == "customerNames" }
        assertTrue(conceptList.isEnumerable)
        assertEquals("io.cratis.arc.contracts.fixtures.CustomerName", conceptList.elementTypeName)
        assertNull(eventCommand.responseTypeName)
        assertFalse(eventCommand.responseIsEnumerable)
        assertEquals(
            listOf(Triple("io.cratis.arc.contracts.fixtures.MetadataEvent", false, CommandResponseValueDisposition.HANDLED)),
            responseValues(eventCommand)
        )
        assertEquals(
            listOf(
                "identifier", "state", "labels", "optionalLabel", "cycle", "explicitState", "annotatedState",
                "permissions", "shape", "shapes", "javaState", "javaAnnotatedState", "javaPermissions", "javaContract"
            ),
            responseType.properties.map { it.name }
        )
        assertEquals("io.cratis.arc.contracts.fixtures.CyclicFixture", cyclicType.properties.single { it.name == "next" }.typeName)
        assertEquals(listOf("New" to 0, "Active" to 1), fixtureEnum.members.map { it.name to it.value })
    }

    @Test
    fun `aggregate response values preserve declaration order dispositions and compatibility projection`() {
        val manifest = loadManifest()
        val runtimeCommands = ContractTestsArcArtifactModule().commandHandlers.associate { handler ->
            handler.metadata.name to handler.metadata
        }
        val expected = mapOf(
            "JavaPairResponseCommand" to listOf(
                Triple(
                    "io.cratis.arc.contracts.fixtures.JavaAggregateClientResponse",
                    false,
                    CommandResponseValueDisposition.CLIENT
                ),
                Triple("io.cratis.arc.contracts.fixtures.HandledResponse", false, CommandResponseValueDisposition.HANDLED)
            ),
            "KotlinPairResponseCommand" to listOf(
                Triple(
                    "io.cratis.arc.contracts.fixtures.AggregateClientResponse",
                    false,
                    CommandResponseValueDisposition.CLIENT
                ),
                Triple("io.cratis.arc.contracts.fixtures.MetadataEvent", false, CommandResponseValueDisposition.HANDLED)
            ),
            "KotlinRoutedEventListResponseCommand" to listOf(
                Triple(
                    "io.cratis.chronicle.eventSequences.EventForEventSourceId",
                    true,
                    CommandResponseValueDisposition.HANDLED
                )
            ),
            "JavaRoutedEventArrayResponseCommand" to listOf(
                Triple(
                    "io.cratis.chronicle.eventSequences.EventForEventSourceId",
                    true,
                    CommandResponseValueDisposition.HANDLED
                )
            ),
            "KotlinNestedResponseCommand" to listOf(
                Triple("io.cratis.arc.contracts.fixtures.HandledResponse", false, CommandResponseValueDisposition.HANDLED),
                Triple(
                    "io.cratis.arc.contracts.fixtures.AggregateClientResponse",
                    true,
                    CommandResponseValueDisposition.CLIENT
                ),
                Triple("io.cratis.arc.contracts.fixtures.MetadataEvent", false, CommandResponseValueDisposition.HANDLED),
                Triple("io.cratis.arc.contracts.fixtures.HandledResponse", false, CommandResponseValueDisposition.HANDLED)
            ),
            "KotlinHandledOnlyResponseCommand" to listOf(
                Triple("io.cratis.arc.contracts.fixtures.HandledResponse", false, CommandResponseValueDisposition.HANDLED),
                Triple("io.cratis.arc.contracts.fixtures.HandledResponse", false, CommandResponseValueDisposition.HANDLED)
            ),
            "KotlinCommandResultResponseCommand" to listOf(
                Triple(
                    "io.cratis.arc.contracts.fixtures.AggregateClientResponse",
                    false,
                    CommandResponseValueDisposition.CLIENT
                ),
                Triple("io.cratis.arc.contracts.fixtures.MetadataEvent", false, CommandResponseValueDisposition.HANDLED)
            ),
            "RoutedEventResponseCommand" to listOf(
                Triple(
                    "io.cratis.chronicle.eventSequences.EventForEventSourceId",
                    false,
                    CommandResponseValueDisposition.HANDLED
                )
            ),
            "ChronicleScopedResponseCommand" to listOf(
                Triple(
                    "io.cratis.arc.chronicle.EventsWithConcurrencyScopes",
                    false,
                    CommandResponseValueDisposition.HANDLED
                )
            )
        )

        expected.forEach { (commandName, expectedValues) ->
            val manifestCommand = manifest.commands.single { command -> command.name == commandName }
            val runtimeCommand = runtimeCommands.getValue(commandName)
            assertEquals(expectedValues, responseValues(manifestCommand))
            assertEquals(expectedValues, responseValues(runtimeCommand))
            assertEquals(manifestCommand.responseValues, runtimeCommand.responseValues)
        }

        listOf(
            "JavaPairResponseCommand",
            "KotlinCommandResultResponseCommand",
            "KotlinPairResponseCommand"
        ).forEach { commandName ->
            val command = manifest.commands.single { descriptor -> descriptor.name == commandName }
            assertFalse(command.responseIsEnumerable)
            assertEquals(responseValues(command).single { value -> value.third == CommandResponseValueDisposition.CLIENT }.first, command.responseTypeName)
        }
        val nested = manifest.commands.single { command -> command.name == "KotlinNestedResponseCommand" }
        assertEquals("io.cratis.arc.contracts.fixtures.AggregateClientResponse", nested.responseTypeName)
        assertTrue(nested.responseIsEnumerable)
        val handledOnly = manifest.commands.single { command -> command.name == "KotlinHandledOnlyResponseCommand" }
        assertNull(handledOnly.responseTypeName)
        assertFalse(handledOnly.responseIsEnumerable)
    }

    @Test
    fun `validation metadata is preserved in commands queries observable queries manifests and modules`() {
        val manifest = loadManifest()
        val module = ContractTestsArcArtifactModule()
        val metadataCommand = manifest.commands.single { it.name == "MetadataCommand" }
        val metadataType = manifest.types.single { it.name == "MetadataCommand" }
        val runtimeCommand = module.commandHandlers.single { it.metadata.name == "MetadataCommand" }.metadata
        val javaCommand = manifest.commands.single { it.name == "JavaAsyncCommand" }

        val expectedDisplayNameRules = listOf(
            listOf("notEmpty", emptyList<String>(), "Display name is required"),
            listOf("length", listOf("2", "40"), "Display name length is invalid")
        )
        assertEquals(expectedDisplayNameRules, rules(metadataCommand.properties.single { it.name == "displayName" }.validationRules))
        assertEquals(expectedDisplayNameRules, rules(metadataType.properties.single { it.name == "displayName" }.validationRules))
        assertEquals(expectedDisplayNameRules, rules(runtimeCommand.properties.single { it.name == "displayName" }.validationRules))
        assertEquals(
            listOf(listOf("notNull", emptyList<String>(), "Command id is required")),
            rules(metadataCommand.properties.single { it.name == "commandId" }.validationRules)
        )
        assertEquals(
            listOf(listOf("length", listOf("1", "3"), "Choose between one and three states")),
            rules(metadataCommand.properties.single { it.name == "states" }.validationRules)
        )
        assertEquals(
            listOf(listOf("notEmpty", emptyList<String>(), "At least one label is required")),
            rules(metadataCommand.properties.single { it.name == "labels" }.validationRules)
        )
        assertEquals(
            listOf(listOf("matches", listOf("^[A-Z][A-Za-z ]+$"), "Display name must start with an uppercase letter")),
            rules(metadataCommand.properties.single { it.name == "formattedName" }.validationRules)
        )
        assertEquals(
            listOf(
                listOf("emailAddress", emptyList<String>(), "Use an example.com address"),
                listOf("matches", listOf("^.+@example\\.com$"), "Use an example.com address")
            ),
            rules(metadataCommand.properties.single { it.name == "email" }.validationRules)
        )
        mapOf(
            "phone" to listOf("phone", emptyList<String>(), "Phone number is invalid"),
            "website" to listOf("url", emptyList<String>(), "Website URL is invalid"),
            "creditCard" to listOf("creditCard", emptyList<String>(), "Credit card number is invalid")
        ).forEach { (propertyName, expectedRule) ->
            assertEquals(
                listOf(expectedRule),
                rules(metadataCommand.properties.single { it.name == propertyName }.validationRules)
            )
            assertEquals(
                listOf(expectedRule),
                rules(metadataType.properties.single { it.name == propertyName }.validationRules)
            )
            assertEquals(
                listOf(expectedRule),
                rules(runtimeCommand.properties.single { it.name == propertyName }.validationRules)
            )
        }
        assertEquals(
            listOf(
                listOf("greaterThan", listOf("1.5"), "Ratio must exceed 1.5"),
                listOf("lessThanOrEqual", listOf("9.5"), "Ratio must not exceed 9.5")
            ),
            rules(metadataCommand.properties.single { it.name == "ratio" }.validationRules)
        )
        assertEquals(
            listOf(
                listOf("greaterThanOrEqual", listOf("18"), "Age must be at least 18"),
                listOf("lessThanOrEqual", listOf("120"), "Age must be at most 120")
            ),
            rules(metadataCommand.properties.single { it.name == "age" }.validationRules)
        )
        assertEquals(
            listOf(
                listOf("greaterThan", listOf("0"), "Positive count is required"),
                listOf("greaterThanOrEqual", listOf("0"), "Non-negative count is required"),
                listOf("lessThan", listOf("0"), "Negative count is required"),
                listOf("lessThanOrEqual", listOf("0"), "Non-positive count is required")
            ),
            listOf("positive", "positiveOrZero", "negative", "negativeOrZero").flatMap { propertyName ->
                rules(metadataCommand.properties.single { it.name == propertyName }.validationRules)
            }
        )
        assertTrue(metadataCommand.properties.single { it.name == "contact" }.validateRecursively)
        assertTrue(metadataCommand.properties.single { it.name == "contacts" }.validateRecursively)
        assertEquals(
            listOf(
                listOf("notNull", emptyList<String>(), "Customer name is required"),
                listOf("notEmpty", emptyList<String>(), "Concept value is required"),
                listOf("length", listOf("2", "40"), "Customer name length is invalid")
            ),
            rules(metadataCommand.properties.single { it.name == "customerName" }.validationRules)
        )
        assertEquals(
            listOf(listOf("greaterThan", listOf("0"), "Quantity must be positive")),
            rules(metadataCommand.properties.single { it.name == "quantity" }.validationRules)
        )
        assertEquals(
            listOf(
                listOf("length", listOf("3", "12"), "Java customer code length is invalid"),
                listOf("matches", listOf("^[A-Z]+$"), "Java customer code must be uppercase")
            ),
            rules(metadataCommand.properties.single { it.name == "javaCode" }.validationRules)
        )

        assertEquals(
            listOf(listOf("notEmpty", emptyList<String>(), "Java command id is required")),
            rules(javaCommand.properties.single { it.name == "commandId" }.validationRules)
        )
        assertEquals(
            listOf(
                listOf("length", listOf("2", "12"), "Java value length is invalid"),
                listOf("matches", listOf("^[a-z]+$"), "Java value must be lowercase")
            ),
            rules(javaCommand.properties.single { it.name == "value" }.validationRules)
        )

        val single = manifest.queries.single { it.name == "single" }
        val filtered = manifest.queries.single { it.name == "filtered" }
        val observable = manifest.queries.single { it.name == "observeAll" && it.declaringTypeName.endsWith("KotlinQueryReadModel") }
        assertEquals(
            listOf(listOf("notEmpty", emptyList<String>(), "Identifier is required")),
            rules(single.parameters.single().validationRules)
        )
        assertTrue(filtered.parameters.single().validateRecursively)
        assertEquals(
            listOf(listOf("matches", listOf("^[a-z]+$"), "Observable label must be lowercase")),
            rules(observable.parameters.single { it.name == "label" }.validationRules)
        )
        val runtimeObservable = module.queryPerformers.single {
            it.fullyQualifiedName.value.endsWith("KotlinQueryReadModel.observeAll")
        }.descriptor
        assertEquals(
            rules(observable.parameters.single { it.name == "label" }.validationRules),
            rules(runtimeObservable.parameters.single { it.name == "label" }.validationRules)
        )
    }

    @Test
    fun `Spring Data host adapters preserve declaration order in manifests and stay outside client parameters`() {
        val manifest = loadManifest()
        val runtimeQueries = ContractTestsArcArtifactModule().queryPerformers.associate { performer ->
            performer.descriptor.fullyQualifiedName to performer.descriptor
        }
        val expectedSources = mapOf(
            "KotlinQueryReadModel.springDataDirect" to listOf(
                QueryParameterSource.CLIENT,
                QueryParameterSource.HOST_ADAPTER,
                QueryParameterSource.SERVICE,
                QueryParameterSource.QUERY_REQUEST,
                QueryParameterSource.HOST_ADAPTER
            ),
            "KotlinQueryReadModel.springDataSuspend" to listOf(
                QueryParameterSource.QUERY_REQUEST,
                QueryParameterSource.HOST_ADAPTER,
                QueryParameterSource.CLIENT,
                QueryParameterSource.HOST_ADAPTER,
                QueryParameterSource.SERVICE
            ),
            "JavaQueryReadModel.springDataJavaDirect" to listOf(
                QueryParameterSource.HOST_ADAPTER,
                QueryParameterSource.CLIENT,
                QueryParameterSource.QUERY_REQUEST,
                QueryParameterSource.SERVICE,
                QueryParameterSource.HOST_ADAPTER
            ),
            "JavaQueryReadModel.springDataAsync" to listOf(
                QueryParameterSource.QUERY_REQUEST,
                QueryParameterSource.HOST_ADAPTER,
                QueryParameterSource.CLIENT,
                QueryParameterSource.SERVICE,
                QueryParameterSource.HOST_ADAPTER
            )
        )

        expectedSources.forEach { (suffix, sources) ->
            val persisted = manifest.queries.single { query -> query.fullyQualifiedName.endsWith(suffix) }
            val runtime = runtimeQueries.getValue(persisted.fullyQualifiedName)
            assertEquals(sources, persisted.parameters.map { parameter -> parameter.source })
            assertEquals(listOf("label"), persisted.parameters.filter { it.source == QueryParameterSource.CLIENT }.map { it.name })
            assertEquals(persisted.parameters, runtime.parameters)
            assertTrue(persisted.isEnumerable)
            assertTrue(persisted.supportsPaging)
            assertTrue(persisted.supportsSorting)
        }
    }

    @Test
    fun `Kotlin query default metadata is deterministic across manifest and runtime descriptors`() {
        val manifest = loadManifest()
        val runtimeQueries = ContractTestsArcArtifactModule().queryPerformers.associate { performer ->
            performer.descriptor.fullyQualifiedName to performer.descriptor
        }
        val persisted = manifest.queries.single { query -> query.name == "defaulted" }
        val runtime = runtimeQueries.getValue(persisted.fullyQualifiedName)

        assertEquals(
            listOf(false, true, false, true, false, true, false),
            persisted.parameters.map { parameter -> parameter.hasDefault }
        )
        assertEquals(persisted.parameters, runtime.parameters)
        assertTrue(
            manifest.queries.filter { query -> query.declaringTypeName.endsWith("JavaQueryReadModel") }
                .flatMap { query -> query.parameters }
                .none { parameter -> parameter.hasDefault }
        )
    }

    @Test
    fun `direct and concept temporal descriptors preserve exact JVM names across manifest and runtime metadata`() {
        val manifest = loadManifest()
        val module = ContractTestsArcArtifactModule()
        val directJvmTypes = listOf(
            "identifier" to "java.util.UUID",
            "date" to "java.time.LocalDate",
            "time" to "java.time.LocalTime"
        )

        listOf("KotlinTemporalCommand", "JavaTemporalCommand").forEach { commandName ->
            val persisted = manifest.commands.single { descriptor -> descriptor.name == commandName }
            val runtime = module.commandHandlers.single { handler -> handler.metadata.name == commandName }.metadata

            assertEquals(directJvmTypes, persisted.properties.map { property -> property.name to property.typeName })
            assertEquals(persisted.name, runtime.name)
            assertEquals(persisted.typeName, runtime.typeName)
            assertEquals(persisted.properties, runtime.properties)
            assertEquals(persisted.responseTypeName, runtime.responseTypeName)
            assertEquals(persisted.responseValues, runtime.responseValues)
        }
        assertEquals(
            "io.cratis.arc.contracts.fixtures.KotlinTemporalResult",
            manifest.commands.single { descriptor -> descriptor.name == "KotlinTemporalCommand" }.responseTypeName
        )
        assertEquals(
            "io.cratis.arc.contracts.fixtures.JavaTemporalResult",
            manifest.commands.single { descriptor -> descriptor.name == "JavaTemporalCommand" }.responseTypeName
        )

        listOf(
            "KotlinTemporalResult",
            "JavaTemporalResult",
            "KotlinTemporalReadModel",
            "JavaTemporalReadModel"
        ).forEach { typeName ->
            val persisted = manifest.types.single { descriptor -> descriptor.name == typeName }
            val runtime = module.types.single { descriptor -> descriptor.name == typeName }

            assertEquals(directJvmTypes, persisted.properties.map { property -> property.name to property.typeName })
            assertEquals(persisted.fullyQualifiedName, runtime.fullyQualifiedName)
            assertEquals(persisted.location, runtime.location)
            assertEquals(persisted.properties, runtime.properties)
            assertEquals(persisted.baseTypeName, runtime.baseTypeName)
            assertEquals(persisted.derivedTypeId, runtime.derivedTypeId)
        }

        mapOf(
            "findKotlinTemporal" to "io.cratis.arc.contracts.fixtures.KotlinTemporalReadModel",
            "findJavaTemporal" to "io.cratis.arc.contracts.fixtures.JavaTemporalReadModel"
        ).forEach { (queryName, declaringTypeName) ->
            val persisted = manifest.queries.single { descriptor -> descriptor.name == queryName }
            val runtime = module.queryPerformers.single { performer -> performer.descriptor.name == queryName }.descriptor

            assertEquals(declaringTypeName, persisted.declaringTypeName)
            assertEquals(declaringTypeName, persisted.returnTypeName)
            assertEquals(directJvmTypes, persisted.parameters.map { parameter -> parameter.name to parameter.typeName })
            assertEquals(persisted.fullyQualifiedName, runtime.fullyQualifiedName)
            assertEquals(persisted.declaringTypeName, runtime.declaringTypeName)
            assertEquals(persisted.returnTypeName, runtime.returnTypeName)
            assertEquals(persisted.parameters, runtime.parameters)
            assertEquals(persisted.isEnumerable, runtime.isEnumerable)
            assertEquals(persisted.transport, runtime.transport)
        }

        val conceptModel = manifest.types.single { descriptor -> descriptor.name == "ConceptTemporalReadModel" }
        val runtimeConceptModel = module.types.single { descriptor -> descriptor.name == "ConceptTemporalReadModel" }
        val conceptJvmTypes = listOf(
            "identifier" to "io.cratis.arc.contracts.fixtures.OrderId",
            "date" to "io.cratis.arc.contracts.fixtures.DeliveryDate",
            "time" to "io.cratis.arc.contracts.fixtures.DeliveryTime",
            "javaIdentifier" to "io.cratis.arc.contracts.fixtures.JavaOrderId",
            "javaDate" to "io.cratis.arc.contracts.fixtures.JavaDeliveryDate",
            "javaTime" to "io.cratis.arc.contracts.fixtures.JavaDeliveryTime"
        )
        assertEquals(conceptJvmTypes, conceptModel.properties.map { property -> property.name to property.typeName })
        assertEquals(conceptModel.properties, runtimeConceptModel.properties)

        val conceptQuery = manifest.queries.single { descriptor -> descriptor.name == "findConceptTemporal" }
        val runtimeConceptQuery = module.queryPerformers.single {
            performer -> performer.descriptor.name == "findConceptTemporal"
        }.descriptor
        assertEquals(conceptJvmTypes, conceptQuery.parameters.map { parameter -> parameter.name to parameter.typeName })
        assertEquals("io.cratis.arc.contracts.fixtures.ConceptTemporalReadModel", conceptQuery.returnTypeName)
        assertEquals(conceptQuery.parameters, runtimeConceptQuery.parameters)
        assertEquals(conceptQuery.returnTypeName, runtimeConceptQuery.returnTypeName)

        assertEquals(5, manifest.formatVersion)
        assertEquals(ArcArtifactManifest.CURRENT_FORMAT_VERSION, manifest.formatVersion)
    }

    @Test
    fun `Kotlin and Java map properties preserve canonical recursive shapes and empty values`() {
        val manifest = loadManifest()
        val runtimeCommands = ContractTestsArcArtifactModule().commandHandlers.associate { handler ->
            handler.metadata.name to handler.metadata
        }

        listOf("KotlinMapMetadataCommand", "JavaMapMetadataCommand").forEach { commandName ->
            val persisted = manifest.commands.single { descriptor -> descriptor.name == commandName }
            val runtime = runtimeCommands.getValue(commandName)
            assertEquals(persisted.properties, runtime.properties)

            val properties = persisted.properties.associateBy { property -> property.name }
            val strings = properties.getValue("strings").shape
            assertEquals(TypeShapeKind.MAP, strings.kind)
            assertEquals(MapKeyCodec.STRING, strings.keyCodec)
            assertEquals(TypeShapeKind.VALUE, strings.keyShape?.kind)
            assertTrue(strings.keyShape?.typeName in setOf("kotlin.String", "java.lang.String"))
            assertTrue(strings.valueShape?.typeName in setOf("kotlin.String", "java.lang.String"))

            val numbers = properties.getValue("numbers").shape
            assertEquals(TypeShapeKind.SEQUENCE, numbers.valueShape?.kind)
            assertEquals(SequenceKind.LIST, numbers.valueShape?.sequenceKind)
            assertTrue(numbers.valueShape?.elementShape?.typeName in setOf("kotlin.Int", "java.lang.Integer"))

            val nested = properties.getValue("nested").shape
            assertEquals(TypeShapeKind.MAP, nested.valueShape?.kind)
            assertTrue(nested.valueShape?.valueShape?.typeName in setOf("kotlin.Boolean", "java.lang.Boolean"))
            val optional = properties.getValue("optional").shape
            assertEquals(TypeShapeKind.MAP, optional.kind)
            assertTrue(optional.nullable)
            assertFalse(requireNotNull(optional.valueShape).nullable)
        }

        val runtimeTypes = ContractTestsArcArtifactModule().types.associateBy { descriptor -> descriptor.name }
        listOf("KotlinMapReadModel", "JavaMapReadModel").forEach { typeName ->
            val persisted = manifest.types.single { descriptor -> descriptor.name == typeName }
            val runtime = runtimeTypes.getValue(typeName)
            assertEquals(persisted.properties, runtime.properties)
            assertEquals(TypeShapeKind.MAP, persisted.properties.single { it.name == "strings" }.shape.kind)
            assertEquals(TypeShapeKind.SEQUENCE, persisted.properties.single { it.name == "numbers" }.shape.valueShape?.kind)
            assertEquals(TypeShapeKind.MAP, persisted.properties.single { it.name == "nested" }.shape.valueShape?.kind)
            assertTrue(persisted.properties.single { it.name == "optional" }.shape.nullable)
        }

        assertTrue(io.cratis.arc.contracts.fixtures.KotlinMapMetadataCommand().strings.isEmpty())
        assertTrue(io.cratis.arc.contracts.fixtures.JavaMapMetadataCommand().strings().isEmpty())
    }

    @Test
    fun `concept descriptors preserve every supported scalar underlying type`() {
        val concepts = loadManifest().concepts.associate { descriptor ->
            descriptor.name to descriptor.underlyingTypeName
        }

        assertEquals("kotlin.String", concepts.getValue("CustomerName"))
        assertEquals("kotlin.Int", concepts.getValue("Quantity"))
        assertEquals("java.util.UUID", concepts.getValue("OrderId"))
        assertEquals("java.time.LocalDate", concepts.getValue("DeliveryDate"))
        assertEquals("java.time.LocalTime", concepts.getValue("DeliveryTime"))
        assertEquals("io.cratis.arc.contracts.fixtures.FixtureState", concepts.getValue("StateCode"))
        assertEquals("java.lang.String", concepts.getValue("JavaCustomerCode"))
        assertEquals("java.lang.Integer", concepts.getValue("JavaQuantity"))
        assertEquals("java.util.UUID", concepts.getValue("JavaOrderId"))
        assertEquals("java.time.LocalDate", concepts.getValue("JavaDeliveryDate"))
        assertEquals("java.time.LocalTime", concepts.getValue("JavaDeliveryTime"))
        assertEquals("io.cratis.arc.contracts.fixtures.JavaFixtureState", concepts.getValue("JavaStateCode"))
    }

    @Test
    fun `generated runtime module carries the same deterministic metadata graph`() {
        val module = ContractTestsArcArtifactModule()
        val manifest = loadManifest()

        assertEquals(manifest.types.map { it.fullyQualifiedName }, module.types.map { it.fullyQualifiedName })
        assertEquals(manifest.interfaces.map { it.fullyQualifiedName }, module.interfaces.map { it.fullyQualifiedName })
        assertEquals(manifest.enums.map { it.fullyQualifiedName }, module.enums.map { it.fullyQualifiedName })
        assertEquals(manifest.concepts, module.concepts)
        val mapper = ArcObjectMapper.create()
        assertEquals(
            mapper.writeValueAsString(manifest.queries),
            mapper.writeValueAsString(module.queryPerformers.map { performer -> performer.descriptor })
        )
    }

    private fun responseValues(
        descriptor: io.cratis.arc.metadata.CommandDescriptor
    ): List<Triple<String, Boolean, CommandResponseValueDisposition>> = descriptor.responseValues.map { value ->
        Triple(value.typeName, value.isEnumerable, value.disposition)
    }

    private fun rules(rules: List<ValidationRuleDescriptor>): List<List<Any?>> = rules.map { rule ->
        listOf(rule.ruleName, rule.arguments.map(Any::toString), rule.message)
    }

    private fun loadManifest(): ArcArtifactManifest {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("META-INF/cratis/arc/ContractTests.json"))
        return stream.use { ArcObjectMapper.create().readValue(it, ArcArtifactManifest::class.java) }
    }
}
