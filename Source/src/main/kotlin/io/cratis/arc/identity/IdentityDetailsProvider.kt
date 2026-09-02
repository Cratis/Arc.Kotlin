// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.identity

/** Supplies application-specific identity details without blocking a thread. */
public interface IdentityDetailsProvider<T : Any> {
    /** Runtime details type used to produce identity documentation metadata. */
    public val detailsType: Class<T>

    /** Provides details and the application authorization decision for [context]. */
    public suspend fun provide(context: IdentityProviderContext): IdentityDetails<T>
}
