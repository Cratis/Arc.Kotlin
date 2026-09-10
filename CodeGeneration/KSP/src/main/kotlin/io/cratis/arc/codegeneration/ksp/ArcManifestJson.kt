// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import tools.jackson.core.json.JsonWriteFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.json.ArcJacksonModule
import io.cratis.arc.json.ArcPropertyNamingStrategy
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.EnumDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.TypeDescriptor
import io.cratis.arc.metadata.ValidationRuleDescriptor

/** Serializes compilation manifests without depending on Kotlin reflection in the processor classloader. */
internal object ArcManifestJson {
    // KSP loads stdlib in its parent loader and kotlin-reflect in the processor child. The parent's Reflection
    // factory cannot see the child's implementation, so KotlinModule introspection receives ClassReference.
    // These manifest-only mix-ins retain KotlinModule's wire names and constructor-based property ordering.
    // Runtime JSON still uses ArcObjectMapper; this writer must remain serialization-only and byte-equivalent.
    private val writer = JsonMapper.builderWithJackson2Defaults()
        .propertyNamingStrategy(ArcPropertyNamingStrategy())
        .changeDefaultPropertyInclusion { inclusion ->
            inclusion
                .withValueInclusion(JsonInclude.Include.NON_NULL)
                .withContentInclusion(JsonInclude.Include.NON_NULL)
        }
        .enable(JsonWriteFeature.WRITE_NAN_AS_STRINGS)
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
        .addModule(ArcJacksonModule())
        .addMixIn(CommandDescriptor::class.java, CommandMixin::class.java)
        .addMixIn(TypeDescriptor::class.java, TypeMixin::class.java)
        .addMixIn(ValidationRuleDescriptor::class.java, ValidationRuleMixin::class.java)
        .addMixIn(EnumDescriptor::class.java, EnumMixin::class.java)
        .addMixIn(PropertyDescriptor::class.java, PropertyMixin::class.java)
        .build()
        .writerFor(ArcArtifactManifest::class.java)

    fun serialize(manifest: ArcArtifactManifest): String = writer.writeValueAsString(manifest) + "\n"

    @JsonPropertyOrder(
        "name", "typeName", "properties", "routeOptions", "location", "authorization", "explicitPath",
        "treatWarningsAsErrors", "responseValues", "summary", "eventMetadata"
    )
    private abstract class CommandMixin

    @JsonPropertyOrder("name", "fullyQualifiedName", "location", "properties", "baseTypeName", "derivedTypeId")
    private abstract class TypeMixin

    @JsonPropertyOrder("ruleName", "arguments", "message")
    private abstract class ValidationRuleMixin

    // EnumDescriptor declares summary before allFlagsExpression, and this writer must reproduce
    // ArcObjectMapper's output byte for byte. See GeneratedArtifactManifestTest.
    @JsonPropertyOrder(
        "name",
        "fullyQualifiedName",
        "location",
        "members",
        "isFlags",
        "summary",
        "allFlagsExpression"
    )
    private abstract class EnumMixin {
        @JsonProperty("isFlags")
        abstract fun isFlags(): Boolean
    }

    private abstract class PropertyMixin {
        @JsonProperty("isCommandKey")
        abstract fun isCommandKey(): Boolean
    }
}
