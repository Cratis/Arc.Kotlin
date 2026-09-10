// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.artifacts.CommandEventStreamIdProvider;
import io.cratis.arc.artifacts.CommandEventSubjectProvider;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.metadata.CommandEventMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Java-facing contract for typed command event defaults. */
final class CommandEventMetadataJavaConformanceTest {
    @Test
    void optionalSlotsHaveOrdinaryJavaConstructorsAndGetters() {
        CommandEventMetadata metadata = new CommandEventMetadata("Order", "Orders", "priority", "sales");

        assertEquals("Order", metadata.getEventSourceType());
        assertEquals("Orders", metadata.getEventStreamType());
        assertEquals("priority", metadata.getEventStreamId());
        assertEquals("sales", metadata.getSubject());

        CommandEventMetadata sourceOnly = new CommandEventMetadata("Order");
        assertEquals("Order", sourceOnly.getEventSourceType());
        assertNull(sourceOnly.getEventStreamType());
        assertSame(sourceOnly,
            CommandDescriptor.withEventMetadata("Run", "sample.Run", sourceOnly).getEventMetadata());
    }

    @Test
    void dynamicStreamAndSubjectProvidersAreOrdinaryJavaInterfaces() {
        DynamicCommand command = new DynamicCommand("stream-42", "subject-42");

        assertEquals("stream-42", command.eventStreamId());
        assertEquals("subject-42", command.eventSubject());
    }

    @Test
    void blankControlAndEmptyMetadataAreRejectedFromJava() {
        assertThrows(IllegalArgumentException.class, () -> new CommandEventMetadata(" "));
        assertThrows(IllegalArgumentException.class,
            () -> new CommandEventMetadata(null, null, null, "unsafe\nsubject"));
        assertThrows(IllegalArgumentException.class,
            () -> new CommandEventMetadata(null, null, null, null));
    }

    private record DynamicCommand(String streamId, String subject)
        implements CommandEventStreamIdProvider, CommandEventSubjectProvider {
        @Override
        public String eventStreamId() {
            return streamId;
        }

        @Override
        public String eventSubject() {
            return subject;
        }
    }
}
