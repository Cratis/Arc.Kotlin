// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.identity

import java.util.concurrent.CompletionStage

/** Java-friendly asynchronous identity details provider. */
public interface AsyncIdentityDetailsProvider<T : Any> {
    /** Runtime details type used to produce identity documentation metadata. */
    public val detailsType: Class<T>

    /** Provides details and the application authorization decision for [context]. */
    public fun provide(context: IdentityProviderContext): CompletionStage<IdentityDetails<T>>
}
