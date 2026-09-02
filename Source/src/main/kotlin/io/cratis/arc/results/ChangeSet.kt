// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.results

/** Immutable changes accompanying an observable query result. */
public class ChangeSet<T> @JvmOverloads constructor(
    added: List<T> = emptyList(),
    replaced: List<T> = emptyList(),
    removed: List<T> = emptyList()
) {
    /** Items added since the preceding result. */
    public val added: List<T> = java.util.List.copyOf(added)

    /** Items replaced since the preceding result. */
    public val replaced: List<T> = java.util.List.copyOf(replaced)

    /** Items removed since the preceding result. */
    public val removed: List<T> = java.util.List.copyOf(removed)
}
