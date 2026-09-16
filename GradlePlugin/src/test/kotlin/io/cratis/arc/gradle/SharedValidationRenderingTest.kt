// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.metadata.ApiEndpointOptions
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeDescriptor
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.ValidationRuleDescriptor
import io.cratis.arc.queries.QueryHttpMethodType
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class SharedValidationRenderingTest {
    private val rule = ValidationRuleDescriptor("minLength", listOf(2))
    private val person = TypeDescriptor("Person", "models.Person", properties = listOf(PropertyDescriptor("name", "kotlin.String", validationRules = listOf(rule))))
    private val shared = SharedValidatorDescriptor("models.Rules", "models.Person", listOf(SharedValidationMember("name", "java.lang.String", listOf(rule))))
    private fun query(namespace: String, parameter: ParameterDescriptor) = QueryDescriptor("find", "$namespace.View", "kotlin.String",
        parameters = listOf(parameter), queryHttpMethod = QueryHttpMethodType.QUERY)
    private fun root() = Files.createTempDirectory(File(System.getProperty("arc.fluent.evidence") ?: System.getProperty("java.io.tmpdir")).toPath(), "shared-render-").toFile()

    @Test
    fun `same query names in both orders and command overlap execute their own nested rules`() {
        val plain = query("plain", ParameterDescriptor("term", "kotlin.String", validationRules = listOf(ValidationRuleDescriptor("notEmpty"))))
        val nested = query("nested", ParameterDescriptor("input", "models.Person"))
        val command = CommandDescriptor("Find", "commands.Find", properties = listOf(PropertyDescriptor("term", "kotlin.String", validationRules = listOf(ValidationRuleDescriptor("notEmpty")))))
        val nestedCommand = CommandDescriptor("Find", "nestedCommands.Find", properties = listOf(PropertyDescriptor("child", "models.Person")))
        for (queries in listOf(listOf(plain, nested), listOf(nested, plain))) {
            val root = root()
            TypeScriptProxyGenerator(MergedArcArtifacts(listOf(command, nestedCommand), queries, listOf(person), emptyList(), sharedValidators = listOf(shared)),
                ProxyGenerationOptions(root, ApiEndpointOptions(enableQueryHttpMethod = true), false, 0)).generate()
            execute(root, """
                import assert from 'node:assert/strict';
                import { FindValidator as Plain } from './plain/Find';
                import { FindValidator as Nested } from './nested/Find';
                import { FindValidator as Command } from './commands/Find';
                import { FindValidator as NestedCommand } from './nestedCommands/Find';
                assert.deepEqual(new Nested().validate({input: {name: 'a'}}).map(r => r.members), [['input.name']]);
                assert.equal(new Nested().validate({input: {name: 'ab'}}).length, 0);
                assert.deepEqual(new Plain().validate({term: ''}).map(r => r.members), [['term']]);
                assert.equal(new Plain().validate({term: 'ok'}).length, 0);
                assert.deepEqual(new Command().validate({term: ''}).map(r => r.members), [['term']]);
                assert.equal(new Command().validate({term: 'ok'}).length, 0);
                assert.deepEqual(new NestedCommand().validate({child: {name: 'a'}}).map(r => r.members), [['child.name']]);
                assert.equal(new NestedCommand().validate({child: {name: ' a '}}).length, 0);
            """.trimIndent())
        }
    }

    @Test
    fun `nullable shared arrays retain null on container not entries`() {
        val root = root()
        val shape = TypeShapeDescriptor.sequence(SequenceKind.ARRAY, TypeShapeDescriptor.value("models.Person"), nullable = true)
        val query = query("batch", ParameterDescriptor("inputs", shape, QueryParameterSource.CLIENT, true))
        TypeScriptProxyGenerator(MergedArcArtifacts(emptyList(), listOf(query), listOf(person), emptyList(), sharedValidators = listOf(shared)),
            ProxyGenerationOptions(root, ApiEndpointOptions(enableQueryHttpMethod = true), false, 0)).generate()
        val generated = root.resolve("batch/Find.ts").readText()
        assertTrue("inputs?: Person[] | null;" in generated, generated)
        execute(root, """
            import assert from 'node:assert/strict';
            import { FindValidator } from './batch/Find';
            assert.equal(new FindValidator().validate({}).length, 0);
            assert.equal(new FindValidator().validate({inputs: null}).length, 0);
            assert.deepEqual(new FindValidator().validate({inputs: [{name: 'a'}]}).map(r => r.members), [['inputs[0].name']]);
        """.trimIndent())
    }

    private fun execute(root: File, source: String) {
        val script = root.resolve("probe.ts").apply { writeText(source) }
        val modules = File(System.getProperty("arc.mapping.nodeModules"))
        val log = root.resolve("client.log")
        val process = ProcessBuilder("node", "--require", modules.resolve("tsx/dist/cjs/index.cjs").absolutePath, script.absolutePath)
            .directory(root).redirectErrorStream(true).redirectOutput(log).apply { environment()["NODE_PATH"] = modules.absolutePath }.start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), log.readText())
    }
}
