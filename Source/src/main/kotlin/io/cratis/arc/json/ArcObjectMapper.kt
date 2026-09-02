// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.core.json.JsonWriteFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedTypeRegistry

/** Creates and configures Jackson mappers with the single supported Arc wire configuration. */
public object ArcObjectMapper {
    /** Creates a mapper with an empty explicit derived-type registry. */
    @JvmStatic
    public fun create(): ObjectMapper = create(ConcurrentDerivedTypeRegistry())

    /** Creates a mapper using a registry populated before polymorphic values are read. */
    @JvmStatic
    public fun create(derivedTypes: DerivedTypeRegistry): ObjectMapper =
        configure(JsonMapper.builder().build(), derivedTypes)

    /** Configures an existing mapper, allowing a host integration to apply exactly the same Arc configuration. */
    @JvmStatic
    public fun configure(objectMapper: ObjectMapper): ObjectMapper =
        configure(objectMapper, ConcurrentDerivedTypeRegistry())

    /** Configures an existing mapper with an explicit derived-type registry. */
    @JvmStatic
    public fun configure(objectMapper: ObjectMapper, derivedTypes: DerivedTypeRegistry): ObjectMapper {
        objectMapper.propertyNamingStrategy = ArcPropertyNamingStrategy()
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)
        objectMapper.enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.mappedFeature())
        objectMapper.enable(JsonWriteFeature.WRITE_NAN_AS_STRINGS.mappedFeature())
        objectMapper.disable(
            SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
            SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS
        )
        objectMapper.registerModule(KotlinModule.Builder().build())
        objectMapper.registerModule(JavaTimeModule())
        objectMapper.registerModule(ArcJacksonModule(derivedTypes))
        return objectMapper
    }
}
