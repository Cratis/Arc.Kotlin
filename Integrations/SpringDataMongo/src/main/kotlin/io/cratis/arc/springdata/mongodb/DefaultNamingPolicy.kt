// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import io.cratis.arc.naming.NamingPolicy
import org.atteo.evo.inflector.English

/**
 * Default [NamingPolicy] that pluralises the simple class name using English-language inflection.
 *
 * Pluralisation is performed by [Evo Inflector](https://github.com/atteo/evo-inflector)
 * (`org.atteo:evo-inflector`). The Chronicle Kernel is .NET and pluralises with Humanizer, so this
 * is a *different implementation chosen for compatibility*, not the same library. The collection
 * name a query reads from must match the one the kernel's projection writes to; where the two
 * algorithms disagree on a noun, the kernel is authoritative and the application must override this
 * policy for that type.
 *
 * The cases pinned by `DefaultNamingPolicyTests` record what Evo Inflector actually produces, so a
 * library upgrade that changes a plural fails the build instead of silently moving a collection.
 *
 * Mirrors `Cratis.Serialization.DefaultNamingPolicy`
 * (`Fundamentals/Source/DotNET/Fundamentals/Serialization/DefaultNamingPolicy.cs`), which is
 * `DefaultNamingPolicy(bool pluralizeReadModelNames = true)` and calls Humanizer's `Pluralize()`.
 *
 * Replace this bean with a custom [NamingPolicy] implementation to override the default policy.
 */
public class DefaultNamingPolicy @JvmOverloads constructor(
    private val pluralizeReadModelNames: Boolean = true
) : NamingPolicy {
    override fun getReadModelName(readModelType: Class<*>): String {
        val name = readModelType.simpleName
        if (!pluralizeReadModelNames) return name
        return KERNEL_ALIGNED_PLURALS[name.lowercase()] ?: English.plural(name)
    }

    override fun getPropertyName(name: String): String = name

    private companion object {
        /**
         * Words where Evo Inflector and the kernel's Humanizer disagree, corrected toward the kernel.
         *
         * Measured, not assumed: both libraries were run over the same word list. Humanizer produced
         * `People` and `Indices` where Evo Inflector produced `Persons` and `Indexes`. The kernel
         * writes the projection, so its spelling decides which collection a query must read, and an
         * uncorrected divergence would silently read from a collection nothing ever writes to.
         *
         * The remaining words checked agreed, including the irregular `Child` -> `Children`,
         * `Mouse` -> `Mice`, `Datum` -> `Data` and the invariant `Series`.
         *
         * This list is empirical and therefore incomplete. A read model whose plural differs from the
         * kernel's must override the policy for that type rather than relying on this map.
         */
        val KERNEL_ALIGNED_PLURALS = mapOf(
            "person" to "People",
            "index" to "Indices"
        )
    }
}
