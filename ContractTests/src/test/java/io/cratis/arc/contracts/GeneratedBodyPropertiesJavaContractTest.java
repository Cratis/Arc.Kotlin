// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.CommandExecutionOptions;
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry;
import io.cratis.arc.commands.DefaultCommandPipeline;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.contracts.fixtures.BodyOutput;
import io.cratis.arc.contracts.fixtures.KotlinBodyCommand;
import io.cratis.arc.generated.ContractTestsArcArtifactModule;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.json.ArcObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GeneratedBodyPropertiesJavaContractTest {
    @Test
    void ordinaryJavaConsumerUsesGeneratedBodyKeyAndInvokesMixedKotlinCommand() throws Exception {
        var mapper = ArcObjectMapper.create();
        var command = mapper.readValue("""
            {"zulu":"z","alpha":"a","backed":"java-backed","title":"java-title","id":"wire-key"}
            """, KotlinBodyCommand.class);
        command.setId("java-key");
        command.getChild().setLast("java-last");
        var module = new ContractTestsArcArtifactModule();
        var handler = module.getCommandHandlers().stream()
            .filter(candidate -> candidate.getCommandType().equals(KotlinBodyCommand.class)).findFirst().orElseThrow();
        assertEquals("java-key", handler.resolveCommandKey(command));
        assertEquals(List.of("zulu", "alpha", "URLValue", "backed", "child", "id", "title"),
            handler.getMetadata().getProperties().stream().map(property -> property.getName()).toList());
        var registry = new ConcurrentCommandHandlerRegistry();
        registry.register(handler);
        ServiceResolver services = new ServiceResolver() {
            @Override public <T> T resolve(Class<T> type) { return null; }
        };
        var executor = Executors.newSingleThreadExecutor();
        try (var scope = JavaAsyncScope.owningExecutorService(executor)) {
            var result = scope.commands(new DefaultCommandPipeline(registry)).execute(command,
                new CommandExecutionOptions(UUID.randomUUID(), new ArcPrincipal(), services))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(result.isSuccess(), result.getExceptionMessages().toString());
            var output = (BodyOutput) result.getResponse();
            assertEquals("java-key", output.getId());
            assertEquals("java-title:java-backed:java-last:url", output.getDetail());
            assertEquals("output-java-key", output.getLabel());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
