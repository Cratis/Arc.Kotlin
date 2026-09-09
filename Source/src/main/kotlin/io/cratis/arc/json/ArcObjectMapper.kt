// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import com.fasterxml.jackson.annotation.JsonInclude
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedTypeRegistry
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.core.json.JsonWriteFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** Creates and configures Jackson mappers with the single supported Arc wire configuration. */
public object ArcObjectMapper {
    /** Creates a mapper with an empty explicit derived-type registry. */
    @JvmStatic
    public fun create(): ObjectMapper = create(ConcurrentDerivedTypeRegistry())

    /** Creates a mapper using a registry populated before polymorphic values are read. */
    @JvmStatic
    public fun create(derivedTypes: DerivedTypeRegistry): ObjectMapper =
        configure(JsonMapper.builderWithJackson2Defaults(), derivedTypes)

    /**
     * Returns an Arc-configured copy of [objectMapper].
     *
     * Jackson 3 mappers are immutable, so the supplied mapper is never modified. It must be a JSON
     * mapper: Jackson 3 no longer permits a generic mapper to be paired with another format.
     */
    @JvmStatic
    public fun configure(objectMapper: ObjectMapper): ObjectMapper =
        configure(objectMapper, ConcurrentDerivedTypeRegistry())

    /** Returns an Arc-configured copy of [objectMapper] with an explicit derived-type registry. */
    @JvmStatic
    public fun configure(objectMapper: ObjectMapper, derivedTypes: DerivedTypeRegistry): ObjectMapper {
        require(objectMapper is JsonMapper) {
            "Arc JSON configuration requires a tools.jackson.databind.json.JsonMapper."
        }
        return configure(objectMapper.rebuild(), derivedTypes)
    }

    private fun configure(builder: JsonMapper.Builder, derivedTypes: DerivedTypeRegistry): ObjectMapper = builder
        .propertyNamingStrategy(ArcPropertyNamingStrategy())
        .changeDefaultPropertyInclusion { inclusion ->
            inclusion
                .withValueInclusion(JsonInclude.Include.NON_NULL)
                .withContentInclusion(JsonInclude.Include.NON_NULL)
        }
        .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
        .enable(JsonWriteFeature.WRITE_NAN_AS_STRINGS)
        .disable(
            DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS,
            DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS
        )
        .addModule(KotlinModule.Builder().build())
        .addModule(ArcJacksonModule(derivedTypes))
        .build()
}
