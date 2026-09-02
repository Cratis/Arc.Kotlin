// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.HandlesCommandResponseValues;
import io.cratis.arc.java.BlockingCommandResponseValueHandler;
import io.cratis.arc.metadata.AuthorizationMetadata;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.metadata.CommandResponseValueDescriptor;
import io.cratis.arc.metadata.CommandResponseValueDisposition;
import io.cratis.arc.metadata.RouteOptions;
import io.cratis.arc.results.CommandResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CommandResponseMetadataJavaConformanceTest {
    @Test
    void annotationExposesOrdinaryJavaClassArray() {
        HandlesCommandResponseValues annotation = JavaHandler.class.getAnnotation(HandlesCommandResponseValues.class);

        assertArrayEquals(new Class<?>[] {HandledValue.class, OtherHandledValue.class}, annotation.value());
    }

    @Test
    void descriptorsAreConstructibleAndImmutableFromJava() {
        CommandResponseValueDescriptor handled = new CommandResponseValueDescriptor(
            HandledValue.class.getName(), true, CommandResponseValueDisposition.HANDLED);
        CommandResponseValueDescriptor client = new CommandResponseValueDescriptor(
            String.class.getName(), false, CommandResponseValueDisposition.CLIENT);
        List<CommandResponseValueDescriptor> values = new ArrayList<>(List.of(handled, client));
        CommandDescriptor descriptor = new CommandDescriptor(
            "Run",
            "tests.Run",
            List.of(),
            new RouteOptions(),
            List.of("tests"),
            new AuthorizationMetadata(),
            null,
            false,
            null,
            false,
            values);

        values.clear();

        assertEquals(List.of(handled, client), descriptor.getResponseValues());
        assertEquals(String.class.getName(), descriptor.getResponseTypeName());
        assertFalse(descriptor.getResponseIsEnumerable());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.getResponseValues().add(handled));
    }

    @Test
    void staticFactoryCopiesImmutableValuesAndProjectsCompatibilityMetadata() {
        CommandResponseValueDescriptor handled = new CommandResponseValueDescriptor(
            HandledValue.class.getName(), true, CommandResponseValueDisposition.HANDLED);
        CommandResponseValueDescriptor client = new CommandResponseValueDescriptor(
            String.class.getName(), false, CommandResponseValueDisposition.CLIENT);
        List<CommandResponseValueDescriptor> values = new ArrayList<>(List.of(handled, client));

        CommandDescriptor descriptor = CommandDescriptor.withResponseValues("Run", "tests.Run", values);
        values.clear();

        assertEquals(List.of(handled, client), descriptor.getResponseValues());
        assertEquals(String.class.getName(), descriptor.getResponseTypeName());
        assertFalse(descriptor.getResponseIsEnumerable());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.getResponseValues().add(handled));
    }

    @Test
    void legacyJavaConstructorProjectsResponseMetadata() {
        CommandDescriptor descriptor = new CommandDescriptor(
            "Find",
            "tests.Find",
            List.of(),
            new RouteOptions(),
            List.of("tests"),
            new AuthorizationMetadata(),
            null,
            false,
            String.class.getName(),
            true);

        assertEquals(String.class.getName(), descriptor.getResponseValues().get(0).getTypeName());
        assertEquals(CommandResponseValueDisposition.CLIENT, descriptor.getResponseValues().get(0).getDisposition());
        assertEquals(true, descriptor.getResponseValues().get(0).isEnumerable());
    }

    @Test
    void contradictoryJavaCompatibilityMetadataFailsFast() {
        CommandResponseValueDescriptor client = new CommandResponseValueDescriptor(
            String.class.getName(), true, CommandResponseValueDisposition.CLIENT);

        assertThrows(IllegalArgumentException.class, () -> new CommandDescriptor(
            "Find",
            "tests.Find",
            List.of(),
            new RouteOptions(),
            List.of("tests"),
            new AuthorizationMetadata(),
            null,
            false,
            Integer.class.getName(),
            true,
            List.of(client)));
    }

    @HandlesCommandResponseValues({HandledValue.class, OtherHandledValue.class})
    private static final class JavaHandler implements BlockingCommandResponseValueHandler {
        @Override
        public boolean canHandle(CommandContext context, Object value) {
            return value instanceof HandledValue || value instanceof OtherHandledValue;
        }

        @Override
        public CommandResult<?> handle(CommandContext context, Object value) {
            return CommandResult.success(context.getCorrelationId());
        }
    }

    private static final class HandledValue {
    }

    private static final class OtherHandledValue {
    }
}
