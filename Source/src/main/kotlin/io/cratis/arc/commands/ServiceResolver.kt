// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

/** Resolves command-scoped services without coupling the core to a dependency-injection host. */
public interface ServiceResolver {
    /** Resolves [type], or returns `null` when the service is unavailable. */
    public fun <T : Any> resolve(type: Class<T>): T?
}
