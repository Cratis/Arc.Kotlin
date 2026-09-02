// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.Command;
import io.cratis.chronicle.eventSequences.EventForEventSourceId;

/** Java routed wrapper array whose generated metadata is consumed by the Chronicle response handler. */
@Command
public final class JavaRoutedEventArrayResponseCommand {
    /** Returns one valid routed Chronicle event. */
    public EventForEventSourceId[] handle() {
        return new EventForEventSourceId[] {
            new EventForEventSourceId("java-array", new MetadataEvent("java-array"))
        };
    }
}
