// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.metadata.CommandResponseValueDescriptor;
import io.cratis.arc.metadata.CommandResponseValueDisposition;
import io.cratis.arc.metadata.MapKeyCodec;
import io.cratis.arc.metadata.ParameterDescriptor;
import io.cratis.arc.metadata.PropertyDescriptor;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.metadata.QueryParameterSource;
import io.cratis.arc.metadata.SequenceKind;
import io.cratis.arc.metadata.TypeShapeDescriptor;
import io.cratis.arc.metadata.TypeShapeKind;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TypeShapeMetadataJavaConformanceTest {
    @Test
    void factoriesAndDescriptorConstructorsAreOrdinaryJavaApis() {
        TypeShapeDescriptor value = TypeShapeDescriptor.value(String.class.getName(), true);
        TypeShapeDescriptor sequence = TypeShapeDescriptor.sequence(SequenceKind.LIST, value, false);
        TypeShapeDescriptor map = TypeShapeDescriptor.map(
            TypeShapeDescriptor.value(String.class.getName()),
            sequence,
            MapKeyCodec.STRING,
            true);

        PropertyDescriptor property = new PropertyDescriptor("values", map);
        ParameterDescriptor parameter = new ParameterDescriptor(
            "values", map, QueryParameterSource.QUERY_CONTEXT);
        ParameterDescriptor defaultedParameter = new ParameterDescriptor(
            "filter", value, QueryParameterSource.CLIENT, true);
        CommandResponseValueDescriptor response = new CommandResponseValueDescriptor(
            map,
            CommandResponseValueDisposition.CLIENT);
        QueryDescriptor query = new QueryDescriptor("values", "tests.Queries", map);

        assertEquals(TypeShapeKind.MAP, map.getKind());
        assertEquals(sequence, map.getValueShape());
        assertEquals(map, property.getShape());
        assertEquals(map, parameter.getShape());
        assertEquals(QueryParameterSource.QUERY_CONTEXT, parameter.getSource());
        assertTrue(!parameter.isFromServices());
        assertTrue(!parameter.getHasDefault());
        assertTrue(defaultedParameter.getHasDefault());
        assertEquals(map, response.getShape());
        assertEquals(map, query.getReturnShape());
        assertEquals("kotlin.collections.Map<java.lang.String, kotlin.collections.List<java.lang.String>>",
            property.getTypeName());
    }

    @Test
    void legacyParameterConstructorsRemainAvailableAndProjectServiceSource() throws Exception {
        ParameterDescriptor legacyShape = new ParameterDescriptor("service", TypeShapeDescriptor.value("tests.Value"), true);
        ParameterDescriptor legacyFlat = new ParameterDescriptor(
            "service", "tests.Value", false, true, false, null, List.of(), false);

        assertEquals(QueryParameterSource.SERVICE, legacyShape.getSource());
        assertEquals(QueryParameterSource.SERVICE, legacyFlat.getSource());
        assertTrue(legacyShape.isFromServices());
        assertTrue(legacyFlat.isFromServices());

        ParameterDescriptor.class.getConstructor(String.class, String.class);
        ParameterDescriptor.class.getConstructor(String.class, String.class, boolean.class, boolean.class);
        ParameterDescriptor.class.getConstructor(
            String.class,
            String.class,
            boolean.class,
            boolean.class,
            boolean.class,
            String.class,
            List.class,
            boolean.class);
        ParameterDescriptor.class.getConstructor(String.class, TypeShapeDescriptor.class);
        ParameterDescriptor.class.getConstructor(String.class, TypeShapeDescriptor.class, boolean.class);
        ParameterDescriptor.class.getConstructor(
            String.class,
            TypeShapeDescriptor.class,
            QueryParameterSource.class,
            boolean.class);
    }

    @Test
    void parameterDefaultsAreRejectedForEveryNonClientSource() {
        TypeShapeDescriptor shape = TypeShapeDescriptor.value("tests.Value");

        Arrays.stream(QueryParameterSource.values())
            .filter(source -> source != QueryParameterSource.CLIENT)
            .forEach(source -> assertThrows(
                IllegalArgumentException.class,
                () -> new ParameterDescriptor("value", shape, source, true)));
    }

    @Test
    void everyParameterSourceIsAnOrdinaryJavaEnumValue() {
        assertEquals(5, QueryParameterSource.values().length);
        assertEquals(QueryParameterSource.CLIENT, QueryParameterSource.valueOf("CLIENT"));
        assertEquals(QueryParameterSource.SERVICE, QueryParameterSource.valueOf("SERVICE"));
        assertEquals(QueryParameterSource.QUERY_REQUEST, QueryParameterSource.valueOf("QUERY_REQUEST"));
        assertEquals(QueryParameterSource.QUERY_CONTEXT, QueryParameterSource.valueOf("QUERY_CONTEXT"));
        assertEquals(QueryParameterSource.HOST_ADAPTER, QueryParameterSource.valueOf("HOST_ADAPTER"));
    }

    @Test
    void explicitConstructorRejectsContradictoryNodes() {
        assertThrows(IllegalArgumentException.class, () -> new TypeShapeDescriptor(
            TypeShapeKind.SEQUENCE,
            false,
            String.class.getName(),
            SequenceKind.LIST,
            TypeShapeDescriptor.value(String.class.getName()),
            null,
            null,
            null));
    }

    @Test
    void descriptorStateIsExposedOnlyThroughFinalFieldsAndGetters() {
        assertTrue(Arrays.stream(TypeShapeDescriptor.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .allMatch(field -> Modifier.isPrivate(field.getModifiers()) && Modifier.isFinal(field.getModifiers())));
        assertEquals(1, MapKeyCodec.values().length);
        assertEquals(MapKeyCodec.STRING, MapKeyCodec.values()[0]);
    }
}
