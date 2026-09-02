// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authorization

import kotlin.jvm.JvmRepeatable

/** Requires the caller to belong to at least one of the specified roles. */
@MustBeDocumented
@JvmRepeatable(RolesContainer::class)
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class Roles(vararg val value: String)
