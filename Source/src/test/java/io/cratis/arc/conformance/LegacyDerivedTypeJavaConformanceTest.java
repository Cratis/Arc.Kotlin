// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.json.ArcObjectMapper;
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Java consumer contract for the deprecated Arc {@code @DerivedType} annotation. */
@SuppressWarnings("deprecation")
final class LegacyDerivedTypeJavaConformanceTest {
    @Test
    void aJavaRecordWithTheDeprecatedArcAnnotationStillRoundTrips() throws Exception {
        ConcurrentDerivedTypeRegistry registry = new ConcurrentDerivedTypeRegistry();
        registry.register(LegacyShape.class, LegacyCircle.class);
        ObjectMapper mapper = ArcObjectMapper.create(registry);

        String json = mapper.writeValueAsString(new LegacyCircle(2.0));

        assertEquals("{\"radius\":2.0,\"_derivedTypeId\":\"legacy-circle\"}", json);
        assertEquals(new LegacyCircle(2.0), mapper.readValue(json, LegacyShape.class));
    }

    /** Polymorphic base. */
    public interface LegacyShape {
    }

    /** Record derivative annotated with the deprecated Arc annotation. */
    @io.cratis.arc.polymorphism.DerivedType(id = "legacy-circle")
    public record LegacyCircle(double radius) implements LegacyShape {
    }
}
