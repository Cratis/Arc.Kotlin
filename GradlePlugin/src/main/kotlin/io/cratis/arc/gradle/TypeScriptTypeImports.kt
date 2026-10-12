// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

internal fun TypeScriptType.recursiveTypes(): List<TypeScriptType> = listOf(this) + nestedTypes.flatMap {
    nested -> nested.recursiveTypes()
}

internal fun customImports(
    types: Collection<TypeScriptType>,
    runtimeTypes: Collection<TypeScriptType> = emptyList()
): List<TypeScriptImport> {
    val valueTargets = runtimeTypes.mapNotNull { type ->
        type.target?.takeIf { target -> type.constructor == target.typeScriptName }
    }.toSet()
    return types.flatMap { type -> type.recursiveTypes() }.mapNotNull(TypeScriptType::target).distinct().map { target ->
        TypeScriptImport(
            target,
            if (target in valueTargets) TypeScriptImportKind.VALUE else TypeScriptImportKind.TYPE
        )
    }
}

internal fun TypeScriptType.globalRuntimeConstructor(): String? {
    if (constructor !in GLOBAL_RUNTIME_CONSTRUCTORS || valueImport?.typeOnly == false) return null
    return constructor.takeIf {
        target == null || target.kind == ArtifactKind.INTERFACE || target.kind == ArtifactKind.ENUM
    }
}
