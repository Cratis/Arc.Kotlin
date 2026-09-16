// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.concepts.ConceptAs;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Java record graph for real Jakarta provider and HTTP exclusion checks. */
public record ConceptExclusionJavaInput(@Valid @NotNull Code ignored, @Valid Code required) {
    public record Code(@NotBlank String value) implements ConceptAs<String> { }
}
