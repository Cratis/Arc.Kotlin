// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.validation.IgnoreValidationValidator;
import io.cratis.arc.validation.ValidationMemberPolicy;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IgnoreValidationAcronymJavaTest {
    @Test
    void unadaptedRealProviderRecognizesURLAndReadsThrowingGetter() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var input = new IgnoreValidationAcronymJavaInput();
            assertEquals(Set.of("URL", "sibling"), factory.getValidator()
                .getConstraintsForClass(input.getClass()).getConstrainedProperties().stream()
                .map(property -> property.getPropertyName()).collect(Collectors.toSet()));
            assertTrue(ValidationMemberPolicy.isIgnored(input.getClass(), "URL"));
            assertFalse(ValidationMemberPolicy.isIgnored(input.getClass(), "uRL"));
            assertThrows(ValidationException.class, () -> factory.getValidator().validate(input));
            assertEquals(1, input.reads);
        }
    }

    @Test
    void explicitlyOwnedFactoryAdapterSkipsURLAndRetainsActiveSiblingConstraint() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var input = new IgnoreValidationAcronymJavaInput();
            assertEquals(Set.of("sibling"), IgnoreValidationValidator.fromFactory(factory).validate(input).stream()
                .map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet()));
            assertEquals(0, input.reads);
            // Adapting does not replace or close the application factory.
            assertThrows(ValidationException.class, () -> factory.getValidator().validate(input));
            assertEquals(1, input.reads);
        }
    }
}
