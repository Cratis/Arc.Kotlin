// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cratis.arc.json.ArcObjectMapper;
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry;
import io.cratis.arc.polymorphism.DerivedType;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves typed reads fail at binding rather than at an ordinary Java field access. */
final class FixedDerivedTypeBindingsJavaConformanceTest {
    private static final String STRING_JSON = "{\"_derivedTypeId\":\"fixed\",\"payload\":\"text\"}";
    private static final String LIST_JSON = "{\"_derivedTypeId\":\"list\",\"payload\":[\"text\"]}";
    private final ObjectMapper mapper = mapper();

    @Test
    void incompatibleFixedBindingFailsDuringTypedJavaRead() {
        var failure = assertThrows(JsonMappingException.class,
            () -> mapper.readValue(STRING_JSON, new TypeReference<Base<Payload>>() {}));
        assertTrue(failure.getOriginalMessage().contains(FixedStringLeaf.class.getName()));
        assertTrue(failure.getOriginalMessage().contains(Payload.class.getName()));
        assertTrue(failure.getOriginalMessage().contains("base<T>"));
    }

    @Test
    void javaFieldAccessDoesNotReceiveAnIncompatibleValue() {
        assertThrows(JsonMappingException.class, () -> {
            Base<Payload> result = mapper.readValue(STRING_JSON, new TypeReference<Base<Payload>>() {});
            // No unchecked consumer cast: before the fix, this ordinary read threw ClassCastException instead.
            Payload payload = result.payload;
            assertEquals("text", payload.name());
        });
    }

    @Test
    void nestedJavaPropertyRetainsItsRequestedBinding() {
        var failure = assertThrows(JsonMappingException.class,
            () -> mapper.readValue("{\"value\":" + STRING_JSON + "}", Envelope.class));
        assertEquals("value", failure.getPath().get(0).getFieldName());
    }

    @Test
    void javaListElementRetainsItsRequestedBinding() {
        var failure = assertThrows(JsonMappingException.class,
            () -> mapper.readValue("[" + STRING_JSON + "]", new TypeReference<List<Base<Payload>>>() {}));
        assertEquals(0, failure.getPath().get(0).getIndex());
    }

    @Test
    void nestedGenericBindingFailsBeforeJavaCollectionElementAccess() {
        assertThrows(JsonMappingException.class, () -> {
            Base<List<Payload>> result = mapper.readValue(LIST_JSON, new TypeReference<Base<List<Payload>>>() {});
            Payload payload = result.payload.get(0);
            assertEquals("text", payload.name());
        });
    }

    @Test
    void compatibleBindingsRemainReadableFromJava() throws Exception {
        Base<String> strings = mapper.readValue(STRING_JSON, new TypeReference<Base<String>>() {});
        String string = strings.payload;
        assertEquals("text", string);
        Base<Object> objects = mapper.readValue(STRING_JSON, new TypeReference<Base<Object>>() {});
        assertEquals("text", objects.payload);
        Base<CharSequence> sequences = mapper.readValue(STRING_JSON, new TypeReference<Base<CharSequence>>() {});
        CharSequence sequence = sequences.payload;
        assertEquals("text", sequence.toString());
        assertEquals("text", mapper.readValue(STRING_JSON, Base.class).payload);
        Base<List<Object>> lists = mapper.readValue(LIST_JSON, new TypeReference<Base<List<Object>>>() {});
        assertEquals("text", lists.payload.get(0));
    }

    @Test
    void nullValuesAndUnboundedRequestsRemainSupportedFromJava() throws Exception {
        assertNull(mapper.readValue("null", new TypeReference<Base<Payload>>() {}));
        Base<String> nullable = mapper.readValue("{\"_derivedTypeId\":\"fixed\",\"payload\":null}",
            new TypeReference<Base<String>>() {});
        assertNull(nullable.payload);
        Base<?> unknown = mapper.readValue(STRING_JSON, new TypeReference<Base<?>>() {});
        assertEquals("text", unknown.payload);
        Base<List<?>> unknownElements = mapper.readValue(LIST_JSON, new TypeReference<Base<List<?>>>() {});
        assertEquals("text", unknownElements.payload.get(0));
    }

    private static ObjectMapper mapper() {
        var registry = new ConcurrentDerivedTypeRegistry();
        registry.register(Base.class, FixedStringLeaf.class);
        registry.register(Base.class, FixedListLeaf.class);
        return ArcObjectMapper.create(registry);
    }

    public abstract static class Base<T> {
        public T payload;
    }

    @DerivedType(id = "fixed")
    public static final class FixedStringLeaf extends Base<String> { }

    @DerivedType(id = "list")
    public static final class FixedListLeaf extends Base<List<String>> { }

    public record Payload(String name) { }

    public record Envelope(Base<Payload> value) { }
}
