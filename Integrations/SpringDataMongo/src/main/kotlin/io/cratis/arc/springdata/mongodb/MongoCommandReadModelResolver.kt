// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

/**
 * Adapter-local command-side read-model lookup seam.
 *
 * Arc's current command argument resolver does not expose the command context to storage integrations. Commands can
 * inject this service and pass themselves explicitly until the core provides a contextual read-model resolver seam.
 */
public interface MongoCommandReadModelResolver {
    /** Resolves [readModelType] by the key generated for [command], or returns `null` when no document exists. */
    public fun <T : Any> resolve(readModelType: Class<T>, command: Any): T?
}
