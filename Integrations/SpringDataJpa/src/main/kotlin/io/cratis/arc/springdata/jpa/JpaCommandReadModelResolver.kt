// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

/**
 * Compatibility-only adapter-local command read-model lookup seam.
 *
 * This API recomputes a key from the command and has no tenant context, so it is unsafe when tenancy is required.
 * New code should use the ownership-aware [JpaReadModelForCommandResolver].
 */
public interface JpaCommandReadModelResolver {
    /** Resolves [readModelType] by the key generated for [command], or returns `null` when no row exists. */
    public fun <T : Any> resolve(readModelType: Class<T>, command: Any): T?
}
