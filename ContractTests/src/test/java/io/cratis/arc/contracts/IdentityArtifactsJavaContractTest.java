// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts;

import io.cratis.arc.contracts.fixtures.JavaIdentityProvider;
import io.cratis.arc.generated.ContractTestsArcArtifactModule;
import io.cratis.arc.identity.IdentityProviderContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IdentityArtifactsJavaContractTest {
    @Test
    void javaProvidersExecuteAndTheirUnannotatedDetailsAreExported() {
        var context = new IdentityProviderContext("java-city", "name", List.of());
        var provider = new JavaIdentityProvider();
        assertEquals("java-city", provider.provide(context).toCompletableFuture().join().getDetails().address().getCity());
        assertEquals("java-city", JavaIdentityProvider.factory().provide(context).toCompletableFuture().join().getDetails().source());
        var types = new ContractTestsArcArtifactModule().getTypes();
        assertTrue(types.stream().anyMatch(type -> type.getFullyQualifiedName().equals(provider.getDetailsType().getName())));
        assertTrue(types.stream().anyMatch(type -> type.getFullyQualifiedName().equals(JavaIdentityProvider.factory().getDetailsType().getName())));
    }
}
