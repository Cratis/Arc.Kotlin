// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import kotlin.reflect.KClass

/**
 * Declares the response value classes that a command response value handler can consume.
 *
 * The annotated declaration must implement [CommandResponseValueHandler] or one of the Java blocking/async
 * response-handler SPIs adapted by Arc. Declared types are statically classified as server-handled. Dynamically
 * registered handlers are re-evaluated after client response installation; an absent or nonmatching registration fails
 * closed rather than exposing the value. Declaring an element type does not cover collections or arrays of that type.
 * KSP discovers declarations from source or indexed dependencies; unindexed libraries remain undiscoverable.
 * Dependency indexes describe supported public top-level declarations, not runtime registration. Register the
 * corresponding handler separately at runtime.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
public annotation class HandlesCommandResponseValues(vararg val value: KClass<*>)
