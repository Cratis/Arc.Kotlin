// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.EnumDescriptor
import io.cratis.arc.metadata.EnumMemberDescriptor
import io.cratis.arc.metadata.InterfaceDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.TypeDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Uses the locked real client packages, a separate external package, strict tsc and actual JS execution. */
internal class ArcProxyMappingRuntimeTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `primitive overrides compile and use real JavaScript constructors`() {
        val model = TypeDescriptor("Primitives", "app.Primitives", listOf("app"), listOf(
            PropertyDescriptor("text", "java.time.Duration"),
            PropertyDescriptor("quantity", "java.math.BigDecimal"),
            PropertyDescriptor("flag", "java.lang.Boolean")
        ))
        generate(listOf(ArcArtifactManifest("App", types = listOf(model))), listOf(
            "--type-to-typescript", "java.time.Duration=string",
            "--type-to-typescript", "java.math.BigDecimal=number",
            "--type-to-typescript", "java.lang.Boolean=boolean"
        ))
        runClient("""
            import assert from 'node:assert/strict';
            import { JsonSerializer } from '@cratis/fundamentals';
            import { Primitives } from './generated/Primitives';
            const value = JsonSerializer.deserialize(Primitives, '{"text":"PT5M","quantity":42,"flag":true}');
            assert.equal(value.text, 'PT5M');
            assert.equal(value.quantity, 42);
            assert.equal(value.flag, true);
            console.log('MAPPED_RUNTIME_OK');
        """.trimIndent())
    }

    @Test
    fun `external classes interfaces enums and arrays compile and hydrate through two manifests`() {
        val money = TypeDescriptor("Money", "shared.Money", listOf("shared"), listOf(PropertyDescriptor("amount", "kotlin.Int")))
        val contract = InterfaceDescriptor("View", "shared.View", listOf("shared"), listOf(PropertyDescriptor("name", "kotlin.String")))
        val state = EnumDescriptor("State", "shared.State", listOf("shared"), listOf(EnumMemberDescriptor("ready", 1)))
        val envelope = TypeDescriptor("Envelope", "app.Envelope", listOf("app"), listOf(
            PropertyDescriptor("money", "shared.Money"),
            PropertyDescriptor("items", "kotlin.collections.List", isEnumerable = true, elementTypeName = "shared.Money"),
            PropertyDescriptor("view", "shared.View"),
            PropertyDescriptor("state", "shared.State")
        ))
        val command = CommandDescriptor("Pay", "app.Pay", listOf(PropertyDescriptor("total", "shared.Money")),
            responseTypeName = "shared.Money", location = listOf("app"))
        val query = QueryDescriptor("lookupMoney", "app.Queries", "shared.Money", location = listOf("app"))
        val listQuery = QueryDescriptor("listMoney", "app.Queries", "shared.Money", location = listOf("app"), isEnumerable = true)
        generate(listOf(
            ArcArtifactManifest("Shared", types = listOf(money), interfaces = listOf(contract), enums = listOf(state)),
            ArcArtifactManifest("App", commands = listOf(command), queries = listOf(query, listQuery), types = listOf(envelope))
        ), listOf("--package-to-npm", "shared=@arc-test/models"))
        for (name in listOf("Money", "View", "State")) {
            assertFalse(directory.resolve("generated/$name.ts").toFile().exists())
        }
        write("node_modules/@arc-test/models/package.json", """{"name":"@arc-test/models","version":"1.0.0","type":"module","exports":"./index.ts"}""")
        write("node_modules/@arc-test/models/index.ts", """
            import { field } from '@cratis/fundamentals';
            export class Money { @field(Number) amount!: number; }
            export interface View { name: string; }
            export enum State { ready = 1 }
        """.trimIndent())
        runClient("""
            import assert from 'node:assert/strict';
            import { JsonSerializer } from '@cratis/fundamentals';
            import { Money, State } from '@arc-test/models';
            import { Envelope } from './generated/Envelope';
            import { Pay } from './generated/Pay';
            import { LookupMoney } from './generated/LookupMoney';
            import { ListMoney } from './generated/ListMoney';
            import { createServer } from 'node:http';
            import { Globals } from '@cratis/arc';
            const model = JsonSerializer.deserialize(Envelope,
                '{"money":{"amount":12},"items":[{"amount":3}],"view":{"name":"view"},"state":1}');
            assert.ok(model.money instanceof Money);
            assert.equal(model.money.amount, 12);
            assert.ok(model.items[0] instanceof Money);
            assert.equal(model.items[0].amount, 3);
            assert.equal(model.view.name, 'view');
            assert.equal(model.state, State.ready);
            const pay = new Pay();
            pay.total = model.money;
            assert.equal(pay.propertyDescriptors[0].type, Money);
            assert.equal(pay.total.amount, 12);
            // A local wire fixture exercises the real command and query HTTP/deserialization path, not a fake serializer.
            const server = createServer((request, response) => {
                response.setHeader('Content-Type', 'application/json');
                const common = { correlationId: '00000000-0000-0000-0000-000000000001',
                    isSuccess: true, isAuthorized: true, isValid: true, hasExceptions: false,
                    validationResults: [], exceptionMessages: [], exceptionStackTrace: '', authorizationFailureReason: '',
                    paging: { page: 0, size: 0, totalItems: 1, totalPages: 1 } };
                const path = request.url ?? '';
                response.end(JSON.stringify(request.method === 'POST'
                    ? { ...common, response: { amount: 24 } }
                    : { ...common, data: path.includes('list-money') ? [{ amount: 48 }] : { amount: 36 }, totalItems: 1 }));
            });
            await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
            try {
                const address = server.address();
                assert.ok(address && typeof address !== 'string');
                const origin = `http://127.0.0.1:${'$'}{address.port}`;
                Globals.origin = origin;
                Globals.apiBasePath = '';
                pay.setOrigin(origin);
                const result = await pay.execute();
                assert.equal(result.isSuccess, true, result.exceptionMessages.join('\n'));
                assert.ok(result.response instanceof Money);
                assert.equal(result.response.amount, 24);
                const single = await new LookupMoney().perform();
                assert.equal(single.isSuccess, true, JSON.stringify(single));
                assert.ok(single.data instanceof Money);
                assert.equal(single.data.amount, 36);
                const many = await new ListMoney().perform();
                assert.equal(many.isSuccess, true, JSON.stringify(many));
                assert.ok(many.data[0] instanceof Money);
                assert.equal(many.data[0].amount, 48);
            } finally {
                await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
            }
            console.log('MAPPED_RUNTIME_OK');
        """.trimIndent())
    }

    private fun generate(manifests: List<ArcArtifactManifest>, mappings: List<String>) {
        val root = directory.resolve("manifests/META-INF/cratis/arc").toFile().apply { mkdirs() }
        manifests.forEach { ArcObjectMapper.create().writeValue(root.resolve("${it.moduleName}.json"), it) }
        GenerateArcProxiesCli.main((listOf(
            "--manifest-classpath", directory.resolve("manifests").toString(),
            "--output-directory", directory.resolve("generated").toString(),
            "--proxy-segments-to-skip", "1"
        ) + mappings).toTypedArray())
    }

    private fun runClient(source: String) {
        val packages = Path.of(requireNotNull(System.getProperty("arc.mapping.nodeModules")))
        assertTrue(packages.resolve("typescript/bin/tsc").toFile().isFile, "Run :ContractTests:typeScriptInstall first")
        for (name in listOf("@cratis", "@types", "react", "tsx", "typescript")) {
            val target = directory.resolve("node_modules/$name")
            Files.createDirectories(target.parent)
            Files.createSymbolicLink(target, packages.resolve(name))
        }
        write("package.json", """{"private":true,"type":"module"}""")
        // Match the repository contract compiler: skip declaration-library checking for Arc's legacy JSX types,
        // while checking every generated .ts file, the external .ts package and this runtime consumer strictly.
        write("tsconfig.json", """
            {"compilerOptions":{"target":"ES2022","module":"ESNext","moduleResolution":"Bundler",
            "verbatimModuleSyntax":true,"strict":true,"noEmit":true,"experimentalDecorators":true,
            "useDefineForClassFields":false,"skipLibCheck":true,"types":["node"]},"include":["generated/**/*.ts","runner.ts"]}
        """.trimIndent())
        write("runner.ts", source)
        execute("node", packages.resolve("typescript/bin/tsc").toString(), "--project", "tsconfig.json")
        val output = execute("node", "--import", "tsx", "runner.ts")
        assertTrue("MAPPED_RUNTIME_OK" in output, output)
    }

    private fun execute(vararg command: String): String {
        val log = directory.resolve("process-${System.nanoTime()}.log").toFile()
        val process = ProcessBuilder(*command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Timed out: ${command.toList()}")
        }
        val output = log.readText()
        assertEquals(0, process.exitValue(), "${command.toList()}\n$output")
        return output
    }

    private fun write(path: String, text: String) {
        directory.resolve(path).toFile().apply { parentFile.mkdirs(); writeText(text) }
    }
}
