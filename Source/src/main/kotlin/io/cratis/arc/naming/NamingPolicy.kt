// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.naming

/**
 * Defines the strategy for deriving storage names from JVM types.
 *
 * The default implementation uses English-language plural inflection so that read-model collection
 * names match those emitted by the Cratis Chronicle Kernel for its projections. A disagreement
 * between the framework naming policy and the kernel causes queries to read from the wrong
 * collection and return empty results silently.
 *
 * An application may register a bean that implements this interface to replace the default policy
 * before any collection lookup is performed. The policy is consulted once per type at lookup time
 * and the result is not cached by this interface — implementations are responsible for their own
 * caching when needed.
 *
 * Mirrors `Cratis.Serialization.INamingPolicy`
 * (`Fundamentals/Source/DotNET/Fundamentals/Serialization/INamingPolicy.cs`), which declares
 * `GetReadModelName(Type)`, `GetPropertyName(string)` and a `JsonPropertyNamingPolicy`. The JSON
 * hook is deliberately omitted here: JSON property naming on the JVM is owned by the configured
 * Jackson module, and duplicating it in this interface would create a second place that can
 * disagree with serialization.
 */
public interface NamingPolicy {
    /**
     * Returns the storage collection name for [readModelType].
     *
     * The returned name must be non-blank. Implementations must not consult ambient or
     * thread-local request state; the same type must map to the same name across calls.
     *
     * @param readModelType The read-model type to name.
     * @return The storage collection name.
     */
    public fun getReadModelName(readModelType: Class<*>): String

    /**
     * Returns the stored member name for [name].
     *
     * The default returns [name] unchanged, matching the .NET default policy.
     *
     * @param name The declared member name.
     * @return The stored member name.
     */
    public fun getPropertyName(name: String): String = name
}
