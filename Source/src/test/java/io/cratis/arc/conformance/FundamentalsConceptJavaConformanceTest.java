// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.json.ArcObjectMapper;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.validation.ConceptValidator;
import io.cratis.fundamentals.concepts.ConceptAs;
import io.cratis.fundamentals.strings.StringExtensions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FundamentalsConceptJavaConformanceTest {
    public record OrderId(UUID value) implements ConceptAs<UUID> { }

    @SuppressWarnings("deprecation")
    public record LegacyCode(String value) implements io.cratis.arc.concepts.ConceptAs<String> { }

    @Test
    void fundamentalsRecordRoundTripsAndSupportsJavaValidators() {
        var mapper = ArcObjectMapper.create();
        var id = new OrderId(UUID.fromString("3c6b4a11-4512-4cb6-b67d-2edbc7543d5b"));
        var json = "\"" + id.value() + "\"";
        assertEquals(json, mapper.writeValueAsString(id));
        assertEquals(id, mapper.readValue(json, OrderId.class));
        ConceptValidator<OrderId> validator = new ConceptValidator<>() {
            @Override public Class<OrderId> getConceptType() { return OrderId.class; }
            @Override public List<ValidationResult> validate(OrderId concept) {
                return List.of(ValidationResult.error("order"));
            }
        };
        assertEquals(OrderId.class, validator.getConceptType());
        assertEquals("order", validator.validate(id).get(0).getMessage());
        assertEquals("orderId", StringExtensions.toCamelCase("OrderId"));
    }

    @Test
    void legacyRecordStillRoundTripsAsAScalar() {
        var mapper = ArcObjectMapper.create();
        var legacy = new LegacyCode("hello");
        assertTrue(ConceptAs.class.isInstance(legacy));
        assertEquals("\"hello\"", mapper.writeValueAsString(legacy));
        assertEquals(legacy, mapper.readValue("\"hello\"", LegacyCode.class));
    }
}
