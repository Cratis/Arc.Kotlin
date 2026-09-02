// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.KeyDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializationConfig
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.Deserializers
import com.fasterxml.jackson.databind.jsontype.TypeSerializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import com.fasterxml.jackson.databind.ser.Serializers
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer
import com.fasterxml.jackson.databind.util.TokenBuffer
import io.cratis.arc.concepts.ArcEnum
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.metadata.MapKeyCodec
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeShapeKind
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedType
import io.cratis.arc.polymorphism.DerivedTypeRegistry
import io.cratis.arc.queries.ObservableQueryHubMessageType
import io.cratis.arc.queries.ObservableQueryTransferMode
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.Modifier
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Jackson module implementing Arc enum, concept, time, and derived-type wire contracts.
 *
 * Register derived types before first deserialization of their base type. The registry remains safe for concurrent
 * reads and explicit registrations, while Jackson is free to cache resolved deserializers.
 */
public class ArcJacksonModule @JvmOverloads constructor(
    derivedTypes: DerivedTypeRegistry = ConcurrentDerivedTypeRegistry()
) : SimpleModule("ArcJacksonModule") {
    private val registry = derivedTypes

    init {
        addSerializer(LocalTime::class.java, ArcLocalTimeSerializer)
        addDeserializer(LocalTime::class.java, ArcLocalTimeDeserializer)
        addKeySerializer(String::class.java, ArcStringMapKeySerializer)
        addKeyDeserializer(String::class.java, ArcStringMapKeyDeserializer)
    }

    override fun setupModule(context: SetupContext) {
        super.setupModule(context)
        context.addSerializers(ArcSerializers)
        context.addDeserializers(ArcDeserializers(registry))
        context.addBeanSerializerModifier(ArcDerivedTypeSerializerModifier)
    }
}

private const val DERIVED_TYPE_ID = "_derivedTypeId"
private val reservedStringMapKeys = setOf("__proto__", "prototype", "constructor")

private object ArcStringMapKeySerializer : JsonSerializer<String>() {
    override fun serialize(value: String, generator: JsonGenerator, serializers: SerializerProvider) {
        if (value in reservedStringMapKeys) {
            throw JsonMappingException.from(generator, "Reserved string map key '$value' is not allowed")
        }
        generator.writeFieldName(value)
    }
}

private object ArcStringMapKeyDeserializer : KeyDeserializer() {
    override fun deserializeKey(key: String, context: DeserializationContext): String {
        if (key in reservedStringMapKeys) {
            return context.reportInputMismatch(String::class.java, "Reserved string map key '%s' is not allowed", key)
        }
        return key
    }
}

private object ArcSerializers : Serializers.Base() {
    override fun findSerializer(
        config: SerializationConfig,
        type: JavaType,
        beanDesc: BeanDescription
    ): JsonSerializer<*>? = when {
        ConceptAs::class.java.isAssignableFrom(type.rawClass) -> ArcConceptSerializer
        type.isEnumType && !isNamedProtocolEnum(type.rawClass) -> ArcEnumSerializer
        else -> null
    }
}

private class ArcDeserializers(private val registry: DerivedTypeRegistry) : Deserializers.Base() {
    override fun findBeanDeserializer(
        type: JavaType,
        config: DeserializationConfig,
        beanDesc: BeanDescription
    ): JsonDeserializer<*>? = when {
        ConceptAs::class.java.isAssignableFrom(type.rawClass) -> ArcConceptDeserializer(type)
        registry.registeredBaseTypes().contains(type.rawClass) -> ArcDerivedTypeDeserializer(type, registry)
        else -> null
    }

    override fun findEnumDeserializer(
        type: Class<*>,
        config: DeserializationConfig,
        beanDesc: BeanDescription
    ): JsonDeserializer<*>? = if (isNamedProtocolEnum(type)) null else ArcEnumDeserializer(type)
}

private fun isNamedProtocolEnum(type: Class<*>): Boolean =
    type == ObservableQueryHubMessageType::class.java ||
        type == ObservableQueryTransferMode::class.java ||
        type == QueryParameterSource::class.java ||
        type == TypeShapeKind::class.java ||
        type == SequenceKind::class.java ||
        type == MapKeyCodec::class.java

private object ArcConceptSerializer : JsonSerializer<ConceptAs<*>>() {
    override fun serialize(value: ConceptAs<*>, generator: JsonGenerator, serializers: SerializerProvider) {
        serializers.defaultSerializeValue(value.value(), generator)
    }
}

private object ArcEnumSerializer : JsonSerializer<Enum<*>>() {
    override fun serialize(value: Enum<*>, generator: JsonGenerator, serializers: SerializerProvider) {
        generator.writeNumber(if (value is ArcEnum) value.value() else value.ordinal)
    }
}

private class ArcEnumDeserializer(enumType: Class<*>) : JsonDeserializer<Any>() {
    private val enumClass = enumType
    private val constants = enumType.enumConstants.orEmpty().filterIsInstance<Enum<*>>()

    override fun deserialize(parser: JsonParser, context: DeserializationContext): Any? = when (parser.currentToken) {
        JsonToken.VALUE_NUMBER_INT -> fromInteger(parser.intValue, parser)
        JsonToken.VALUE_STRING -> fromString(parser.text, parser)
        JsonToken.VALUE_NULL -> null
        else -> throw JsonMappingException.from(
            parser,
            "Expected an integer or enum name for ${enumClass.name}"
        )
    }

    private fun fromInteger(value: Int, parser: JsonParser): Enum<*> = constants.firstOrNull { constant ->
        if (constant is ArcEnum) constant.value() == value else constant.ordinal == value
    } ?: throw JsonMappingException.from(parser, "Integer $value is not defined by ${enumClass.name}")

