// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.validation.IgnoreValidation;
import jakarta.validation.constraints.NotBlank;

/** Ordinary bean-only acronym property: no URL backing field can mask a naming mismatch. */
public final class IgnoreValidationAcronymJavaInput {
    public int reads;
    @NotBlank public String sibling = "";

    @IgnoreValidation @NotBlank
    public String getURL() {
        reads++;
        throw new AssertionError("ignored Java acronym getter read");
    }
}
