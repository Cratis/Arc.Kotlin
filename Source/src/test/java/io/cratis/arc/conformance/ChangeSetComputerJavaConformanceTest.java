// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.queries.ChangeSetComputer;
import io.cratis.arc.results.ChangeSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java-facing contract for keyless collection deltas. */
final class ChangeSetComputerJavaConformanceTest {
    @Test
    void keylessValuesUseSerializedAddAndRemoveIdentity() {
        Keyless before = new Keyless("before");
        Keyless after = new Keyless("after");

        ChangeSet<?> changeSet = new ChangeSetComputer().compute(List.of(before), List.of(after));

        assertEquals(List.of(after), changeSet.getAdded());
        assertEquals(List.of(before), changeSet.getRemoved());
        assertTrue(changeSet.getReplaced().isEmpty());
    }

    private record Keyless(String value) {
    }
}
