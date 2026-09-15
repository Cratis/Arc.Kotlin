// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandExecutionOptions;
import io.cratis.arc.commands.CommandExecutionScope;
import io.cratis.arc.commands.CommandKeyProvider;
import io.cratis.arc.commands.CommandResponseValues;
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry;
import io.cratis.arc.commands.DefaultCommandPipeline;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.java.BlockingCommandFilterAdapter;
import io.cratis.arc.java.BlockingCommandHandler;
import io.cratis.arc.java.BlockingCommandHandlerAdapter;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.results.CommandResult;
import io.cratis.chronicle.eventSequences.EventForEventSourceId;
import io.cratis.chronicle.events.EventType;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChronicleCommandKeyRoutingJavaConformanceTest {
    private static final ServiceResolver SERVICES = new ServiceResolver() {
        @Override
        public <T> T resolve(Class<T> type) {
            return null;
        }
    };

    @ParameterizedTest(name = "{0}, staged={1}, throwOnSecondCall={2}")
    @CsvSource({
        "single,false,false", "single,false,true", "single,true,false", "single,true,true",
        "many,false,false", "many,false,true", "many,true,false", "many,true,true",
        "mixed,false,false", "mixed,false,true", "mixed,true,false", "mixed,true,true",
        "separate,true,false", "separate,true,true"
    })
    void routingRetainsKeyCapturedBeforeValidationAndCommandMutation(
        String shape, boolean staged, boolean throwOnSecondCall
    ) throws Exception {
        ChangingKeyCommand command = new ChangingKeyCommand("captured-source", throwOnSecondCall);
        ChronicleRoutingEventSink sink = new ChronicleRoutingEventSink();
        CommandResult<?> result = execute(command, response(shape), staged, sink);

        assertTrue(result.isSuccess(), result.getExceptionMessages().toString());
        assertNull(result.getResponse());
        assertEquals(1, command.keyCalls);
        assertEquals("changed-source", command.key);
        assertEquals(1, sink.getAppendCalls());
        assertEquals(
            staged || shape.equals("mixed") ? "routed-batch" : shape.equals("single") ? "single" : "plain-batch",
            sink.getAppendKind());
        assertEquals(
            switch (shape) {
                case "single" -> List.of("captured-source");
                case "many", "separate" -> List.of("captured-source", "captured-source");
                default -> List.of("captured-source", "explicit-source");
            },
            sink.getEvents().stream().map(EventForEventSourceId::getEventSourceId).toList());
        if (staged) {
            assertEquals(1L, sink.getEvents().stream().map(EventForEventSourceId::getCausation).distinct().count());
        }
        for (EventForEventSourceId event : sink.getEvents()) {
            assertEquals(1, event.getCausation().size());
            assertEquals("captured-source", event.getCausation().get(0).getProperties().get("commandKey"));
        }
    }

    @ParameterizedTest(name = "{0}, staged={1}")
    @CsvSource({
        "single,false", "single,true", "many,false", "many,true",
        "mixed,false", "mixed,true", "routed,false", "routed,true"
    })
    void nullCapturedKeyFailsClosedOnlyWhenPlainEventsNeedRouting(String shape, boolean staged) throws Exception {
        ChangingKeyCommand command = new ChangingKeyCommand(null, false);
        ChronicleRoutingEventSink sink = new ChronicleRoutingEventSink();
        CommandResult<?> result = execute(command, response(shape), staged, sink);

        assertEquals(1, command.keyCalls);
        assertEquals("changed-source", command.key);
        assertNull(result.getResponse());
        assertTrue(result.getExceptionMessages().isEmpty());
        if (shape.equals("routed")) {
            assertTrue(result.isSuccess());
            assertEquals(1, sink.getEvents().size());
            EventForEventSourceId event = sink.getEvents().get(0);
            assertEquals("explicit-source", event.getEventSourceId());
            assertEquals(1, event.getCausation().size());
            assertFalse(event.getCausation().get(0).getProperties().containsKey("commandKey"));
        } else {
            assertFalse(result.isSuccess());
            assertEquals(1, result.getValidationResults().size());
            assertEquals("commandKey", result.getValidationResults().get(0).getReasonDetail());
            assertEquals(0, sink.getAppendCalls());
            assertTrue(sink.getEvents().isEmpty());
        }
    }

    private static CommandResult<?> execute(
        ChangingKeyCommand command, Object response, boolean staged, ChronicleRoutingEventSink sink
    ) throws Exception {
        String capturedKey = command.key;
        ConcurrentCommandHandlerRegistry registry = new ConcurrentCommandHandlerRegistry();
        registry.register(new BlockingCommandHandlerAdapter(new BlockingCommandHandler() {
            @Override
            public Class<?> getCommandType() {
                return ChangingKeyCommand.class;
            }

            @Override
            public CommandDescriptor getMetadata() {
                return new CommandDescriptor("ChangingKeyCommand", getCommandType().getName());
            }

            @Override
            public Object invoke(CommandContext context) {
                assertEquals(capturedKey, context.getCommandKey());
                command.key = "changed-source";
                return response;
            }
        }));
        BlockingCommandFilterAdapter validation = new BlockingCommandFilterAdapter(context -> {
            assertEquals(capturedKey, context.getCommandKey());
            assertEquals(1, command.keyCalls);
            return CommandResult.success(context.getCorrelationId());
        });
        ChronicleCommandTransaction transactions = staged ? new ChronicleCommandTransaction() : null;
        List<CommandExecutionScope> scopes = staged
            ? List.of(new ChronicleCommandExecutionScope(transactions)) : List.of();
        DefaultCommandPipeline pipeline = new DefaultCommandPipeline(
            registry, List.of(validation), scopes,
            List.of(new ChronicleCommandResponseValueHandler(namespace -> sink.getEventStore(), registry, transactions)));
        try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(Runnable::run)) {
            return scope.commands(pipeline).execute(
                command, new CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), SERVICES))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static Object response(String shape) {
        return switch (shape) {
            case "single" -> new RoutingEvent("first");
            case "many" -> List.of(new RoutingEvent("first"), new RoutingEvent("second"));
            case "separate" -> new CommandResponseValues(List.of(new RoutingEvent("first"), new RoutingEvent("second")));
            case "mixed" -> List.of(
                new RoutingEvent("first"), new EventForEventSourceId("explicit-source", new RoutingEvent("second")));
            case "routed" -> new EventForEventSourceId("explicit-source", new RoutingEvent("only"));
            default -> throw new IllegalArgumentException("Unexpected shape: " + shape);
        };
    }

    private static final class ChangingKeyCommand implements CommandKeyProvider {
        private String key;
        private final boolean throwOnSecondCall;
        private int keyCalls;

        private ChangingKeyCommand(String key, boolean throwOnSecondCall) {
            this.key = key;
            this.throwOnSecondCall = throwOnSecondCall;
        }

        @Override
        public Object commandKey() {
            keyCalls++;
            if (throwOnSecondCall && keyCalls != 1) {
                throw new IllegalStateException("The command key must not be resolved twice.");
            }
            return key;
        }
    }

    @EventType
    private record RoutingEvent(String value) {
    }
}
