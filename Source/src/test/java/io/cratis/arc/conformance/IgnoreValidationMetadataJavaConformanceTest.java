// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.json.ArcObjectMapper;
import io.cratis.arc.metadata.PropertyDescriptor;
import io.cratis.arc.metadata.TypeShapeDescriptor;
import io.cratis.arc.metadata.ValidationRuleDescriptor;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IgnoreValidationMetadataJavaConformanceTest {
    @Test
    void explicitEdgeFlagRoundTripsAndOldConstructorsRemainCallable() {
        var shape = TypeShapeDescriptor.value("java.lang.String");
        var descriptor = new PropertyDescriptor("name", shape, false, List.of(), false, List.of(), null, true);
        var mapper = ArcObjectMapper.create();
        String json = mapper.writeValueAsString(descriptor);
        assertTrue(json.contains("\"ignoreValidation\":true"), json);
        assertEquals(descriptor, mapper.readValue(json, PropertyDescriptor.class));
        assertFalse(new PropertyDescriptor("name", "java.lang.String").getIgnoreValidation());
        assertFalse(new PropertyDescriptor("name", shape).getIgnoreValidation());
        assertFalse(new PropertyDescriptor("name", shape, false, List.of(), false, List.of(), null).getIgnoreValidation());
        assertFalse(new PropertyDescriptor("name", null, null, null, null, null, null, null, null, shape).getIgnoreValidation());
        assertFalse(new PropertyDescriptor("name", null, null, null, null, null, null, null, null, shape, null).getIgnoreValidation());
        assertNotEquals(new PropertyDescriptor("name", shape), descriptor);
    }

    @Test
    void ignoredMetadataCannotSmuggleEffectiveRulesOrRecursiveTraversal() {
        var shape = TypeShapeDescriptor.value("java.lang.String");
        assertThrows(IllegalArgumentException.class, () -> new PropertyDescriptor("name", shape, false,
            List.of(new ValidationRuleDescriptor("notNull")), false, List.of(), null, true));
        assertThrows(IllegalArgumentException.class, () -> new PropertyDescriptor("name", shape, false,
            List.of(), true, List.of(), null, true));
    }
}
