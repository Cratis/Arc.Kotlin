// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.concepts.ConceptAs;

/** Invalid Java command that exposes a concept with a raw, unresolvable generic value. */
@Command
public record RawConceptCommand(RawConcept value) {
    public String handle() {
        return value.value().toString();
    }
}

@SuppressWarnings("rawtypes")
record RawConcept(Object value) implements ConceptAs {
}
