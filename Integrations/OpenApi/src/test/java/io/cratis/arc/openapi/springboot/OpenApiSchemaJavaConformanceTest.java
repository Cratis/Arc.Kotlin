// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.openapi.springboot;

import io.cratis.arc.artifacts.ArcArtifactModule;
import io.cratis.arc.json.ArcObjectMapper;
import io.cratis.arc.metadata.InterfaceDescriptor;
import io.cratis.arc.metadata.PropertyDescriptor;
import io.cratis.arc.metadata.TypeShapeDescriptor;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class OpenApiSchemaJavaConformanceTest {
    @Test
    void javaCallerGetsInterfaceWireNamesAndOptionalityUsingExistingShortOverloads() {
        var module = new ArcArtifactModule(List.of(), List.of(), List.of(), List.of(), List.of(
            new InterfaceDescriptor("NamedContract", "sample.NamedContract", List.of("sample"), List.of(
                new PropertyDescriptor("Title", TypeShapeDescriptor.value("java.lang.String")),
                new PropertyDescriptor("OptionalTitle", TypeShapeDescriptor.value("java.lang.String", true)),
                new PropertyDescriptor("URLValue", TypeShapeDescriptor.value("java.lang.String"))
            ))
        )) {};
        var document = new ArcOpenApiGenerator().generate(List.of(module));
        Schema<?> schema = document.getOpenApi().getComponents().getSchemas().get("NamedContract");
        var mapper = ArcObjectMapper.create();
        var wire = mapper.readTree(mapper.writeValueAsString(new NamedRecord("title", null, "url")));
        assertEquals(Set.of("title", "optionalTitle", "URLValue"), schema.getProperties().keySet());
        assertEquals(Set.copyOf(wire.propertyNames()), Set.copyOf(schema.getRequired()));
        Schema<?> optionalTitle = schema.getProperties().get("optionalTitle");
        assertEquals("null", optionalTitle.getAnyOf().get(1).getType());
        assertNull(schema.getDiscriminator());
        var json = mapper.readTree(document.json());
        assertEquals("string", json.at("/components/schemas/NamedContract/properties/title/type").stringValue());
    }

    private record NamedRecord(String Title, String OptionalTitle, String URLValue) {}
}
