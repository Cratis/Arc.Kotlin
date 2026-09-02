// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.openapi.springboot;

import io.cratis.arc.artifacts.ArcArtifactModule;
import io.cratis.arc.metadata.ApiEndpointOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the published OpenAPI API is straightforward to consume from Java. */
final class JavaOpenApiAccess {
    @Test
    void generatorAndCachedDocumentAreJavaFriendly() {
        ArcArtifactModule module = new ArcArtifactModule(List.of(), List.of()) { };
        ArcOpenApiDocument document = new ArcOpenApiGenerator().generate(
            List.of(module),
            new ApiEndpointOptions(),
            false);

        assertEquals("3.1.0", document.getOpenApi().getOpenapi());
        assertTrue(document.json().length > 0);
    }
}
