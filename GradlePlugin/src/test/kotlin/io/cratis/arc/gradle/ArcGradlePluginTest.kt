// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.ApiEndpointOptions
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ConceptDescriptor
import io.cratis.arc.metadata.EnumDescriptor
import io.cratis.arc.metadata.EnumMemberDescriptor
import io.cratis.arc.metadata.InterfaceDescriptor
import io.cratis.arc.metadata.MapKeyCodec
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeDescriptor
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.TypeShapeKind
import io.cratis.arc.metadata.ValidationRuleDescriptor
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryTransportType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ArcGradlePluginTest {
    private companion object {
        const val DOTNET_ENUMERABLE_COMMAND_PATH = "Commands/CreateFixtures.ts"
        const val DOTNET_ENUMERABLE_COMMAND_SCALAR_GENERIC =
            "export class CreateFixtures extends Command<ICreateFixtures, FixtureModel> implements ICreateFixtures {"
        const val DOTNET_ENUMERABLE_COMMAND_ARRAY_GENERIC =
            "export class CreateFixtures extends Command<ICreateFixtures, FixtureModel[]> implements ICreateFixtures {"
    }

    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `discovers manifests from directories and jars deterministically`() {
        val directory = temporaryDirectory.resolve("classes")
        val directoryManifest = ArcArtifactManifest("DirectoryModule")
        writeManifest(directory.resolve("META-INF/cratis/arc/directory.json"), directoryManifest)
        val jar = temporaryDirectory.resolve("dependency.jar")
        val jarManifest = ArcArtifactManifest("JarModule")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("META-INF/cratis/arc/jar.json"))
            output.write(ArcObjectMapper.create().writeValueAsBytes(jarManifest))
            output.closeEntry()
        }

        val discovered = ArcManifestDiscovery.discover(listOf(jar.toFile(), directory.toFile()))

        assertEquals(listOf("DirectoryModule", "JarModule"), discovered.map { it.manifest.moduleName })
    }

    @Test
    fun `merge includes interfaces once in deterministic order`() {
        val shared = InterfaceDescriptor("Shared", "sample.Shared")
        val first = ArcArtifactManifest("First", interfaces = listOf(shared))
        val second = ArcArtifactManifest(
            "Second",
            interfaces = listOf(InterfaceDescriptor("Other", "sample.Other"), shared)
        )

        val merged = ArcManifestDiscovery.merge(
            listOf(DiscoveredArcManifest("second", second), DiscoveredArcManifest("first", first))
        )

        assertEquals(listOf("sample.Other", "sample.Shared"), merged.interfaces.map { it.fullyQualifiedName })
    }

    @Test
    fun `rejects unsupported manifest versions and module collisions`() {
        val one = temporaryDirectory.resolve("one")
        val two = temporaryDirectory.resolve("two")
        writeManifest(one.resolve("META-INF/cratis/arc/one.json"), ArcArtifactManifest("Same"))
        writeManifest(two.resolve("META-INF/cratis/arc/two.json"), ArcArtifactManifest("Same"))
        assertThrows(GradleException::class.java) { ArcManifestDiscovery.discover(listOf(one.toFile(), two.toFile())) }

        val bad = temporaryDirectory.resolve("bad")
        writeManifest(bad.resolve("META-INF/cratis/arc/bad.json"), ArcArtifactManifest("Bad", formatVersion = 99))
        assertThrows(GradleException::class.java) { ArcManifestDiscovery.discover(listOf(bad.toFile())) }
    }

    @Test
    fun `rejects unversioned directory manifests with their source path`() {
        val manifest = temporaryDirectory.resolve("unversioned/META-INF/cratis/arc/unversioned.json")
        writeRawManifest(manifest, """{"moduleName":"Unversioned"}""")

        val exception = assertThrows(GradleException::class.java) {
            ArcManifestDiscovery.discover(listOf(temporaryDirectory.resolve("unversioned").toFile()))
        }

        assertTrue(exception.message.orEmpty().contains(manifest.toString()))
        assertTrue(exception.message.orEmpty().contains("explicit numeric formatVersion=5"))
    }

    @Test
    fun `rejects format 5 manifests containing only legacy flat shape metadata`() {
        val root = temporaryDirectory.resolve("legacy-only")
        val manifest = root.resolve("META-INF/cratis/arc/legacy.json")
        writeRawManifest(
            manifest,
            """
            {
              "formatVersion": 5,
              "moduleName": "LegacyOnly",
              "commands": [{
                "name": "Run",
                "typeName": "sample.Run",
                "properties": [{"name": "value", "typeName": "kotlin.String"}]
              }]
            }
            """.trimIndent()
        )

        val exception = assertThrows(GradleException::class.java) {
            ArcManifestDiscovery.discover(listOf(root.toFile()))
        }

        assertTrue(exception.message.orEmpty().contains("legacy field(s) typeName"))
        assertTrue(exception.message.orEmpty().contains("commands[0].properties[0]"))
        assertTrue(exception.message.orEmpty().contains(manifest.toString()))
    }

    @Test
    fun `rejects format 5 jar manifests mixing canonical and legacy shape metadata`() {
        val jar = temporaryDirectory.resolve("mixed.jar")
        writeJarManifest(
            jar,
            "META-INF/cratis/arc/mixed.json",
            """
            {
              "formatVersion": 5,
              "moduleName": "Mixed",
              "commands": [{
                "name": "Run",
                "typeName": "sample.Run",
                "responseValues": [{
                  "shape": {"kind": "VALUE", "nullable": false, "typeName": "kotlin.String"},
                  "typeName": "kotlin.String",
                  "disposition": "CLIENT"
                }]
              }]
            }
            """.trimIndent()
        )

        val exception = assertThrows(GradleException::class.java) {
            ArcManifestDiscovery.discover(listOf(jar.toFile()))
        }

        assertTrue(exception.message.orEmpty().contains("legacy field(s) typeName"))
        assertTrue(exception.message.orEmpty().contains("commands[0].responseValues[0]"))
        assertTrue(exception.message.orEmpty().contains("${jar}!/META-INF/cratis/arc/mixed.json"))
    }

    @Test
    fun `discovers format 5 manifests containing canonical shapes on all typed nodes`() {
        val root = temporaryDirectory.resolve("canonical")
        val manifest = root.resolve("META-INF/cratis/arc/canonical.json")
        val valueShape = """{"kind":"VALUE","nullable":false,"typeName":"kotlin.String"}"""
        writeRawManifest(
            manifest,
            """
            {
              "formatVersion": 5,
              "moduleName": "Canonical",
              "commands": [{
                "name": "Run",
                "typeName": "sample.Run",
                "properties": [{"name": "value", "shape": $valueShape}],
                "responseValues": [{"shape": $valueShape, "disposition": "CLIENT"}]
              }],
              "queries": [{
                "name": "find",
                "declaringTypeName": "sample.Queries",
                "returnShape": $valueShape,
                "parameters": [{"name": "filter", "shape": $valueShape, "source": "CLIENT", "hasDefault": false}]
              }],
              "types": [{
                "name": "Model",
                "fullyQualifiedName": "sample.Model",
                "properties": [{"name": "value", "shape": $valueShape}]
              }],
              "interfaces": [{
                "name": "Contract",
                "fullyQualifiedName": "sample.Contract",
                "properties": [{"name": "value", "shape": $valueShape}]
              }]
            }
            """.trimIndent()
        )

        val discovered = ArcManifestDiscovery.discover(listOf(root.toFile()))

        assertEquals("Canonical", discovered.single().manifest.moduleName)
        assertEquals(TypeShapeKind.VALUE, discovered.single().manifest.queries.single().returnShape.kind)
    }

    @Test
    fun `canonical query parameter nodes require an explicit source`() {
        val root = temporaryDirectory.resolve("missing-parameter-source")
        val manifest = root.resolve("META-INF/cratis/arc/missing-source.json")
        writeRawManifest(
            manifest,
            """
            {
              "formatVersion": 5,
              "moduleName": "MissingSource",
              "queries": [{
                "name": "find",
                "declaringTypeName": "sample.Queries",
                "returnShape": {"kind":"VALUE","nullable":false,"typeName":"kotlin.String"},
                "parameters": [{
                  "name": "filter",
                  "shape": {"kind":"VALUE","nullable":false,"typeName":"kotlin.String"}
                }]
              }]
            }
            """.trimIndent()
        )

        val exception = assertThrows(GradleException::class.java) {
            ArcManifestDiscovery.discover(listOf(root.toFile()))
        }

        assertTrue(exception.message.orEmpty().contains("missing canonical source metadata"))
        assertTrue(exception.message.orEmpty().contains("queries[0].parameters[0]"))
    }

    @Test
    fun `legacy programmatic parameter constructors serialize a canonical client source`() {
        val root = temporaryDirectory.resolve("programmatic-parameter-source")
        val manifest = root.resolve("META-INF/cratis/arc/programmatic.json")
        writeManifest(
            manifest,
            ArcArtifactManifest(
                "Programmatic",
                queries = listOf(
                    QueryDescriptor(
                        "find",
                        "sample.Queries",
                        "kotlin.String",
                        listOf(ParameterDescriptor("filter", "kotlin.String"))
                    )
                )
            )
        )

        val discovered = ArcManifestDiscovery.discover(listOf(root.toFile())).single().manifest

        assertEquals(QueryParameterSource.CLIENT, discovered.queries.single().parameters.single().source)
    }

    @Test
    fun `canonical manifest ingestion requires boolean query default metadata`() {
        listOf("", ", \"hasDefault\": null", ", \"hasDefault\": \"false\"").forEachIndexed {
            index, defaultField ->
            val root = temporaryDirectory.resolve("query-default-metadata-$index")
            val manifest = root.resolve("META-INF/cratis/arc/query.json")
            val valueShape = """{"kind":"VALUE","nullable":false,"typeName":"kotlin.String"}"""
            writeRawManifest(
                manifest,
                """
                {
                  "formatVersion":5,
                  "moduleName":"QueryDefaults$index",
                  "queries":[{
                    "name":"find", "declaringTypeName":"sample.Queries",
                    "returnShape":$valueShape,
                    "parameters":[{"name":"value","shape":$valueShape,"source":"CLIENT"$defaultField}]
                  }]
                }
                """.trimIndent()
            )

            val exception = assertThrows(GradleException::class.java) {
                ArcManifestDiscovery.discover(listOf(root.toFile()))
            }
            assertTrue(exception.message.orEmpty().contains("canonical boolean hasDefault"))
        }
    }

    @Test
    fun `canonical manifest ingestion rejects unsafe map leaves and map transport contexts`() {
        val unsafeLeafRoot = temporaryDirectory.resolve("unsafe-map-leaf")
        val unsafeLeafManifest = unsafeLeafRoot.resolve("META-INF/cratis/arc/unsafe.json")
        writeRawManifest(
            unsafeLeafManifest,
            """
            {
              "formatVersion": 5,
              "moduleName": "UnsafeLeaf",
              "commands": [{
                "name": "Run",
                "typeName": "sample.Run",
                "properties": [{
                  "name": "values",
                  "shape": {
                    "kind": "MAP", "nullable": false, "keyCodec": "STRING",
                    "keyShape": {"kind":"VALUE","nullable":false,"typeName":"kotlin.String"},
                    "valueShape": {"kind":"VALUE","nullable":false,"typeName":"kotlin.Double"}
                  }
                }]
              }]
            }
            """.trimIndent()
        )
        val unsafeLeaf = assertThrows(GradleException::class.java) {
            ArcManifestDiscovery.discover(listOf(unsafeLeafRoot.toFile()))
        }
        assertTrue(unsafeLeaf.message.orEmpty().contains("unsupported map value leaf 'kotlin.Double'"))

        val queryRoot = temporaryDirectory.resolve("unsafe-map-query")
        val queryManifest = queryRoot.resolve("META-INF/cratis/arc/unsafe-query.json")
        val mapShape = """{
          "kind":"MAP", "nullable":false, "keyCodec":"STRING",
          "keyShape":{"kind":"VALUE","nullable":false,"typeName":"kotlin.String"},
          "valueShape":{"kind":"VALUE","nullable":false,"typeName":"kotlin.String"}
        }"""
        writeRawManifest(
            queryManifest,
            """
            {
              "formatVersion":5,
              "moduleName":"UnsafeQuery",
              "queries":[{
                "name":"find", "declaringTypeName":"sample.Queries",
                "returnShape":{"kind":"VALUE","nullable":false,"typeName":"kotlin.String"},
                "parameters":[{"name":"values","shape":$mapShape,"source":"CLIENT"}]
              }]
            }
            """.trimIndent()
        )
        val unsafeQuery = assertThrows(GradleException::class.java) {
            ArcManifestDiscovery.discover(listOf(queryRoot.toFile()))
        }
        assertTrue(unsafeQuery.message.orEmpty().contains("uses a map in unsupported context"))
    }

    @Test
    fun `generates compatible command query model enum and indexes`() {
        val output = temporaryDirectory.resolve("generated")
        val state = EnumDescriptor(
            "FixtureState",
            "sample.models.FixtureState",
            listOf("sample", "models"),
            listOf(EnumMemberDescriptor("New", 0), EnumMemberDescriptor("Active", 1))
        )
        val model = TypeDescriptor(
            "FixtureModel",
            "sample.models.FixtureModel",
            listOf("sample", "models"),
            listOf(
                PropertyDescriptor("identifier", "java.util.UUID"),
                PropertyDescriptor("updatedAt", "java.time.Instant"),
                PropertyDescriptor("state", state.fullyQualifiedName)
            )
        )
        val command = CommandDescriptor(
            "CreateFixture",
            "sample.commands.CreateFixture",
            listOf(
                PropertyDescriptor("fixtureId", "java.util.UUID", isCommandKey = true),
                PropertyDescriptor("states", "kotlin.collections.List<sample.models.FixtureState>", isEnumerable = true, elementTypeName = state.fullyQualifiedName)
            ),
            location = listOf("sample", "commands"),
            responseTypeName = model.fullyQualifiedName,
            responseIsEnumerable = true
        )
        val query = QueryDescriptor(
            "byId",
            model.fullyQualifiedName,
            model.fullyQualifiedName,
            listOf(ParameterDescriptor("identifier", "java.util.UUID")),
            fullyQualifiedName = "sample.models.FixtureModel.byId",
            location = listOf("sample", "models"),
            explicitPath = "/MixedCase/By_Id",
            queryHttpMethod = QueryHttpMethodType.QUERY
        )
        val allQuery = QueryDescriptor(
            "all",
            model.fullyQualifiedName,
            model.fullyQualifiedName,
            fullyQualifiedName = "sample.models.FixtureModel.all",
            location = listOf("sample", "models"),
            isEnumerable = true,
            supportsPaging = true,
            supportsSorting = true
        )
        generate(
            output,
            MergedArcArtifacts(listOf(command), listOf(query, allQuery), listOf(model), listOf(state)),
            ApiEndpointOptions(segmentsToSkipForRoute = 1)
        )

        val commandText = Files.readString(output.resolve("commands/CreateFixture.ts"))
        assertTrue(commandText.contains("extends Command<ICreateFixture, FixtureModel[]>"))
        assertTrue(commandText.contains("super(FixtureModel, true);"))
        assertTrue(commandText.lineSequence().drop(1).take(4).joinToString("\n").contains("**DO NOT EDIT**"))
        assertTrue(commandText.contains("readonly route: string = '/api/commands/create-fixture';"))
        assertTrue(commandText.contains("import { Guid } from '@cratis/fundamentals';"))
        assertTrue(commandText.contains("new PropertyDescriptor('fixtureId', Guid, false)"))
        assertTrue(commandText.contains("new PropertyDescriptor('states', Number, false)"))
        assertTrue(commandText.contains("get requestParameters(): string[] {\n        return [\n        ];"))
        assertTrue(commandText.contains("propertyChanged('states')"))
        assertFalse(commandText.contains("sanitizeArcStringMap"))
        assertTrue(commandText.contains("import type { FixtureState } from '../models/FixtureState';"))
        assertTrue(commandText.contains("import { FixtureModel } from '../models/FixtureModel';"))

        val queryText = Files.readString(output.resolve("models/ById.ts"))
        assertTrue(queryText.contains("extends QueryFor<FixtureModel, ByIdParameters>"))
        assertTrue(queryText.contains("readonly route: string = '/MixedCase/By_Id';"))
        assertTrue(queryText.contains("readonly queryName: string = 'sample.models.FixtureModel.byId';"))
        assertTrue(queryText.contains("this.setHttpMethod(QueryHttpMethod.Query);"))
        assertTrue(queryText.contains("import { Guid } from '@cratis/fundamentals';"))
        assertTrue(queryText.contains("new ParameterDescriptor('identifier', Guid, false)"))
        assertTrue(queryText.contains("static useSuspense"))
        assertTrue(queryText.contains("static when"))
        val allQueryText = Files.readString(output.resolve("models/All.ts"))
        assertTrue(allQueryText.contains("extends QueryFor<FixtureModel[]>"))
        assertTrue(allQueryText.contains("static useWithPaging"))
        assertTrue(allQueryText.contains("static useSuspenseWithPaging"))
        assertFalse(allQueryText.contains("QueryHttpMethod"))
        assertTrue(queryText.contains("PerformQuery<ByIdParameters>"))

        val modelText = Files.readString(output.resolve("models/FixtureModel.ts"))
        assertTrue(modelText.contains("import { field, Guid } from '@cratis/fundamentals';"))
        assertTrue(modelText.contains("@field(Guid)"))
        assertTrue(modelText.contains("identifier!: Guid;"))
        assertTrue(modelText.contains("@field(Date)"))
        assertTrue(modelText.contains("updatedAt!: Date;"))
        assertTrue(modelText.contains("@field(Number)"))
        val enumText = Files.readString(output.resolve("models/FixtureState.ts"))
        assertTrue(enumText.contains("new = 0"))
        assertTrue(enumText.contains("active = 1"))
        assertTrue(Files.readString(output.resolve("models/index.ts")).contains("export * from './ById';"))
    }

    @Test
    fun `command response generics distinguish scalar enumerable and handled-only responses`() {
        val location = listOf("sample", "commands")
        val response = TypeDescriptor("CommandResponse", "sample.commands.CommandResponse", location)
        val commands = listOf(
            CommandDescriptor(
                "ScalarResponseCommand",
                "sample.commands.ScalarResponseCommand",
                location = location,
                responseTypeName = response.fullyQualifiedName
            ),
            CommandDescriptor(
                "EnumerableResponseCommand",
                "sample.commands.EnumerableResponseCommand",
                location = location,
                responseTypeName = response.fullyQualifiedName,
                responseIsEnumerable = true
            ),
            CommandDescriptor(
                "HandledOnlyResponseCommand",
                "sample.commands.HandledOnlyResponseCommand",
                location = location
            )
        )
        val output = temporaryDirectory.resolve("command-response-generics")

        generate(output, MergedArcArtifacts(commands, emptyList(), listOf(response), emptyList()))

        val scalar = Files.readString(output.resolve("commands/ScalarResponseCommand.ts"))
        assertTrue(scalar.contains("extends Command<IScalarResponseCommand, CommandResponse>"))
        assertTrue(scalar.contains("super(CommandResponse, false);"))
        assertFalse(scalar.contains("extends Command<IScalarResponseCommand, CommandResponse[]>"))

        val enumerable = Files.readString(output.resolve("commands/EnumerableResponseCommand.ts"))
        assertTrue(enumerable.contains("extends Command<IEnumerableResponseCommand, CommandResponse[]>"))
        assertTrue(enumerable.contains("super(CommandResponse, true);"))

        val handledOnly = Files.readString(output.resolve("commands/HandledOnlyResponseCommand.ts"))
        assertTrue(handledOnly.contains("extends Command<IHandledOnlyResponseCommand>"))
        assertTrue(handledOnly.contains("super(Object, false);"))
        assertFalse(handledOnly.contains("extends Command<IHandledOnlyResponseCommand,"))
    }

    @Test
    fun `model interface enum and flags render exact dotnet template contracts`() {
        val shape = InterfaceDescriptor(
            "Shape",
            "sample.contracts.Shape",
            listOf("sample", "contracts"),
            listOf(PropertyDescriptor("displayName", "kotlin.String"))
        )
        val base = TypeDescriptor(
            "ShapeBase",
            "sample.models.ShapeBase",
            listOf("sample", "models"),
            listOf(PropertyDescriptor("createdAt", "java.time.Instant"))
        )
        val circle = TypeDescriptor(
            "Circle",
            "sample.models.Circle",
            listOf("sample", "models"),
            listOf(PropertyDescriptor("radius", "kotlin.Double")),
            base.fullyQualifiedName,
            "circle"
        )
        val holder = TypeDescriptor(
            "ShapeHolder",
            "sample.models.ShapeHolder",
            listOf("sample", "models"),
            listOf(
                PropertyDescriptor(
                    "shapes",
                    "kotlin.collections.List<sample.contracts.Shape>",
                    isEnumerable = true,
                    elementTypeName = shape.fullyQualifiedName,
                    derivatives = listOf(circle.fullyQualifiedName)
                )
            )
        )
        val state = EnumDescriptor(
            "State",
            "sample.models.State",
            listOf("sample", "models"),
            listOf(EnumMemberDescriptor("Unknown", 0), EnumMemberDescriptor("Ready", 17))
        )
        val permissions = EnumDescriptor(
            "Permissions",
            "sample.models.Permissions",
            listOf("sample", "models"),
            listOf(
                EnumMemberDescriptor("None", 0),
                EnumMemberDescriptor("Read", 1),
                EnumMemberDescriptor("Write", 2)
            ),
            isFlags = true
        )
        val output = temporaryDirectory.resolve("dotnet-template-golden")

        generate(
            output,
            MergedArcArtifacts(
                emptyList(),
                emptyList(),
                listOf(base, circle, holder),
                listOf(state, permissions),
                listOf(shape)
            )
        )

        assertGolden("type.golden", output.resolve("models/Circle.ts"))
        assertGolden("type-derivatives.golden", output.resolve("models/ShapeHolder.ts"))
        assertGolden("interface.golden", output.resolve("contracts/Shape.ts"))
        assertGolden("enum.golden", output.resolve("models/State.ts"))
        assertGolden("flags-enum.golden", output.resolve("models/Permissions.ts"))
    }

    @Test
    fun `string keyed map properties render recursive records with Object runtime metadata`() {
        val strings = TypeShapeDescriptor.map(
            TypeShapeDescriptor.value("kotlin.String"),
            TypeShapeDescriptor.value("kotlin.String"),
            MapKeyCodec.STRING
        )
        val numbers = TypeShapeDescriptor.map(
            TypeShapeDescriptor.value("java.lang.String"),
            TypeShapeDescriptor.sequence(SequenceKind.LIST, TypeShapeDescriptor.value("java.lang.Integer"))
        )
        val nested = TypeShapeDescriptor.map(
            TypeShapeDescriptor.value("kotlin.String"),
            TypeShapeDescriptor.map(
                TypeShapeDescriptor.value("kotlin.String"),
                TypeShapeDescriptor.value("kotlin.Boolean")
            ),
            nullable = true
        )
        val properties = listOf(
            PropertyDescriptor("strings", strings),
            PropertyDescriptor("numbers", numbers),
            PropertyDescriptor("nested", nested)
        )
        val command = CommandDescriptor("MapCommand", "sample.MapCommand", properties)
        val model = TypeDescriptor("MapModel", "sample.MapModel", emptyList(), properties)
        val contract = InterfaceDescriptor("MapContract", "sample.MapContract", emptyList(), properties)
        val output = temporaryDirectory.resolve("map-shapes")

        generate(output, MergedArcArtifacts(listOf(command), emptyList(), listOf(model), emptyList(), listOf(contract)))

        val generatedCommand = Files.readString(output.resolve("MapCommand.ts"))
        assertTrue(generatedCommand.contains("strings?: Record<string, string>;"))
        assertTrue(generatedCommand.contains("numbers?: Record<string, number[]>;"))
        assertTrue(generatedCommand.contains("nested?: Record<string, Record<string, boolean>>;"))
        assertTrue(generatedCommand.contains("new PropertyDescriptor('strings', Object, false)"))
        assertTrue(generatedCommand.contains("new PropertyDescriptor('nested', Object, true)"))
        assertTrue(generatedCommand.contains("private _nested?: Record<string, Record<string, boolean>>;"))
        assertEquals(1, "function sanitizeArcStringMap".toRegex().findAll(generatedCommand).count())
        assertTrue(generatedCommand.contains("new Set(['__proto__', 'prototype', 'constructor'])"))
        assertTrue(
            generatedCommand.indexOf("sanitizeArcStringMap(value, 'strings')") <
                generatedCommand.indexOf("this.propertyChanged('strings');")
        )
        assertTrue(generatedCommand.contains("Object.getPrototypeOf(value)"))
        assertTrue(generatedCommand.contains("prototype !== Object.prototype && prototype !== null"))
        val generatedModel = Files.readString(output.resolve("MapModel.ts"))
        assertTrue(generatedModel.contains("@field(Object)\n    strings!: Record<string, string>;"))
        assertTrue(generatedModel.contains("@field(Object)\n    nested?: Record<string, Record<string, boolean>>;"))
        assertFalse(generatedModel.contains("sanitizeArcStringMap"))
        val generatedContract = Files.readString(output.resolve("MapContract.ts"))
        assertTrue(generatedContract.contains("numbers: Record<string, number[]>;"))
        assertTrue(generatedContract.contains("nested?: Record<string, Record<string, boolean>>;"))
    }

    @Test
    fun `map metadata consumers reject floating point leaves`() {
        listOf("kotlin.Float", "kotlin.Double", "java.lang.Float", "java.lang.Double", "float", "double").forEach {
            typeName ->
            val command = CommandDescriptor(
                "UnsafeFloatingMap",
                "sample.UnsafeFloatingMap",
                listOf(
                    PropertyDescriptor(
                        "values",
                        TypeShapeDescriptor.map(
                            TypeShapeDescriptor.value("kotlin.String"),
                            TypeShapeDescriptor.value(typeName)
                        )
                    )
                )
            )

            val exception = assertThrows(GradleException::class.java) {
                generate(
                    temporaryDirectory.resolve("floating-map-${typeName.replace('.', '-') }"),
                    MergedArcArtifacts(listOf(command), emptyList(), emptyList(), emptyList())
                )
            }
            assertTrue(exception.message.orEmpty().contains("unsupported value leaf '$typeName'"))
        }
    }

    @Test
    fun `map metadata consumers reject typed model values`() {
        val model = TypeDescriptor("TypedValue", "sample.TypedValue", emptyList())
        val command = CommandDescriptor(
            "UnsafeMap",
            "sample.UnsafeMap",
            listOf(
                PropertyDescriptor(
                    "values",
                    TypeShapeDescriptor.map(
                        TypeShapeDescriptor.value("kotlin.String"),
                        TypeShapeDescriptor.value(model.fullyQualifiedName)
                    )
                )
            )
        )

        val exception = assertThrows(GradleException::class.java) {
            generate(
                temporaryDirectory.resolve("typed-map-value"),
                MergedArcArtifacts(listOf(command), emptyList(), listOf(model), emptyList())
            )
        }

        assertTrue(exception.message.orEmpty().contains("unsupported value leaf 'sample.TypedValue'"))
    }

    @Test
    fun `jvm proxies are byte identical to normalized dotnet proxies`() {
        val output = temporaryDirectory.resolve("cross-runtime-differential")

        generate(
            output,
            crossRuntimeFixtureArtifacts(),
            ApiEndpointOptions(segmentsToSkipForRoute = 1),
            proxySegmentsToSkip = 1
        )

        val expected = expectedProxyTree(resourcePath("/differential/dotnet"))
        val actual = proxyTree(output, normalizeGeneratedHeader = true)
        assertEquals(expected.keys.toList(), actual.keys.toList(), "Generated proxy paths differ from .NET")
        expected.forEach { (path, body) ->
            val actualBody = actual.getValue(path)
            val firstDifference = body.indices.firstOrNull { index ->
                index >= actualBody.length || body[index] != actualBody[index]
            } ?: minOf(body.length, actualBody.length).takeIf { body.length != actualBody.length }
            assertEquals(
                body,
                actualBody,
                "Generated proxy body differs from .NET for $path; expected ${body.length} bytes, " +
                    "actual ${actualBody.length} bytes, first difference $firstDifference"
            )
        }
    }

    @Test
    fun `normalized dotnet differential preserves the proven string key Record fixture exactly`() {
        val expected = expectedProxyTree(resourcePath("/differential/dotnet"))
            .getValue("Models/FixtureModel.ts")

        assertTrue(expected.contains("@field(Object)\n    labelsByCategory!: Record<string, string>;"))
        assertFalse(expected.contains("ValueMap"))
        assertFalse(expected.contains("_entries"))
    }

    @Test
    fun `known dotnet enumerable command generic is normalized on the expected side`() {
        val scalarExpected = "before\n$DOTNET_ENUMERABLE_COMMAND_SCALAR_GENERIC\nafter\n"

        val normalized = normalizeKnownDotNetEnumerableCommandGeneric(DOTNET_ENUMERABLE_COMMAND_PATH, scalarExpected)

        assertEquals("before\n$DOTNET_ENUMERABLE_COMMAND_ARRAY_GENERIC\nafter\n", normalized)
    }

    @Test
    fun `known dotnet enumerable command normalization leaves every other path unchanged`() {
        val unrelatedBody = "before\n$DOTNET_ENUMERABLE_COMMAND_SCALAR_GENERIC\nafter\n"

        assertEquals(
            unrelatedBody,
            normalizeKnownDotNetEnumerableCommandGeneric("Commands/AnotherCommand.ts", unrelatedBody)
        )
    }

    @Test
    fun `normalized dotnet enumerable command accepts correct jvm generic but rejects scalar regression`() {
        val normalizedExpected = normalizeKnownDotNetEnumerableCommandGeneric(
            DOTNET_ENUMERABLE_COMMAND_PATH,
            DOTNET_ENUMERABLE_COMMAND_SCALAR_GENERIC
        )

        assertEquals(DOTNET_ENUMERABLE_COMMAND_ARRAY_GENERIC, normalizedExpected)
        assertNotEquals(normalizedExpected, DOTNET_ENUMERABLE_COMMAND_SCALAR_GENERIC)
    }

    @Test
    fun `known dotnet enumerable command normalization requires exactly one scalar declaration`() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeKnownDotNetEnumerableCommandGeneric(DOTNET_ENUMERABLE_COMMAND_PATH, "unrelated")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeKnownDotNetEnumerableCommandGeneric(
                DOTNET_ENUMERABLE_COMMAND_PATH,
                "$DOTNET_ENUMERABLE_COMMAND_SCALAR_GENERIC\n$DOTNET_ENUMERABLE_COMMAND_SCALAR_GENERIC"
            )
        }
    }

    @Test
    fun `command keys are not request parameters unless explicitly present in the route`() {
        val command = CommandDescriptor(
            "UpdateFixture",
            "sample.UpdateFixture",
            listOf(
                PropertyDescriptor("commandId", "kotlin.String", isCommandKey = true),
                PropertyDescriptor("slug", "kotlin.String")
            )
        )
        val output = temporaryDirectory.resolve("command-route-parameters")

        generate(
            output,
            MergedArcArtifacts(listOf(command), emptyList(), emptyList(), emptyList()),
            ApiEndpointOptions(routePrefix = "api/fixtures/{slug}")
        )

        val generated = Files.readString(output.resolve("UpdateFixture.ts"))
        assertTrue(generated.contains("return [\n            'slug',\n        ];"))
        assertFalse(generated.contains("return [\n            'commandId',"))
    }

    @Test
    fun `nullable query parameters and parameterized paging hooks match Arc declarations`() {
        val model = TypeDescriptor("Result", "sample.Result", listOf("sample"))
        val query = QueryDescriptor(
            "search",
            model.fullyQualifiedName,
            model.fullyQualifiedName,
            listOf(ParameterDescriptor("filter", "kotlin.String", isNullable = true)),
            fullyQualifiedName = "sample.Result.search",
            location = listOf("sample"),
            isEnumerable = true,
            supportsPaging = true,
            supportsSorting = true
        )
        val output = temporaryDirectory.resolve("query-contracts")

        generate(output, MergedArcArtifacts(emptyList(), listOf(query), listOf(model), emptyList()))

        val generated = Files.readString(output.resolve("Search.ts"))
        assertTrue(generated.contains("filter?: string;"))
        assertTrue(generated.contains("filter!: string;"))
        assertTrue(generated.contains("PerformQuery, SetSorting, SetPage, SetPageSize"))
        assertTrue(generated.contains("useQueryWithPaging<Result[], Search>"))
        assertTrue(generated.contains("useSuspenseQueryWithPaging<Result[], Search>"))
    }

    @Test
    fun `defaulted query parameters are optional without emitting server expressions`() {
        val model = TypeDescriptor("Result", "sample.Result", listOf("sample"))
        val parameters = listOf(
            ParameterDescriptor(
                "limit",
                TypeShapeDescriptor.value("kotlin.Int"),
                QueryParameterSource.CLIENT,
                hasDefault = true,
                validationRules = listOf(ValidationRuleDescriptor("notEmpty", message = "Limit is required"))
            ),
            ParameterDescriptor("required", "kotlin.String")
        )
        val queries = listOf(
            QueryDescriptor(
                "search",
                model.fullyQualifiedName,
                model.fullyQualifiedName,
                parameters,
                fullyQualifiedName = "sample.Result.search",
                location = listOf("sample")
            ),
            QueryDescriptor(
                "observe",
                model.fullyQualifiedName,
                model.fullyQualifiedName,
                parameters,
                fullyQualifiedName = "sample.Result.observe",
                location = listOf("sample"),
                transport = QueryTransportType.OBSERVABLE
            )
        )
        val output = temporaryDirectory.resolve("defaulted-query-parameters")

        generate(output, MergedArcArtifacts(emptyList(), queries, listOf(model), emptyList()))

        listOf("Search.ts", "Observe.ts").forEach { fileName ->
            val generated = Files.readString(output.resolve(fileName))
            assertTrue(generated.contains("limit?: number;"), fileName)
            assertTrue(generated.contains("required!: string;"), fileName)
            assertTrue(generated.contains("return [\n            'required',\n        ];"), fileName)
            assertFalse(generated.contains("limit!:"), fileName)
            assertTrue(generated.contains("query.limit === undefined"), fileName)
            assertTrue(generated.contains("member === 'limit' || member.startsWith('limit.')"), fileName)
            assertFalse(generated.contains("= 42"), fileName)
        }
    }

    @Test
    fun `route conflicts include endpoint names after skipped namespace`() {
        val commands = listOf("First", "Second").map {
            CommandDescriptor(it, "company.sample.features.$it", location = listOf("company", "sample", "features"))
        }
        val output = temporaryDirectory.resolve("routes")
        generate(
            output,
            MergedArcArtifacts(commands, emptyList(), emptyList(), emptyList()),
            ApiEndpointOptions("api", 2, false, false, true),
            proxySegmentsToSkip = 2
        )

        assertTrue(Files.readString(output.resolve("features/First.ts")).contains("'/api/features/first'"))
        assertTrue(Files.readString(output.resolve("features/Second.ts")).contains("'/api/features/second'"))
    }

    @Test
    fun `does not rewrite unchanged files and removes only stale generated files`() {
        val output = temporaryDirectory.resolve("stable")
        val model = TypeDescriptor("Value", "sample.Value", listOf("sample"), listOf(PropertyDescriptor("value", "kotlin.String")))
        val artifacts = MergedArcArtifacts(emptyList(), emptyList(), listOf(model), emptyList())
        generate(output, artifacts)
        val generated = output.resolve("Value.ts")
        val initialTime = FileTime.fromMillis(System.currentTimeMillis() - 10_000)
        Files.setLastModifiedTime(generated, initialTime)
        val stale = output.resolve("Stale.ts")
        Files.writeString(stale, "// @generated by Cratis. Source: old.Stale. Hash: ${"A".repeat(64)}\nold\n")
        val handWritten = output.resolve("Custom.ts")
        Files.writeString(handWritten, "export const custom = true;\n")

        generate(output, artifacts)

        assertEquals(initialTime, Files.getLastModifiedTime(generated))
        assertFalse(Files.exists(stale))
        assertTrue(Files.exists(handWritten))
    }

    @Test
    fun `maps supported JVM scalar and temporal types`() {
        val properties = listOf(
            PropertyDescriptor("text", "kotlin.String"),
            PropertyDescriptor("character", "kotlin.Char"),
            PropertyDescriptor("flag", "java.lang.Boolean"),
            PropertyDescriptor("count", "kotlin.Long"),
            PropertyDescriptor("amount", "java.math.BigDecimal"),
            PropertyDescriptor("identifier", "java.util.UUID"),
            PropertyDescriptor("simpleIdentifier", "UUID"),
            PropertyDescriptor("date", "java.time.LocalDate"),
            PropertyDescriptor("simpleDate", "LocalDate"),
            PropertyDescriptor("time", "java.time.LocalTime"),
            PropertyDescriptor("simpleTime", "LocalTime"),
            PropertyDescriptor("dateTime", "java.time.LocalDateTime"),
            PropertyDescriptor("instant", "java.time.Instant"),
            PropertyDescriptor("offset", "java.time.OffsetDateTime"),
            PropertyDescriptor("zoned", "java.time.ZonedDateTime"),
            PropertyDescriptor("duration", "java.time.Duration"),
            PropertyDescriptor("period", "Period"),
            PropertyDescriptor("offsetTime", "java.time.OffsetTime")
        )
        val type = TypeDescriptor("Mappings", "sample.Mappings", listOf("sample"), properties)
        val output = temporaryDirectory.resolve("mappings")
        generate(output, MergedArcArtifacts(emptyList(), emptyList(), listOf(type), emptyList()))

        val generated = Files.readString(output.resolve("Mappings.ts"))
        listOf("text!: string", "character!: string", "flag!: boolean").forEach {
            assertTrue(generated.contains(it))
        }
        listOf("count!: number", "amount!: number").forEach { assertTrue(generated.contains(it)) }
        assertTrue(generated.contains("import { field, DateOnly, Guid, TimeOnly } from '@cratis/fundamentals';"))
        listOf("identifier!: Guid", "simpleIdentifier!: Guid").forEach { assertTrue(generated.contains(it)) }
        listOf("date!: DateOnly", "simpleDate!: DateOnly").forEach { assertTrue(generated.contains(it)) }
        listOf("time!: TimeOnly", "simpleTime!: TimeOnly").forEach { assertTrue(generated.contains(it)) }
        listOf("dateTime!: Date", "instant!: Date", "offset!: Date", "zoned!: Date").forEach {
            assertTrue(generated.contains(it))
        }
        listOf("duration!: string", "period!: string", "offsetTime!: string").forEach {
            assertTrue(generated.contains(it))
        }
    }

    @Test
    fun `external temporal and guid values render across every proxy artifact path`() {
        val location = listOf("sample", "temporal")
        val model = TypeDescriptor(
            "TemporalModel",
            "sample.temporal.TemporalModel",
            location,
            listOf(
                PropertyDescriptor("identifier", "java.util.UUID"),
                PropertyDescriptor("date", "LocalDate"),
                PropertyDescriptor("time", "java.time.LocalTime")
            ),
            derivedTypeId = "temporal"
        )
        val contract = InterfaceDescriptor(
            "TemporalContract",
            "sample.temporal.TemporalContract",
            location,
            listOf(
                PropertyDescriptor("identifier", "UUID"),
                PropertyDescriptor("date", "java.time.LocalDate"),
                PropertyDescriptor("time", "LocalTime")
            )
        )
        val command = CommandDescriptor(
            "ScheduleTemporal",
            "sample.temporal.ScheduleTemporal",
            listOf(
                PropertyDescriptor("identifier", "java.util.UUID"),
                PropertyDescriptor("date", "LocalDate"),
                PropertyDescriptor("time", "java.time.LocalTime")
            ),
            location = location,
            responseTypeName = "java.time.LocalDate"
        )
        val scalarQuery = QueryDescriptor(
            "temporalById",
            model.fullyQualifiedName,
            "LocalDate",
            listOf(
                ParameterDescriptor("identifier", "UUID"),
                ParameterDescriptor("time", "java.time.LocalTime")
            ),
            fullyQualifiedName = "sample.temporal.TemporalModel.temporalById",
            location = location
        )
        val enumerableQuery = QueryDescriptor(
            "temporalIds",
            model.fullyQualifiedName,
            "java.util.UUID",
            listOf(ParameterDescriptor("date", "java.time.LocalDate")),
            fullyQualifiedName = "sample.temporal.TemporalModel.temporalIds",
            location = location,
            isEnumerable = true
        )
        val observableQuery = QueryDescriptor(
            "observeTimes",
            model.fullyQualifiedName,
            "LocalTime",
            listOf(
                ParameterDescriptor("identifier", "java.util.UUID"),
                ParameterDescriptor("date", "LocalDate")
            ),
            fullyQualifiedName = "sample.temporal.TemporalModel.observeTimes",
            location = location,
            transport = QueryTransportType.OBSERVABLE,
            isEnumerable = true
        )
        val output = temporaryDirectory.resolve("external-value-paths")

        generate(
            output,
            MergedArcArtifacts(
                commands = listOf(command),
                queries = listOf(scalarQuery, enumerableQuery, observableQuery),
                types = listOf(model),
                enums = emptyList(),
                interfaces = listOf(contract)
            )
        )

        val modelText = Files.readString(output.resolve("temporal/TemporalModel.ts"))
        assertTrue(
            modelText.contains(
                "import { field, derivedType, DateOnly, Guid, TimeOnly } from '@cratis/fundamentals';"
            )
        )
        assertEquals(1, modelText.lineSequence().count { it.contains("from '@cratis/fundamentals'") })
        assertTrue(modelText.contains("@derivedType('temporal')"))
        assertTrue(modelText.contains("@field(Guid)\n    identifier!: Guid;"))
        assertTrue(modelText.contains("@field(DateOnly)\n    date!: DateOnly;"))
        assertTrue(modelText.contains("@field(TimeOnly)\n    time!: TimeOnly;"))

        val interfaceText = Files.readString(output.resolve("temporal/TemporalContract.ts"))
        assertTrue(interfaceText.contains("import { DateOnly, Guid, TimeOnly } from '@cratis/fundamentals';"))
        assertEquals(1, interfaceText.lineSequence().count { it.contains("from '@cratis/fundamentals'") })
        assertTrue(interfaceText.contains("identifier: Guid;"))
        assertTrue(interfaceText.contains("date: DateOnly;"))
        assertTrue(interfaceText.contains("time: TimeOnly;"))

        val commandText = Files.readString(output.resolve("temporal/ScheduleTemporal.ts"))
        assertTrue(commandText.contains("import { DateOnly, Guid, TimeOnly } from '@cratis/fundamentals';"))
        assertEquals(1, commandText.lineSequence().count { it.contains("from '@cratis/fundamentals'") })
        assertTrue(commandText.contains("extends Command<IScheduleTemporal, DateOnly>"))
        assertTrue(commandText.contains("new PropertyDescriptor('identifier', Guid, false)"))
        assertTrue(commandText.contains("new PropertyDescriptor('date', DateOnly, false)"))
        assertTrue(commandText.contains("new PropertyDescriptor('time', TimeOnly, false)"))
        assertTrue(commandText.contains("super(DateOnly, false);"))

        val scalarQueryText = Files.readString(output.resolve("temporal/TemporalById.ts"))
        assertTrue(scalarQueryText.contains("import { DateOnly, Guid, TimeOnly } from '@cratis/fundamentals';"))
        assertTrue(scalarQueryText.contains("extends QueryFor<DateOnly, TemporalByIdParameters>"))
        assertTrue(scalarQueryText.contains("new ParameterDescriptor('identifier', Guid, false)"))
        assertTrue(scalarQueryText.contains("new ParameterDescriptor('time', TimeOnly, false)"))
        assertTrue(scalarQueryText.contains("super(DateOnly, false);"))

        val enumerableQueryText = Files.readString(output.resolve("temporal/TemporalIds.ts"))
        assertTrue(enumerableQueryText.contains("import { DateOnly, Guid } from '@cratis/fundamentals';"))
        assertTrue(enumerableQueryText.contains("extends QueryFor<Guid[], TemporalIdsParameters>"))
        assertTrue(enumerableQueryText.contains("new ParameterDescriptor('date', DateOnly, false)"))
        assertTrue(enumerableQueryText.contains("super(Guid, true);"))

        val observableQueryText = Files.readString(output.resolve("temporal/ObserveTimes.ts"))
        assertTrue(observableQueryText.contains("import { DateOnly, Guid, TimeOnly } from '@cratis/fundamentals';"))
        assertTrue(observableQueryText.contains("extends ObservableQueryFor<TimeOnly[], ObserveTimesParameters>"))
        assertTrue(observableQueryText.contains("new ParameterDescriptor('identifier', Guid, false)"))
        assertTrue(observableQueryText.contains("new ParameterDescriptor('date', DateOnly, false)"))
        assertTrue(observableQueryText.contains("super(TimeOnly, true);"))
    }

    @Test
    fun `concepts render as underlying types constructors and merged validation without wrapper imports`() {
        val location = listOf("sample", "concepts")
        val fixtureState = EnumDescriptor(
            "FixtureState",
            "sample.concepts.FixtureState",
            location,
            listOf(EnumMemberDescriptor("New", 0), EnumMemberDescriptor("Active", 1))
        )
        val javaFixtureState = EnumDescriptor(
            "JavaFixtureState",
            "sample.concepts.JavaFixtureState",
            location,
            listOf(EnumMemberDescriptor("UNKNOWN", 0), EnumMemberDescriptor("READY", 17))
        )
        val concepts = listOf(
            ConceptDescriptor("CustomerName", "sample.concepts.CustomerName", "kotlin.String"),
            ConceptDescriptor("Quantity", "sample.concepts.Quantity", "kotlin.Int"),
            ConceptDescriptor("OrderId", "sample.concepts.OrderId", "java.util.UUID"),
            ConceptDescriptor("DeliveryDate", "sample.concepts.DeliveryDate", "java.time.LocalDate"),
            ConceptDescriptor("DeliveryTime", "sample.concepts.DeliveryTime", "java.time.LocalTime"),
            ConceptDescriptor("StateCode", "sample.concepts.StateCode", fixtureState.fullyQualifiedName),
            ConceptDescriptor("JavaCustomerCode", "sample.concepts.JavaCustomerCode", "java.lang.String"),
            ConceptDescriptor("JavaQuantity", "sample.concepts.JavaQuantity", "java.lang.Integer"),
            ConceptDescriptor("JavaOrderId", "sample.concepts.JavaOrderId", "java.util.UUID"),
            ConceptDescriptor("JavaDeliveryDate", "sample.concepts.JavaDeliveryDate", "java.time.LocalDate"),
            ConceptDescriptor("JavaDeliveryTime", "sample.concepts.JavaDeliveryTime", "java.time.LocalTime"),
            ConceptDescriptor("JavaStateCode", "sample.concepts.JavaStateCode", javaFixtureState.fullyQualifiedName)
        )
        val properties = concepts.map { concept ->
            PropertyDescriptor(concept.name.replaceFirstChar(Char::lowercaseChar), concept.fullyQualifiedName)
        } + PropertyDescriptor(
            "customerNames",
            "kotlin.collections.List<sample.concepts.CustomerName>",
            isEnumerable = true,
            elementTypeName = "sample.concepts.CustomerName",
            validateRecursively = true
        )
        val commandProperties = properties.map { property ->
            if (property.name != "customerName") {
                property
            } else {
                PropertyDescriptor(
                    property.name,
                    property.typeName,
                    validationRules = listOf(
                        ValidationRuleDescriptor("notNull", message = "Customer name is required"),
                        ValidationRuleDescriptor("notEmpty", message = "Concept value is required"),
                        ValidationRuleDescriptor("length", listOf(2, 40), "Customer name length is invalid")
                    ),
                    validateRecursively = true
                )
            }
        }
        val holder = TypeDescriptor("ConceptHolder", "sample.concepts.ConceptHolder", location, properties)
        val contract = InterfaceDescriptor(
            "ConceptContract",
            "sample.concepts.ConceptContract",
            location,
            listOf(
                PropertyDescriptor("orderId", "sample.concepts.OrderId"),
                PropertyDescriptor("deliveryDate", "sample.concepts.DeliveryDate"),
                PropertyDescriptor("deliveryTime", "sample.concepts.DeliveryTime")
            )
        )
        val command = CommandDescriptor(
            "UseConcepts",
            "sample.concepts.UseConcepts",
            commandProperties,
            location = location,
            responseTypeName = "sample.concepts.JavaDeliveryDate"
        )
        val query = QueryDescriptor(
            "byConcept",
            "sample.concepts.ConceptReadModel",
            "sample.concepts.StateCode",
            concepts.map { concept ->
                ParameterDescriptor(concept.name.replaceFirstChar(Char::lowercaseChar), concept.fullyQualifiedName)
            } + ParameterDescriptor(
                "customerNames",
                "kotlin.collections.List<sample.concepts.CustomerName>",
                isEnumerable = true,
                elementTypeName = "sample.concepts.CustomerName",
                validateRecursively = true
            ),
            fullyQualifiedName = "sample.concepts.ConceptReadModel.byConcept",
            location = location
        )
        val observableQuery = QueryDescriptor(
            "observeConcepts",
            "sample.concepts.ConceptReadModel",
            "sample.concepts.JavaOrderId",
            listOf(
                ParameterDescriptor("deliveryDate", "sample.concepts.DeliveryDate"),
                ParameterDescriptor("deliveryTime", "sample.concepts.JavaDeliveryTime")
            ),
            fullyQualifiedName = "sample.concepts.ConceptReadModel.observeConcepts",
            location = location,
            transport = QueryTransportType.OBSERVABLE,
            isEnumerable = true
        )
        val output = temporaryDirectory.resolve("concepts")

        generate(
            output,
            MergedArcArtifacts(
                commands = listOf(command),
                queries = listOf(query, observableQuery),
                types = listOf(holder),
                enums = listOf(fixtureState, javaFixtureState),
                interfaces = listOf(contract),
                concepts = concepts
            )
        )

        val holderText = Files.readString(output.resolve("concepts/ConceptHolder.ts"))
        listOf(
            "customerName!: string;",
            "quantity!: number;",
            "orderId!: Guid;",
            "deliveryDate!: DateOnly;",
            "deliveryTime!: TimeOnly;",
            "stateCode!: FixtureState;",
            "javaCustomerCode!: string;",
            "javaQuantity!: number;",
            "javaOrderId!: Guid;",
            "javaDeliveryDate!: DateOnly;",
            "javaDeliveryTime!: TimeOnly;",
            "javaStateCode!: JavaFixtureState;",
            "customerNames!: string[];"
        ).forEach { expected -> assertTrue(holderText.contains(expected), "Missing '$expected'") }
        assertTrue(holderText.contains("import { field, DateOnly, Guid, TimeOnly } from '@cratis/fundamentals';"))
        assertTrue(holderText.contains("@field(Guid)"))
        assertTrue(holderText.contains("@field(DateOnly)"))
        assertTrue(holderText.contains("@field(TimeOnly)"))
        assertTrue(holderText.contains("import type { FixtureState } from './FixtureState';"))
        assertTrue(holderText.contains("import type { JavaFixtureState } from './JavaFixtureState';"))

        val commandText = Files.readString(output.resolve("concepts/UseConcepts.ts"))
        assertTrue(commandText.contains("import { DateOnly, Guid, TimeOnly } from '@cratis/fundamentals';"))
        assertTrue(commandText.contains("extends Command<IUseConcepts, DateOnly>"))
        assertTrue(commandText.contains("super(DateOnly, false);"))
        assertTrue(commandText.contains("new PropertyDescriptor('orderId', Guid, false)"))
        assertTrue(commandText.contains("new PropertyDescriptor('deliveryDate', DateOnly, false)"))
        assertTrue(commandText.contains("new PropertyDescriptor('deliveryTime', TimeOnly, false)"))
        assertTrue(commandText.contains("new PropertyDescriptor('stateCode', Number, false)"))
        assertTrue(commandText.contains("new PropertyDescriptor('customerNames', String, false)"))
        assertTrue(commandText.contains("this.ruleFor(c => c.customerName).notNull().withMessage('Customer name is required');"))
        assertTrue(commandText.contains("this.ruleFor(c => c.customerName).notEmpty().withMessage('Concept value is required');"))
        assertTrue(commandText.contains("this.ruleFor(c => c.customerName).length(2, 40).withMessage('Customer name length is invalid');"))

        val queryText = Files.readString(output.resolve("concepts/ByConcept.ts"))
        assertTrue(queryText.contains("import { DateOnly, Guid, TimeOnly } from '@cratis/fundamentals';"))
        assertTrue(queryText.contains("extends QueryFor<FixtureState, ByConceptParameters>"))
        assertTrue(queryText.contains("super(Number, false);"))
        assertTrue(queryText.contains("customerNames: string[];"))
        assertTrue(queryText.contains("new ParameterDescriptor('javaOrderId', Guid, false)"))
        assertTrue(queryText.contains("new ParameterDescriptor('javaDeliveryDate', DateOnly, false)"))
        assertTrue(queryText.contains("new ParameterDescriptor('javaDeliveryTime', TimeOnly, false)"))
        assertTrue(queryText.contains("new ParameterDescriptor('javaStateCode', Number, false)"))

        val interfaceText = Files.readString(output.resolve("concepts/ConceptContract.ts"))
        assertTrue(interfaceText.contains("import { DateOnly, Guid, TimeOnly } from '@cratis/fundamentals';"))
        assertTrue(interfaceText.contains("orderId: Guid;"))
        assertTrue(interfaceText.contains("deliveryDate: DateOnly;"))
        assertTrue(interfaceText.contains("deliveryTime: TimeOnly;"))

        val observableQueryText = Files.readString(output.resolve("concepts/ObserveConcepts.ts"))
        assertTrue(observableQueryText.contains("import { DateOnly, Guid, TimeOnly } from '@cratis/fundamentals';"))
        assertTrue(observableQueryText.contains("extends ObservableQueryFor<Guid[], ObserveConceptsParameters>"))
        assertTrue(observableQueryText.contains("new ParameterDescriptor('deliveryDate', DateOnly, false)"))
        assertTrue(observableQueryText.contains("new ParameterDescriptor('deliveryTime', TimeOnly, false)"))
        assertTrue(observableQueryText.contains("super(Guid, true);"))

        concepts.forEach { concept ->
            listOf(holderText, commandText, queryText, interfaceText, observableQueryText).forEach { generated ->
                assertFalse(generated.contains("import { ${concept.name} }"))
            }
            assertFalse(Files.exists(output.resolve("concepts/${concept.name}.ts")))
        }
    }

    @Test
    fun `observable query renderer matches enumerable and single golden contracts`() {
        val model = TypeDescriptor(
            "ObservableValue",
            "sample.models.ObservableValue",
            listOf("sample", "models"),
            listOf(
                PropertyDescriptor("value", "kotlin.String"),
                PropertyDescriptor("updatedAt", "java.time.Instant")
            )
        )
        val observableValues = QueryDescriptor(
            "observeValues",
            model.fullyQualifiedName,
            model.fullyQualifiedName,
            listOf(
                ParameterDescriptor("filter", "kotlin.String"),
                ParameterDescriptor(
                    "labels",
                    "kotlin.collections.List<kotlin.String>",
                    isNullable = true,
                    isEnumerable = true,
                    elementTypeName = "kotlin.String"
                )
            ),
            fullyQualifiedName = "sample.models.ObservableValue.observeValues",
            location = listOf("sample", "models"),
            authorization = AuthorizationMetadata(roles = listOf("viewer", "auditor")),
            explicitPath = "/observable/{filter}",
            queryHttpMethod = QueryHttpMethodType.QUERY,
            transport = QueryTransportType.OBSERVABLE,
            isEnumerable = true,
            treatWarningsAsErrors = true
        )
        val observableValue = QueryDescriptor(
            "observeValue",
            model.fullyQualifiedName,
            model.fullyQualifiedName,
            fullyQualifiedName = "sample.models.ObservableValue.observeValue",
            location = listOf("sample", "models"),
            explicitPath = "/observable/value",
            transport = QueryTransportType.OBSERVABLE
        )
        val output = temporaryDirectory.resolve("observable-golden")

        generate(
            output,
            MergedArcArtifacts(emptyList(), listOf(observableValues, observableValue), listOf(model), emptyList())
        )

        assertTrue(Files.exists(output.resolve("models/ObserveValues.ts")))
        assertTrue(Files.exists(output.resolve("models/ObserveValue.ts")))

        val withoutHttpPreference = temporaryDirectory.resolve("observable-without-http-preference")
        generate(
            withoutHttpPreference,
            MergedArcArtifacts(emptyList(), listOf(observableValue), listOf(model), emptyList()),
            ApiEndpointOptions(enableQueryHttpMethod = false)
        )
        val withoutHttpPreferenceText = Files.readString(withoutHttpPreference.resolve("models/ObserveValue.ts"))
        assertFalse(withoutHttpPreferenceText.contains("QueryHttpMethod"))
        assertFalse(withoutHttpPreferenceText.contains("setHttpMethod"))
    }

    @Test
    fun `same named queries fail like the dotnet generator`() {
        val first = QueryDescriptor(
            "observeAll",
            "sample.FirstModel",
            "kotlin.String",
            fullyQualifiedName = "sample.FirstModel.observeAll",
            location = listOf("sample"),
            transport = QueryTransportType.OBSERVABLE,
            isEnumerable = true
        )
        val second = QueryDescriptor(
            "observeAll",
            "sample.SecondModel",
            "kotlin.String",
            fullyQualifiedName = "sample.SecondModel.observeAll",
            location = listOf("sample"),
            transport = QueryTransportType.OBSERVABLE,
            isEnumerable = true
        )
        val output = temporaryDirectory.resolve("query-collision")

        val exception = assertThrows(GradleException::class.java) {
            generate(output, MergedArcArtifacts(emptyList(), listOf(first, second), emptyList(), emptyList()))
        }

        assertTrue(exception.message.orEmpty().contains("Duplicate TypeScript output"))
    }

    @Test
    fun `command query and observable templates render Arc string constraint rules exactly`() {
        val model = TypeDescriptor("ValidationResult", "sample.ValidationResult", listOf("sample"))
        val command = CommandDescriptor(
            "ValidateContact",
            "sample.ValidateContact",
            listOf(
                PropertyDescriptor(
                    "phone",
                    "kotlin.String",
                    validationRules = listOf(ValidationRuleDescriptor("phone", message = "Phone is invalid"))
                )
            ),
            location = listOf("sample")
        )
        val query = QueryDescriptor(
            "byUrl",
            model.fullyQualifiedName,
            model.fullyQualifiedName,
            listOf(
                ParameterDescriptor(
                    "url",
                    "kotlin.String",
                    validationRules = listOf(ValidationRuleDescriptor("url"))
                )
            ),
            fullyQualifiedName = "sample.ValidationResult.byUrl",
            location = listOf("sample")
        )
        val observable = QueryDescriptor(
            "observeCard",
            model.fullyQualifiedName,
            model.fullyQualifiedName,
            listOf(
                ParameterDescriptor(
                    "card",
                    "kotlin.String",
                    validationRules = listOf(ValidationRuleDescriptor("creditCard", message = "Card is invalid"))
                )
            ),
            fullyQualifiedName = "sample.ValidationResult.observeCard",
            location = listOf("sample"),
            transport = QueryTransportType.OBSERVABLE
        )
        val output = temporaryDirectory.resolve("string-constraints")

        generate(
            output,
            MergedArcArtifacts(listOf(command), listOf(query, observable), listOf(model), emptyList())
        )

        assertEquals(
            listOf("this.ruleFor(c => c.phone).phone().withMessage('Phone is invalid');"),
            Files.readAllLines(output.resolve("ValidateContact.ts")).map(String::trim).filter { it.startsWith("this.ruleFor") }
        )
        assertEquals(
            listOf("this.ruleFor(c => c.url).url();"),
            Files.readAllLines(output.resolve("ByUrl.ts")).map(String::trim).filter { it.startsWith("this.ruleFor") }
        )
        assertEquals(
            emptyList<String>(),
            Files.readAllLines(output.resolve("ObserveCard.ts")).map(String::trim).filter { it.startsWith("this.ruleFor") }
        )
    }

    @Test
    fun `query templates alphabetize parameters and sort properties and render exact validators`() {
        val model = TypeDescriptor(
            "OrderedResult",
            "sample.OrderedResult",
            listOf("sample"),
            listOf(PropertyDescriptor("zeta", "kotlin.String"), PropertyDescriptor("alpha", "kotlin.String"))
        )
        val query = QueryDescriptor(
            "ordered",
            model.fullyQualifiedName,
            model.fullyQualifiedName,
            listOf(
                ParameterDescriptor("zeta", "kotlin.String"),
                ParameterDescriptor(
                    "alpha",
                    "kotlin.String",
                    isNullable = true,
                    validationRules = listOf(
                        ValidationRuleDescriptor("matches", listOf("^a/b\\\\$"), "Use 'alpha'\nonly")
                    )
                )
            ),
            fullyQualifiedName = "sample.OrderedResult.ordered",
            location = listOf("sample"),
            isEnumerable = true,
            supportsSorting = true
        )
        val output = temporaryDirectory.resolve("ordered-query")

        generate(output, MergedArcArtifacts(emptyList(), listOf(query), listOf(model), emptyList()))

        val generated = Files.readString(output.resolve("Ordered.ts"))
        assertTrue(generated.indexOf("alpha?: string;") < generated.indexOf("zeta: string;"))
        assertTrue(generated.indexOf("private _alpha:") < generated.indexOf("private _zeta:"))
        assertTrue(generated.contains("alpha!: string;"))
        assertTrue(generated.contains(".matches(/^a\\/b\\\\$/).withMessage('Use \\'alpha\\'\\nonly');"))
        assertFalse(generated.contains("new RegExp"))
    }

    @Test
    fun `empty models and enumerable queries omit unused field and sorting action imports`() {
        val model = TypeDescriptor("EmptyResult", "sample.EmptyResult", listOf("sample"))
        val query = QueryDescriptor(
            "allEmpty",
            model.fullyQualifiedName,
            model.fullyQualifiedName,
            fullyQualifiedName = "sample.EmptyResult.allEmpty",
            location = listOf("sample"),
            isEnumerable = true
        )
        val output = temporaryDirectory.resolve("empty-imports")

        generate(output, MergedArcArtifacts(emptyList(), listOf(query), listOf(model), emptyList()))

        val modelText = Files.readString(output.resolve("EmptyResult.ts"))
        val queryText = Files.readString(output.resolve("AllEmpty.ts"))
        assertFalse(modelText.contains("import { field }"))
        assertFalse(queryText.contains("SortingActions"))
        assertFalse(queryText.contains("Sorting"))
        assertFalse(queryText.contains("Paging"))
        assertTrue(queryText.contains("QueryFor, QueryResultWithState"))
    }

    @Test
    fun `generated imports distinguish interface types from runtime values across proxy paths`() {
        val location = listOf("sample", "imports")
        val baseContract = InterfaceDescriptor(
            "BaseContract",
            "sample.imports.BaseContract",
            location
        )
        val contract = InterfaceDescriptor(
            "GeneratedContract",
            "sample.imports.GeneratedContract",
            location,
            listOf(
                PropertyDescriptor("label", "kotlin.String"),
                PropertyDescriptor("base", baseContract.fullyQualifiedName)
            )
        )
        val concrete = TypeDescriptor("ConcreteModel", "sample.imports.ConcreteModel", location)
        val base = TypeDescriptor("BaseModel", "sample.imports.BaseModel", location)
        val derived = TypeDescriptor(
            "DerivedModel",
            "sample.imports.DerivedModel",
            location,
            listOf(PropertyDescriptor("contract", contract.fullyQualifiedName)),
            baseTypeName = base.fullyQualifiedName
        )
        val holder = TypeDescriptor(
            "ContractHolder",
            "sample.imports.ContractHolder",
            location,
            listOf(
                PropertyDescriptor(
                    "contracts",
                    "kotlin.collections.List<${contract.fullyQualifiedName}>",
                    isEnumerable = true,
                    elementTypeName = contract.fullyQualifiedName,
                    derivatives = listOf(concrete.fullyQualifiedName)
                )
            )
        )
        val interfaceResponseCommand = CommandDescriptor(
            "UseContract",
            "sample.imports.UseContract",
            listOf(
                PropertyDescriptor("contract", contract.fullyQualifiedName),
                PropertyDescriptor("concrete", concrete.fullyQualifiedName)
            ),
            location = location,
            responseTypeName = contract.fullyQualifiedName
        )
        val concreteResponseCommand = CommandDescriptor(
            "UseConcrete",
            "sample.imports.UseConcrete",
            location = location,
            responseTypeName = concrete.fullyQualifiedName
        )
        val query = QueryDescriptor(
            "findContract",
            contract.fullyQualifiedName,
            contract.fullyQualifiedName,
            listOf(ParameterDescriptor("concrete", concrete.fullyQualifiedName)),
            fullyQualifiedName = "sample.imports.GeneratedContract.findContract",
            location = location
        )
        val observableQuery = QueryDescriptor(
            "observeContracts",
            contract.fullyQualifiedName,
            contract.fullyQualifiedName,
            listOf(ParameterDescriptor("concrete", concrete.fullyQualifiedName)),
            fullyQualifiedName = "sample.imports.GeneratedContract.observeContracts",
            location = location,
            transport = QueryTransportType.OBSERVABLE,
            isEnumerable = true,
            supportsPaging = true,
            supportsSorting = true
        )
        val output = temporaryDirectory.resolve("type-imports")

        generate(
            output,
            MergedArcArtifacts(
                commands = listOf(interfaceResponseCommand, concreteResponseCommand),
                queries = listOf(query, observableQuery),
                types = listOf(concrete, base, derived, holder),
                enums = emptyList(),
                interfaces = listOf(baseContract, contract)
            )
        )

        val contractText = Files.readString(output.resolve("imports/GeneratedContract.ts"))
        assertTrue(contractText.contains("import type { BaseContract } from './BaseContract';"))

        val derivedText = Files.readString(output.resolve("imports/DerivedModel.ts"))
        assertTrue(derivedText.contains("import { BaseModel } from './BaseModel';"))
        assertTrue(derivedText.contains("import type { GeneratedContract } from './GeneratedContract';"))
        assertTrue(derivedText.contains("export class DerivedModel extends BaseModel"))
        assertTrue(derivedText.contains("@field(Object)\n    contract!: GeneratedContract;"))

        val holderText = Files.readString(output.resolve("imports/ContractHolder.ts"))
        assertTrue(holderText.contains("import { ConcreteModel } from './ConcreteModel';"))
        assertTrue(holderText.contains("import type { GeneratedContract } from './GeneratedContract';"))
        assertEquals(1, holderText.lineSequence().count { it.contains("ConcreteModel } from") })
        assertTrue(holderText.contains("@field(Object, true, [ConcreteModel])"))

        val interfaceCommandText = Files.readString(output.resolve("imports/UseContract.ts"))
        assertTrue(interfaceCommandText.contains("import { ConcreteModel } from './ConcreteModel';"))
        assertTrue(interfaceCommandText.contains("import type { GeneratedContract } from './GeneratedContract';"))
        assertEquals(1, interfaceCommandText.lineSequence().count { it.contains("ConcreteModel } from") })
        assertFalse(interfaceCommandText.contains("import { GeneratedContract }"))
        assertTrue(interfaceCommandText.contains("new PropertyDescriptor('contract', Object, false)"))
        assertTrue(interfaceCommandText.contains("new PropertyDescriptor('concrete', ConcreteModel, false)"))
        assertTrue(interfaceCommandText.contains("super(Object, false);"))
        assertTrue(
            interfaceCommandText.contains(
                "import { useCommand, type SetCommandValues, type ClearCommandValues } " +
                    "from '@cratis/arc.react/commands';"
            )
        )

        val concreteCommandText = Files.readString(output.resolve("imports/UseConcrete.ts"))
        assertTrue(concreteCommandText.contains("import { ConcreteModel } from './ConcreteModel';"))
        assertTrue(concreteCommandText.contains("super(ConcreteModel, false);"))

        val queryText = Files.readString(output.resolve("imports/FindContract.ts"))
        assertTrue(queryText.contains("import { ConcreteModel } from './ConcreteModel';"))
        assertTrue(queryText.contains("import type { GeneratedContract } from './GeneratedContract';"))
        assertTrue(queryText.contains("super(Object, false);"))
        assertTrue(queryText.contains("type PerformQuery, type SetSorting"))

        val observableText = Files.readString(output.resolve("imports/ObserveContracts.ts"))
        assertTrue(observableText.contains("import { ConcreteModel } from './ConcreteModel';"))
        assertTrue(observableText.contains("import type { GeneratedContract } from './GeneratedContract';"))
        assertTrue(observableText.contains("type ChangeSet"))
        assertTrue(observableText.contains("type SetSorting, type SetPage, type SetPageSize"))
        assertTrue(observableText.contains("super(Object, true);"))
    }

    @Test
    fun `global runtime constructors reject only generated value shadowing`() {
        val supportInterface = InterfaceDescriptor(
            "SupportContract",
            "sample.shadow.SupportContract",
            listOf("sample", "shadow")
        )
        val constructorTypes = listOf(
            "Date" to "java.time.Instant",
            "String" to "kotlin.String",
            "Number" to "kotlin.Int",
            "Boolean" to "kotlin.Boolean",
            "Object" to supportInterface.fullyQualifiedName
        )

        constructorTypes.forEach { (constructor, propertyType) ->
            val current = TypeDescriptor(
                constructor,
                "sample.current.$constructor",
                listOf("sample", "current"),
                listOf(PropertyDescriptor("value", propertyType))
            )
            val currentException = assertThrows(GradleException::class.java) {
                generate(
                    temporaryDirectory.resolve("global-current-${constructor.lowercase()}"),
                    MergedArcArtifacts(
                        emptyList(),
                        emptyList(),
                        listOf(current),
                        emptyList(),
                        listOf(supportInterface)
                    )
                )
            }
            assertTrue(currentException.message.orEmpty().contains("global runtime constructor '$constructor'"))

            val imported = TypeDescriptor(
                constructor,
                "sample.imported.$constructor",
                listOf("sample", "imported")
            )
            val holder = TypeDescriptor(
                "${constructor}Holder",
                "sample.imported.${constructor}Holder",
                listOf("sample", "imported"),
                listOf(
                    PropertyDescriptor("imported", imported.fullyQualifiedName),
                    PropertyDescriptor("global", propertyType)
                )
            )
            val importedException = assertThrows(GradleException::class.java) {
                generate(
                    temporaryDirectory.resolve("global-imported-${constructor.lowercase()}"),
                    MergedArcArtifacts(
                        emptyList(),
                        emptyList(),
                        listOf(imported, holder),
                        emptyList(),
                        listOf(supportInterface)
                    )
                )
            }
            assertTrue(importedException.message.orEmpty().contains("global runtime constructor '$constructor'"))
            assertTrue(importedException.message.orEmpty().contains("value import 'sample.imported.$constructor'"))
        }

        val harmlessDate = TypeDescriptor("Date", "sample.harmless.Date", listOf("sample", "harmless"))
        generate(
            temporaryDirectory.resolve("global-harmless-current"),
            MergedArcArtifacts(emptyList(), emptyList(), listOf(harmlessDate), emptyList())
        )

        val dateContract = InterfaceDescriptor(
            "Date",
            "sample.harmless.DateContract",
            listOf("sample", "harmless")
        )
        val instantHolder = TypeDescriptor(
            "InstantHolder",
            "sample.harmless.InstantHolder",
            listOf("sample", "harmless"),
            listOf(
                PropertyDescriptor("contract", dateContract.fullyQualifiedName),
                PropertyDescriptor("instant", "java.time.Instant")
            )
        )
        val harmlessOutput = temporaryDirectory.resolve("global-harmless-type-import")
        generate(
            harmlessOutput,
            MergedArcArtifacts(
                emptyList(),
                emptyList(),
                listOf(instantHolder),
                emptyList(),
                listOf(dateContract)
            )
        )
        val harmlessText = Files.readString(harmlessOutput.resolve("harmless/InstantHolder.ts"))
        assertTrue(harmlessText.contains("import type { Date } from './Date';"))
        assertTrue(harmlessText.contains("@field(Date)\n    instant!: Date;"))
    }

    @Test
    fun `index updates preserve manual content and remove only stale generated exports`() {
        val output = temporaryDirectory.resolve("manual-index")
        Files.createDirectories(output)
        Files.writeString(output.resolve("Custom.ts"), "export const custom = true;\n")
        Files.writeString(output.resolve("index.ts"), "// manual\nexport * from './Custom';\n")
        val model = TypeDescriptor("Value", "sample.Value", listOf("sample"))

        generate(output, MergedArcArtifacts(emptyList(), emptyList(), listOf(model), emptyList()))
        assertEquals(
            "// manual\nexport * from './Custom';\nexport * from './Value';\n",
            Files.readString(output.resolve("index.ts"))
        )

        generate(output, MergedArcArtifacts(emptyList(), emptyList(), emptyList(), emptyList()))
        assertEquals("// manual\nexport * from './Custom';\n", Files.readString(output.resolve("index.ts")))
        assertTrue(Files.exists(output.resolve("Custom.ts")))
    }

    @Test
    fun `external value imports reject current and imported artifact name collisions`() {
        val externalTypes = listOf(
            "Guid" to "java.util.UUID",
            "DateOnly" to "java.time.LocalDate",
            "TimeOnly" to "java.time.LocalTime"
        )

        externalTypes.forEach { (externalName, sourceType) ->
            val current = TypeDescriptor(
                externalName,
                "sample.current.$externalName",
                listOf("sample", "current"),
                listOf(PropertyDescriptor("value", sourceType))
            )
            val currentException = assertThrows(GradleException::class.java) {
                generate(
                    temporaryDirectory.resolve("current-${externalName.lowercase()}"),
                    MergedArcArtifacts(emptyList(), emptyList(), listOf(current), emptyList())
                )
            }
            assertTrue(currentException.message.orEmpty().contains("Duplicate TypeScript name '$externalName'"))
            assertTrue(currentException.message.orEmpty().contains("@cratis/fundamentals"))

            val imported = TypeDescriptor(
                externalName,
                "sample.imported.$externalName",
                listOf("sample", "imported")
            )
            val holder = TypeDescriptor(
                "${externalName}Holder",
                "sample.imported.${externalName}Holder",
                listOf("sample", "imported"),
                listOf(
                    PropertyDescriptor("localValue", imported.fullyQualifiedName),
                    PropertyDescriptor("externalValue", sourceType)
                )
            )
            val importedException = assertThrows(GradleException::class.java) {
                generate(
                    temporaryDirectory.resolve("imported-${externalName.lowercase()}"),
                    MergedArcArtifacts(emptyList(), emptyList(), listOf(imported, holder), emptyList())
                )
            }
            assertTrue(importedException.message.orEmpty().contains("Duplicate TypeScript name '$externalName'"))
            assertTrue(importedException.message.orEmpty().contains("@cratis/fundamentals"))
        }
    }

    @Test
    fun `fails clearly for unsupported generic types and ambiguous output`() {
        val unsupported = TypeDescriptor(
            "Unsupported",
            "sample.Unsupported",
            listOf("sample"),
            listOf(PropertyDescriptor("values", "kotlin.collections.Map<kotlin.String,kotlin.String>"))
        )
        assertThrows(GradleException::class.java) {
            generate(
                temporaryDirectory.resolve("unsupported"),
                MergedArcArtifacts(emptyList(), emptyList(), listOf(unsupported), emptyList())
            )
        }

        val duplicateType = TypeDescriptor("Duplicate", "sample.Duplicate", listOf("sample"))
        val duplicateEnum = EnumDescriptor("Duplicate", "sample.OtherDuplicate", listOf("sample"))
        assertThrows(GradleException::class.java) {
            generate(
                temporaryDirectory.resolve("duplicate"),
                MergedArcArtifacts(emptyList(), emptyList(), listOf(duplicateType), listOf(duplicateEnum))
            )
        }
    }

    @Test
    fun `manifest paths cannot escape proxy output`() {
        val unsafe = TypeDescriptor("Escape", "sample.Escape", listOf("safe", "..", "outside"))

        val exception = assertThrows(GradleException::class.java) {
            generate(
                temporaryDirectory.resolve("safe-output"),
                MergedArcArtifacts(emptyList(), emptyList(), listOf(unsafe), emptyList())
            )
        }

        assertTrue(exception.message.orEmpty().contains("Unsafe Arc proxy path segment"))
        assertFalse(Files.exists(temporaryDirectory.resolve("outside/Escape.ts")))
    }

    @Test
    fun `plugin exposes extension and wires generation task`() {
        val project = ProjectBuilder.builder().withName("consumer").build()
        project.pluginManager.apply(ArcGradlePlugin::class.java)

        val extension = project.extensions.getByType(ArcExtension::class.java)
        assertEquals("consumer", extension.moduleName.get())
        assertEquals(17, project.extensions.getByType(org.gradle.api.plugins.JavaPluginExtension::class.java).toolchain.languageVersion.get().asInt())
        assertTrue(project.pluginManager.hasPlugin("org.jetbrains.kotlin.jvm"))
        assertTrue(project.pluginManager.hasPlugin("com.google.devtools.ksp"))
        assertNotNull(project.tasks.findByName("generateArcProxies"))
        assertTrue(project.tasks.getByName("build").taskDependencies.getDependencies(null).any { it.name == "generateArcProxies" })
    }

    private fun crossRuntimeFixtureArtifacts(): MergedArcArtifacts {
        val commandsLocation = listOf("DifferentialFixture", "Commands")
        val contractsLocation = listOf("DifferentialFixture", "Contracts")
        val modelsLocation = listOf("DifferentialFixture", "Models")
        val models = "differential.fixture.models"
        val fixtureModelName = "$models.FixtureModel"
        val emptyModelName = "$models.EmptyModel"
        val shapeBaseName = "$models.ShapeBase"
        val circleName = "$models.Circle"
        val shapeHolderName = "$models.ShapeHolder"
        val shapeName = "differential.fixture.contracts.Shape"
        val fixtureStateName = "$models.FixtureState"
        val fixturePermissionsName = "$models.FixturePermissions"
        val fixtureModel = TypeDescriptor(
            "FixtureModel",
            fixtureModelName,
            modelsLocation,
            listOf(
                PropertyDescriptor("identifier", "kotlin.String"),
                PropertyDescriptor(
                    "labelsByCategory",
                    TypeShapeDescriptor.map(
                        TypeShapeDescriptor.value("kotlin.String"),
                        TypeShapeDescriptor.value("kotlin.String")
                    )
                ),
                PropertyDescriptor("permissions", fixturePermissionsName),
                PropertyDescriptor("state", fixtureStateName),
                PropertyDescriptor("updatedAt", "java.time.OffsetDateTime")
            )
        )
        val emptyModel = TypeDescriptor("EmptyModel", emptyModelName, modelsLocation)
        val shapeBase = TypeDescriptor(
            "ShapeBase",
            shapeBaseName,
            modelsLocation,
            listOf(PropertyDescriptor("createdAt", "java.time.OffsetDateTime"))
        )
        val circle = TypeDescriptor(
            "Circle",
            circleName,
            modelsLocation,
            listOf(
                PropertyDescriptor("displayName", "kotlin.String"),
                PropertyDescriptor("radius", "kotlin.Double")
            ),
            shapeBaseName,
            "circle"
        )
        val shapeHolder = TypeDescriptor(
            "ShapeHolder",
            shapeHolderName,
            modelsLocation,
            listOf(
                PropertyDescriptor(
                    "shapes",
                    "kotlin.collections.List<$shapeName>",
                    isEnumerable = true,
                    elementTypeName = shapeName,
                    derivatives = listOf(circleName)
                )
            )
        )
        val command = CommandDescriptor(
            "CreateFixtures",
            "differential.fixture.commands.CreateFixtures",
            listOf(
                PropertyDescriptor(
                    "fixtureId",
                    "kotlin.String",
                    validationRules = listOf(ValidationRuleDescriptor("notEmpty"))
                ),
                PropertyDescriptor(
                    "states",
                    "kotlin.collections.List<$fixtureStateName>",
                    isEnumerable = true,
                    elementTypeName = fixtureStateName,
                    validationRules = listOf(ValidationRuleDescriptor("notEmpty"))
                )
            ),
            location = commandsLocation,
            authorization = AuthorizationMetadata(roles = listOf("creator", "admin")),
            treatWarningsAsErrors = true,
            responseTypeName = fixtureModelName,
            responseIsEnumerable = true
        )
        val queries = listOf(
            QueryDescriptor(
                "byId",
                fixtureModelName,
                fixtureModelName,
                listOf(ParameterDescriptor("identifier", "kotlin.String")),
                fullyQualifiedName = "$fixtureModelName.byId",
                location = modelsLocation
            ),
            QueryDescriptor(
                "all",
                fixtureModelName,
                fixtureModelName,
                fullyQualifiedName = "$fixtureModelName.all",
                location = modelsLocation,
                isEnumerable = true,
                supportsPaging = true,
                supportsSorting = true
            ),
            QueryDescriptor(
                "search",
                fixtureModelName,
                fixtureModelName,
                listOf(
                    ParameterDescriptor("filter", "kotlin.String"),
                    ParameterDescriptor(
                        "labels",
                        "kotlin.collections.List<kotlin.String>",
                        isNullable = true,
                        isEnumerable = true,
                        elementTypeName = "kotlin.String"
                    )
                ),
                fullyQualifiedName = "$fixtureModelName.search",
                location = modelsLocation,
                authorization = AuthorizationMetadata(roles = listOf("viewer", "auditor")),
                explicitPath = "/custom/fixture-search",
                queryHttpMethod = QueryHttpMethodType.QUERY,
                isEnumerable = true,
                supportsPaging = true,
                supportsSorting = true,
                treatWarningsAsErrors = true
            ),
            QueryDescriptor(
                "observe",
                fixtureModelName,
                fixtureModelName,
                listOf(ParameterDescriptor("filter", "kotlin.String")),
                fullyQualifiedName = "$fixtureModelName.observe",
                location = modelsLocation,
                transport = QueryTransportType.OBSERVABLE,
                isEnumerable = true,
                supportsPaging = true,
                supportsSorting = true
            ),
            QueryDescriptor(
                "observeOne",
                fixtureModelName,
                fixtureModelName,
                fullyQualifiedName = "$fixtureModelName.observeOne",
                location = modelsLocation,
                transport = QueryTransportType.OBSERVABLE
            ),
            QueryDescriptor(
                "getEmpty",
                emptyModelName,
                emptyModelName,
                fullyQualifiedName = "$emptyModelName.getEmpty",
                location = modelsLocation
            ),
            QueryDescriptor(
                "getShapes",
                shapeHolderName,
                shapeHolderName,
                fullyQualifiedName = "$shapeHolderName.getShapes",
                location = modelsLocation
            )
        )
        val enums = listOf(
            EnumDescriptor(
                "FixtureState",
                fixtureStateName,
                modelsLocation,
                listOf(EnumMemberDescriptor("New", 0), EnumMemberDescriptor("Active", 17))
            ),
            EnumDescriptor(
                "FixturePermissions",
                fixturePermissionsName,
                modelsLocation,
                listOf(
                    EnumMemberDescriptor("None", 0),
                    EnumMemberDescriptor("Read", 1),
                    EnumMemberDescriptor("Write", 2)
                ),
                isFlags = true
            )
        )
        val shape = TypeDescriptor(
            "Shape",
            shapeName,
            contractsLocation,
            listOf(PropertyDescriptor("displayName", "kotlin.String"))
        )
        return MergedArcArtifacts(
            listOf(command),
            queries,
            listOf(circle, emptyModel, fixtureModel, shape, shapeBase, shapeHolder),
            enums,
            emptyList()
        )
    }

    private fun resourcePath(resourceName: String): Path =
        Path.of(requireNotNull(javaClass.getResource(resourceName)) { "Missing resource '$resourceName'." }.toURI())

    private fun expectedProxyTree(root: Path): Map<String, String> =
        proxyTree(root).mapValues { (relativePath, body) ->
            normalizeKnownDotNetVerbatimModuleImports(
                normalizeKnownDotNetCommandSuppression(
                    relativePath,
                    normalizeKnownDotNetEnumerableCommandGeneric(
                        relativePath,
                        normalizeKnownDotNetMapFixtureFormatting(relativePath, body)
                    )
                )
            )
        }

    private fun proxyTree(root: Path, normalizeGeneratedHeader: Boolean = false): Map<String, String> =
        Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile)
                .sorted()
                .iterator()
                .asSequence()
                .associate { path ->
                    val relativePath = root.relativize(path).toString().replace('\\', '/')
                    val body = Files.readString(path)
                        .replace("\r\n", "\n")
                        .split('\n')
                        .joinToString("\n") { line -> line.trimEnd() }
                    relativePath to if (normalizeGeneratedHeader) normalizeGeneratedHeader(body) else body
                }
        }

    private fun normalizeKnownDotNetMapFixtureFormatting(relativePath: String, body: String): String = when (relativePath) {
        "Models/FixtureModel.ts" -> body.replace('"', '\'')
            .lineSequence()
            .joinToString("\n") { line ->
                if (line.startsWith("   ") && !line.startsWith("    ")) " $line" else line
            }
        DOTNET_ENUMERABLE_COMMAND_PATH -> body
            .replace('"', '\'')
            .replace(
                "import {\n" +
                    "    useCommand,\n" +
                    "    type SetCommandValues,\n" +
                    "    type ClearCommandValues,\n" +
                    "} from '@cratis/arc.react/commands';",
                "import { useCommand, type SetCommandValues, type ClearCommandValues } " +
                    "from '@cratis/arc.react/commands';"
            )
            .replace("this.ruleFor((c) =>", "this.ruleFor(c =>")
            .replace(
                "    get requestParameters(): string[] {\n        return [];\n    }",
                "    get requestParameters(): string[] {\n        return [\n        ];\n    }"
            )
            .replace(
                "export class CreateFixtures\n" +
                    "    extends Command<ICreateFixtures, FixtureModel>\n" +
                    "    implements ICreateFixtures\n" +
                    "{",
                DOTNET_ENUMERABLE_COMMAND_SCALAR_GENERIC
            )
            .replace(
                "    static use(\n" +
                    "        initialValues?: ICreateFixtures,\n" +
                    "    ): [CreateFixtures, SetCommandValues<ICreateFixtures>, ClearCommandValues] {\n" +
                    "        return useCommand<CreateFixtures, ICreateFixtures>(\n" +
                    "            CreateFixtures,\n" +
                    "            initialValues,\n" +
                    "        );\n" +
                    "    }",
                "    static use(initialValues?: ICreateFixtures): " +
                    "[CreateFixtures, SetCommandValues<ICreateFixtures>, ClearCommandValues] {\n" +
                    "        return useCommand<CreateFixtures, ICreateFixtures>(CreateFixtures, initialValues);\n" +
                    "    }"
            )
        else -> body
    }

    private fun normalizeKnownDotNetCommandSuppression(relativePath: String, body: String): String {
        if (relativePath != DOTNET_ENUMERABLE_COMMAND_PATH || "// @ts-ignore" in body) return body
        val marker = "        return useCommand<CreateFixtures, ICreateFixtures>(CreateFixtures, initialValues);"
        require(body.indexOf(marker) >= 0 && body.indexOf(marker) == body.lastIndexOf(marker)) {
            "Expected exactly one CreateFixtures useCommand return in $DOTNET_ENUMERABLE_COMMAND_PATH"
        }
        return body.replace(
            marker,
            "        // eslint-disable-next-line @typescript-eslint/ban-ts-comment\n" +
                "        // @ts-ignore\n$marker"
        )
    }

    private fun normalizeKnownDotNetEnumerableCommandGeneric(relativePath: String, body: String): String {
        if (relativePath != DOTNET_ENUMERABLE_COMMAND_PATH) return body

        val replacementStart = body.indexOf(DOTNET_ENUMERABLE_COMMAND_SCALAR_GENERIC)
        require(replacementStart >= 0) {
            "Expected known .NET enumerable-command scalar generic in $DOTNET_ENUMERABLE_COMMAND_PATH: " +
                DOTNET_ENUMERABLE_COMMAND_SCALAR_GENERIC
        }
        require(
            body.indexOf(
                DOTNET_ENUMERABLE_COMMAND_SCALAR_GENERIC,
                replacementStart + DOTNET_ENUMERABLE_COMMAND_SCALAR_GENERIC.length
            ) < 0
        ) {
            "Expected exactly one known .NET enumerable-command scalar generic in $DOTNET_ENUMERABLE_COMMAND_PATH"
        }

        val normalized = body.replaceRange(
            replacementStart,
            replacementStart + DOTNET_ENUMERABLE_COMMAND_SCALAR_GENERIC.length,
            DOTNET_ENUMERABLE_COMMAND_ARRAY_GENERIC
        )
        check(DOTNET_ENUMERABLE_COMMAND_SCALAR_GENERIC !in normalized) {
            "Known .NET enumerable-command scalar generic remained after normalization in $DOTNET_ENUMERABLE_COMMAND_PATH"
        }
        check(
            normalized.regionMatches(
                replacementStart,
                DOTNET_ENUMERABLE_COMMAND_ARRAY_GENERIC,
                0,
                DOTNET_ENUMERABLE_COMMAND_ARRAY_GENERIC.length
            )
        ) {
            "Known .NET enumerable-command array generic was not applied in $DOTNET_ENUMERABLE_COMMAND_PATH"
        }
        return normalized
    }

    private fun normalizeKnownDotNetVerbatimModuleImports(body: String): String = body
        .replace(
            "import { useCommand, SetCommandValues, ClearCommandValues } " +
                "from '@cratis/arc.react/commands';",
            "import { useCommand, type SetCommandValues, type ClearCommandValues } " +
                "from '@cratis/arc.react/commands';"
        )
        .replace(
            "import { useQuery, useQueryWithPaging, useSuspenseQuery, useSuspenseQueryWithPaging, " +
                "PerformQuery, SetSorting, SetPage, SetPageSize, QueryWhen } from '@cratis/arc.react/queries';",
            "import { useQuery, useQueryWithPaging, useSuspenseQuery, useSuspenseQueryWithPaging, " +
                "type PerformQuery, type SetSorting, type SetPage, type SetPageSize, QueryWhen } " +
                "from '@cratis/arc.react/queries';"
        )
        .replace(
            "import { useQuery, useSuspenseQuery, PerformQuery, SetSorting, QueryWhen } " +
                "from '@cratis/arc.react/queries';",
            "import { useQuery, useSuspenseQuery, type PerformQuery, type SetSorting, QueryWhen } " +
                "from '@cratis/arc.react/queries';"
        )
        .replace(
            "Paging, ChangeSet } from '@cratis/arc/queries';",
            "Paging, type ChangeSet } from '@cratis/arc/queries';"
        )
        .replace(
            "useChangeStream, SetSorting, SetPage, SetPageSize, ObservableQueryWhen } " +
                "from '@cratis/arc.react/queries';",
            "useChangeStream, type SetSorting, type SetPage, type SetPageSize, ObservableQueryWhen } " +
                "from '@cratis/arc.react/queries';"
        )

    private fun normalizeGeneratedHeader(body: String): String {
        val firstLineEnd = body.indexOf('\n')
        if (firstLineEnd < 0 || !body.startsWith("// @generated by Cratis. Source: ")) return body
        val firstLine = body.substring(0, firstLineEnd)
        val normalized = firstLine.substringBefore(". Time:").substringBefore(". Hash:")
        return normalized + body.substring(firstLineEnd)
    }

    private fun generate(
        output: Path,
        artifacts: MergedArcArtifacts,
        endpoints: ApiEndpointOptions = ApiEndpointOptions(),
        proxySegmentsToSkip: Int = 1
    ) {
        TypeScriptProxyGenerator(
            artifacts,
            ProxyGenerationOptions(output.toFile(), endpoints, true, proxySegmentsToSkip)
        ).generate()
    }

    private fun writeManifest(path: Path, manifest: ArcArtifactManifest) {
        Files.createDirectories(path.parent)
        ArcObjectMapper.create().writeValue(path.toFile(), manifest)
    }

    private fun writeRawManifest(path: Path, json: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, json)
    }

    private fun writeJarManifest(path: Path, entryName: String, json: String) {
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            output.putNextEntry(JarEntry(entryName))
            output.write(json.toByteArray())
            output.closeEntry()
        }
    }

    private fun assertGolden(resourceName: String, generatedFile: Path) {
        val expected = requireNotNull(javaClass.getResourceAsStream("/golden/$resourceName")) {
            "Missing golden resource '$resourceName'."
        }.bufferedReader().use { it.readText() }
        val actual = Files.readString(generatedFile).lineSequence().drop(1).joinToString("\n")
        assertEquals(expected.replace("\r\n", "\n").trimEnd(), actual.trimEnd())
    }
}
