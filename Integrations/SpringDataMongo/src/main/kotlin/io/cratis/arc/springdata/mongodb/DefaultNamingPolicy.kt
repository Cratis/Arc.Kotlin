// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:Suppress("DEPRECATION")

package io.cratis.arc.springdata.mongodb

import io.cratis.arc.naming.NamingPolicy
import io.cratis.fundamentals.serialization.DefaultNamingPolicy as FundamentalsDefaultNamingPolicy

/**
 * Default [NamingPolicy] that pluralises the simple class name using English-language inflection.
 *
 * This is a compatibility facade that delegates to
 * [io.cratis.fundamentals.serialization.DefaultNamingPolicy], which owns the pluralisation
 * (Evo Inflector with Humanizer-aligned corrections) and its pinned tests. It remains an Arc
 * [NamingPolicy] so existing injection points and subclasses keep working.
 *
 * Mirrors `Cratis.Serialization.DefaultNamingPolicy`
 * (`Fundamentals/Source/DotNET/Fundamentals/Serialization/DefaultNamingPolicy.cs`), which is
 * `DefaultNamingPolicy(bool pluralizeReadModelNames = true)` and calls Humanizer's `Pluralize()`.
 *
 * Replace this bean with a custom naming policy implementation to override the default policy.
 */
@Deprecated(
    "Use io.cratis.fundamentals.serialization.DefaultNamingPolicy.",
    ReplaceWith(
        "DefaultNamingPolicy(pluralizeReadModelNames)",
        "io.cratis.fundamentals.serialization.DefaultNamingPolicy"
    )
)
public class DefaultNamingPolicy @JvmOverloads constructor(
    pluralizeReadModelNames: Boolean = true
) : NamingPolicy {
    private val delegate = FundamentalsDefaultNamingPolicy(pluralizeReadModelNames)

    override fun getReadModelName(readModelType: Class<*>): String = delegate.getReadModelName(readModelType)

    override fun getPropertyName(name: String): String = delegate.getPropertyName(name)
}
