// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authorization

/** Requires an authenticated caller satisfying the declared policy, roles, and schemes. */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class Authorize(
    public val policy: String = "",
    public val roles: Array<String> = [],
    public val schemes: Array<String> = []
)
