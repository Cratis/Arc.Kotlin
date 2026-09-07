// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.metadata.AuthorizationMetadata;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.metadata.DocumentationSummaries;
import io.cratis.arc.metadata.EnumDescriptor;
import io.cratis.arc.metadata.InterfaceDescriptor;
import io.cratis.arc.metadata.ParameterDescriptor;
import io.cratis.arc.metadata.PropertyDescriptor;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.metadata.QueryParameterSource;
import io.cratis.arc.metadata.RouteOptions;
import io.cratis.arc.metadata.TypeDescriptor;
import io.cratis.arc.metadata.TypeShapeDescriptor;
import io.cratis.arc.queries.QueryHttpMethodType;
import io.cratis.arc.queries.QueryTransportType;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DocumentationSummaryJavaConformanceTest {
    @Test
    void documentationSummariesValidatesFromJava() {
        assertNull(DocumentationSummaries.validate(null, "sample.Fixture"));
        assertEquals("Documented.", DocumentationSummaries.validate("Documented.", "sample.Fixture"));
        assertEquals(512, DocumentationSummaries.MAX_LENGTH_IN_CODE_POINTS);
        assertThrows(
            IllegalArgumentException.class,
            () -> DocumentationSummaries.validate("two\nlines", "sample.Fixture"));
    }

    @Test
    void everyDescriptorAcceptsASummaryFromAnOrdinaryJavaConstructor() {
        TypeShapeDescriptor shape = TypeShapeDescriptor.value("kotlin.String");

        PropertyDescriptor property = new PropertyDescriptor(
            "value", shape, false, List.of(), false, List.of(), "Documented property.");
        ParameterDescriptor parameter = new ParameterDescriptor(
            "filter", shape, QueryParameterSource.CLIENT, false, List.of(), false, "Documented parameter.");
        CommandDescriptor command = new CommandDescriptor(
            "Run",
            "sample.Run",
            List.of(property),
            new RouteOptions(),
            List.of("sample"),
            new AuthorizationMetadata(),
            null,
            false,
            null,
            false,
            List.of(),
            "Documented command.");
        QueryDescriptor query = new QueryDescriptor(
            "find",
            "sample.Queries",
            shape,
            List.of(parameter),
            new RouteOptions(),
            "sample.Queries.find",
            List.of("sample"),
            new AuthorizationMetadata(),
            null,
            QueryHttpMethodType.AUTO,
            QueryTransportType.REQUEST_RESPONSE,
            false,
            false,
            false,
            "Documented query.");
        TypeDescriptor type = new TypeDescriptor(
            "Model", "sample.Model", List.of("sample"), List.of(property), null, null, "Documented type.");
        InterfaceDescriptor contract = new InterfaceDescriptor(
            "Contract", "sample.Contract", List.of("sample"), List.of(property), "Documented interface.");
        EnumDescriptor state = new EnumDescriptor(
            "State", "sample.State", List.of("sample"), List.of(), false, "Documented enum.");

        assertEquals("Documented property.", property.getSummary());
        assertEquals("Documented parameter.", parameter.getSummary());
        assertEquals("Documented command.", command.getSummary());
        assertEquals("Documented query.", query.getSummary());
        assertEquals("Documented type.", type.getSummary());
        assertEquals("Documented interface.", contract.getSummary());
        assertEquals("Documented enum.", state.getSummary());
    }

    @Test
    void descriptorsBuiltWithoutASummaryReportNone() {
        TypeShapeDescriptor shape = TypeShapeDescriptor.value("kotlin.String");

        assertNull(new PropertyDescriptor("value", shape).getSummary());
        assertNull(new ParameterDescriptor("filter", shape, QueryParameterSource.CLIENT, false).getSummary());
        assertNull(new CommandDescriptor("Run", "sample.Run").getSummary());
        assertNull(new QueryDescriptor("find", "sample.Queries", shape).getSummary());
        assertNull(new TypeDescriptor("Model", "sample.Model").getSummary());
        assertNull(new InterfaceDescriptor("Contract", "sample.Contract").getSummary());
        assertNull(new EnumDescriptor("State", "sample.State").getSummary());
    }
}
