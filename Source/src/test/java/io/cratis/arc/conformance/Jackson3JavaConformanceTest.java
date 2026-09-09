// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.json.ArcObjectMapper;
import io.cratis.arc.json.ArcPropertyNamingStrategy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Java-facing contract for Arc's immutable Jackson 3 mapper configuration. */
final class Jackson3JavaConformanceTest {
    @Test
    void configureReturnsAnArcConfiguredCopyWithoutMutatingTheInput() {
        JsonMapper original = JsonMapper.builder().build();

        ObjectMapper configured = ArcObjectMapper.configure(original);

        assertNotSame(original, configured);
        assertNull(original.serializationConfig().getPropertyNamingStrategy());
        assertInstanceOf(ArcPropertyNamingStrategy.class,
            configured.serializationConfig().getPropertyNamingStrategy());
    }
}
