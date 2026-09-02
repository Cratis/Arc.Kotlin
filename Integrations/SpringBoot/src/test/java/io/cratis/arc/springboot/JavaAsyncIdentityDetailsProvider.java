// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.identity.AsyncIdentityDetailsProvider;
import io.cratis.arc.identity.IdentityDetails;
import io.cratis.arc.identity.IdentityProviderContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Java fixture proving the asynchronous identity provider contract. */
public final class JavaAsyncIdentityDetailsProvider
    implements AsyncIdentityDetailsProvider<JavaAsyncIdentityDetailsProvider.Details> {

    @Override
    public Class<Details> getDetailsType() {
        return Details.class;
    }

    @Override
    public CompletionStage<IdentityDetails<Details>> provide(IdentityProviderContext context) {
        return CompletableFuture.completedFuture(new IdentityDetails<>(true, new Details(context.getName())));
    }

    /** Typed Java details model. */
    public record Details(String displayName) {
    }
}