    private fun fromString(value: String, parser: JsonParser): Enum<*> = constants.firstOrNull {
        it.name.equals(value, ignoreCase = true)
    } ?: throw JsonMappingException.from(parser, "Name '$value' is not defined by ${enumClass.name}")
}

private sealed interface CachedConceptConstructor {
    data class Available(val handle: MethodHandle) : CachedConceptConstructor
    data object Missing : CachedConceptConstructor
}

private val conceptConstructors = object : ClassValue<CachedConceptConstructor>() {
    override fun computeValue(type: Class<*>): CachedConceptConstructor {
        val constructor = type.constructors.firstOrNull {
            Modifier.isPublic(it.modifiers) && it.parameterCount == 1
        } ?: return CachedConceptConstructor.Missing
        return CachedConceptConstructor.Available(MethodHandles.publicLookup().unreflectConstructor(constructor))
    }
}

private class ArcConceptDeserializer(private val conceptType: JavaType) : JsonDeserializer<Any>() {
    private val valueType: JavaType = conceptType.findSuperType(ConceptAs::class.java)?.containedType(0)
        ?: throw IllegalArgumentException("${conceptType.rawClass.name} does not declare a ConceptAs value type")
    private val constructor = conceptConstructors.get(conceptType.rawClass)

    override fun deserialize(parser: JsonParser, context: DeserializationContext): Any? {
        if (parser.currentToken == JsonToken.VALUE_NULL) return null
        val value: Any? = context.readValue(parser, valueType)
        val handle = (constructor as? CachedConceptConstructor.Available)?.handle
            ?: throw JsonMappingException.from(
                parser,
                "${conceptType.rawClass.name} must expose a public single-value constructor"
            )

        return try {
            handle.invokeWithArguments(value)
        } catch (exception: Throwable) {
            throw JsonMappingException.from(parser, "Could not create ${conceptType.rawClass.name}", exception)
        }
    }
}

private class ArcDerivedTypeDeserializer(
    private val baseType: JavaType,
    private val registry: DerivedTypeRegistry
) : JsonDeserializer<Any>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Any? {
        if (parser.currentToken == JsonToken.VALUE_NULL) return null
        val node = parser.codec.readTree<JsonNode>(parser) as? ObjectNode
            ?: throw JsonMappingException.from(parser, "Expected an object for ${baseType.rawClass.name}")
        val idNode = node.remove(DERIVED_TYPE_ID)
        val id = idNode?.takeIf(JsonNode::isTextual)?.textValue()
            ?: throw JsonMappingException.from(parser, "Missing textual $DERIVED_TYPE_ID for ${baseType.rawClass.name}")
        val derivedType = registry.resolve(baseType.rawClass, id)
            ?: throw JsonMappingException.from(parser, "Unknown derived type identifier '$id' for ${baseType.rawClass.name}")

        val treeParser = node.traverse(parser.codec)
        treeParser.nextToken()
        return context.readValue(treeParser, derivedType)
    }
}

private object ArcDerivedTypeSerializerModifier : BeanSerializerModifier() {
    override fun modifySerializer(
        config: SerializationConfig,
        beanDesc: BeanDescription,
        serializer: JsonSerializer<*>
    ): JsonSerializer<*> {
        val derivedType = beanDesc.beanClass.getAnnotation(DerivedType::class.java) ?: return serializer
        @Suppress("UNCHECKED_CAST")
        return ArcDerivedTypeSerializer(serializer as JsonSerializer<Any>, derivedType.id)
    }
}

private class ArcDerivedTypeSerializer(
    private val delegate: JsonSerializer<Any>,
    private val id: String
) : JsonSerializer<Any>() {
    override fun serialize(value: Any, generator: JsonGenerator, serializers: SerializerProvider) {
        val buffer = TokenBuffer(generator.codec, false)
        delegate.serialize(value, buffer, serializers)
        val parser = buffer.asParser(generator.codec)
        parser.nextToken()
        val node = generator.codec.readTree<JsonNode>(parser) as? ObjectNode
            ?: throw JsonMappingException.from(generator, "Derived type ${value.javaClass.name} did not serialize as an object")
        node.put(DERIVED_TYPE_ID, id)
        generator.writeTree(node)
    }

    override fun serializeWithType(
        value: Any,
        generator: JsonGenerator,
        serializers: SerializerProvider,
        typeSerializer: TypeSerializer
    ) {
        serialize(value, generator, serializers)
    }
}

private val arcLocalTimeFormatter: DateTimeFormatter = DateTimeFormatterBuilder()
    .appendPattern("HH:mm:ss")
    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 7, true)
    .toFormatter(Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

private object ArcLocalTimeSerializer : StdScalarSerializer<LocalTime>(LocalTime::class.java) {
    override fun serialize(value: LocalTime, generator: JsonGenerator, provider: SerializerProvider) {
        if (value.nano % 100 != 0) {
            provider.reportMappingProblem(
                "LocalTime %s has precision finer than the Arc/.NET 100-nanosecond wire precision",
                value
            )
        }
        generator.writeString(arcLocalTimeFormatter.format(value))
    }
}

private object ArcLocalTimeDeserializer : JsonDeserializer<LocalTime>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): LocalTime = try {
        LocalTime.parse(parser.valueAsString, arcLocalTimeFormatter)
    } catch (exception: DateTimeParseException) {
        throw JsonMappingException.from(parser, "Invalid Arc LocalTime value '${parser.valueAsString}'", exception)
    }
}
