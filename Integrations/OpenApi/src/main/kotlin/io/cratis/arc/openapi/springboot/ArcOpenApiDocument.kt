// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.openapi.springboot

import io.swagger.v3.oas.models.OpenAPI

/** Immutable OpenAPI model and its cached deterministic JSON representation. */
public class ArcOpenApiDocument(
    /** Generated OpenAPI 3.1 model. */
    public val openApi: OpenAPI,
    json: ByteArray
) {
    private val cachedJson = json.copyOf()

    /** Returns a defensive copy of the JSON bytes generated at startup. */
    public fun json(): ByteArray = cachedJson.copyOf()
}
