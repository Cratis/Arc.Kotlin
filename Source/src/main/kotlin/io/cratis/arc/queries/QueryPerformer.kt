// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.metadata.QueryDescriptor

/** Build-time generated, reflection-free invoker for one query. */
public interface QueryPerformer {
    /** Generated query metadata used by hosts and filters. */
    public val descriptor: QueryDescriptor

    /** Exact fully qualified query name used for registry lookup. */
    public val fullyQualifiedName: FullyQualifiedQueryName

    /** Whether the query explicitly allows an unauthenticated caller. */
    public val allowsAnonymous: Boolean
        get() = descriptor.authorization.allowAnonymous

    /** Whether the generated query supports database-owned paging. */
    public val supportsPaging: Boolean
        get() = descriptor.supportsPaging

    /** Whether the generated query supports database-owned sorting. */
    public val supportsSorting: Boolean
        get() = descriptor.supportsSorting

    /** Performs the query with the explicit execution [context]. */
    public suspend fun perform(context: QueryContext): Any?
}
