// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.identity.AsyncIdentityDetailsProvider;

class HiddenJavaIdentityDetails { public String value; }

public final class InvalidJavaIdentityProviders {
    public static final class GenericProvider<T> implements AsyncIdentityDetailsProvider<T> {
        public Class<T> getDetailsType() { throw new IllegalStateException(); }
        public java.util.concurrent.CompletionStage<io.cratis.arc.identity.IdentityDetails<T>> provide(io.cratis.arc.identity.IdentityProviderContext context) {
            throw new IllegalStateException();
        }
    }
    public static AsyncIdentityDetailsProvider<HiddenJavaIdentityDetails> hiddenIdentity() { throw new IllegalStateException(); }
    public static AsyncIdentityDetailsProvider<? extends HiddenJavaIdentityDetails> projectedIdentity() { throw new IllegalStateException(); }
    public static <P extends AsyncIdentityDetailsProvider<HiddenJavaIdentityDetails>> P genericProviderReturn() { throw new IllegalStateException(); }
    public static AsyncIdentityDetailsProvider rawIdentity() { throw new IllegalStateException(); }
    public static AsyncIdentityDetailsProvider<?> wildcardIdentity() { throw new IllegalStateException(); }
    public static AsyncIdentityDetailsProvider<Object> erasedIdentity() { throw new IllegalStateException(); }
    public static <T> AsyncIdentityDetailsProvider<T> genericIdentity() { throw new IllegalStateException(); }
}
