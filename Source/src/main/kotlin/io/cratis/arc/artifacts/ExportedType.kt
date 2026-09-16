// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.artifacts

/**
 * Marks a type as an Arc artifact root even when no command or query references it.
 *
 * Code generation normally reaches model types through `@Command` and `@ReadModel`. A module that
 * contributes only identity detail types or shared data-transfer types has no such root, so it
 * contributes no manifest and no generated client proxies. Annotating the type makes it a root:
 * it and every type reachable from it are collected, and the compilation emits an artifact module
 * and manifest even with no commands and no queries.
 *
 * Apply it to a public top-level concrete class, enum class, interface or concept. Abstract classes
 * are reached through their concrete derived types instead.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
public annotation class ExportedType
