// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.concepts.ConceptAs;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Java record string concept with reusable validation metadata. */
public record JavaCustomerCode(
    @Size(min = 3, max = 12, message = "Java customer code length is invalid")
    @Pattern(regexp = "^[A-Z]+$", message = "Java customer code must be uppercase") String value
) implements ConceptAs<String> {
}
