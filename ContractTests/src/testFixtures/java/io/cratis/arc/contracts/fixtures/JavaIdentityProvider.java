// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.identity.AsyncIdentityDetailsProvider;
import io.cratis.arc.identity.IdentityDetails;
import io.cratis.arc.identity.IdentityProviderContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class JavaIdentityProvider implements AsyncIdentityDetailsProvider<JavaIdentityDetails> {
    @Override
    public Class<JavaIdentityDetails> getDetailsType() { return JavaIdentityDetails.class; }

    @Override
    public CompletionStage<IdentityDetails<JavaIdentityDetails>> provide(IdentityProviderContext context) {
        return CompletableFuture.completedFuture(new IdentityDetails<>(true,
            new JavaIdentityDetails(new IdentityAddress(context.getId()))));
    }

    public static AsyncIdentityDetailsProvider<JavaFactoryIdentityDetails> factory() {
        return new AsyncIdentityDetailsProvider<>() {
            @Override
            public Class<JavaFactoryIdentityDetails> getDetailsType() { return JavaFactoryIdentityDetails.class; }

            @Override
            public CompletionStage<IdentityDetails<JavaFactoryIdentityDetails>> provide(IdentityProviderContext context) {
                return CompletableFuture.completedFuture(new IdentityDetails<>(true,
                    new JavaFactoryIdentityDetails(context.getId())));
            }
        };
    }
}
