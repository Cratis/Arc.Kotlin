// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.concepts.ConceptAs;
import java.util.UUID;

/** Java record UUID concept. */
public record JavaOrderId(UUID value) implements ConceptAs<UUID> {
}
