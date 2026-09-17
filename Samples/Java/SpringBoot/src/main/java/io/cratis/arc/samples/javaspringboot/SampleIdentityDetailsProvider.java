// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot;

import io.cratis.arc.identity.AsyncIdentityDetailsProvider;
import io.cratis.arc.identity.IdentityDetails;
import io.cratis.arc.identity.IdentityProviderContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Supplies the application-specific identity details served from {@code /.cratis/me}. */
public final class SampleIdentityDetailsProvider implements AsyncIdentityDetailsProvider<SampleIdentityDetails> {
    @Override
    public Class<SampleIdentityDetails> getDetailsType() {
        return SampleIdentityDetails.class;
    }

    @Override
    public CompletionStage<IdentityDetails<SampleIdentityDetails>> provide(IdentityProviderContext context) {
        return CompletableFuture.completedFuture(
            new IdentityDetails<>(true, new SampleIdentityDetails("Arc.Kotlin Java sample")));
    }
}
