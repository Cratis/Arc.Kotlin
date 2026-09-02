// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:JvmName("ServiceResolvers")

package io.cratis.arc.commands

/** Resolves [type] or throws a deterministic [MissingServiceException]. */
public fun <T : Any> ServiceResolver.require(type: Class<T>): T = resolve(type) ?: throw MissingServiceException(type)

/** Resolves a service using its reified Kotlin type, or returns `null` when it is unavailable. */
@JvmSynthetic
public inline fun <reified T : Any> ServiceResolver.resolve(): T? = resolve(T::class.java)

/** Resolves a service using its reified Kotlin type or throws [MissingServiceException]. */
@JvmSynthetic
public inline fun <reified T : Any> ServiceResolver.require(): T = require(T::class.java)
