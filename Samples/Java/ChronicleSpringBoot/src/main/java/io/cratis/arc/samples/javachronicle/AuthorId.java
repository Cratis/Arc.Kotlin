// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javachronicle;

import io.cratis.arc.concepts.ConceptAs;
import java.util.UUID;

/** A UUID-backed command key: a {@code Guid} in the generated proxy, a string event source id in Chronicle. */
public record AuthorId(UUID value) implements ConceptAs<UUID>, io.cratis.chronicle.concepts.ConceptAs<UUID> {
    @Override
    public UUID getValue() { return value; }
}
