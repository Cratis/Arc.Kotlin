// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javachronicle;

import io.cratis.arc.concepts.ConceptAs;

/** A string-backed command key serialized consistently by Arc and Chronicle. */
public record AuthorId(String value) implements ConceptAs<String>, io.cratis.chronicle.concepts.ConceptAs<String> {
    @Override
    public String getValue() { return value; }
}
