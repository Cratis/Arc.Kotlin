// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.json.ArcObjectMapper;
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry;
import io.cratis.fundamentals.serialization.DerivedType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Java consumer contract for the Fundamentals {@code @DerivedType} annotation. */
final class FundamentalsDerivedTypeJavaConformanceTest {
    @Test
    void aJavaRecordWithThePositionalFundamentalsAnnotationRoundTrips() throws Exception {
        ConcurrentDerivedTypeRegistry registry = new ConcurrentDerivedTypeRegistry();
        registry.register(FundamentalsShape.class, FundamentalsCircle.class);
        ObjectMapper mapper = ArcObjectMapper.create(registry);

        String json = mapper.writeValueAsString(new FundamentalsCircle(2.0));

        assertEquals("{\"radius\":2.0,\"_derivedTypeId\":\"circle\"}", json);
        assertEquals(new FundamentalsCircle(2.0), mapper.readValue(json, FundamentalsShape.class));
    }

    /** Polymorphic base. */
    public interface FundamentalsShape {
    }

    /** Record derivative annotated with the Fundamentals annotation. */
    @DerivedType("circle")
    public record FundamentalsCircle(double radius) implements FundamentalsShape {
    }
}
