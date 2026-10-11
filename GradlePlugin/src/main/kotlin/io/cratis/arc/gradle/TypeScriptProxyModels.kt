// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.ValidationRuleDescriptor

internal enum class ArtifactKind { COMMAND, TYPE, INTERFACE, ENUM, QUERY }

internal data class ArtifactTarget(
    val sourceName: String,
    val typeScriptName: String,
    val directory: List<String>,
    val kind: ArtifactKind,
    val useProxyFileSuffix: Boolean
) {
    val fileStem: String = typeScriptName + if (useProxyFileSuffix) ".proxy" else ""
    val relativePath: String = (directory + "$fileStem.ts").joinToString("/")
}

internal data class TypeScriptValueImport(
    val packageName: String,
    val name: String,
    val typeOnly: Boolean = false
)

internal enum class TypeScriptImportKind { TYPE, VALUE }

internal data class TypeScriptImport(val target: ArtifactTarget, val kind: TypeScriptImportKind)

internal data class TypeScriptType(
    val name: String,
    val constructor: String,
    val target: ArtifactTarget? = null,
    val valueImport: TypeScriptValueImport? = null,
    val nestedTypes: List<TypeScriptType> = emptyList()
)

internal data class ValidationTarget(
    val propertyName: String,
    val rules: List<ValidationRuleDescriptor>,
    val skipWhenUndefined: Boolean = false,
    val sharedEdge: Boolean = false
) {
    val hasRules: Boolean = rules.isNotEmpty() || sharedEdge
}

internal data class QueryRenderModel(
    val query: QueryDescriptor,
    val target: ArtifactTarget,
    val route: String,
    val parameters: List<ParameterDescriptor>,
    val validationTargets: List<ValidationTarget>,
    val model: TypeScriptType,
    val referencedTypes: List<TypeScriptType>,
    val imports: List<TypeScriptImport>,
    val sortableProperties: List<String>,
    val className: String,
    val modelType: String,
    val parameterType: String,
    val method: String?,
    val supportsSorting: Boolean,
    val supportsPaging: Boolean,
    val hasSortableProperties: Boolean
)
