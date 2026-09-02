// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

/**
 * Selects the generated proxy HTTP method independently of query transport behavior.
 *
 * On a read-model class the value is the default for every declared query; a method annotation overrides it.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class QueryHttpMethod(public val value: QueryHttpMethodType = QueryHttpMethodType.AUTO)
