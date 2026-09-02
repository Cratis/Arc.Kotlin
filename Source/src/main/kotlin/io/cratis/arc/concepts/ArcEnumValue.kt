// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.concepts

/** Declares the explicit Arc integer wire value for an enum entry when KSP cannot prove it from source. */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
public annotation class ArcEnumValue(public val value: Int)
