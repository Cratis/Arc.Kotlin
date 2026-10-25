// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.naming

/**
 * Defines the strategy for deriving storage names from JVM types.
 *
 * This interface now lives in Cratis Fundamentals as
 * [io.cratis.fundamentals.serialization.NamingPolicy]; this type remains as a sub-interface so
 * implementations and callers compiled against Arc keep linking. Every Arc policy is a
 * Fundamentals policy.
 *
 * The default implementation uses English-language plural inflection so that read-model collection
 * names match those emitted by the Cratis Chronicle Kernel for its projections. A disagreement
 * between the framework naming policy and the kernel causes queries to read from the wrong
 * collection and return empty results silently.
 *
 * An application may register a bean that implements either interface to replace the default
 * policy before any collection lookup is performed. The policy is consulted once per type at lookup
 * time and the result is not cached by this interface — implementations are responsible for their
 * own caching when needed.
 *
 * Mirrors `Cratis.Serialization.INamingPolicy`
 * (`Fundamentals/Source/DotNET/Fundamentals/Serialization/INamingPolicy.cs`). The JSON hook is
 * deliberately omitted: JSON property naming on the JVM is owned by the configured Jackson module.
 */
@Deprecated(
    "Use io.cratis.fundamentals.serialization.NamingPolicy.",
    ReplaceWith("NamingPolicy", "io.cratis.fundamentals.serialization.NamingPolicy")
)
public interface NamingPolicy : io.cratis.fundamentals.serialization.NamingPolicy {
    /**
     * Returns the storage collection name for [readModelType].
     *
     * The returned name must be non-blank. Implementations must not consult ambient or
     * thread-local request state; the same type must map to the same name across calls.
     *
     * @param readModelType The read-model type to name.
     * @return The storage collection name.
     */
    override fun getReadModelName(readModelType: Class<*>): String

    /**
     * Returns the stored member name for [name].
     *
     * The default returns [name] unchanged, matching the .NET default policy.
     *
     * @param name The declared member name.
     * @return The stored member name.
     */
    override fun getPropertyName(name: String): String = super.getPropertyName(name)
}
