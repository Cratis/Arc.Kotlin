// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts;

import io.cratis.arc.commands.CommandKeyProvider;
import io.cratis.chronicle.events.EventType;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Java compile conformance for command keys and Chronicle event response shapes. */
class CommandKeyProviderJavaContractTest {
    @Test
    void java_commands_can_provide_keys_and_return_event_shapes() {
        var command = new JavaCommand("java-key");

        assertEquals("java-key", command.commandKey());
        assertEquals("one", command.oneEvent().value());
        assertEquals(List.of(new JavaEvent("one"), new JavaEvent("two")), command.manyEvents());
    }

    record JavaCommand(String key) implements CommandKeyProvider {
        @Override
        public Object commandKey() {
            return key;
        }

        JavaEvent oneEvent() {
            return new JavaEvent("one");
        }

        List<JavaEvent> manyEvents() {
            return List.of(new JavaEvent("one"), new JavaEvent("two"));
        }
    }

    @EventType
    record JavaEvent(String value) {
    }
}
