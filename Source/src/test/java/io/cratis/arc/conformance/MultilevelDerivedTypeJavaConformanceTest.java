// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cratis.arc.json.ArcObjectMapper;
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry;
import io.cratis.arc.polymorphism.DerivedType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Executes the multilevel registry contract using only Java-visible public Arc APIs. */
final class MultilevelDerivedTypeJavaConformanceTest {
    private static final String MIDDLE_JSON =
        "{\"_derivedTypeId\":\"java-middle\",\"inherited\":\"root-value\",\"middle\":\"middle-value\"}";
    private static final String LEAF_JSON =
        "{\"_derivedTypeId\":\"java-leaf\",\"inherited\":\"leaf-root\","
            + "\"middle\":\"leaf-middle\",\"leaf\":\"leaf-value\"}";

    private final ObjectMapper mapper = ArcObjectMapper.create(registry());

    @Test
    void rootSelectsExactMiddleFromJava() throws Exception {
        var value = mapper.readValue(MIDDLE_JSON, Root.class);

        assertEquals(Middle.class, value.getClass());
        assertEquals("root-value", value.inherited);
        assertEquals("middle-value", ((Middle) value).middle);
        assertNull(((Middle) value).child);
    }

    @Test
    void rootSelectsExactLeafFromJava() throws Exception {
        assertLeaf(mapper.readValue(LEAF_JSON, Root.class));
    }

    @Test
    void middleSelectsExactLeafFromJava() throws Exception {
        assertLeaf(mapper.readValue(LEAF_JSON, Middle.class));
    }

    @Test
    void rootRegistrationDoesNotAllowMiddleToSelectItselfFromJava() {
        assertThrows(JsonMappingException.class, () -> mapper.readValue(MIDDLE_JSON, Middle.class));
    }

    @Test
    void explicitSelfRegistrationAllowsExactMiddleFromJava() throws Exception {
        var explicit = registry();
        explicit.register(Middle.class, Middle.class);
        var configured = ArcObjectMapper.create(explicit);
        var value = configured.readValue(MIDDLE_JSON, Middle.class);

        assertEquals(Middle.class, value.getClass());
        assertEquals("root-value", value.inherited);
        assertEquals("middle-value", value.middle);
    }

    @Test
    void resolvedMiddleReadsAnIndependentlyRegisteredChildFromJava() throws Exception {
        var json = "{\"_derivedTypeId\":\"java-middle\",\"inherited\":\"root-value\","
            + "\"middle\":\"middle-value\",\"child\":" + LEAF_JSON + "}";
        var value = mapper.readValue(json, Root.class);

        assertEquals(Middle.class, value.getClass());
        assertLeaf(((Middle) value).child);
    }

    @Test
    void resolvedMiddleCannotBypassItsChildAllowlistFromJava() {
        var json = "{\"_derivedTypeId\":\"java-middle\",\"inherited\":\"root-value\","
            + "\"middle\":\"middle-value\",\"child\":" + MIDDLE_JSON + "}";
        var exception = assertThrows(JsonMappingException.class, () -> mapper.readValue(json, Root.class));

        assertEquals(1, exception.getPath().size());
        assertEquals("child", exception.getPath().get(0).getFieldName());
    }

    private static ConcurrentDerivedTypeRegistry registry() {
        var registry = new ConcurrentDerivedTypeRegistry();
        registry.register(Root.class, Middle.class);
        registry.register(Root.class, Leaf.class);
        registry.register(Middle.class, Leaf.class);
        return registry;
    }

    private static void assertLeaf(Root value) {
        assertEquals(Leaf.class, value.getClass());
        assertEquals("leaf-root", value.inherited);
        assertEquals("leaf-middle", ((Leaf) value).middle);
        assertEquals("leaf-value", ((Leaf) value).leaf);
    }

    public abstract static class Root {
        public final String inherited;

        protected Root(String inherited) {
            this.inherited = inherited;
        }
    }

    @DerivedType(id = "java-middle")
    public static class Middle extends Root {
        public final String middle;
        public final Middle child;

        @JsonCreator
        public Middle(
            @JsonProperty("inherited") String inherited,
            @JsonProperty("middle") String middle,
            @JsonProperty("child") Middle child
        ) {
            super(inherited);
            this.middle = middle;
            this.child = child;
        }
    }

    @DerivedType(id = "java-leaf")
    public static final class Leaf extends Middle {
        public final String leaf;

        @JsonCreator
        public Leaf(
            @JsonProperty("inherited") String inherited,
            @JsonProperty("middle") String middle,
            @JsonProperty("leaf") String leaf
        ) {
            super(inherited, middle, null);
            this.leaf = leaf;
        }
    }
}
