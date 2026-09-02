// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.symbol.KSFile
import io.cratis.arc.metadata.CommandResponseValueDisposition
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.TypeShapeDescriptor

internal data class CommandModel(
    val qualifiedName: String,
    val simpleName: String,
    val handlerClassName: String,
    val parameters: List<HandlerParameterModel>,
    val provide: ProvideModel?,
    val properties: List<PropertyModel>,
    val commandKeyPropertyName: String?,
    val commandKeyUsesFunction: Boolean,
    val authorization: AuthorizationModel,
    val treatWarningsAsErrors: Boolean,
    val responseTypeName: String?,
    val responseIsEnumerable: Boolean,
    val responseValues: List<CommandResponseValueModel>,
    val invocationKind: InvocationKind,
    val containingFile: KSFile
)

internal data class HandlerParameterModel(
    val name: String,
    val sourceTypeName: String,
    val resolution: CommandParameterResolution = CommandParameterResolution.REQUIRED
)

internal enum class CommandParameterResolution {
    REQUIRED,
    NULLABLE,
    OPTIONAL
}

internal data class ProvideModel(
    val parameters: List<HandlerParameterModel>,
    val invocationKind: InvocationKind
)

internal data class QueryModel(
    val declaringTypeName: String,
    val methodName: String,
    val performerClassName: String,
    val parameters: List<QueryParameterModel>,
    val returnTypeName: String,
    val authorization: AuthorizationModel,
    val explicitPath: String?,
    val queryHttpMethod: String,
    val transport: String,
    val isEnumerable: Boolean,
    val supportsPaging: Boolean,
    val supportsSorting: Boolean,
    val treatWarningsAsErrors: Boolean,
    val invocationKind: QueryInvocationKind,
    val adaptsSpringDataPage: Boolean,
    val containingFile: KSFile,
    val source: com.google.devtools.ksp.symbol.KSFunctionDeclaration
) {
    val fullyQualifiedName: String = "$declaringTypeName.$methodName"
}

internal data class QueryParameterModel(
    val name: String,
    val typeName: String,
    val shape: TypeShapeDescriptor,
    val renderedTypeName: String,
    val erasedTypeName: String,
    val source: QueryParameterSource,
    val hostAdapterKind: QueryHostAdapterKind?,
    val hasDefault: Boolean,
    val isNullable: Boolean,
    val isEnumerable: Boolean,
    val elementTypeName: String?,
    val validationRules: List<ValidationRuleModel>,
    val validateRecursively: Boolean
) {
    val isFromServices: Boolean get() = source == QueryParameterSource.SERVICE
}

internal data class PropertyModel(
    val name: String,
    val typeName: String,
    val isNullable: Boolean,
    val isCommandKey: Boolean,
    val isEnumerable: Boolean,
    val elementTypeName: String?,
    val shape: TypeShapeDescriptor,
    val validationRules: List<ValidationRuleModel>,
    val validateRecursively: Boolean,
    val derivatives: List<String> = emptyList()
)

internal data class ValidationRuleModel(
    val ruleName: String,
    val arguments: List<Any> = emptyList(),
    val message: String? = null
)

internal data class TypeModel(
    val name: String,
    val fullyQualifiedName: String,
    val location: List<String>,
    val properties: List<PropertyModel>,
    val baseTypeName: String? = null,
    val derivedTypeId: String? = null
)

internal data class InterfaceModel(
    val name: String,
    val fullyQualifiedName: String,
    val location: List<String>,
    val properties: List<PropertyModel>
)

internal data class EnumModel(
    val name: String,
    val fullyQualifiedName: String,
    val location: List<String>,
    val members: List<EnumMemberModel>,
    val isFlags: Boolean
)

internal data class EnumMemberModel(
    val name: String,
    val value: Int
)

internal data class ConceptModel(
    val name: String,
    val fullyQualifiedName: String,
    val underlyingTypeName: String
)

internal data class CommandResponseModel(
    val values: List<CommandResponseValueModel>
) {
    private val clientValue: CommandResponseValueModel? =
        values.singleOrNull { value -> value.disposition == CommandResponseValueDisposition.CLIENT }

    val typeName: String? = clientValue?.typeName
    val isEnumerable: Boolean = clientValue?.isEnumerable ?: false
}

internal data class CommandResponseValueModel(
    val typeName: String,
    val isEnumerable: Boolean,
    val disposition: CommandResponseValueDisposition
)

internal data class TypeShape(
    val typeName: String,
    val descriptor: TypeShapeDescriptor,
    val valueType: com.google.devtools.ksp.symbol.KSType,
    val underlyingTypeName: String? = null,
    val underlyingValueType: com.google.devtools.ksp.symbol.KSType? = null
) {
    val isNullable: Boolean get() = descriptor.nullable
    val isEnumerable: Boolean get() = descriptor.kind == io.cratis.arc.metadata.TypeShapeKind.SEQUENCE
    val elementTypeName: String? get() = descriptor.elementShape?.typeName
}

internal data class AuthorizationModel(
    val allowAnonymous: Boolean,
    val policy: String?,
    val roles: List<String>,
    val schemes: List<String>
)

internal enum class InvocationKind {
    VALUE,
    UNIT,
    COMPLETION_STAGE,
    COMPLETION_STAGE_VOID
}

internal enum class QueryInvocationKind {
    DIRECT,
    COMPLETION_STAGE,
    FLOW,
    JDK_PUBLISHER
}

internal enum class QueryHostAdapterKind {
    PAGEABLE,
    SORT
}
